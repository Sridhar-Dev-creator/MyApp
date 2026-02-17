import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Bucket4j;
import io.github.bucket4j.Refill;
import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;

/**
 * COMPLETE ACTIVE-ACTIVE SYSTEM
 * = Conflict Resolution + Custom WAN Replication
 * 
 * Architecture:
 * Pod TX ←(WAN)→ Pod VA ←(WAN)→ Pod PA
 * 
 * Each pod:
 * 1. Handles local rate limits (RPS, RPM, RPH, RPD)
 * 2. Detects conflicts on simultaneous writes
 * 3. Resolves conflicts (Last-Write-Wins)
 * 4. Replicates to other pods via WAN
 */

// ==================== VERSIONED VALUE WITH METADATA ====================

public static class VersionedValue<T> {
    public T value;
    public long version;
    public long timestamp;
    public String sourceNode;
    public Map<String, Long> vectorClock;
    
    public VersionedValue(T value, String sourceNode, long version, Map<String, Long> vectorClock) {
        this.value = value;
        this.sourceNode = sourceNode;
        this.version = version;
        this.timestamp = System.currentTimeMillis();
        this.vectorClock = new ConcurrentHashMap<>(vectorClock);
    }
    
    @Override
    public String toString() {
        return String.format("VersionedValue{val=%s, v=%d, ts=%d, src=%s}", 
            value, version, timestamp, sourceNode);
    }
}

// ==================== CONFLICT RESOLUTION ====================

public interface ConflictResolutionStrategy<T> {
    VersionedValue<T> resolve(VersionedValue<T> existing, VersionedValue<T> incoming);
    String getName();
}

public static class LastWriteWinsStrategy<T> implements ConflictResolutionStrategy<T> {
    @Override
    public VersionedValue<T> resolve(VersionedValue<T> existing, VersionedValue<T> incoming) {
        if (incoming.timestamp > existing.timestamp) {
            return incoming;
        }
        return existing;
    }
    
    @Override
    public String getName() {
        return "LWW";
    }
}

// ==================== WAN EVENT ====================

public static class WanEvent<T> implements Serializable {
    public String mapName;
    public String key;
    public VersionedValue<T> value;
    public String operation; // PUT, REMOVE
    public long timestamp;
    public String sourceNode;
    
    public WanEvent(String mapName, String key, VersionedValue<T> value, 
                    String operation, String sourceNode) {
        this.mapName = mapName;
        this.key = key;
        this.value = value;
        this.operation = operation;
        this.sourceNode = sourceNode;
        this.timestamp = System.currentTimeMillis();
    }
}

// ==================== WAN REPLICATION QUEUE ====================

public static class WanReplicationQueue<T> {
    private final BlockingQueue<WanEvent<T>> queue;
    private final String name;
    private final int maxSize;
    
    public WanReplicationQueue(String name, int maxSize) {
        this.name = name;
        this.maxSize = maxSize;
        this.queue = new LinkedBlockingQueue<>(maxSize);
    }
    
    public boolean offer(WanEvent<T> event) {
        return queue.offer(event);
    }
    
    public void drainTo(Collection<WanEvent<T>> collection, int maxEvents) {
        queue.drainTo(collection, maxEvents);
    }
    
    public int size() {
        return queue.size();
    }
    
    public void clear() {
        queue.clear();
    }
}

// ==================== WAN BATCH PUBLISHER ====================

public static class WanBatchPublisher<T> implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(WanBatchPublisher.class.getName());
    
    private final WanReplicationQueue<T> queue;
    private final String targetUrl;
    private final int batchSize;
    private final long batchDelayMs;
    private final RateLimiter rateLimiter;
    private volatile boolean running = true;
    
    public WanBatchPublisher(WanReplicationQueue<T> queue, String targetUrl,
                            int batchSize, long batchDelayMs, long eventsPerSec) {
        this.queue = queue;
        this.targetUrl = targetUrl;
        this.batchSize = batchSize;
        this.batchDelayMs = batchDelayMs;
        this.rateLimiter = new RateLimiter(eventsPerSec);
    }
    
    @Override
    public void run() {
        List<WanEvent<T>> batch = new ArrayList<>();
        long lastSentTime = System.currentTimeMillis();
        
        while (running) {
            try {
                queue.drainTo(batch, batchSize);
                
                long now = System.currentTimeMillis();
                boolean timeSinceLastSend = (now - lastSentTime) >= batchDelayMs;
                
                if (!batch.isEmpty() && (batch.size() >= batchSize || timeSinceLastSend)) {
                    sendBatch(batch);
                    batch.clear();
                    lastSentTime = now;
                } else {
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                LOGGER.info("WAN Publisher interrupted");
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
    
    private void sendBatch(List<WanEvent<T>> events) {
        try {
            // Apply rate limiting
            rateLimiter.consume(events.size());
            
            // Serialize and send
            String json = eventsToJson(events);
            boolean success = sendToTarget(json);
            
            if (success) {
                LOGGER.info("Sent " + events.size() + " events to " + targetUrl);
            } else {
                LOGGER.warning("Failed to send batch, requeuing");
                for (WanEvent<T> event : events) {
                    queue.offer(event);
                }
            }
        } catch (InterruptedException e) {
            LOGGER.warning("Rate limiting interrupted");
            Thread.currentThread().interrupt();
        }
    }
    
    private boolean sendToTarget(String json) {
        try {
            URL url = new URL(targetUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);
            
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes());
                os.flush();
            }
            
            int status = conn.getResponseCode();
            return status >= 200 && status < 300;
        } catch (IOException e) {
            LOGGER.warning("Send failed: " + e.getMessage());
            return false;
        }
    }
    
    private String eventsToJson(List<WanEvent<T>> events) {
        StringBuilder sb = new StringBuilder("{\"events\":[");
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) sb.append(",");
            WanEvent<T> e = events.get(i);
            sb.append("{\"map\":\"").append(e.mapName)
              .append("\",\"key\":\"").append(e.key)
              .append("\",\"val\":\"").append(e.value.value)
              .append("\",\"v\":").append(e.value.version)
              .append(",\"ts\":").append(e.value.timestamp)
              .append(",\"src\":\"").append(e.sourceNode).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }
    
    public void stop() {
        running = false;
    }
}

// ==================== RATE LIMITER ====================

public static class RateLimiter {
    private long tokens;
    private long lastRefill;
    private final long maxTokens;
    private final long rate;
    
    public RateLimiter(long tokensPerSecond) {
        this.maxTokens = tokensPerSecond;
        this.rate = tokensPerSecond;
        this.tokens = maxTokens;
        this.lastRefill = System.currentTimeMillis();
    }
    
    public synchronized void consume(long tokens) throws InterruptedException {
        while (!tryConsume(tokens)) {
            Thread.sleep(10);
        }
    }
    
    private synchronized boolean tryConsume(long tokens) {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRefill;
        this.tokens = Math.min(this.tokens + (elapsed * rate / 1000), maxTokens);
        this.lastRefill = now;
        
        if (this.tokens >= tokens) {
            this.tokens -= tokens;
            return true;
        }
        return false;
    }
}

// ==================== BUCKET4J CACHE SERVICE WITH WAN + CONFLICT RESOLUTION ====================

public static class Bucket4jCacheServiceWithWan<K, V> {
    private static final Logger LOGGER = Logger.getLogger(Bucket4jCacheServiceWithWan.class.getName());
    
    // Configuration
    public static class EndpointLimitConfig {
        public int rps;
        public int rpm;
        public int rph;
        public int rpd;
        
        public EndpointLimitConfig(int rps, int rpm, int rph, int rpd) {
            this.rps = rps;
            this.rpm = rpm;
            this.rph = rph;
            this.rpd = rpd;
        }
    }
    
    // Core components
    private final String nodeId;
    private final Map<String, EndpointLimitConfig> bucketConfigs;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Map<K, VersionedValue<V>> cache = new ConcurrentHashMap<>();
    private final Map<K, ReadWriteLock> keyLocks = new ConcurrentHashMap<>();
    private final Map<String, WanReplicationQueue<V>> wanQueues = new ConcurrentHashMap<>();
    private final Map<String, ExecutorService> wanPublishers = new ConcurrentHashMap<>();
    private final ConflictResolutionStrategy<V> strategy;
    
    // WAN Configuration
    public static class WanConfig {
        public String replicationName;
        public String targetUrl;
        public int batchSize = 500;
        public long batchDelayMs = 1000;
        public long eventsPerSecond = 1000;
        public List<String> mapNames = new ArrayList<>();
    }
    
    public Bucket4jCacheServiceWithWan(String nodeId,
                                       Map<String, EndpointLimitConfig> bucketConfigs,
                                       ConflictResolutionStrategy<V> strategy) {
        this.nodeId = nodeId;
        this.bucketConfigs = bucketConfigs;
        this.strategy = strategy;
        initializeBuckets();
    }
    
    private void initializeBuckets() {
        for (Map.Entry<String, EndpointLimitConfig> entry : bucketConfigs.entrySet()) {
            EndpointLimitConfig config = entry.getValue();
            
            Bucket bucket = Bucket4j.builder()
                .addLimit(Bandwidth.classic(config.rps, Refill.intervally(config.rps, Duration.ofSeconds(1))))
                .addLimit(Bandwidth.classic(config.rpm, Refill.intervally(config.rpm, Duration.ofMinutes(1))))
                .addLimit(Bandwidth.classic(config.rph, Refill.intervally(config.rph, Duration.ofHours(1))))
                .addLimit(Bandwidth.classic(config.rpd, Refill.intervally(config.rpd, Duration.ofDays(1))))
                .build();
            
            buckets.put(entry.getKey(), bucket);
        }
    }
    
    /**
     * PUT with rate limit + conflict resolution + WAN replication
     */
    public boolean put(K key, V value, String endpointId, String mapName) throws InterruptedException {
        // Step 1: Check rate limit (RPS, RPM, RPH, RPD)
        if (!checkRateLimit(endpointId)) {
            LOGGER.warning("Rate limit exceeded for endpoint: " + endpointId);
            return false;
        }
        
        // Step 2: Get lock for this key (prevent concurrent writes to same key)
        ReadWriteLock lock = keyLocks.computeIfAbsent(key, k -> new ReentrantReadWriteLock());
        lock.writeLock().lock();
        
        try {
            // Step 3: Create versioned value
            VersionedValue<V> existing = cache.get(key);
            long newVersion = existing != null ? existing.version + 1 : 1;
            VersionedValue<V> newVersionedValue = new VersionedValue<>(value, nodeId, newVersion, new HashMap<>());
            
            // Step 4: Detect and resolve conflicts
            if (existing != null) {
                if (isConflict(existing, newVersionedValue)) {
                    newVersionedValue = strategy.resolve(existing, newVersionedValue);
                    LOGGER.info("Conflict resolved for key: " + key + ", strategy: " + strategy.getName());
                }
            }
            
            // Step 5: Store locally
            cache.put(key, newVersionedValue);
            
            // Step 6: Queue for WAN replication
            queueForReplication(mapName, key, newVersionedValue);
            
            LOGGER.info("Put successful - Key: " + key + ", Version: " + newVersionedValue.version);
            return true;
            
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * GET with read lock
     */
    public V get(K key) throws InterruptedException {
        ReadWriteLock lock = keyLocks.computeIfAbsent(key, k -> new ReentrantReadWriteLock());
        lock.readLock().lock();
        
        try {
            VersionedValue<V> versioned = cache.get(key);
            return versioned != null ? versioned.value : null;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Receive replicated event from other pod
     */
    public void applyWanEvent(K key, VersionedValue<V> remoteValue, String mapName) throws InterruptedException {
        ReadWriteLock lock = keyLocks.computeIfAbsent(key, k -> new ReentrantReadWriteLock());
        lock.writeLock().lock();
        
        try {
            VersionedValue<V> existing = cache.get(key);
            
            if (existing == null) {
                cache.put(key, remoteValue);
                LOGGER.info("Applied WAN event - Key: " + key + " (new)");
            } else if (isConflict(existing, remoteValue)) {
                VersionedValue<V> resolved = strategy.resolve(existing, remoteValue);
                cache.put(key, resolved);
                LOGGER.info("Applied WAN event with conflict resolution - Key: " + key);
            } else if (remoteValue.version > existing.version) {
                cache.put(key, remoteValue);
                LOGGER.info("Applied WAN event - Key: " + key + ", Version: " + remoteValue.version);
            }
            // Otherwise, existing version is newer, ignore remote
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Start WAN replication to target pod
     */
    public void startWanReplication(WanConfig config) {
        for (String mapName : config.mapNames) {
            WanReplicationQueue<V> queue = new WanReplicationQueue<>(
                "wan-queue-" + config.replicationName + "-" + mapName,
                10000
            );
            wanQueues.put(mapName, queue);
            
            WanBatchPublisher<V> publisher = new WanBatchPublisher<>(
                queue,
                config.targetUrl,
                config.batchSize,
                config.batchDelayMs,
                config.eventsPerSecond
            );
            
            ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "WAN-" + config.replicationName + "-" + mapName);
                t.setDaemon(true);
                return t;
            });
            
            executor.submit(publisher);
            wanPublishers.put(config.replicationName, executor);
            
            LOGGER.info("Started WAN replication: " + config.replicationName);
        }
    }
    
    /**
     * Stop WAN replication
     */
    public void stopWanReplication() {
        wanPublishers.values().forEach(ExecutorService::shutdownNow);
        wanPublishers.clear();
        wanQueues.clear();
        LOGGER.info("Stopped WAN replication");
    }
    
    // ==================== PRIVATE HELPERS ====================
    
    private boolean checkRateLimit(String endpointId) {
        Bucket bucket = buckets.get(endpointId);
        if (bucket == null) return true;
        return bucket.tryConsume(1);
    }
    
    private boolean isConflict(VersionedValue<V> existing, VersionedValue<V> incoming) {
        // Concurrent if neither clearly happened before the other
        return existing.timestamp == incoming.timestamp && 
               !existing.sourceNode.equals(incoming.sourceNode);
    }
    
    private void queueForReplication(String mapName, K key, VersionedValue<V> value) {
        WanReplicationQueue<V> queue = wanQueues.get(mapName);
        if (queue != null) {
            WanEvent<V> event = new WanEvent<>(mapName, (String) key, value, "PUT", nodeId);
            if (!queue.offer(event)) {
                LOGGER.warning("WAN queue full, event dropped");
            }
        }
    }
    
    public Map<K, VersionedValue<V>> getCacheSnapshot() {
        return new HashMap<>(cache);
    }
}

// ==================== USAGE: YOUR 3-POD SETUP ====================

/**
 * Pod TX Service
 */
public static class PodTXService {
    private static final Logger LOGGER = Logger.getLogger(PodTXService.class.getName());
    private final Bucket4jCacheServiceWithWan<String, String> cacheService;
    
    public PodTXService() {
        // Configure rate limits
        Map<String, Bucket4jCacheServiceWithWan.EndpointLimitConfig> configs = new ConcurrentHashMap<>();
        configs.put("users", new Bucket4jCacheServiceWithWan.EndpointLimitConfig(
            1000,      // RPS: 1000 requests/sec
            60000,     // RPM: 60,000 requests/min
            1000000,   // RPH: 1,000,000 requests/hour
            10000000   // RPD: 10,000,000 requests/day
        ));
        
        // Create cache service with Last-Write-Wins strategy
        this.cacheService = new Bucket4jCacheServiceWithWan<>(
            "POD-TX-1",
            configs,
            new LastWriteWinsStrategy<>()
        );
        
        // Configure WAN replication to other pods
        Bucket4jCacheServiceWithWan.WanConfig toVA = new Bucket4jCacheServiceWithWan.WanConfig();
        toVA.replicationName = "tx-to-va";
        toVA.targetUrl = "http://va-pod:8080/wan/replicate";
        toVA.mapNames.addAll(Arrays.asList("users", "orders", "products"));
        toVA.eventsPerSecond = 1000;
        cacheService.startWanReplication(toVA);
        
        Bucket4jCacheServiceWithWan.WanConfig toPA = new Bucket4jCacheServiceWithWan.WanConfig();
        toPA.replicationName = "tx-to-pa";
        toPA.targetUrl = "http://pa-pod:8080/wan/replicate";
        toPA.mapNames.addAll(Arrays.asList("users", "orders", "products"));
        toPA.eventsPerSecond = 1000;
        cacheService.startWanReplication(toPA);
        
        LOGGER.info("Pod TX initialized with WAN replication to VA and PA");
    }
    
    public void handleUserUpdate(String userId, String userData) throws InterruptedException {
        boolean success = cacheService.put(userId, userData, "users", "users");
        if (success) {
            LOGGER.info("User updated: " + userId + " (will be replicated to VA and PA)");
        } else {
            LOGGER.warning("Rate limit exceeded");
        }
    }
    
    public String readUser(String userId) throws InterruptedException {
        return cacheService.get(userId);
    }
    
    public void shutdown() {
        cacheService.stopWanReplication();
        LOGGER.info("Pod TX shutdown");
    }
}

/**
 * Pod VA Service (similar to Pod TX)
 */
public static class PodVAService {
    private final Bucket4jCacheServiceWithWan<String, String> cacheService;
    
    public PodVAService() {
        Map<String, Bucket4jCacheServiceWithWan.EndpointLimitConfig> configs = new ConcurrentHashMap<>();
        configs.put("users", new Bucket4jCacheServiceWithWan.EndpointLimitConfig(1000, 60000, 1000000, 10000000));
        
        this.cacheService = new Bucket4jCacheServiceWithWan<>(
            "POD-VA-1",
            configs,
            new LastWriteWinsStrategy<>()
        );
        
        // Replicate to TX and PA
        Bucket4jCacheServiceWithWan.WanConfig toTX = new Bucket4jCacheServiceWithWan.WanConfig();
        toTX.replicationName = "va-to-tx";
        toTX.targetUrl = "http://tx-pod:8080/wan/replicate";
        toTX.mapNames.addAll(Arrays.asList("users", "orders", "products"));
        cacheService.startWanReplication(toTX);
        
        Bucket4jCacheServiceWithWan.WanConfig toPA = new Bucket4jCacheServiceWithWan.WanConfig();
        toPA.replicationName = "va-to-pa";
        toPA.targetUrl = "http://pa-pod:8080/wan/replicate";
        toPA.mapNames.addAll(Arrays.asList("users", "orders", "products"));
        cacheService.startWanReplication(toPA);
    }
    
    public void handleUserUpdate(String userId, String userData) throws InterruptedException {
        cacheService.put(userId, userData, "users", "users");
    }
    
    public String readUser(String userId) throws InterruptedException {
        return cacheService.get(userId);
    }
}

/**
 * Similar for Pod PA...
 */

// ==================== TEST: SIMULTANEOUS WRITES ====================

public static class TestActiveActiveSystem {
    public static void main(String[] args) throws Exception {
        System.out.println("=== Active-Active System Test ===\n");
        
        PodTXService podTX = new PodTXService();
        PodVAService podVA = new PodVAService();
        
        ExecutorService executor = Executors.newFixedThreadPool(2);
        
        // Pod TX writes at time T
        executor.submit(() -> {
            try {
                podTX.handleUserUpdate("user:1", "TX-Data-{age:30}");
                System.out.println("✓ Pod TX wrote user:1");
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        
        // Pod VA writes 5ms later (concurrent write)
        executor.submit(() -> {
            try {
                Thread.sleep(5);
                podVA.handleUserUpdate("user:1", "VA-Data-{age:31}");
                System.out.println("✓ Pod VA wrote user:1");
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        
        executor.awaitTermination(3, TimeUnit.SECONDS);
        executor.shutdown();
        
        // Wait for replication
        Thread.sleep(2000);
        
        // Both pods should see same value (eventual consistency)
        String txValue = podTX.readUser("user:1");
        String vaValue = podVA.readUser("user:1");
        
        System.out.println("\nResults:");
        System.out.println("Pod TX sees: " + txValue);
        System.out.println("Pod VA sees: " + vaValue);
        System.out.println("Both same? " + (txValue != null && txValue.equals(vaValue)));
        
        podTX.shutdown();
    }
}

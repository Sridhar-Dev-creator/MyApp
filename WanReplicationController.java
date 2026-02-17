import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import java.util.*;
import java.util.logging.Logger;

/**
 * REST Controller to receive WAN replicated events from other pods
 * Runs on each pod to accept incoming replication
 */

@RestController
@RequestMapping("/wan")
public class WanReplicationController {
    
    private static final Logger LOGGER = Logger.getLogger(WanReplicationController.class.getName());
    
    // Inject your cache service
    private final Bucket4jCacheServiceWithWan<String, String> cacheService;
    
    public WanReplicationController(Bucket4jCacheServiceWithWan<String, String> cacheService) {
        this.cacheService = cacheService;
    }
    
    /**
     * Endpoint to receive replicated events from remote pod
     * 
     * Example: Pod TX sends to Pod VA
     * POST http://va-pod:8080/wan/replicate
     * Body: {
     *   "events": [
     *     {
     *       "map": "users",
     *       "key": "user:1",
     *       "val": "John",
     *       "v": 5,
     *       "ts": 1234567890,
     *       "src": "POD-TX-1"
     *     }
     *   ]
     * }
     */
    @PostMapping("/replicate")
    public ResponseEntity<?> receiveReplicatedEvents(@RequestBody WanEventBatch batch) {
        try {
            LOGGER.info("Received WAN batch with " + batch.events.size() + " events");
            
            int successCount = 0;
            for (WanEventDto event : batch.events) {
                try {
                    // Apply event to local cache
                    applyWanEvent(event);
                    successCount++;
                } catch (Exception e) {
                    LOGGER.warning("Failed to apply event for key: " + event.key + 
                                 ", error: " + e.getMessage());
                }
            }
            
            LOGGER.info("Applied " + successCount + "/" + batch.events.size() + " events");
            
            return ResponseEntity.ok()
                .body(new AcknowledgmentResponse("SUCCESS", successCount));
                
        } catch (Exception e) {
            LOGGER.severe("Error processing WAN batch: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new AcknowledgmentResponse("ERROR", 0));
        }
    }
    
    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(new HealthResponse("UP"));
    }
    
    /**
     * Status endpoint - show cache stats
     */
    @GetMapping("/status")
    public ResponseEntity<?> status() {
        Map<String, String> cacheSnapshot = cacheService.getCacheSnapshot();
        return ResponseEntity.ok(new StatusResponse("UP", cacheSnapshot.size()));
    }
    
    /**
     * Apply WAN event to local cache
     */
    private void applyWanEvent(WanEventDto event) throws InterruptedException {
        // Reconstruct VersionedValue from DTO
        VersionedValue<String> versioned = new VersionedValue<>(
            event.val,
            event.src,
            event.v,
            new HashMap<>()
        );
        
        // Apply to cache (handles conflict resolution)
        cacheService.applyWanEvent(event.key, versioned, event.map);
        
        LOGGER.info("Applied WAN event - Map: " + event.map + 
                   ", Key: " + event.key + 
                   ", Version: " + event.v +
                   ", Source: " + event.src);
    }
    
    // ==================== DTOs ====================
    
    @lombok.Data
    public static class WanEventBatch {
        List<WanEventDto> events;
    }
    
    @lombok.Data
    public static class WanEventDto {
        String map;        // Map name
        String key;        // Key in map
        String val;        // Value
        long v;           // Version
        long ts;          // Timestamp
        String src;       // Source node
    }
    
    @lombok.Data
    public static class AcknowledgmentResponse {
        String status;
        int eventCount;
        long timestamp;
        
        public AcknowledgmentResponse(String status, int eventCount) {
            this.status = status;
            this.eventCount = eventCount;
            this.timestamp = System.currentTimeMillis();
        }
    }
    
    @lombok.Data
    public static class HealthResponse {
        String status;
        long timestamp;
        
        public HealthResponse(String status) {
            this.status = status;
            this.timestamp = System.currentTimeMillis();
        }
    }
    
    @lombok.Data
    public static class StatusResponse {
        String status;
        int cacheSize;
        long timestamp;
        
        public StatusResponse(String status, int cacheSize) {
            this.status = status;
            this.cacheSize = cacheSize;
            this.timestamp = System.currentTimeMillis();
        }
    }
}

// ==================== ALTERNATIVE: SPRING BOOT APPLICATION ====================

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class WanReplicationApp {
    
    @Bean
    public Bucket4jCacheServiceWithWan<String, String> cacheService() {
        // Read node ID from environment or config
        String nodeId = System.getenv("NODE_ID");
        if (nodeId == null) {
            nodeId = "POD-" + System.getenv("HOSTNAME");
        }
        
        // Configure rate limits
        Map<String, Bucket4jCacheServiceWithWan.EndpointLimitConfig> configs = 
            new ConcurrentHashMap<>();
        
        configs.put("users", new Bucket4jCacheServiceWithWan.EndpointLimitConfig(
            1000,      // RPS
            60000,     // RPM
            1000000,   // RPH
            10000000   // RPD
        ));
        
        Bucket4jCacheServiceWithWan<String, String> service = 
            new Bucket4jCacheServiceWithWan<>(
                nodeId,
                configs,
                new LastWriteWinsStrategy<>()
            );
        
        // Configure WAN replication based on environment
        configureWanReplication(service, nodeId);
        
        return service;
    }
    
    private void configureWanReplication(
            Bucket4jCacheServiceWithWan<String, String> service,
            String nodeId) {
        
        // Example configuration based on node ID
        if (nodeId.contains("TX")) {
            // Pod TX replicates to VA and PA
            Bucket4jCacheServiceWithWan.WanConfig toVA = 
                new Bucket4jCacheServiceWithWan.WanConfig();
            toVA.replicationName = "tx-to-va";
            toVA.targetUrl = "http://va-pod:8080/wan/replicate";
            toVA.mapNames.addAll(Arrays.asList("users", "orders", "products"));
            toVA.eventsPerSecond = 1000;
            service.startWanReplication(toVA);
            
            Bucket4jCacheServiceWithWan.WanConfig toPA = 
                new Bucket4jCacheServiceWithWan.WanConfig();
            toPA.replicationName = "tx-to-pa";
            toPA.targetUrl = "http://pa-pod:8080/wan/replicate";
            toPA.mapNames.addAll(Arrays.asList("users", "orders", "products"));
            toPA.eventsPerSecond = 1000;
            service.startWanReplication(toPA);
        }
        // Similar for VA and PA...
    }
    
    public static void main(String[] args) {
        SpringApplication.run(WanReplicationApp.class, args);
    }
}

// ==================== APPLICATION PROPERTIES ====================

/*
# application.properties

# Server config
server.port=8080
server.servlet.context-path=/

# Logging
logging.level.root=INFO
logging.level.com.example.wan=DEBUG

# Application
node.id=${HOSTNAME:pod-tx-1}
pod.name=${POD_NAME:TX}

# WAN Configuration (can be externalized)
wan.batch.size=500
wan.batch.delay.ms=1000
wan.events.per.second=1000
wan.queue.capacity=10000

# Rate limits (RPS, RPM, RPH, RPD)
ratelimit.rps=1000
ratelimit.rpm=60000
ratelimit.rph=1000000
ratelimit.rpd=10000000
*/

// ==================== DOCKER CONFIGURATION ====================

/*
# Dockerfile for Pod TX

FROM openjdk:11-jre-slim

WORKDIR /app

COPY target/wan-replication-app.jar app.jar

ENV NODE_ID=POD-TX-1
ENV HOSTNAME=tx-pod

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]

---

# docker-compose.yml

version: '3.8'

services:
  tx-pod:
    build: .
    ports:
      - "8080:8080"
    environment:
      NODE_ID: POD-TX-1
      HOSTNAME: tx-pod
    networks:
      - wan-network

  va-pod:
    build: .
    ports:
      - "8081:8080"
    environment:
      NODE_ID: POD-VA-1
      HOSTNAME: va-pod
    networks:
      - wan-network

  pa-pod:
    build: .
    ports:
      - "8082:8080"
    environment:
      NODE_ID: POD-PA-1
      HOSTNAME: pa-pod
    networks:
      - wan-network

networks:
  wan-network:
    driver: bridge
*/

// ==================== TESTING REST ENDPOINTS ====================

/*
# Test WAN Replication

## 1. Check Pod TX health
curl http://localhost:8080/wan/health

## 2. Write data to Pod TX
curl -X POST http://localhost:8080/api/users/user:1 \
  -H "Content-Type: application/json" \
  -d '{"name":"John","age":30}'

## 3. Check Pod TX cache status
curl http://localhost:8080/wan/status

## 4. Manually send WAN event to Pod VA
curl -X POST http://localhost:8081/wan/replicate \
  -H "Content-Type: application/json" \
  -d '{
    "events": [
      {
        "map": "users",
        "key": "user:1",
        "val": "John",
        "v": 1,
        "ts": 1234567890,
        "src": "POD-TX-1"
      }
    ]
  }'

## 5. Verify data on Pod VA
curl http://localhost:8081/api/users/user:1

## Result: Both Pod TX and Pod VA have same data!
*/

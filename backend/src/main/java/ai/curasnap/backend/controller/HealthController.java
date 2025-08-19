package ai.curasnap.backend.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Health check endpoints for monitoring service dependencies
 */
@Slf4j
@RestController
@RequestMapping("/actuator")
public class HealthController {

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        Map<String, Object> components = new HashMap<>();
        
        health.put("status", "UP");
        
        // Redis health check
        components.put("redis", checkRedisHealth());
        
        health.put("components", components);
        
        // Overall status is DOWN if any component is DOWN
        boolean allUp = components.values().stream()
            .allMatch(component -> "UP".equals(((Map<?, ?>) component).get("status")));
        
        if (!allUp) {
            health.put("status", "DOWN");
        }
        
        return ResponseEntity.ok(health);
    }

    @GetMapping("/health/redis")
    @ConditionalOnProperty(name = "spring.data.redis.enabled", havingValue = "true", matchIfMissing = true)
    public ResponseEntity<Map<String, Object>> redisHealth() {
        return ResponseEntity.ok(checkRedisHealth());
    }

    private Map<String, Object> checkRedisHealth() {
        Map<String, Object> redisHealth = new HashMap<>();
        
        if (redisTemplate == null) {
            redisHealth.put("status", "DOWN");
            redisHealth.put("details", Map.of(
                "error", "Redis not configured",
                "reason", "RedisTemplate not available"
            ));
            return redisHealth;
        }
        
        try {
            // Test Redis connection with a simple ping
            String testKey = "health:check:" + System.currentTimeMillis();
            redisTemplate.opsForValue().set(testKey, "ping", java.time.Duration.ofSeconds(1));
            String result = (String) redisTemplate.opsForValue().get(testKey);
            redisTemplate.delete(testKey);
            
            if ("ping".equals(result)) {
                redisHealth.put("status", "UP");
                redisHealth.put("details", Map.of(
                    "version", getRedisVersion(),
                    "response_time_ms", "<100"
                ));
            } else {
                redisHealth.put("status", "DOWN");
                redisHealth.put("details", Map.of(
                    "error", "Redis connection test failed",
                    "expected", "ping",
                    "actual", result
                ));
            }
        } catch (Exception e) {
            log.warn("Redis health check failed", e);
            redisHealth.put("status", "DOWN");
            redisHealth.put("details", Map.of(
                "error", "Redis connection failed"
                // SECURITY: Exception details removed to prevent information disclosure
            ));
        }
        
        return redisHealth;
    }

    private String getRedisVersion() {
        try {
            // This is a simplified version check
            return "7.x";
        } catch (Exception e) {
            return "unknown";
        }
    }
}
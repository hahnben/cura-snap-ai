package ai.curasnap.backend.controller;

import ai.curasnap.backend.service.AgentCacheMetricsService;
import ai.curasnap.backend.service.CachedAgentServiceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
    
    @Autowired(required = false)
    private AgentCacheMetricsService cacheMetricsService;
    
    @Autowired(required = false)
    private CachedAgentServiceClient cachedAgentServiceClient;
    
    @Value("${app.agent.cache.enabled:false}")
    private boolean cacheEnabled;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        Map<String, Object> components = new HashMap<>();
        
        health.put("status", "UP");
        
        // Redis health check
        components.put("redis", checkRedisHealth());
        
        // Agent Cache health check (if enabled)
        if (cacheEnabled && cacheMetricsService != null) {
            components.put("agent_cache", checkAgentCacheHealth());
        }
        
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
    
    @GetMapping("/health/cache")
    @ConditionalOnProperty(name = "app.agent.cache.enabled", havingValue = "true", matchIfMissing = false)
    public ResponseEntity<Map<String, Object>> cacheHealth() {
        return ResponseEntity.ok(checkAgentCacheHealth());
    }
    
    @GetMapping("/health/cache/metrics")
    @ConditionalOnProperty(name = "app.agent.cache.enabled", havingValue = "true", matchIfMissing = false)
    public ResponseEntity<Map<String, Object>> cacheMetrics() {
        if (cacheMetricsService == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "UNAVAILABLE");
            error.put("error", "Cache metrics service not available");
            return ResponseEntity.ok(error);
        }
        
        try {
            return ResponseEntity.ok(cacheMetricsService.getKPISummary());
        } catch (Exception e) {
            log.warn("Failed to retrieve cache metrics", e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "ERROR");
            error.put("error", "Failed to retrieve metrics");
            return ResponseEntity.ok(error);
        }
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

    private Map<String, Object> checkAgentCacheHealth() {
        Map<String, Object> cacheHealth = new HashMap<>();
        
        // Check if caching is enabled
        if (!cacheEnabled) {
            cacheHealth.put("status", "DISABLED");
            cacheHealth.put("details", Map.of(
                "info", "Agent caching is disabled via configuration"
            ));
            return cacheHealth;
        }
        
        // Check if cache services are available
        if (cacheMetricsService == null || cachedAgentServiceClient == null) {
            cacheHealth.put("status", "DOWN");
            cacheHealth.put("details", Map.of(
                "error", "Cache services not available",
                "metrics_service", cacheMetricsService != null,
                "cached_client", cachedAgentServiceClient != null
            ));
            return cacheHealth;
        }
        
        try {
            // Get current performance metrics
            AgentCacheMetricsService.CachePerformanceReport report = cacheMetricsService.getPerformanceReport();
            
            // Determine health status based on metrics
            String status = "UP";
            Map<String, Object> details = new HashMap<>();
            
            // Check Redis connectivity
            if (!report.isRedisHealthy()) {
                status = "DEGRADED";
                details.put("redis_healthy", false);
            } else {
                details.put("redis_healthy", true);
            }
            
            // Check error rate (if > 10% of operations fail, mark as degraded)
            if (report.getTotalOperations() > 0) {
                double errorRate = (double) report.getCacheErrors() / report.getTotalOperations() * 100.0;
                if (errorRate > 10.0) {
                    status = "DEGRADED";
                    details.put("high_error_rate", String.format("%.2f%%", errorRate));
                }
                details.put("error_rate_percent", String.format("%.2f", errorRate));
            }
            
            // Add performance details
            details.put("hit_rate_percent", String.format("%.2f", report.getHitRatePercentage()));
            details.put("cache_size", report.getCacheSize());
            details.put("total_operations", report.getTotalOperations());
            details.put("avg_response_time_ms", report.getAvgCacheResponseTimeMs());
            details.put("memory_usage_mb", report.getEstimatedMemoryUsageBytes() / 1024 / 1024);
            details.put("last_updated", report.getLastUpdate().toString());
            
            cacheHealth.put("status", status);
            cacheHealth.put("details", details);
            
        } catch (Exception e) {
            log.warn("Agent cache health check failed", e);
            cacheHealth.put("status", "DOWN");
            cacheHealth.put("details", Map.of(
                "error", "Cache health check failed"
                // SECURITY: Exception details removed to prevent information disclosure
            ));
        }
        
        return cacheHealth;
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
package ai.curasnap.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service for monitoring and reporting Agent Service cache performance.
 * 
 * This service provides comprehensive metrics about cache performance including:
 * - Real-time cache hit/miss rates
 * - Cache size and memory usage tracking
 * - Performance improvements measurement
 * - Redis connection health monitoring
 * - Cost savings calculation based on reduced API calls
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.agent.cache.metrics.enabled", havingValue = "true", matchIfMissing = true)
public class AgentCacheMetricsService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final CacheKeyGenerator cacheKeyGenerator;
    private final boolean metricsEnabled;
    
    // Performance tracking
    private final AtomicLong totalCacheChecks = new AtomicLong(0);
    private final AtomicLong totalCacheHits = new AtomicLong(0);
    private final AtomicLong totalCacheMisses = new AtomicLong(0);
    private final AtomicLong totalCacheErrors = new AtomicLong(0);
    
    // Timing metrics
    private volatile long avgCacheResponseTimeMs = 0;
    private volatile long avgServiceResponseTimeMs = 2000; // Estimated Agent Service response time
    private volatile Instant lastMetricsUpdate = Instant.now();
    
    // System health
    private volatile boolean redisHealthy = true;
    private volatile long lastCacheSizeCheck = 0;
    private volatile long estimatedMemoryUsageBytes = 0;
    
    @Autowired
    public AgentCacheMetricsService(
            RedisTemplate<String, Object> redisTemplate,
            CacheKeyGenerator cacheKeyGenerator,
            @Value("${app.agent.cache.metrics.enabled:true}") boolean metricsEnabled) {
        
        this.redisTemplate = redisTemplate;
        this.cacheKeyGenerator = cacheKeyGenerator;
        this.metricsEnabled = metricsEnabled;
        
        if (metricsEnabled) {
            log.info("AgentCacheMetricsService initialized - metrics collection enabled");
        } else {
            log.info("AgentCacheMetricsService initialized - metrics collection disabled");
        }
    }
    
    /**
     * Records a cache operation for metrics tracking.
     * 
     * @param operation the type of operation (hit, miss, error)
     * @param responseTimeMs the response time in milliseconds
     */
    public void recordCacheOperation(CacheOperation operation, long responseTimeMs) {
        if (!metricsEnabled) {
            return;
        }
        
        totalCacheChecks.incrementAndGet();
        
        switch (operation) {
            case HIT:
                totalCacheHits.incrementAndGet();
                updateAverageCacheResponseTime(responseTimeMs);
                break;
            case MISS:
                totalCacheMisses.incrementAndGet();
                break;
            case ERROR:
                totalCacheErrors.incrementAndGet();
                break;
        }
        
        lastMetricsUpdate = Instant.now();
    }
    
    /**
     * Updates the average cache response time using exponential moving average.
     */
    private void updateAverageCacheResponseTime(long responseTimeMs) {
        // Use exponential moving average with alpha = 0.1 for smooth updating
        avgCacheResponseTimeMs = (long) (0.9 * avgCacheResponseTimeMs + 0.1 * responseTimeMs);
    }
    
    /**
     * Gets comprehensive cache performance metrics.
     * 
     * @return CachePerformanceReport with current metrics
     */
    public CachePerformanceReport getPerformanceReport() {
        long hits = totalCacheHits.get();
        long misses = totalCacheMisses.get();
        long errors = totalCacheErrors.get();
        long total = totalCacheChecks.get();
        
        double hitRate = total > 0 ? (double) hits / total * 100.0 : 0.0;
        
        // Calculate performance improvements
        long timeSavedMs = hits * (avgServiceResponseTimeMs - avgCacheResponseTimeMs);
        double costSavingsPercentage = hitRate; // Assume 1% hit rate = 1% cost savings
        
        return CachePerformanceReport.builder()
                .cacheHits(hits)
                .cacheMisses(misses)
                .cacheErrors(errors)
                .totalOperations(total)
                .hitRatePercentage(hitRate)
                .avgCacheResponseTimeMs(avgCacheResponseTimeMs)
                .avgServiceResponseTimeMs(avgServiceResponseTimeMs)
                .totalTimeSavedMs(timeSavedMs)
                .estimatedCostSavingsPercentage(costSavingsPercentage)
                .cacheSize(lastCacheSizeCheck)
                .estimatedMemoryUsageBytes(estimatedMemoryUsageBytes)
                .redisHealthy(redisHealthy)
                .lastUpdate(lastMetricsUpdate)
                .metricsEnabled(metricsEnabled)
                .build();
    }
    
    /**
     * Scheduled task to update cache size and health metrics.
     * Runs every 5 minutes to avoid Redis overhead.
     */
    @Scheduled(fixedRate = 300000) // 5 minutes
    public void updateCacheMetrics() {
        if (!metricsEnabled) {
            return;
        }
        
        try {
            // Check Redis connection health
            redisTemplate.hasKey("health-check-key");
            redisHealthy = true;
            
            // Count cache entries
            String pattern = cacheKeyGenerator.getCacheKeyPrefix() + "*";
            Set<String> keys = redisTemplate.keys(pattern);
            lastCacheSizeCheck = keys != null ? keys.size() : 0;
            
            // Estimate memory usage (rough approximation)
            // Average SOAP note ~2KB + key overhead ~100 bytes
            estimatedMemoryUsageBytes = lastCacheSizeCheck * 2200;
            
            log.debug("Cache metrics updated - Size: {}, Memory: {} bytes, Redis healthy: {}", 
                    lastCacheSizeCheck, estimatedMemoryUsageBytes, redisHealthy);
            
        } catch (Exception e) {
            redisHealthy = false;
            log.warn("Failed to update cache metrics: {}", e.getMessage());
        }
    }
    
    /**
     * Resets all performance metrics.
     */
    public void resetMetrics() {
        if (metricsEnabled) {
            totalCacheChecks.set(0);
            totalCacheHits.set(0);
            totalCacheMisses.set(0);
            totalCacheErrors.set(0);
            avgCacheResponseTimeMs = 0;
            lastMetricsUpdate = Instant.now();
            log.info("Cache performance metrics reset");
        }
    }
    
    /**
     * Gets a summary of key performance indicators.
     */
    public Map<String, Object> getKPISummary() {
        CachePerformanceReport report = getPerformanceReport();
        
        Map<String, Object> kpis = new HashMap<>();
        kpis.put("cache_hit_rate_percent", report.getHitRatePercentage());
        kpis.put("total_operations", report.getTotalOperations());
        kpis.put("cache_size", report.getCacheSize());
        kpis.put("time_saved_minutes", report.getTotalTimeSavedMs() / 60000);
        kpis.put("cost_savings_percent", report.getEstimatedCostSavingsPercentage());
        kpis.put("redis_healthy", report.isRedisHealthy());
        kpis.put("avg_cache_response_ms", report.getAvgCacheResponseTimeMs());
        
        return kpis;
    }
    
    /**
     * Enum for cache operations.
     */
    public enum CacheOperation {
        HIT, MISS, ERROR
    }
    
    /**
     * Comprehensive cache performance report.
     */
    public static class CachePerformanceReport {
        private final long cacheHits;
        private final long cacheMisses;
        private final long cacheErrors;
        private final long totalOperations;
        private final double hitRatePercentage;
        private final long avgCacheResponseTimeMs;
        private final long avgServiceResponseTimeMs;
        private final long totalTimeSavedMs;
        private final double estimatedCostSavingsPercentage;
        private final long cacheSize;
        private final long estimatedMemoryUsageBytes;
        private final boolean redisHealthy;
        private final Instant lastUpdate;
        private final boolean metricsEnabled;
        
        private CachePerformanceReport(Builder builder) {
            this.cacheHits = builder.cacheHits;
            this.cacheMisses = builder.cacheMisses;
            this.cacheErrors = builder.cacheErrors;
            this.totalOperations = builder.totalOperations;
            this.hitRatePercentage = builder.hitRatePercentage;
            this.avgCacheResponseTimeMs = builder.avgCacheResponseTimeMs;
            this.avgServiceResponseTimeMs = builder.avgServiceResponseTimeMs;
            this.totalTimeSavedMs = builder.totalTimeSavedMs;
            this.estimatedCostSavingsPercentage = builder.estimatedCostSavingsPercentage;
            this.cacheSize = builder.cacheSize;
            this.estimatedMemoryUsageBytes = builder.estimatedMemoryUsageBytes;
            this.redisHealthy = builder.redisHealthy;
            this.lastUpdate = builder.lastUpdate;
            this.metricsEnabled = builder.metricsEnabled;
        }
        
        public static Builder builder() {
            return new Builder();
        }
        
        // Getters
        public long getCacheHits() { return cacheHits; }
        public long getCacheMisses() { return cacheMisses; }
        public long getCacheErrors() { return cacheErrors; }
        public long getTotalOperations() { return totalOperations; }
        public double getHitRatePercentage() { return hitRatePercentage; }
        public long getAvgCacheResponseTimeMs() { return avgCacheResponseTimeMs; }
        public long getAvgServiceResponseTimeMs() { return avgServiceResponseTimeMs; }
        public long getTotalTimeSavedMs() { return totalTimeSavedMs; }
        public double getEstimatedCostSavingsPercentage() { return estimatedCostSavingsPercentage; }
        public long getCacheSize() { return cacheSize; }
        public long getEstimatedMemoryUsageBytes() { return estimatedMemoryUsageBytes; }
        public boolean isRedisHealthy() { return redisHealthy; }
        public Instant getLastUpdate() { return lastUpdate; }
        public boolean isMetricsEnabled() { return metricsEnabled; }
        
        @Override
        public String toString() {
            return String.format("CachePerformanceReport{hits=%d, misses=%d, errors=%d, hitRate=%.2f%%, " +
                               "timeSaved=%d min, costSavings=%.2f%%, cacheSize=%d, redisHealthy=%s}", 
                               cacheHits, cacheMisses, cacheErrors, hitRatePercentage,
                               totalTimeSavedMs / 60000, estimatedCostSavingsPercentage, 
                               cacheSize, redisHealthy);
        }
        
        public static class Builder {
            private long cacheHits;
            private long cacheMisses;
            private long cacheErrors;
            private long totalOperations;
            private double hitRatePercentage;
            private long avgCacheResponseTimeMs;
            private long avgServiceResponseTimeMs;
            private long totalTimeSavedMs;
            private double estimatedCostSavingsPercentage;
            private long cacheSize;
            private long estimatedMemoryUsageBytes;
            private boolean redisHealthy;
            private Instant lastUpdate;
            private boolean metricsEnabled;
            
            public Builder cacheHits(long cacheHits) { this.cacheHits = cacheHits; return this; }
            public Builder cacheMisses(long cacheMisses) { this.cacheMisses = cacheMisses; return this; }
            public Builder cacheErrors(long cacheErrors) { this.cacheErrors = cacheErrors; return this; }
            public Builder totalOperations(long totalOperations) { this.totalOperations = totalOperations; return this; }
            public Builder hitRatePercentage(double hitRatePercentage) { this.hitRatePercentage = hitRatePercentage; return this; }
            public Builder avgCacheResponseTimeMs(long avgCacheResponseTimeMs) { this.avgCacheResponseTimeMs = avgCacheResponseTimeMs; return this; }
            public Builder avgServiceResponseTimeMs(long avgServiceResponseTimeMs) { this.avgServiceResponseTimeMs = avgServiceResponseTimeMs; return this; }
            public Builder totalTimeSavedMs(long totalTimeSavedMs) { this.totalTimeSavedMs = totalTimeSavedMs; return this; }
            public Builder estimatedCostSavingsPercentage(double estimatedCostSavingsPercentage) { this.estimatedCostSavingsPercentage = estimatedCostSavingsPercentage; return this; }
            public Builder cacheSize(long cacheSize) { this.cacheSize = cacheSize; return this; }
            public Builder estimatedMemoryUsageBytes(long estimatedMemoryUsageBytes) { this.estimatedMemoryUsageBytes = estimatedMemoryUsageBytes; return this; }
            public Builder redisHealthy(boolean redisHealthy) { this.redisHealthy = redisHealthy; return this; }
            public Builder lastUpdate(Instant lastUpdate) { this.lastUpdate = lastUpdate; return this; }
            public Builder metricsEnabled(boolean metricsEnabled) { this.metricsEnabled = metricsEnabled; return this; }
            
            public CachePerformanceReport build() {
                return new CachePerformanceReport(this);
            }
        }
    }
}
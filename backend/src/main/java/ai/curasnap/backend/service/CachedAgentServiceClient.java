package ai.curasnap.backend.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cached wrapper for AgentServiceClient that provides Redis-based caching.
 * 
 * This service acts as a decorator around AgentServiceClient, providing:
 * - Redis-based response caching with configurable TTL
 * - Automatic fallback to original service on cache errors
 * - Performance metrics and cache hit/miss tracking
 * - Cost savings by reducing API calls to AI service
 * 
 * The cache uses SHA256-based keys to ensure security and collision resistance.
 */
@Service
@Primary
@ConditionalOnProperty(name = "app.agent.cache.enabled", havingValue = "true", matchIfMissing = false)
public class CachedAgentServiceClient implements AgentServiceClientInterface {

    private static final Logger logger = LoggerFactory.getLogger(CachedAgentServiceClient.class);
    
    private final AgentServiceClient delegateClient;
    private final CacheKeyGenerator cacheKeyGenerator;
    private final RedisTemplate<String, Object> redisTemplate;
    private final AgentCacheMetricsService metricsService;
    private final MeterRegistry meterRegistry;
    private final boolean cacheEnabled;
    private final Duration cacheTtl;
    private final boolean metricsEnabled;
    
    // Performance metrics (legacy - kept for backward compatibility)
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicLong cacheErrors = new AtomicLong(0);
    private final AtomicLong totalRequests = new AtomicLong(0);
    
    // Micrometer metrics
    private final Counter cacheHitCounter;
    private final Counter cacheMissCounter;
    private final Counter cacheErrorCounter;
    private final Timer cacheHitTimer;
    private final Timer cacheMissTimer;
    private final Timer cacheWriteTimer;
    private Gauge cacheSizeGauge;
    
    // Cache monitoring
    private volatile long lastCacheSizeCheck = 0;
    private volatile long currentCacheSize = 0;
    
    @Autowired
    public CachedAgentServiceClient(
            AgentServiceClient delegateClient,
            CacheKeyGenerator cacheKeyGenerator,
            RedisTemplate<String, Object> redisTemplate,
            AgentCacheMetricsService metricsService,
            MeterRegistry meterRegistry,
            @Value("${app.agent.cache.enabled:true}") boolean cacheEnabled,
            @Value("${app.agent.cache.ttl.hours:24}") int cacheTtlHours,
            @Value("${app.agent.cache.metrics.enabled:true}") boolean metricsEnabled) {
        
        this.delegateClient = delegateClient;
        this.cacheKeyGenerator = cacheKeyGenerator;
        this.redisTemplate = redisTemplate;
        this.metricsService = metricsService;
        this.meterRegistry = meterRegistry;
        this.cacheEnabled = cacheEnabled;
        this.cacheTtl = Duration.ofHours(cacheTtlHours);
        this.metricsEnabled = metricsEnabled;
        
        // Initialize Micrometer metrics
        this.cacheHitCounter = Counter.builder("agent_cache_operations_total")
                .description("Agent service cache operations")
                .tag("operation", "hit")
                .tag("cache_type", "agent_service")
                .register(meterRegistry);
                
        this.cacheMissCounter = Counter.builder("agent_cache_operations_total")
                .description("Agent service cache operations")
                .tag("operation", "miss")
                .tag("cache_type", "agent_service")
                .register(meterRegistry);
                
        this.cacheErrorCounter = Counter.builder("agent_cache_operations_total")
                .description("Agent service cache operations")
                .tag("operation", "error")
                .tag("cache_type", "agent_service")
                .register(meterRegistry);
                
        this.cacheHitTimer = Timer.builder("agent_cache_hit_duration_seconds")
                .description("Cache hit response time")
                .tag("cache_type", "agent_service")
                .register(meterRegistry);
                
        this.cacheMissTimer = Timer.builder("agent_cache_miss_duration_seconds")
                .description("Cache miss (original service call) duration")
                .tag("cache_type", "agent_service")
                .register(meterRegistry);
                
        this.cacheWriteTimer = Timer.builder("agent_cache_write_duration_seconds")
                .description("Cache write operation duration")
                .tag("cache_type", "agent_service")
                .register(meterRegistry);
                
        // Initialize cache size gauge
        this.cacheSizeGauge = Gauge.builder("agent_cache_size_entries", this, CachedAgentServiceClient::getCurrentCacheSize)
                .description("Current number of entries in agent service cache")
                .tag("cache_type", "agent_service")
                .register(meterRegistry);
                
        // Initialize cache hit rate gauge
        Gauge.builder("agent_cache_hit_rate_percent", this, client -> {
                    long hits = client.cacheHits.get();
                    long total = client.totalRequests.get();
                    return total > 0 ? (double) hits / total * 100.0 : 0.0;
                })
                .description("Agent service cache hit rate percentage")
                .tag("cache_type", "agent_service")
                .register(meterRegistry);
                
        // Record cache configuration metrics as static value
        meterRegistry.gauge("agent_cache_ttl_hours", 
                          io.micrometer.core.instrument.Tags.of("cache_type", "agent_service"), 
                          cacheTtlHours);
        
        logger.info("CachedAgentServiceClient initialized - Cache enabled: {}, TTL: {} hours, Metrics: {}", 
                   cacheEnabled, cacheTtlHours, metricsEnabled);
    }
    
    /**
     * Formats transcript to SOAP with Redis caching.
     * 
     * Process:
     * 1. Generate secure cache key from transcript content
     * 2. Check Redis cache for existing result
     * 3. If cache hit: return cached result and update metrics
     * 4. If cache miss: call original service, cache result, update metrics
     * 5. On any cache error: fallback to original service without caching
     * 
     * @param transcript the transcript content to format
     * @return formatted SOAP note text, or null if service unavailable
     */
    public String formatTranscriptToSoap(String transcript) {
        if (metricsEnabled) {
            totalRequests.incrementAndGet();
        }
        
        // If caching is disabled, delegate directly
        if (!cacheEnabled) {
            logger.debug("Cache disabled, delegating directly to AgentServiceClient");
            return delegateClient.formatTranscriptToSoap(transcript);
        }
        
        try {
            long startTime = System.currentTimeMillis();
            
            // Generate secure cache key
            String cacheKey = cacheKeyGenerator.generateCacheKey(transcript);
            logger.debug("Generated cache key for transcript lookup");
            
            // Try cache lookup
            Object cachedResult = redisTemplate.opsForValue().get(cacheKey);
            
            if (cachedResult instanceof String) {
                // Cache hit
                long responseTime = System.currentTimeMillis() - startTime;
                if (metricsEnabled) {
                    cacheHits.incrementAndGet();
                    metricsService.recordCacheOperation(AgentCacheMetricsService.CacheOperation.HIT, responseTime);
                    
                    // Record Micrometer metrics
                    cacheHitCounter.increment();
                    cacheHitTimer.record(Duration.ofMillis(responseTime));
                }
                logger.debug("Cache hit for transcript (key: {}...) in {}ms", cacheKey.substring(0, Math.min(20, cacheKey.length())), responseTime);
                return (String) cachedResult;
            }
            
            // Cache miss - call original service
            if (metricsEnabled) {
                cacheMisses.incrementAndGet();
                metricsService.recordCacheOperation(AgentCacheMetricsService.CacheOperation.MISS, 0);
                cacheMissCounter.increment();
            }
            logger.debug("Cache miss for transcript, calling original AgentServiceClient");
            
            // Time the original service call
            Timer.Sample serviceSample = Timer.start(meterRegistry);
            String soapResult = delegateClient.formatTranscriptToSoap(transcript);
            if (metricsEnabled) {
                serviceSample.stop(cacheMissTimer);
            }
            
            // Cache the result if successful
            if (soapResult != null) {
                Timer.Sample writeTimer = Timer.start(meterRegistry);
                try {
                    redisTemplate.opsForValue().set(cacheKey, soapResult, cacheTtl);
                    if (metricsEnabled) {
                        writeTimer.stop(cacheWriteTimer);
                    }
                    logger.debug("Cached SOAP result with TTL: {} hours", cacheTtl.toHours());
                } catch (Exception cacheWriteException) {
                    // Log but don't fail the request on cache write errors
                    logger.warn("Failed to cache SOAP result: {}", cacheWriteException.getMessage());
                    if (metricsEnabled) {
                        cacheErrors.incrementAndGet();
                        meterRegistry.counter("agent_cache_operations_total", 
                                            "operation", "error",
                                            "cache_type", "agent_service",
                                            "error_type", "cache_write_failure").increment();
                    }
                }
            }
            
            return soapResult;
            
        } catch (Exception cacheException) {
            // Log cache error and fallback to original service
            logger.warn("Cache operation failed, falling back to original service: {}", cacheException.getMessage());
            if (metricsEnabled) {
                cacheErrors.incrementAndGet();
                metricsService.recordCacheOperation(AgentCacheMetricsService.CacheOperation.ERROR, 0);
                meterRegistry.counter("agent_cache_operations_total", 
                                    "operation", "error",
                                    "cache_type", "agent_service",
                                    "error_type", cacheException.getClass().getSimpleName()).increment();
            }
            
            // Fallback to original service
            Timer.Sample fallbackTimer = Timer.start(meterRegistry);
            String result = delegateClient.formatTranscriptToSoap(transcript);
            if (metricsEnabled) {
                fallbackTimer.stop(Timer.builder("agent_cache_fallback_duration_seconds")
                                     .description("Cache fallback service call duration")
                                     .tag("cache_type", "agent_service")
                                     .register(meterRegistry));
            }
            return result;
        }
    }
    
    /**
     * Checks if the Agent Service is available.
     * Delegates to the underlying AgentServiceClient.
     * 
     * @return true if the service is enabled and should be used
     */
    public boolean isAgentServiceAvailable() {
        return delegateClient.isAgentServiceAvailable();
    }
    
    /**
     * Clears all cached entries for this service.
     * 
     * This method removes all cache entries with the agent service prefix.
     * Use with caution as this will force all subsequent requests to hit the AI service.
     * 
     * @return number of keys cleared from cache
     */
    public long clearCache() {
        if (!cacheEnabled) {
            logger.info("Cache is disabled, no entries to clear");
            return 0;
        }
        
        try {
            String pattern = cacheKeyGenerator.getCacheKeyPrefix() + "*";
            var keys = redisTemplate.keys(pattern);
            
            if (keys != null && !keys.isEmpty()) {
                Long deleted = redisTemplate.delete(keys);
                logger.info("Cleared {} cache entries with pattern: {}", deleted, pattern);
                return deleted != null ? deleted : 0;
            }
            
            logger.info("No cache entries found to clear");
            return 0;
            
        } catch (Exception e) {
            logger.error("Failed to clear cache: {}", e.getMessage());
            return 0;
        }
    }
    
    /**
     * Gets current cache performance metrics.
     * 
     * @return CacheMetrics object with current performance data
     */
    public CacheMetrics getMetrics() {
        if (!metricsEnabled) {
            return new CacheMetrics(0, 0, 0, 0, 0.0, false);
        }
        
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        long errors = cacheErrors.get();
        long total = totalRequests.get();
        
        double hitRate = total > 0 ? (double) hits / total * 100.0 : 0.0;
        
        return new CacheMetrics(hits, misses, errors, total, hitRate, cacheEnabled);
    }
    
    /**
     * Gets current cache size by querying Redis.
     * This method is called by the Micrometer gauge.
     */
    private double getCurrentCacheSize() {
        if (!cacheEnabled) {
            return 0.0;
        }
        
        try {
            // Only check cache size every 30 seconds to avoid performance impact
            long now = System.currentTimeMillis();
            if (now - lastCacheSizeCheck > 30000) {
                String pattern = cacheKeyGenerator.getCacheKeyPrefix() + "*";
                Set<String> keys = redisTemplate.keys(pattern);
                currentCacheSize = keys != null ? keys.size() : 0;
                lastCacheSizeCheck = now;
            }
            return currentCacheSize;
        } catch (Exception e) {
            logger.debug("Failed to get cache size: {}", e.getMessage());
            return 0.0;
        }
    }
    
    /**
     * Gets detailed cache performance metrics with Micrometer integration
     */
    public DetailedCacheMetrics getDetailedMetrics() {
        if (!metricsEnabled) {
            return new DetailedCacheMetrics();
        }
        
        return new DetailedCacheMetrics(
            cacheHits.get(),
            cacheMisses.get(),
            cacheErrors.get(),
            totalRequests.get(),
            getCurrentCacheSize(),
            cacheHitTimer.mean(java.util.concurrent.TimeUnit.MILLISECONDS),
            cacheMissTimer.mean(java.util.concurrent.TimeUnit.MILLISECONDS),
            cacheWriteTimer.mean(java.util.concurrent.TimeUnit.MILLISECONDS),
            cacheTtl.toHours(),
            cacheEnabled
        );
    }
    
    /**
     * Extended cache metrics with additional performance data
     */
    public static class DetailedCacheMetrics {
        private final long cacheHits;
        private final long cacheMisses;
        private final long cacheErrors;
        private final long totalRequests;
        private final double cacheSize;
        private final double averageHitTime;
        private final double averageMissTime;
        private final double averageWriteTime;
        private final long cacheTtlHours;
        private final boolean cacheEnabled;
        
        public DetailedCacheMetrics() {
            this(0, 0, 0, 0, 0, 0, 0, 0, 0, false);
        }
        
        public DetailedCacheMetrics(long cacheHits, long cacheMisses, long cacheErrors,
                                   long totalRequests, double cacheSize,
                                   double averageHitTime, double averageMissTime, double averageWriteTime,
                                   long cacheTtlHours, boolean cacheEnabled) {
            this.cacheHits = cacheHits;
            this.cacheMisses = cacheMisses;
            this.cacheErrors = cacheErrors;
            this.totalRequests = totalRequests;
            this.cacheSize = cacheSize;
            this.averageHitTime = averageHitTime;
            this.averageMissTime = averageMissTime;
            this.averageWriteTime = averageWriteTime;
            this.cacheTtlHours = cacheTtlHours;
            this.cacheEnabled = cacheEnabled;
        }
        
        // Getters
        public long getCacheHits() { return cacheHits; }
        public long getCacheMisses() { return cacheMisses; }
        public long getCacheErrors() { return cacheErrors; }
        public long getTotalRequests() { return totalRequests; }
        public double getCacheSize() { return cacheSize; }
        public double getAverageHitTime() { return averageHitTime; }
        public double getAverageMissTime() { return averageMissTime; }
        public double getAverageWriteTime() { return averageWriteTime; }
        public long getCacheTtlHours() { return cacheTtlHours; }
        public boolean isCacheEnabled() { return cacheEnabled; }
        
        public double getHitRatePercentage() {
            return totalRequests > 0 ? (double) cacheHits / totalRequests * 100.0 : 0.0;
        }
        
        public double getErrorRatePercentage() {
            return totalRequests > 0 ? (double) cacheErrors / totalRequests * 100.0 : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("DetailedCacheMetrics{hits=%d, misses=%d, errors=%d, total=%d, size=%.0f, " +
                               "hitRate=%.2f%%, errorRate=%.2f%%, avgHitTime=%.2fms, avgMissTime=%.2fms, avgWriteTime=%.2fms, " +
                               "ttl=%dh, enabled=%s}",
                               cacheHits, cacheMisses, cacheErrors, totalRequests, cacheSize,
                               getHitRatePercentage(), getErrorRatePercentage(),
                               averageHitTime, averageMissTime, averageWriteTime,
                               cacheTtlHours, cacheEnabled);
        }
    }
    
    /**
     * Scheduled method to record cache health metrics
     */
    @Scheduled(fixedRate = 60000) // Every minute
    public void recordCacheHealthMetrics() {
        if (!metricsEnabled || !cacheEnabled) {
            return;
        }
        
        try {
            // Record current cache utilization
            double cacheSize = getCurrentCacheSize();
            meterRegistry.gauge("agent_cache_utilization_entries", cacheSize);
            
            // Calculate and record cache efficiency metrics
            long totalHits = cacheHits.get();
            long totalMisses = cacheMisses.get();
            long totalErrors = cacheErrors.get();
            long totalRequests = this.totalRequests.get();
            
            if (totalRequests > 0) {
                double hitRate = (double) totalHits / totalRequests;
                double errorRate = (double) totalErrors / totalRequests;
                
                meterRegistry.gauge("agent_cache_efficiency_hit_rate", hitRate);
                meterRegistry.gauge("agent_cache_efficiency_error_rate", errorRate);
                
                // Estimate cost savings (assuming cache hits save ~$0.001 per request)
                double estimatedSavings = totalHits * 0.001;
                meterRegistry.gauge("agent_cache_cost_savings_estimated_dollars", estimatedSavings);
            }
            
        } catch (Exception e) {
            logger.debug("Failed to record cache health metrics: {}", e.getMessage());
        }
    }
    
    /**
     * Resets all performance metrics.
     */
    public void resetMetrics() {
        if (metricsEnabled) {
            cacheHits.set(0);
            cacheMisses.set(0);
            cacheErrors.set(0);
            totalRequests.set(0);
            logger.info("Cache metrics reset");
            
            // Note: Micrometer counters cannot be reset, they are cumulative
            // This only resets the AtomicLong counters used for internal tracking
        }
    }
    
    /**
     * Data class for cache performance metrics.
     */
    public static class CacheMetrics {
        private final long cacheHits;
        private final long cacheMisses;
        private final long cacheErrors;
        private final long totalRequests;
        private final double hitRatePercentage;
        private final boolean cacheEnabled;
        
        public CacheMetrics(long cacheHits, long cacheMisses, long cacheErrors, 
                           long totalRequests, double hitRatePercentage, boolean cacheEnabled) {
            this.cacheHits = cacheHits;
            this.cacheMisses = cacheMisses;
            this.cacheErrors = cacheErrors;
            this.totalRequests = totalRequests;
            this.hitRatePercentage = hitRatePercentage;
            this.cacheEnabled = cacheEnabled;
        }
        
        public long getCacheHits() { return cacheHits; }
        public long getCacheMisses() { return cacheMisses; }
        public long getCacheErrors() { return cacheErrors; }
        public long getTotalRequests() { return totalRequests; }
        public double getHitRatePercentage() { return hitRatePercentage; }
        public boolean isCacheEnabled() { return cacheEnabled; }
        
        @Override
        public String toString() {
            return String.format("CacheMetrics{hits=%d, misses=%d, errors=%d, total=%d, hitRate=%.2f%%, enabled=%s}", 
                               cacheHits, cacheMisses, cacheErrors, totalRequests, hitRatePercentage, cacheEnabled);
        }
    }
}
package ai.curasnap.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
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
    private final boolean cacheEnabled;
    private final Duration cacheTtl;
    private final boolean metricsEnabled;
    
    // Performance metrics
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicLong cacheErrors = new AtomicLong(0);
    private final AtomicLong totalRequests = new AtomicLong(0);
    
    @Autowired
    public CachedAgentServiceClient(
            AgentServiceClient delegateClient,
            CacheKeyGenerator cacheKeyGenerator,
            RedisTemplate<String, Object> redisTemplate,
            AgentCacheMetricsService metricsService,
            @Value("${app.agent.cache.enabled:true}") boolean cacheEnabled,
            @Value("${app.agent.cache.ttl.hours:24}") int cacheTtlHours,
            @Value("${app.agent.cache.metrics.enabled:true}") boolean metricsEnabled) {
        
        this.delegateClient = delegateClient;
        this.cacheKeyGenerator = cacheKeyGenerator;
        this.redisTemplate = redisTemplate;
        this.metricsService = metricsService;
        this.cacheEnabled = cacheEnabled;
        this.cacheTtl = Duration.ofHours(cacheTtlHours);
        this.metricsEnabled = metricsEnabled;
        
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
                }
                logger.debug("Cache hit for transcript (key: {}...) in {}ms", cacheKey.substring(0, Math.min(20, cacheKey.length())), responseTime);
                return (String) cachedResult;
            }
            
            // Cache miss - call original service
            if (metricsEnabled) {
                cacheMisses.incrementAndGet();
                metricsService.recordCacheOperation(AgentCacheMetricsService.CacheOperation.MISS, 0);
            }
            logger.debug("Cache miss for transcript, calling original AgentServiceClient");
            
            String soapResult = delegateClient.formatTranscriptToSoap(transcript);
            
            // Cache the result if successful
            if (soapResult != null) {
                try {
                    redisTemplate.opsForValue().set(cacheKey, soapResult, cacheTtl);
                    logger.debug("Cached SOAP result with TTL: {} hours", cacheTtl.toHours());
                } catch (Exception cacheWriteException) {
                    // Log but don't fail the request on cache write errors
                    logger.warn("Failed to cache SOAP result: {}", cacheWriteException.getMessage());
                    if (metricsEnabled) {
                        cacheErrors.incrementAndGet();
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
            }
            
            // Fallback to original service
            return delegateClient.formatTranscriptToSoap(transcript);
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
     * Resets all performance metrics.
     */
    public void resetMetrics() {
        if (metricsEnabled) {
            cacheHits.set(0);
            cacheMisses.set(0);
            cacheErrors.set(0);
            totalRequests.set(0);
            logger.info("Cache metrics reset");
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
package ai.curasnap.backend.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CachedAgentServiceClient.
 * 
 * Tests cover caching logic, fallback behavior, metrics tracking,
 * and error handling scenarios.
 */
@ExtendWith(MockitoExtension.class)
class CachedAgentServiceClientTest {

    @Mock
    private AgentServiceClient delegateClient;
    
    @Mock
    private CacheKeyGenerator cacheKeyGenerator;
    
    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    
    @Mock
    private AgentCacheMetricsService metricsService;
    
    @Mock
    private ValueOperations<String, Object> valueOperations;

    private MeterRegistry meterRegistry;
    private CachedAgentServiceClient cachedClient;
    
    private static final String TEST_TRANSCRIPT = "Patient reports headache and nausea.";
    private static final String TEST_CACHE_KEY = "agent:soap:test123hash";
    private static final String TEST_SOAP_RESULT = "S: Patient reports headache and nausea.\nO: ...\nA: ...\nP: ...";

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        cachedClient = new CachedAgentServiceClient(
            delegateClient,
            cacheKeyGenerator,
            redisTemplate,
            metricsService,
            meterRegistry,
            true,  // cacheEnabled
            24,    // cacheTtlHours
            true   // metricsEnabled
        );
    }

    @Test
    @DisplayName("Should return cached result on cache hit")
    void shouldReturnCachedResultOnHit() {
        // Arrange
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(cacheKeyGenerator.generateCacheKey(TEST_TRANSCRIPT)).thenReturn(TEST_CACHE_KEY);
        when(valueOperations.get(TEST_CACHE_KEY)).thenReturn(TEST_SOAP_RESULT);
        
        // Act
        String result = cachedClient.formatTranscriptToSoap(TEST_TRANSCRIPT);
        
        // Assert
        assertEquals(TEST_SOAP_RESULT, result, "Should return cached result");
        
        // Verify interactions
        verify(cacheKeyGenerator).generateCacheKey(TEST_TRANSCRIPT);
        verify(valueOperations).get(TEST_CACHE_KEY);
        verify(metricsService).recordCacheOperation(eq(AgentCacheMetricsService.CacheOperation.HIT), anyLong());
        
        // Should not call delegate service on cache hit
        verify(delegateClient, never()).formatTranscriptToSoap(any());
    }

    @Test
    @DisplayName("Should call delegate service and cache result on cache miss")
    void shouldCallDelegateServiceOnMiss() {
        // Arrange
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(cacheKeyGenerator.generateCacheKey(TEST_TRANSCRIPT)).thenReturn(TEST_CACHE_KEY);
        when(valueOperations.get(TEST_CACHE_KEY)).thenReturn(null); // Cache miss
        when(delegateClient.formatTranscriptToSoap(TEST_TRANSCRIPT)).thenReturn(TEST_SOAP_RESULT);
        
        // Act
        String result = cachedClient.formatTranscriptToSoap(TEST_TRANSCRIPT);
        
        // Assert
        assertEquals(TEST_SOAP_RESULT, result, "Should return delegate service result");
        
        // Verify interactions
        verify(cacheKeyGenerator).generateCacheKey(TEST_TRANSCRIPT);
        verify(valueOperations).get(TEST_CACHE_KEY);
        verify(delegateClient).formatTranscriptToSoap(TEST_TRANSCRIPT);
        verify(valueOperations).set(TEST_CACHE_KEY, TEST_SOAP_RESULT, Duration.ofHours(24));
        verify(metricsService).recordCacheOperation(AgentCacheMetricsService.CacheOperation.MISS, 0);
    }

    @Test
    @DisplayName("Should handle cache read errors gracefully")
    void shouldHandleCacheReadErrors() {
        // Arrange
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(cacheKeyGenerator.generateCacheKey(TEST_TRANSCRIPT)).thenReturn(TEST_CACHE_KEY);
        when(valueOperations.get(TEST_CACHE_KEY)).thenThrow(new RuntimeException("Redis connection failed"));
        when(delegateClient.formatTranscriptToSoap(TEST_TRANSCRIPT)).thenReturn(TEST_SOAP_RESULT);
        
        // Act
        String result = cachedClient.formatTranscriptToSoap(TEST_TRANSCRIPT);
        
        // Assert
        assertEquals(TEST_SOAP_RESULT, result, "Should return delegate service result on cache error");
        
        // Verify fallback to delegate service
        verify(delegateClient).formatTranscriptToSoap(TEST_TRANSCRIPT);
        verify(metricsService).recordCacheOperation(AgentCacheMetricsService.CacheOperation.ERROR, 0);
    }

    @Test
    @DisplayName("Should handle cache write errors without failing request")
    void shouldHandleCacheWriteErrors() {
        // Arrange
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(cacheKeyGenerator.generateCacheKey(TEST_TRANSCRIPT)).thenReturn(TEST_CACHE_KEY);
        when(valueOperations.get(TEST_CACHE_KEY)).thenReturn(null); // Cache miss
        when(delegateClient.formatTranscriptToSoap(TEST_TRANSCRIPT)).thenReturn(TEST_SOAP_RESULT);
        doThrow(new RuntimeException("Redis write failed")).when(valueOperations)
            .set(TEST_CACHE_KEY, TEST_SOAP_RESULT, Duration.ofHours(24));
        
        // Act
        String result = cachedClient.formatTranscriptToSoap(TEST_TRANSCRIPT);
        
        // Assert
        assertEquals(TEST_SOAP_RESULT, result, "Should return result even if cache write fails");
        
        // Verify delegate service was called
        verify(delegateClient).formatTranscriptToSoap(TEST_TRANSCRIPT);
        verify(metricsService).recordCacheOperation(AgentCacheMetricsService.CacheOperation.MISS, 0);
    }

    @Test
    @DisplayName("Should bypass cache when disabled")
    void shouldBypassCacheWhenDisabled() {
        // Arrange - create client with cache disabled
        CachedAgentServiceClient disabledCachedClient = new CachedAgentServiceClient(
            delegateClient,
            cacheKeyGenerator,
            redisTemplate,
            metricsService,
            meterRegistry,
            false, // cacheEnabled = false
            24,
            true
        );
        
        when(delegateClient.formatTranscriptToSoap(TEST_TRANSCRIPT)).thenReturn(TEST_SOAP_RESULT);
        
        // Act
        String result = disabledCachedClient.formatTranscriptToSoap(TEST_TRANSCRIPT);
        
        // Assert
        assertEquals(TEST_SOAP_RESULT, result, "Should return delegate service result");
        
        // Verify cache was bypassed
        verify(delegateClient).formatTranscriptToSoap(TEST_TRANSCRIPT);
        verify(cacheKeyGenerator, never()).generateCacheKey(any());
        verify(valueOperations, never()).get(any());
    }

    @Test
    @DisplayName("Should handle null results from delegate service")
    void shouldHandleNullResults() {
        // Arrange
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(cacheKeyGenerator.generateCacheKey(TEST_TRANSCRIPT)).thenReturn(TEST_CACHE_KEY);
        when(valueOperations.get(TEST_CACHE_KEY)).thenReturn(null); // Cache miss
        when(delegateClient.formatTranscriptToSoap(TEST_TRANSCRIPT)).thenReturn(null);
        
        // Act
        String result = cachedClient.formatTranscriptToSoap(TEST_TRANSCRIPT);
        
        // Assert
        assertNull(result, "Should return null when delegate service returns null");
        
        // Verify null results are not cached
        verify(valueOperations, never()).set(eq(TEST_CACHE_KEY), any(), any(Duration.class));
    }

    @Test
    @DisplayName("Should delegate isAgentServiceAvailable to original client")
    void shouldDelegateAvailabilityCheck() {
        // Arrange
        when(delegateClient.isAgentServiceAvailable()).thenReturn(true);
        
        // Act
        boolean available = cachedClient.isAgentServiceAvailable();
        
        // Assert
        assertTrue(available, "Should return delegate client availability status");
        verify(delegateClient).isAgentServiceAvailable();
    }

    @Test
    @DisplayName("Should clear cache correctly")
    void shouldClearCache() {
        // Arrange
        String pattern = "agent:soap:*";
        Set<String> keys = Set.of("agent:soap:key1", "agent:soap:key2", "agent:soap:key3");
        
        when(cacheKeyGenerator.getCacheKeyPrefix()).thenReturn("agent:soap:");
        when(redisTemplate.keys(pattern)).thenReturn(keys);
        when(redisTemplate.delete(keys)).thenReturn(3L);
        
        // Act
        long cleared = cachedClient.clearCache();
        
        // Assert
        assertEquals(3, cleared, "Should return number of cleared keys");
        
        // Verify interactions
        verify(cacheKeyGenerator).getCacheKeyPrefix();
        verify(redisTemplate).keys(pattern);
        verify(redisTemplate).delete(keys);
    }

    @Test
    @DisplayName("Should handle empty cache during clear")
    void shouldHandleEmptyCacheClear() {
        // Arrange
        String pattern = "agent:soap:*";
        when(cacheKeyGenerator.getCacheKeyPrefix()).thenReturn("agent:soap:");
        when(redisTemplate.keys(pattern)).thenReturn(Set.of()); // Empty set
        
        // Act
        long cleared = cachedClient.clearCache();
        
        // Assert
        assertEquals(0, cleared, "Should return 0 for empty cache");
        
        // Verify delete was not called for empty set
        verify(redisTemplate, never()).delete(any(Set.class));
    }

    @Test
    @DisplayName("Should handle cache clear errors gracefully")
    void shouldHandleCacheClearErrors() {
        // Arrange
        when(cacheKeyGenerator.getCacheKeyPrefix()).thenReturn("agent:soap:");
        when(redisTemplate.keys("agent:soap:*")).thenThrow(new RuntimeException("Redis error"));
        
        // Act
        long cleared = cachedClient.clearCache();
        
        // Assert
        assertEquals(0, cleared, "Should return 0 on error");
        
        // Should not propagate the exception
        assertDoesNotThrow(() -> cachedClient.clearCache());
    }

    @Test
    @DisplayName("Should provide current metrics")
    void shouldProvideCurrentMetrics() {
        // Act
        CachedAgentServiceClient.CacheMetrics metrics = cachedClient.getMetrics();
        
        // Assert
        assertNotNull(metrics, "Should return metrics object");
        assertTrue(metrics.isCacheEnabled(), "Cache should be enabled in this test setup");
        assertEquals(0, metrics.getTotalRequests(), "Initial total requests should be 0");
        assertEquals(0.0, metrics.getHitRatePercentage(), "Initial hit rate should be 0%");
    }

    @Test
    @DisplayName("Should reset metrics correctly")
    void shouldResetMetrics() {
        // Act
        assertDoesNotThrow(() -> cachedClient.resetMetrics());
        
        // Verify metrics are reset
        CachedAgentServiceClient.CacheMetrics metrics = cachedClient.getMetrics();
        assertEquals(0, metrics.getTotalRequests(), "Total requests should be reset to 0");
        assertEquals(0, metrics.getCacheHits(), "Cache hits should be reset to 0");
        assertEquals(0, metrics.getCacheMisses(), "Cache misses should be reset to 0");
        assertEquals(0, metrics.getCacheErrors(), "Cache errors should be reset to 0");
    }

    @Test
    @DisplayName("Should ignore non-string cached values")
    void shouldIgnoreNonStringCachedValues() {
        // Arrange
        Integer nonStringValue = 12345;
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(cacheKeyGenerator.generateCacheKey(TEST_TRANSCRIPT)).thenReturn(TEST_CACHE_KEY);
        when(valueOperations.get(TEST_CACHE_KEY)).thenReturn(nonStringValue); // Non-string value
        when(delegateClient.formatTranscriptToSoap(TEST_TRANSCRIPT)).thenReturn(TEST_SOAP_RESULT);
        
        // Act
        String result = cachedClient.formatTranscriptToSoap(TEST_TRANSCRIPT);
        
        // Assert
        assertEquals(TEST_SOAP_RESULT, result, "Should fallback to delegate service for non-string cache values");
        
        // Verify delegate service was called despite cache containing a value
        verify(delegateClient).formatTranscriptToSoap(TEST_TRANSCRIPT);
        verify(metricsService).recordCacheOperation(AgentCacheMetricsService.CacheOperation.MISS, 0);
    }
}
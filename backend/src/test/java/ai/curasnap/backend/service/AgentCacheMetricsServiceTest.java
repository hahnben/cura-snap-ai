package ai.curasnap.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AgentCacheMetricsService.
 * 
 * Tests cover metrics collection, performance reporting, scheduled tasks,
 * and error handling scenarios.
 */
@ExtendWith(MockitoExtension.class)
class AgentCacheMetricsServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    
    @Mock
    private CacheKeyGenerator cacheKeyGenerator;

    private AgentCacheMetricsService metricsService;

    @BeforeEach
    void setUp() {
        metricsService = new AgentCacheMetricsService(
            redisTemplate,
            cacheKeyGenerator,
            true // metricsEnabled
        );
    }

    @Test
    @DisplayName("Should record cache hit operations correctly")
    void shouldRecordCacheHitOperations() {
        // Act
        metricsService.recordCacheOperation(AgentCacheMetricsService.CacheOperation.HIT, 50);
        metricsService.recordCacheOperation(AgentCacheMetricsService.CacheOperation.HIT, 30);
        
        // Assert
        AgentCacheMetricsService.CachePerformanceReport report = metricsService.getPerformanceReport();
        assertEquals(2, report.getCacheHits(), "Should record correct number of cache hits");
        assertEquals(0, report.getCacheMisses(), "Cache misses should remain 0");
        assertEquals(0, report.getCacheErrors(), "Cache errors should remain 0");
        assertEquals(2, report.getTotalOperations(), "Total operations should be 2");
        assertEquals(100.0, report.getHitRatePercentage(), 0.01, "Hit rate should be 100%");
    }

    @Test
    @DisplayName("Should record cache miss operations correctly")
    void shouldRecordCacheMissOperations() {
        // Act
        metricsService.recordCacheOperation(AgentCacheMetricsService.CacheOperation.MISS, 0);
        metricsService.recordCacheOperation(AgentCacheMetricsService.CacheOperation.MISS, 0);
        metricsService.recordCacheOperation(AgentCacheMetricsService.CacheOperation.MISS, 0);
        
        // Assert
        AgentCacheMetricsService.CachePerformanceReport report = metricsService.getPerformanceReport();
        assertEquals(0, report.getCacheHits(), "Cache hits should remain 0");
        assertEquals(3, report.getCacheMisses(), "Should record correct number of cache misses");
        assertEquals(0, report.getCacheErrors(), "Cache errors should remain 0");
        assertEquals(3, report.getTotalOperations(), "Total operations should be 3");
        assertEquals(0.0, report.getHitRatePercentage(), 0.01, "Hit rate should be 0%");
    }

    @Test
    @DisplayName("Should record cache error operations correctly")
    void shouldRecordCacheErrorOperations() {
        // Act
        metricsService.recordCacheOperation(AgentCacheMetricsService.CacheOperation.ERROR, 0);
        metricsService.recordCacheOperation(AgentCacheMetricsService.CacheOperation.ERROR, 0);
        
        // Assert
        AgentCacheMetricsService.CachePerformanceReport report = metricsService.getPerformanceReport();
        assertEquals(0, report.getCacheHits(), "Cache hits should remain 0");
        assertEquals(0, report.getCacheMisses(), "Cache misses should remain 0");
        assertEquals(2, report.getCacheErrors(), "Should record correct number of cache errors");
        assertEquals(2, report.getTotalOperations(), "Total operations should be 2");
        assertEquals(0.0, report.getHitRatePercentage(), 0.01, "Hit rate should be 0% with only errors");
    }

    @Test
    @DisplayName("Should calculate mixed operation metrics correctly")
    void shouldCalculateMixedOperationMetrics() {
        // Act - simulate realistic cache usage: 70% hits, 25% misses, 5% errors
        for (int i = 0; i < 70; i++) {
            metricsService.recordCacheOperation(AgentCacheMetricsService.CacheOperation.HIT, 10);
        }
        for (int i = 0; i < 25; i++) {
            metricsService.recordCacheOperation(AgentCacheMetricsService.CacheOperation.MISS, 0);
        }
        for (int i = 0; i < 5; i++) {
            metricsService.recordCacheOperation(AgentCacheMetricsService.CacheOperation.ERROR, 0);
        }
        
        // Assert
        AgentCacheMetricsService.CachePerformanceReport report = metricsService.getPerformanceReport();
        assertEquals(70, report.getCacheHits(), "Should record correct cache hits");
        assertEquals(25, report.getCacheMisses(), "Should record correct cache misses");
        assertEquals(5, report.getCacheErrors(), "Should record correct cache errors");
        assertEquals(100, report.getTotalOperations(), "Total operations should be 100");
        assertEquals(70.0, report.getHitRatePercentage(), 0.01, "Hit rate should be 70%");
    }

    @Test
    @DisplayName("Should handle zero operations gracefully")
    void shouldHandleZeroOperations() {
        // Act - get report without any operations
        AgentCacheMetricsService.CachePerformanceReport report = metricsService.getPerformanceReport();
        
        // Assert
        assertEquals(0, report.getCacheHits(), "Cache hits should be 0");
        assertEquals(0, report.getCacheMisses(), "Cache misses should be 0");
        assertEquals(0, report.getCacheErrors(), "Cache errors should be 0");
        assertEquals(0, report.getTotalOperations(), "Total operations should be 0");
        assertEquals(0.0, report.getHitRatePercentage(), 0.01, "Hit rate should be 0% for no operations");
    }

    @Test
    @DisplayName("Should update average response time correctly")
    void shouldUpdateAverageResponseTime() {
        // Act
        metricsService.recordCacheOperation(AgentCacheMetricsService.CacheOperation.HIT, 100);
        metricsService.recordCacheOperation(AgentCacheMetricsService.CacheOperation.HIT, 50);
        metricsService.recordCacheOperation(AgentCacheMetricsService.CacheOperation.HIT, 25);
        
        // Assert
        AgentCacheMetricsService.CachePerformanceReport report = metricsService.getPerformanceReport();
        
        // Average should be influenced by exponential moving average
        // Starting at 0, then 0.9*0 + 0.1*100 = 10, then 0.9*10 + 0.1*50 = 14, then 0.9*14 + 0.1*25 ≈ 15
        assertTrue(report.getAvgCacheResponseTimeMs() > 0, "Average response time should be greater than 0");
        assertTrue(report.getAvgCacheResponseTimeMs() < 100, "Average response time should be less than 100");
    }

    @Test
    @DisplayName("Should provide KPI summary correctly")
    void shouldProvideKPISummary() {
        // Arrange
        metricsService.recordCacheOperation(AgentCacheMetricsService.CacheOperation.HIT, 20);
        metricsService.recordCacheOperation(AgentCacheMetricsService.CacheOperation.HIT, 30);
        metricsService.recordCacheOperation(AgentCacheMetricsService.CacheOperation.MISS, 0);
        
        // Act
        Map<String, Object> kpis = metricsService.getKPISummary();
        
        // Assert
        assertNotNull(kpis, "KPI summary should not be null");
        assertEquals(66.67, (Double) kpis.get("cache_hit_rate_percent"), 0.01, "Hit rate should be ~66.67%");
        assertEquals(3L, kpis.get("total_operations"), "Total operations should be 3");
        assertTrue(kpis.containsKey("cache_size"), "Should contain cache size");
        assertTrue(kpis.containsKey("time_saved_minutes"), "Should contain time saved");
        assertTrue(kpis.containsKey("cost_savings_percent"), "Should contain cost savings");
        assertTrue(kpis.containsKey("redis_healthy"), "Should contain Redis health status");
        assertTrue(kpis.containsKey("avg_cache_response_ms"), "Should contain average response time");
    }

    @Test
    @DisplayName("Should reset metrics correctly")
    void shouldResetMetrics() {
        // Arrange - record some operations
        metricsService.recordCacheOperation(AgentCacheMetricsService.CacheOperation.HIT, 50);
        metricsService.recordCacheOperation(AgentCacheMetricsService.CacheOperation.MISS, 0);
        metricsService.recordCacheOperation(AgentCacheMetricsService.CacheOperation.ERROR, 0);
        
        // Act
        metricsService.resetMetrics();
        
        // Assert
        AgentCacheMetricsService.CachePerformanceReport report = metricsService.getPerformanceReport();
        assertEquals(0, report.getCacheHits(), "Cache hits should be reset to 0");
        assertEquals(0, report.getCacheMisses(), "Cache misses should be reset to 0");
        assertEquals(0, report.getCacheErrors(), "Cache errors should be reset to 0");
        assertEquals(0, report.getTotalOperations(), "Total operations should be reset to 0");
        assertEquals(0.0, report.getHitRatePercentage(), 0.01, "Hit rate should be reset to 0%");
    }

    @Test
    @DisplayName("Should update cache metrics from Redis")
    void shouldUpdateCacheMetricsFromRedis() {
        // Arrange
        String pattern = "agent:soap:*";
        Set<String> keys = Set.of("agent:soap:key1", "agent:soap:key2", "agent:soap:key3");
        
        when(cacheKeyGenerator.getCacheKeyPrefix()).thenReturn("agent:soap:");
        when(redisTemplate.hasKey("health-check-key")).thenReturn(true);
        when(redisTemplate.keys(pattern)).thenReturn(keys);
        
        // Act
        metricsService.updateCacheMetrics();
        
        // Assert
        AgentCacheMetricsService.CachePerformanceReport report = metricsService.getPerformanceReport();
        assertTrue(report.isRedisHealthy(), "Redis should be marked as healthy");
        assertEquals(3, report.getCacheSize(), "Cache size should be 3");
        assertEquals(6600, report.getEstimatedMemoryUsageBytes(), "Memory usage should be calculated correctly (3 * 2200)");
    }

    @Test
    @DisplayName("Should handle Redis connection errors during metrics update")
    void shouldHandleRedisErrorsDuringMetricsUpdate() {
        // Arrange
        when(redisTemplate.hasKey("health-check-key")).thenThrow(new RuntimeException("Redis connection failed"));
        
        // Act
        metricsService.updateCacheMetrics();
        
        // Assert
        AgentCacheMetricsService.CachePerformanceReport report = metricsService.getPerformanceReport();
        assertFalse(report.isRedisHealthy(), "Redis should be marked as unhealthy after connection error");
    }

    @Test
    @DisplayName("Should not record operations when metrics disabled")
    void shouldNotRecordOperationsWhenDisabled() {
        // Arrange - create service with metrics disabled
        AgentCacheMetricsService disabledMetricsService = new AgentCacheMetricsService(
            redisTemplate,
            cacheKeyGenerator,
            false // metricsEnabled = false
        );
        
        // Act
        disabledMetricsService.recordCacheOperation(AgentCacheMetricsService.CacheOperation.HIT, 50);
        disabledMetricsService.recordCacheOperation(AgentCacheMetricsService.CacheOperation.MISS, 0);
        
        // Assert
        AgentCacheMetricsService.CachePerformanceReport report = disabledMetricsService.getPerformanceReport();
        assertFalse(report.isMetricsEnabled(), "Metrics should be disabled");
        assertEquals(0, report.getTotalOperations(), "No operations should be recorded when disabled");
    }

    @Test
    @DisplayName("Should calculate time savings correctly")
    void shouldCalculateTimeSavings() {
        // Arrange - simulate some cache hits
        for (int i = 0; i < 10; i++) {
            metricsService.recordCacheOperation(AgentCacheMetricsService.CacheOperation.HIT, 10);
        }
        
        // Act
        AgentCacheMetricsService.CachePerformanceReport report = metricsService.getPerformanceReport();
        
        // Assert
        assertTrue(report.getTotalTimeSavedMs() > 0, "Time saved should be positive");
        // Each cache hit saves (2000ms - ~10ms) = ~1990ms, 10 hits = ~19900ms
        assertTrue(report.getTotalTimeSavedMs() > 15000, "Time saved should be significant for multiple hits");
    }

    @Test
    @DisplayName("Performance report toString should be informative")
    void performanceReportToStringShouldBeInformative() {
        // Arrange
        metricsService.recordCacheOperation(AgentCacheMetricsService.CacheOperation.HIT, 25);
        metricsService.recordCacheOperation(AgentCacheMetricsService.CacheOperation.MISS, 0);
        
        // Act
        AgentCacheMetricsService.CachePerformanceReport report = metricsService.getPerformanceReport();
        String reportString = report.toString();
        
        // Assert
        assertNotNull(reportString, "ToString should not be null");
        assertTrue(reportString.contains("hits=1"), "Should contain hit count");
        assertTrue(reportString.contains("misses=1"), "Should contain miss count");
        assertTrue(reportString.contains("hitRate=50"), "Should contain hit rate");
        assertTrue(reportString.contains("redisHealthy="), "Should contain Redis health status");
    }
}
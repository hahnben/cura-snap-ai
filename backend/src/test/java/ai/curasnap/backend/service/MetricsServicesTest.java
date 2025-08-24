package ai.curasnap.backend.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for Phase 4.2: Metrics & Monitoring core services.
 * 
 * Tests the RequestLatencyMetricsService and BusinessMetricsService
 * without requiring Redis or full Spring context.
 */
public class MetricsServicesTest {

    private MeterRegistry meterRegistry;
    private RequestLatencyMetricsService requestLatencyMetricsService;
    private BusinessMetricsService businessMetricsService;
    private RedisTemplate<String, Object> redisTemplate;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        redisTemplate = mock(RedisTemplate.class);
        requestLatencyMetricsService = new RequestLatencyMetricsService(meterRegistry);
        businessMetricsService = new BusinessMetricsService(meterRegistry, redisTemplate);
    }

    /**
     * Test RequestLatencyMetricsService basic functionality
     */
    @Test
    void testRequestLatencyMetricsService() {
        // Test basic operation timing
        String result = requestLatencyMetricsService.timeOperation(
            "test_operation", 
            "/test/endpoint", 
            "POST",
            () -> {
                try {
                    Thread.sleep(50); // Simulate work
                    return "test_result";
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "interrupted";
                }
            }
        );

        assertThat(result).isEqualTo("test_result");

        // Verify metrics were recorded
        Timer timer = meterRegistry.find("http_request_duration_seconds")
                                   .tag("operation", "test_operation")
                                   .tag("endpoint", "/test/endpoint")
                                   .tag("method", "POST")
                                   .timer();
        
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.max(java.util.concurrent.TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(50);

        // Verify success counter
        Counter successCounter = meterRegistry.find("http_requests_total")
                                             .tag("operation", "test_operation")
                                             .tag("status", "success")
                                             .counter();
        assertThat(successCounter).isNotNull();
        assertThat(successCounter.count()).isEqualTo(1.0);
    }

    /**
     * Test RequestLatencyMetricsService error handling
     */
    @Test
    void testRequestLatencyMetricsServiceErrorHandling() {
        assertThrows(RuntimeException.class, () -> {
            requestLatencyMetricsService.timeOperation(
                "error_operation",
                "/test/error",
                "POST",
                () -> {
                    throw new RuntimeException("Test error");
                }
            );
        });

        // Verify error metrics were recorded
        Counter errorCounter = meterRegistry.find("http_requests_total")
                                           .tag("operation", "error_operation")
                                           .tag("status", "error")
                                           .tag("error_type", "RuntimeException")
                                           .counter();
        assertThat(errorCounter).isNotNull();
        assertThat(errorCounter.count()).isEqualTo(1.0);
    }

    /**
     * Test custom counter and gauge recording
     */
    @Test
    void testCustomMetricsRecording() {
        // Test counter increment
        requestLatencyMetricsService.incrementCounter(
            "test_business_metric", 
            "user_id", "test_user", 
            "action", "test_action"
        );

        Counter customCounter = meterRegistry.find("test_business_metric")
                                           .tag("user_id", "test_user")
                                           .tag("action", "test_action")
                                           .counter();
        assertThat(customCounter).isNotNull();
        assertThat(customCounter.count()).isEqualTo(1.0);

        // Test gauge recording
        requestLatencyMetricsService.recordGauge(
            "test_gauge_metric", 
            42.5, 
            "category", "test_category"
        );

        Gauge customGauge = meterRegistry.find("test_gauge_metric")
                                       .tag("category", "test_category")
                                       .gauge();
        assertThat(customGauge).isNotNull();
        assertThat(customGauge.value()).isEqualTo(42.5);
    }

    /**
     * Test BusinessMetricsService KPI tracking
     */
    @Test
    void testBusinessMetricsService() {
        // Record SOAP note generation
        businessMetricsService.recordSoapNoteGenerated(
            "test_user", 
            true, 
            1500, 
            Duration.ofMillis(500), 
            85.5
        );

        // Verify SOAP generation counter
        Counter soapCounter = meterRegistry.find("soap_notes_generated_total")
                                         .counter();
        assertThat(soapCounter).isNotNull();
        assertThat(soapCounter.count()).isEqualTo(1.0);

        // Record audio transcription
        businessMetricsService.recordAudioTranscription(
            "test_user",
            true,
            Duration.ofSeconds(120),
            1024000L,
            92.3
        );

        // Verify audio transcription counter
        Counter audioCounter = meterRegistry.find("audio_transcriptions_success_total")
                                          .counter();
        assertThat(audioCounter).isNotNull();
        assertThat(audioCounter.count()).isEqualTo(1.0);

        // Test user session recording
        businessMetricsService.recordUserSession(
            "test_user",
            Duration.ofMinutes(15),
            "internal_medicine"
        );

        // Verify session counter
        Counter sessionCounter = meterRegistry.find("user_sessions_total")
                                            .counter();
        assertThat(sessionCounter).isNotNull();
        assertThat(sessionCounter.count()).isEqualTo(1.0);

        // Test KPI summary generation
        Map<String, Object> kpis = businessMetricsService.getBusinessKPISummary();
        assertThat(kpis).isNotNull();
        assertThat(kpis).containsKey("soapGeneration");
        assertThat(kpis).containsKey("audioProcessing");
        assertThat(kpis).containsKey("userEngagement");
    }

    /**
     * Test multiple operations and metric aggregation
     */
    @Test
    void testMetricAggregation() {
        // Generate multiple operations
        for (int i = 0; i < 5; i++) {
            businessMetricsService.recordSoapNoteGenerated(
                "user_" + i, 
                true, 
                1000 + i * 100, 
                Duration.ofMillis(200 + i * 50), 
                80.0 + i
            );
        }

        // Record one failure
        businessMetricsService.recordSoapNoteGenerated(
            "user_fail", 
            false, 
            0, 
            Duration.ofMillis(100), 
            0.0
        );

        // Verify aggregated counters
        Counter successCounter = meterRegistry.find("soap_notes_generated_total")
                                            .counter();
        assertThat(successCounter.count()).isEqualTo(5.0);

        // Test KPI summary reflects aggregated data instead of individual counters
        Map<String, Object> kpis = businessMetricsService.getBusinessKPISummary();
        
        @SuppressWarnings("unchecked")
        Map<String, Object> soapGeneration = (Map<String, Object>) kpis.get("soapGeneration");
        assertThat(soapGeneration.get("totalGenerated")).isEqualTo(5.0);
        assertThat(soapGeneration.get("totalFailures")).isEqualTo(1.0);
        
        // Verify the failure counter exists and has expected count
        var failureCounters = meterRegistry.find("soap_generation_failures_total").counters();
        assertThat(failureCounters).hasSize(1);
        Counter failureCounter = failureCounters.iterator().next();
        assertThat(failureCounter.count()).isEqualTo(1.0);
    }

    /**
     * Test error scenarios and edge cases
     */
    @Test
    void testErrorScenarios() {
        // Test null handling in business metrics (should handle gracefully, but we expect userId to not be null in normal usage)
        assertThrows(NullPointerException.class, () -> {
            businessMetricsService.recordSoapNoteGenerated(null, true, 1000, Duration.ofMillis(500), 85.0);
        });

        // Test zero and negative values
        assertDoesNotThrow(() -> {
            businessMetricsService.recordSoapNoteGenerated("test_user", true, 0, Duration.ZERO, 0.0);
        });

        // Test very large values
        assertDoesNotThrow(() -> {
            businessMetricsService.recordAudioTranscription(
                "test_user", 
                true, 
                Duration.ofHours(5), 
                1_000_000_000L, 
                100.0
            );
        });
    }

    /**
     * Test concurrent metric updates
     */
    @Test
    void testConcurrentMetrics() throws InterruptedException {
        int threadCount = 10;
        int operationsPerThread = 100;
        
        Thread[] threads = new Thread[threadCount];
        
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < operationsPerThread; i++) {
                    requestLatencyMetricsService.incrementCounter(
                        "concurrent_test_metric",
                        "thread", String.valueOf(threadId),
                        "operation", String.valueOf(i)
                    );
                }
            });
        }

        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }

        // Verify total count
        double totalCount = meterRegistry.find("concurrent_test_metric")
                                       .counters()
                                       .stream()
                                       .mapToDouble(Counter::count)
                                       .sum();
        
        assertThat(totalCount).isEqualTo(threadCount * operationsPerThread);
    }
}
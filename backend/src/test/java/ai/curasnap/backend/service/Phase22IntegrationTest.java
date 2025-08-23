package ai.curasnap.backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.net.ConnectException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration test for Phase 2.2 Error Handling & Retry Logic
 * Tests basic functionality of all new services together
 */
@ExtendWith(MockitoExtension.class)
class Phase22IntegrationTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    
    @Mock
    private ValueOperations<String, Object> valueOperations;
    
    @Mock
    private WorkerHealthService workerHealthService;

    @Test
    void phase22Services_BasicFunctionality_ShouldWork() {
        // Given
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        
        // Test ErrorClassificationService
        ErrorClassificationService errorClassificationService = 
            new ErrorClassificationService(redisTemplate, workerHealthService);
        
        // Test CircuitBreakerService
        CircuitBreakerService circuitBreakerService = new CircuitBreakerService();
        circuitBreakerService.initialize();
        
        // When - Test error classification
        ErrorClassificationService.ErrorCategory category = 
            errorClassificationService.classifyError("test-service", 
                new ConnectException("Connection refused"));
        
        // Then - Should classify network errors correctly
        assertThat(category).isEqualTo(ErrorClassificationService.ErrorCategory.TRANSIENT_NETWORK);
        
        // When - Test circuit breaker initial state
        CircuitBreakerService.CircuitBreakerState state = 
            circuitBreakerService.getCircuitBreakerState("test-service");
            
        // Then - Should start in closed state
        assertThat(state).isEqualTo(CircuitBreakerService.CircuitBreakerState.CLOSED);
    }

    @Test
    void errorClassificationService_DifferentErrorTypes_ShouldClassifyCorrectly() {
        // Given
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        ErrorClassificationService service = new ErrorClassificationService(redisTemplate, workerHealthService);
        
        // Test different error types
        assertThat(service.classifyError("svc", new RuntimeException("Rate limit exceeded")))
            .isEqualTo(ErrorClassificationService.ErrorCategory.RATE_LIMITED);
            
        assertThat(service.classifyError("svc", new RuntimeException("Service unavailable")))
            .isEqualTo(ErrorClassificationService.ErrorCategory.SERVICE_UNAVAILABLE);
            
        assertThat(service.classifyError("svc", new RuntimeException("Invalid input format")))
            .isEqualTo(ErrorClassificationService.ErrorCategory.VALIDATION_ERROR);
            
        assertThat(service.classifyError("svc", new RuntimeException("Unauthorized access")))
            .isEqualTo(ErrorClassificationService.ErrorCategory.AUTHENTICATION_ERROR);
    }

    @Test
    void circuitBreakerService_BasicOperations_ShouldWork() throws Exception {
        // Given
        CircuitBreakerService service = new CircuitBreakerService();
        service.initialize();
        
        String serviceName = "test-service";
        
        // Test successful call
        String result = service.executeWithCircuitBreaker(serviceName, 
            () -> "success", 
            () -> "fallback");
        
        assertThat(result).isEqualTo("success");
        assertThat(service.getCircuitBreakerState(serviceName))
            .isEqualTo(CircuitBreakerService.CircuitBreakerState.CLOSED);
            
        // Test failed call with fallback
        String failResult = service.executeWithCircuitBreaker(serviceName,
            () -> { throw new RuntimeException("Test error"); },
            () -> "fallback");
            
        assertThat(failResult).isEqualTo("fallback");
        // Circuit should still be closed after one failure
        assertThat(service.getCircuitBreakerState(serviceName))
            .isEqualTo(CircuitBreakerService.CircuitBreakerState.CLOSED);
    }

    @Test
    void adaptiveRetryService_BasicFunctionality_ShouldWork() {
        // Given
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        
        ErrorClassificationService errorClassificationService = 
            new ErrorClassificationService(redisTemplate, workerHealthService);
        CircuitBreakerService circuitBreakerService = new CircuitBreakerService();
        circuitBreakerService.initialize();
        AdaptiveRetryService adaptiveRetryService = new AdaptiveRetryService(
            errorClassificationService, circuitBreakerService, workerHealthService, redisTemplate);
        
        // When - Test retry calculation for transient error
        AdaptiveRetryService.AdaptiveRetryResult result = 
            adaptiveRetryService.calculateAdaptiveRetry(
                "test-service", "job-123", 
                ai.curasnap.backend.model.dto.JobData.JobType.AUDIO_PROCESSING,
                new ConnectException("Connection failed"), 1);
        
        // Then - Should allow retry for transient network errors
        assertThat(result.isShouldRetry()).isTrue();
        assertThat(result.getDelayUntilNextTry().toMillis()).isGreaterThan(0);
        
        // When - Test retry calculation for validation error
        AdaptiveRetryService.AdaptiveRetryResult validationResult = 
            adaptiveRetryService.calculateAdaptiveRetry(
                "test-service", "job-456", 
                ai.curasnap.backend.model.dto.JobData.JobType.AUDIO_PROCESSING,
                new RuntimeException("Invalid input"), 0);
        
        // Then - Should not retry validation errors
        assertThat(validationResult.isShouldRetry()).isFalse();
    }

    @Test
    void serviceDegradationService_BasicFunctionality_ShouldWork() {
        // Given
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        
        CircuitBreakerService circuitBreakerService = new CircuitBreakerService();
        circuitBreakerService.initialize();
        ServiceDegradationService service = new ServiceDegradationService(
            circuitBreakerService, workerHealthService, redisTemplate);
        
        // When - Check initial state
        ServiceDegradationService.DegradationLevel level = service.getCurrentDegradationLevel();
        boolean isDegraded = service.isSystemDegraded();
        
        // Then - Should start in normal state
        assertThat(level).isEqualTo(ServiceDegradationService.DegradationLevel.NORMAL);
        assertThat(isDegraded).isFalse();
        
        // When - Test manual degradation
        service.setManualDegradationLevel(
            ServiceDegradationService.DegradationLevel.MODERATE_DEGRADATION, 
            "Test degradation");
            
        // Then - Should be in degraded state
        assertThat(service.getCurrentDegradationLevel())
            .isEqualTo(ServiceDegradationService.DegradationLevel.MODERATE_DEGRADATION);
        assertThat(service.isSystemDegraded()).isTrue();
    }

    @Test
    void enhancedMonitoringService_BasicFunctionality_ShouldWork() {
        // Given
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        
        CircuitBreakerService circuitBreakerService = new CircuitBreakerService();
        circuitBreakerService.initialize();
        ServiceDegradationService serviceDegradationService = new ServiceDegradationService(
            circuitBreakerService, workerHealthService, redisTemplate);
        EnhancedMonitoringService service = new EnhancedMonitoringService(
            redisTemplate, workerHealthService, circuitBreakerService, serviceDegradationService);
        
        // When - Record a metric
        service.recordMetric("test.metric", 42.0);
        service.incrementCounter("test.counter");
        
        // Then - Should be able to retrieve metrics
        EnhancedMonitoringService.MetricTimeSeries metric = service.getMetric("test.metric");
        assertThat(metric).isNotNull();
        assertThat(metric.getLatestValue()).isEqualTo(42.0);
        
        long counterValue = service.getCounterValue("test.counter");
        assertThat(counterValue).isEqualTo(1);
        
        // Test health summary
        var healthSummary = service.getHealthSummary();
        assertThat(healthSummary).isNotNull();
        assertThat(healthSummary).containsKey("timestamp");
    }

    @Test
    void deadLetterQueueService_BasicFunctionality_ShouldWork() {
        // Given
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForList()).thenReturn(mock(org.springframework.data.redis.core.ListOperations.class));
        
        ErrorClassificationService errorClassificationService = 
            new ErrorClassificationService(redisTemplate, workerHealthService);
        CircuitBreakerService circuitBreakerService = new CircuitBreakerService();
        circuitBreakerService.initialize();
        DeadLetterQueueService service = new DeadLetterQueueService(
            redisTemplate, errorClassificationService, circuitBreakerService, workerHealthService);
        
        // When - Test DLQ statistics (should not throw exceptions)
        DeadLetterQueueService.DLQStatistics stats = service.getDLQStatistics("audio_processing");
        
        // Then - Should return valid statistics
        assertThat(stats).isNotNull();
        assertThat(stats.getQueueName()).isEqualTo("audio_processing");
        assertThat(stats.getTotalEntries()).isGreaterThanOrEqualTo(0);
    }
}
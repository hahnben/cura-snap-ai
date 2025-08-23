package ai.curasnap.backend.service;

import ai.curasnap.backend.model.dto.JobData;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AdaptiveRetryService
 */
@ExtendWith(MockitoExtension.class)
class AdaptiveRetryServiceTest {

    @Mock
    private ErrorClassificationService errorClassificationService;
    
    @Mock
    private CircuitBreakerService circuitBreakerService;
    
    @Mock
    private WorkerHealthService workerHealthService;
    
    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    
    @Mock
    private ValueOperations<String, Object> valueOperations;

    private AdaptiveRetryService adaptiveRetryService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        adaptiveRetryService = new AdaptiveRetryService(
            errorClassificationService, 
            circuitBreakerService, 
            workerHealthService, 
            redisTemplate
        );
    }

    @Test
    void calculateAdaptiveRetry_TransientNetworkError_ShouldRetryWithExponentialBackoff() {
        // Given
        String serviceName = "transcription-service";
        String jobId = "test-job-123";
        JobData.JobType jobType = JobData.JobType.AUDIO_PROCESSING;
        Throwable error = new RuntimeException("Connection timeout");
        int currentRetryCount = 1;

        when(errorClassificationService.classifyError(serviceName, error))
                .thenReturn(ErrorClassificationService.ErrorCategory.TRANSIENT_NETWORK);
        when(circuitBreakerService.getCircuitBreakerState(serviceName))
                .thenReturn(CircuitBreakerService.CircuitBreakerState.CLOSED);

        // When
        AdaptiveRetryService.AdaptiveRetryResult result = adaptiveRetryService.calculateAdaptiveRetry(
                serviceName, jobId, jobType, error, currentRetryCount);

        // Then
        assertThat(result.isShouldRetry()).isTrue();
        assertThat(result.getDelayUntilNextTry()).isGreaterThan(Duration.ZERO);
        assertThat(result.getNextRetryAt()).isAfter(Instant.now());
        assertThat(result.getReason()).contains("Adaptive retry scheduled");
    }

    @Test
    void calculateAdaptiveRetry_ValidationError_ShouldNotRetry() {
        // Given
        String serviceName = "transcription-service";
        String jobId = "test-job-123";
        JobData.JobType jobType = JobData.JobType.AUDIO_PROCESSING;
        Throwable error = new RuntimeException("Invalid file format");
        int currentRetryCount = 0;

        when(errorClassificationService.classifyError(serviceName, error))
                .thenReturn(ErrorClassificationService.ErrorCategory.VALIDATION_ERROR);
        when(circuitBreakerService.getCircuitBreakerState(serviceName))
                .thenReturn(CircuitBreakerService.CircuitBreakerState.CLOSED);

        // When
        AdaptiveRetryService.AdaptiveRetryResult result = adaptiveRetryService.calculateAdaptiveRetry(
                serviceName, jobId, jobType, error, currentRetryCount);

        // Then
        assertThat(result.isShouldRetry()).isFalse();
        assertThat(result.getReason()).contains("Max retries exceeded or circuit breaker prevents retry");
    }

    @Test
    void calculateAdaptiveRetry_CircuitBreakerOpen_ShouldNotRetry() {
        // Given
        String serviceName = "transcription-service";
        String jobId = "test-job-123";
        JobData.JobType jobType = JobData.JobType.AUDIO_PROCESSING;
        Throwable error = new RuntimeException("Service unavailable");
        int currentRetryCount = 0;

        when(errorClassificationService.classifyError(serviceName, error))
                .thenReturn(ErrorClassificationService.ErrorCategory.SERVICE_UNAVAILABLE);
        when(circuitBreakerService.getCircuitBreakerState(serviceName))
                .thenReturn(CircuitBreakerService.CircuitBreakerState.OPEN);

        // When
        AdaptiveRetryService.AdaptiveRetryResult result = adaptiveRetryService.calculateAdaptiveRetry(
                serviceName, jobId, jobType, error, currentRetryCount);

        // Then
        assertThat(result.isShouldRetry()).isFalse();
        assertThat(result.getReason()).contains("Max retries exceeded or circuit breaker prevents retry");
    }

    @Test
    void calculateAdaptiveRetry_RateLimited_ShouldRetryWithLinearBackoff() {
        // Given
        String serviceName = "agent-service";
        String jobId = "test-job-456";
        JobData.JobType jobType = JobData.JobType.TEXT_PROCESSING;
        Throwable error = new RuntimeException("Rate limit exceeded");
        int currentRetryCount = 0;

        when(errorClassificationService.classifyError(serviceName, error))
                .thenReturn(ErrorClassificationService.ErrorCategory.RATE_LIMITED);
        when(circuitBreakerService.getCircuitBreakerState(serviceName))
                .thenReturn(CircuitBreakerService.CircuitBreakerState.CLOSED);

        // When
        AdaptiveRetryService.AdaptiveRetryResult result = adaptiveRetryService.calculateAdaptiveRetry(
                serviceName, jobId, jobType, error, currentRetryCount);

        // Then
        assertThat(result.isShouldRetry()).isTrue();
        assertThat(result.getDelayUntilNextTry()).isGreaterThanOrEqualTo(Duration.ofMinutes(1));
        assertThat(result.getReason()).contains("RATE_LIMITED error with LINEAR backoff");
    }

    @Test
    void calculateAdaptiveRetry_ServiceUnavailable_ShouldRetryWithFibonacciBackoff() {
        // Given
        String serviceName = "transcription-service";
        String jobId = "test-job-789";
        JobData.JobType jobType = JobData.JobType.AUDIO_PROCESSING;
        Throwable error = new RuntimeException("Service temporarily unavailable");
        int currentRetryCount = 2;

        when(errorClassificationService.classifyError(serviceName, error))
                .thenReturn(ErrorClassificationService.ErrorCategory.SERVICE_UNAVAILABLE);
        when(circuitBreakerService.getCircuitBreakerState(serviceName))
                .thenReturn(CircuitBreakerService.CircuitBreakerState.CLOSED);

        // When
        AdaptiveRetryService.AdaptiveRetryResult result = adaptiveRetryService.calculateAdaptiveRetry(
                serviceName, jobId, jobType, error, currentRetryCount);

        // Then
        assertThat(result.isShouldRetry()).isTrue();
        assertThat(result.getDelayUntilNextTry()).isGreaterThan(Duration.ofSeconds(10));
        assertThat(result.getReason()).contains("SERVICE_UNAVAILABLE error with FIBONACCI backoff");
    }

    @Test
    void calculateAdaptiveRetry_MaxRetriesExceeded_ShouldNotRetry() {
        // Given
        String serviceName = "transcription-service";
        String jobId = "test-job-999";
        JobData.JobType jobType = JobData.JobType.AUDIO_PROCESSING;
        Throwable error = new RuntimeException("Connection timeout");
        int currentRetryCount = 5; // Exceeds max retries for transient network

        when(errorClassificationService.classifyError(serviceName, error))
                .thenReturn(ErrorClassificationService.ErrorCategory.TRANSIENT_NETWORK);
        when(circuitBreakerService.getCircuitBreakerState(serviceName))
                .thenReturn(CircuitBreakerService.CircuitBreakerState.CLOSED);

        // When
        AdaptiveRetryService.AdaptiveRetryResult result = adaptiveRetryService.calculateAdaptiveRetry(
                serviceName, jobId, jobType, error, currentRetryCount);

        // Then
        assertThat(result.isShouldRetry()).isFalse();
        assertThat(result.getReason()).contains("Max retries exceeded");
    }

    @Test
    void calculateAdaptiveRetry_AuthenticationError_ShouldNotRetry() {
        // Given
        String serviceName = "agent-service";
        String jobId = "test-job-auth";
        JobData.JobType jobType = JobData.JobType.TEXT_PROCESSING;
        Throwable error = new RuntimeException("Unauthorized access");
        int currentRetryCount = 0;

        when(errorClassificationService.classifyError(serviceName, error))
                .thenReturn(ErrorClassificationService.ErrorCategory.AUTHENTICATION_ERROR);
        when(circuitBreakerService.getCircuitBreakerState(serviceName))
                .thenReturn(CircuitBreakerService.CircuitBreakerState.CLOSED);

        // When
        AdaptiveRetryService.AdaptiveRetryResult result = adaptiveRetryService.calculateAdaptiveRetry(
                serviceName, jobId, jobType, error, currentRetryCount);

        // Then
        assertThat(result.isShouldRetry()).isFalse();
        assertThat(result.getReason()).contains("Max retries exceeded or circuit breaker prevents retry");
    }

    @Test
    void calculateAdaptiveRetry_UnknownError_ShouldRetryWithExponentialBackoff() {
        // Given
        String serviceName = "unknown-service";
        String jobId = "test-job-unknown";
        JobData.JobType jobType = JobData.JobType.AUDIO_PROCESSING;
        Throwable error = new RuntimeException("Unknown error");
        int currentRetryCount = 1;

        when(errorClassificationService.classifyError(serviceName, error))
                .thenReturn(ErrorClassificationService.ErrorCategory.UNKNOWN_ERROR);
        when(circuitBreakerService.getCircuitBreakerState(serviceName))
                .thenReturn(CircuitBreakerService.CircuitBreakerState.CLOSED);

        // When
        AdaptiveRetryService.AdaptiveRetryResult result = adaptiveRetryService.calculateAdaptiveRetry(
                serviceName, jobId, jobType, error, currentRetryCount);

        // Then
        assertThat(result.isShouldRetry()).isTrue();
        assertThat(result.getDelayUntilNextTry()).isGreaterThan(Duration.ZERO);
        assertThat(result.getReason()).contains("UNKNOWN_ERROR error with EXPONENTIAL backoff");
    }

    @Test
    void calculateAdaptiveRetry_ExceptionDuringProcessing_ShouldReturnFallback() {
        // Given
        String serviceName = "transcription-service";
        String jobId = "test-job-exception";
        JobData.JobType jobType = JobData.JobType.AUDIO_PROCESSING;
        Throwable error = new RuntimeException("Test error");
        int currentRetryCount = 1;

        when(errorClassificationService.classifyError(serviceName, error))
                .thenThrow(new RuntimeException("Classification service error"));

        // When
        AdaptiveRetryService.AdaptiveRetryResult result = adaptiveRetryService.calculateAdaptiveRetry(
                serviceName, jobId, jobType, error, currentRetryCount);

        // Then - should fall back to simple exponential backoff
        assertThat(result.isShouldRetry()).isTrue();
        assertThat(result.getDelayUntilNextTry()).isEqualTo(Duration.ofSeconds(4)); // 2^1 * 2 seconds base
        assertThat(result.getReason()).contains("Fallback retry strategy due to calculation error");
    }

    @Test
    void calculateAdaptiveRetry_CircuitBreakerHalfOpen_ShouldApplyCautiousDelay() {
        // Given
        String serviceName = "transcription-service";
        String jobId = "test-job-half-open";
        JobData.JobType jobType = JobData.JobType.AUDIO_PROCESSING;
        Throwable error = new RuntimeException("Service recovering");
        int currentRetryCount = 0;

        when(errorClassificationService.classifyError(serviceName, error))
                .thenReturn(ErrorClassificationService.ErrorCategory.TRANSIENT_NETWORK);
        when(circuitBreakerService.getCircuitBreakerState(serviceName))
                .thenReturn(CircuitBreakerService.CircuitBreakerState.HALF_OPEN);

        // When
        AdaptiveRetryService.AdaptiveRetryResult result = adaptiveRetryService.calculateAdaptiveRetry(
                serviceName, jobId, jobType, error, currentRetryCount);

        // Then - should still retry but with increased delay due to half-open circuit
        assertThat(result.isShouldRetry()).isTrue();
        assertThat(result.getDelayUntilNextTry()).isGreaterThan(Duration.ofSeconds(2));
        assertThat(result.getReason()).contains("TRANSIENT_NETWORK error with EXPONENTIAL backoff");
    }
}
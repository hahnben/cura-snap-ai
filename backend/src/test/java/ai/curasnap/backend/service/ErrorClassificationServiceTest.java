package ai.curasnap.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.HashOperations;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ErrorClassificationService
 */
@ExtendWith(MockitoExtension.class)
class ErrorClassificationServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    
    @Mock
    private WorkerHealthService workerHealthService;
    
    @Mock
    private ValueOperations<String, Object> valueOperations;
    
    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private ErrorClassificationService errorClassificationService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        
        errorClassificationService = new ErrorClassificationService(redisTemplate, workerHealthService);
    }

    @Test
    void classifyError_NetworkConnectionError_ShouldReturnTransientNetwork() {
        // Given
        String serviceName = "transcription-service";
        Throwable error = new ConnectException("Connection refused");

        // When
        ErrorClassificationService.ErrorCategory category = 
                errorClassificationService.classifyError(serviceName, error);

        // Then
        assertThat(category).isEqualTo(ErrorClassificationService.ErrorCategory.TRANSIENT_NETWORK);
    }

    @Test
    void classifyError_SocketTimeout_ShouldReturnTransientNetwork() {
        // Given
        String serviceName = "agent-service";
        Throwable error = new SocketTimeoutException("Read timeout");

        // When
        ErrorClassificationService.ErrorCategory category = 
                errorClassificationService.classifyError(serviceName, error);

        // Then
        assertThat(category).isEqualTo(ErrorClassificationService.ErrorCategory.TRANSIENT_NETWORK);
    }

    @Test
    void classifyError_RateLimitMessage_ShouldReturnRateLimited() {
        // Given
        String serviceName = "transcription-service";
        Throwable error = new RuntimeException("Rate limit exceeded - too many requests");

        // When
        ErrorClassificationService.ErrorCategory category = 
                errorClassificationService.classifyError(serviceName, error);

        // Then
        assertThat(category).isEqualTo(ErrorClassificationService.ErrorCategory.RATE_LIMITED);
    }

    @Test
    void classifyError_Http429_ShouldReturnRateLimited() {
        // Given
        String serviceName = "agent-service";
        Throwable error = new RuntimeException("HTTP 429: Too Many Requests");

        // When
        ErrorClassificationService.ErrorCategory category = 
                errorClassificationService.classifyError(serviceName, error);

        // Then
        assertThat(category).isEqualTo(ErrorClassificationService.ErrorCategory.RATE_LIMITED);
    }

    @Test
    void classifyError_ServiceUnavailable_ShouldReturnServiceUnavailable() {
        // Given
        String serviceName = "transcription-service";
        Throwable error = new RuntimeException("Service unavailable - HTTP 503");

        // When
        ErrorClassificationService.ErrorCategory category = 
                errorClassificationService.classifyError(serviceName, error);

        // Then
        assertThat(category).isEqualTo(ErrorClassificationService.ErrorCategory.SERVICE_UNAVAILABLE);
    }

    @Test
    void classifyError_BadGateway_ShouldReturnServiceUnavailable() {
        // Given
        String serviceName = "agent-service";
        Throwable error = new RuntimeException("502 Bad Gateway");

        // When
        ErrorClassificationService.ErrorCategory category = 
                errorClassificationService.classifyError(serviceName, error);

        // Then
        assertThat(category).isEqualTo(ErrorClassificationService.ErrorCategory.SERVICE_UNAVAILABLE);
    }

    @Test
    void classifyError_AuthenticationError_ShouldReturnAuthenticationError() {
        // Given
        String serviceName = "agent-service";
        Throwable error = new RuntimeException("Unauthorized - invalid token");

        // When
        ErrorClassificationService.ErrorCategory category = 
                errorClassificationService.classifyError(serviceName, error);

        // Then
        assertThat(category).isEqualTo(ErrorClassificationService.ErrorCategory.AUTHENTICATION_ERROR);
    }

    @Test
    void classifyError_ForbiddenAccess_ShouldReturnAuthenticationError() {
        // Given
        String serviceName = "transcription-service";
        Throwable error = new RuntimeException("HTTP 403 Forbidden");

        // When
        ErrorClassificationService.ErrorCategory category = 
                errorClassificationService.classifyError(serviceName, error);

        // Then
        assertThat(category).isEqualTo(ErrorClassificationService.ErrorCategory.AUTHENTICATION_ERROR);
    }

    @Test
    void classifyError_ValidationError_ShouldReturnValidationError() {
        // Given
        String serviceName = "transcription-service";
        Throwable error = new RuntimeException("Invalid input format - unsupported file type");

        // When
        ErrorClassificationService.ErrorCategory category = 
                errorClassificationService.classifyError(serviceName, error);

        // Then
        assertThat(category).isEqualTo(ErrorClassificationService.ErrorCategory.VALIDATION_ERROR);
    }

    @Test
    void classifyError_ParseError_ShouldReturnValidationError() {
        // Given
        String serviceName = "agent-service";
        Throwable error = new RuntimeException("JSON parse error: malformed request");

        // When
        ErrorClassificationService.ErrorCategory category = 
                errorClassificationService.classifyError(serviceName, error);

        // Then
        assertThat(category).isEqualTo(ErrorClassificationService.ErrorCategory.VALIDATION_ERROR);
    }

    @Test
    void classifyError_OutOfMemory_ShouldReturnResourceExhaustion() {
        // Given
        String serviceName = "transcription-service";
        Throwable error = new OutOfMemoryError("Java heap space");

        // When
        ErrorClassificationService.ErrorCategory category = 
                errorClassificationService.classifyError(serviceName, error);

        // Then
        assertThat(category).isEqualTo(ErrorClassificationService.ErrorCategory.RESOURCE_EXHAUSTION);
    }

    @Test
    void classifyError_DiskSpaceError_ShouldReturnResourceExhaustion() {
        // Given
        String serviceName = "transcription-service";
        Throwable error = new RuntimeException("No space left on device");

        // When
        ErrorClassificationService.ErrorCategory category = 
                errorClassificationService.classifyError(serviceName, error);

        // Then
        assertThat(category).isEqualTo(ErrorClassificationService.ErrorCategory.RESOURCE_EXHAUSTION);
    }

    @Test
    void classifyError_TranscriptionSpecific_ShouldReturnTranscriptionError() {
        // Given
        String serviceName = "transcription-service";
        Throwable error = new RuntimeException("Transcription failed: audio format not supported by Whisper");

        // When
        ErrorClassificationService.ErrorCategory category = 
                errorClassificationService.classifyError(serviceName, error);

        // Then
        assertThat(category).isEqualTo(ErrorClassificationService.ErrorCategory.TRANSCRIPTION_ERROR);
    }

    @Test
    void classifyError_AgentServiceSpecific_ShouldReturnAgentServiceError() {
        // Given
        String serviceName = "agent-service";
        Throwable error = new RuntimeException("OpenAI API error: model not available");

        // When
        ErrorClassificationService.ErrorCategory category = 
                errorClassificationService.classifyError(serviceName, error);

        // Then
        assertThat(category).isEqualTo(ErrorClassificationService.ErrorCategory.AGENT_SERVICE_ERROR);
    }

    @Test
    void classifyError_GPTError_ShouldReturnAgentServiceError() {
        // Given
        String serviceName = "agent-service";
        Throwable error = new RuntimeException("GPT-4 request failed");

        // When
        ErrorClassificationService.ErrorCategory category = 
                errorClassificationService.classifyError(serviceName, error);

        // Then
        assertThat(category).isEqualTo(ErrorClassificationService.ErrorCategory.AGENT_SERVICE_ERROR);
    }

    @Test
    void classifyError_FileNotFound_ShouldReturnDataError() {
        // Given
        String serviceName = "transcription-service";
        Throwable error = new RuntimeException("Audio file not found");

        // When
        ErrorClassificationService.ErrorCategory category = 
                errorClassificationService.classifyError(serviceName, error);

        // Then
        assertThat(category).isEqualTo(ErrorClassificationService.ErrorCategory.DATA_ERROR);
    }

    @Test
    void classifyError_CorruptedFile_ShouldReturnDataError() {
        // Given
        String serviceName = "transcription-service";
        Throwable error = new RuntimeException("File is corrupted or incomplete");

        // When
        ErrorClassificationService.ErrorCategory category = 
                errorClassificationService.classifyError(serviceName, error);

        // Then
        assertThat(category).isEqualTo(ErrorClassificationService.ErrorCategory.DATA_ERROR);
    }

    @Test
    void classifyError_UnknownError_ShouldReturnUnknownError() {
        // Given
        String serviceName = "unknown-service";
        Throwable error = new RuntimeException("Some random error that doesn't match any pattern");

        // When
        ErrorClassificationService.ErrorCategory category = 
                errorClassificationService.classifyError(serviceName, error);

        // Then
        assertThat(category).isEqualTo(ErrorClassificationService.ErrorCategory.UNKNOWN_ERROR);
    }

    @Test
    void classifyError_NullError_ShouldReturnUnknownError() {
        // Given
        String serviceName = "test-service";
        Throwable error = null;

        // When
        ErrorClassificationService.ErrorCategory category = 
                errorClassificationService.classifyError(serviceName, error);

        // Then
        assertThat(category).isEqualTo(ErrorClassificationService.ErrorCategory.UNKNOWN_ERROR);
    }

    @Test
    void classifyError_CaseInsensitive_ShouldMatchPattern() {
        // Given
        String serviceName = "test-service";
        Throwable error = new RuntimeException("CONNECTION REFUSED");

        // When
        ErrorClassificationService.ErrorCategory category = 
                errorClassificationService.classifyError(serviceName, error);

        // Then
        assertThat(category).isEqualTo(ErrorClassificationService.ErrorCategory.TRANSIENT_NETWORK);
    }

    @Test
    void classifyError_CachedResult_ShouldReturnCachedCategory() {
        // Given
        String serviceName = "cached-service";
        Throwable error1 = new RuntimeException("Connection timeout");
        Throwable error2 = new RuntimeException("Connection timeout");

        // When - First call should classify and cache
        ErrorClassificationService.ErrorCategory category1 = 
                errorClassificationService.classifyError(serviceName, error1);
        
        // Second call with same error should use cached result
        ErrorClassificationService.ErrorCategory category2 = 
                errorClassificationService.classifyError(serviceName, error2);

        // Then
        assertThat(category1).isEqualTo(ErrorClassificationService.ErrorCategory.TRANSIENT_NETWORK);
        assertThat(category2).isEqualTo(ErrorClassificationService.ErrorCategory.TRANSIENT_NETWORK);
        assertThat(category1).isEqualTo(category2);
    }

    @Test
    void classifyError_MultipleErrorsForSameService_ShouldCacheResults() {
        // Given
        String serviceName = "stats-test-service";
        
        // When - Classify some errors to generate statistics
        ErrorClassificationService.ErrorCategory cat1 = 
                errorClassificationService.classifyError(serviceName, new ConnectException("Connection refused"));
        ErrorClassificationService.ErrorCategory cat2 = 
                errorClassificationService.classifyError(serviceName, new RuntimeException("Rate limit exceeded"));
        ErrorClassificationService.ErrorCategory cat3 = 
                errorClassificationService.classifyError(serviceName, new RuntimeException("Invalid input"));

        // Then
        assertThat(cat1).isEqualTo(ErrorClassificationService.ErrorCategory.TRANSIENT_NETWORK);
        assertThat(cat2).isEqualTo(ErrorClassificationService.ErrorCategory.RATE_LIMITED);
        assertThat(cat3).isEqualTo(ErrorClassificationService.ErrorCategory.VALIDATION_ERROR);
    }

    @Test
    void classifyError_ServiceWithNoErrors_ShouldClassifyCorrectly() {
        // Given
        String serviceName = "no-errors-service";
        Throwable error = new RuntimeException("Some random error that doesn't match patterns");

        // When
        ErrorClassificationService.ErrorCategory category = 
                errorClassificationService.classifyError(serviceName, error);

        // Then
        assertThat(category).isEqualTo(ErrorClassificationService.ErrorCategory.UNKNOWN_ERROR);
    }

    @Test
    void classifyError_TransientNetworkErrors_ShouldBeConsistent() {
        // Given
        String serviceName = "test-service";
        Throwable networkError = new ConnectException("Connection refused");
        Throwable timeoutError = new SocketTimeoutException("Read timeout");

        // When
        ErrorClassificationService.ErrorCategory cat1 = 
                errorClassificationService.classifyError(serviceName, networkError);
        ErrorClassificationService.ErrorCategory cat2 = 
                errorClassificationService.classifyError(serviceName, timeoutError);

        // Then
        assertThat(cat1).isEqualTo(ErrorClassificationService.ErrorCategory.TRANSIENT_NETWORK);
        assertThat(cat2).isEqualTo(ErrorClassificationService.ErrorCategory.TRANSIENT_NETWORK);
    }

    @Test
    void classifyError_ValidationErrors_ShouldNotBeRetryable() {
        // Given
        String serviceName = "test-service";
        Throwable validationError = new RuntimeException("Invalid input format");

        // When
        ErrorClassificationService.ErrorCategory category = 
                errorClassificationService.classifyError(serviceName, validationError);

        // Then
        assertThat(category).isEqualTo(ErrorClassificationService.ErrorCategory.VALIDATION_ERROR);
    }

    @Test
    void classifyError_ServiceSpecificErrors_ShouldMapCorrectly() {
        // Given
        String serviceName = "transcription-service";
        Throwable transcriptionError = new RuntimeException("Transcription failed: Whisper error");

        // When
        ErrorClassificationService.ErrorCategory category = 
                errorClassificationService.classifyError(serviceName, transcriptionError);

        // Then
        assertThat(category).isEqualTo(ErrorClassificationService.ErrorCategory.TRANSCRIPTION_ERROR);
    }

    @Test
    void classifyError_AuthenticationErrors_ShouldNotBeRetryable() {
        // Given
        String serviceName = "test-service";
        Throwable authError = new RuntimeException("Unauthorized - invalid token");

        // When
        ErrorClassificationService.ErrorCategory category = 
                errorClassificationService.classifyError(serviceName, authError);

        // Then
        assertThat(category).isEqualTo(ErrorClassificationService.ErrorCategory.AUTHENTICATION_ERROR);
    }
}
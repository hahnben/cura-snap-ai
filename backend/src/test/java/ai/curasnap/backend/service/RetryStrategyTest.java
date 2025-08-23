package ai.curasnap.backend.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class RetryStrategyTest {

    @Test
    void testExponentialBackoffCalculation() {
        // Given
        RetryStrategy.RetryConfig config = RetryStrategy.RetryConfig.builder()
                .policy(RetryStrategy.RetryPolicy.EXPONENTIAL_BACKOFF)
                .initialDelay(Duration.ofSeconds(1))
                .maxDelay(Duration.ofMinutes(1))
                .backoffMultiplier(2.0)
                .maxRetries(3)
                .enableJitter(false) // Disable jitter for predictable testing
                .build();

        Instant lastFailure = Instant.now();

        // When & Then - Test retry attempts
        // First retry (attempt 0)
        RetryStrategy.RetryResult result1 = RetryStrategy.calculateNextRetry(
                config, 0, lastFailure, null);
        assertTrue(result1.isShouldRetry());
        assertEquals(Duration.ofSeconds(1), result1.getDelayUntilNextTry());

        // Second retry (attempt 1) 
        RetryStrategy.RetryResult result2 = RetryStrategy.calculateNextRetry(
                config, 1, lastFailure, null);
        assertTrue(result2.isShouldRetry());
        assertEquals(Duration.ofSeconds(2), result2.getDelayUntilNextTry());

        // Third retry (attempt 2)
        RetryStrategy.RetryResult result3 = RetryStrategy.calculateNextRetry(
                config, 2, lastFailure, null);
        assertTrue(result3.isShouldRetry());
        assertEquals(Duration.ofSeconds(4), result3.getDelayUntilNextTry());

        // Max retries exceeded (attempt 3)
        RetryStrategy.RetryResult result4 = RetryStrategy.calculateNextRetry(
                config, 3, lastFailure, null);
        assertFalse(result4.isShouldRetry());
        assertEquals(Duration.ZERO, result4.getDelayUntilNextTry());
    }

    @Test
    void testLinearBackoffCalculation() {
        // Given
        RetryStrategy.RetryConfig config = RetryStrategy.RetryConfig.builder()
                .policy(RetryStrategy.RetryPolicy.LINEAR_BACKOFF)
                .initialDelay(Duration.ofSeconds(2))
                .maxDelay(Duration.ofMinutes(1))
                .maxRetries(3)
                .enableJitter(false)
                .build();

        Instant lastFailure = Instant.now();

        // When & Then
        RetryStrategy.RetryResult result1 = RetryStrategy.calculateNextRetry(
                config, 0, lastFailure, null);
        assertTrue(result1.isShouldRetry());
        assertEquals(Duration.ofSeconds(2), result1.getDelayUntilNextTry()); // 2 * (0 + 1) = 2

        RetryStrategy.RetryResult result2 = RetryStrategy.calculateNextRetry(
                config, 1, lastFailure, null);
        assertTrue(result2.isShouldRetry());
        assertEquals(Duration.ofSeconds(4), result2.getDelayUntilNextTry()); // 2 * (1 + 1) = 4

        RetryStrategy.RetryResult result3 = RetryStrategy.calculateNextRetry(
                config, 2, lastFailure, null);
        assertTrue(result3.isShouldRetry());
        assertEquals(Duration.ofSeconds(6), result3.getDelayUntilNextTry()); // 2 * (2 + 1) = 6
    }

    @Test
    void testFixedDelayPolicy() {
        // Given
        RetryStrategy.RetryConfig config = RetryStrategy.RetryConfig.builder()
                .policy(RetryStrategy.RetryPolicy.FIXED_DELAY)
                .initialDelay(Duration.ofSeconds(5))
                .maxRetries(3)
                .enableJitter(false)
                .build();

        Instant lastFailure = Instant.now();

        // When & Then
        for (int attempt = 0; attempt < 3; attempt++) {
            RetryStrategy.RetryResult result = RetryStrategy.calculateNextRetry(
                    config, attempt, lastFailure, null);
            assertTrue(result.isShouldRetry());
            assertEquals(Duration.ofSeconds(5), result.getDelayUntilNextTry());
        }
    }

    @Test
    void testImmediateRetryPolicy() {
        // Given
        RetryStrategy.RetryConfig config = RetryStrategy.RetryConfig.builder()
                .policy(RetryStrategy.RetryPolicy.IMMEDIATE)
                .maxRetries(2)
                .build();

        Instant lastFailure = Instant.now();

        // When & Then
        for (int attempt = 0; attempt < 2; attempt++) {
            RetryStrategy.RetryResult result = RetryStrategy.calculateNextRetry(
                    config, attempt, lastFailure, null);
            assertTrue(result.isShouldRetry());
            assertEquals(Duration.ZERO, result.getDelayUntilNextTry());
        }
    }

    @Test
    void testMaxDelayEnforcement() {
        // Given
        RetryStrategy.RetryConfig config = RetryStrategy.RetryConfig.builder()
                .policy(RetryStrategy.RetryPolicy.EXPONENTIAL_BACKOFF)
                .initialDelay(Duration.ofSeconds(10))
                .maxDelay(Duration.ofSeconds(30)) // Smaller max delay
                .backoffMultiplier(4.0) // Large multiplier
                .maxRetries(5)
                .enableJitter(false)
                .build();

        Instant lastFailure = Instant.now();

        // When & Then - Test that delay never exceeds maxDelay
        RetryStrategy.RetryResult result1 = RetryStrategy.calculateNextRetry(
                config, 0, lastFailure, null);
        assertEquals(Duration.ofSeconds(10), result1.getDelayUntilNextTry());

        RetryStrategy.RetryResult result2 = RetryStrategy.calculateNextRetry(
                config, 1, lastFailure, null);
        assertEquals(Duration.ofSeconds(30), result2.getDelayUntilNextTry()); // Capped at maxDelay

        RetryStrategy.RetryResult result3 = RetryStrategy.calculateNextRetry(
                config, 2, lastFailure, null);
        assertEquals(Duration.ofSeconds(30), result3.getDelayUntilNextTry()); // Still capped
    }

    @Test
    void testJitterApplication() {
        // Given
        RetryStrategy.RetryConfig config = RetryStrategy.RetryConfig.builder()
                .policy(RetryStrategy.RetryPolicy.EXPONENTIAL_BACKOFF)
                .initialDelay(Duration.ofSeconds(10))
                .backoffMultiplier(2.0)
                .maxRetries(3)
                .enableJitter(true)
                .jitterFactor(0.2) // 20% jitter
                .build();

        Instant lastFailure = Instant.now();

        // When - Calculate multiple retry attempts to test jitter variability
        Duration baseDelay = Duration.ofSeconds(10);
        RetryStrategy.RetryResult result = RetryStrategy.calculateNextRetry(
                config, 0, lastFailure, null);

        // Then - Delay should be within jitter range (10s ± 20% = 8s to 12s)
        long actualDelayMs = result.getDelayUntilNextTry().toMillis();
        long baseDelayMs = baseDelay.toMillis();
        double jitterRange = baseDelayMs * 0.2;
        
        assertTrue(actualDelayMs >= baseDelayMs - jitterRange);
        assertTrue(actualDelayMs <= baseDelayMs + jitterRange);
    }

    @Test
    void testPredefinedRetryConfigs() {
        // Test that predefined configs are properly configured
        
        // Standard backoff
        RetryStrategy.RetryConfig standard = RetryStrategy.STANDARD_BACKOFF;
        assertEquals(RetryStrategy.RetryPolicy.EXPONENTIAL_BACKOFF, standard.getPolicy());
        assertEquals(3, standard.getMaxRetries());
        assertEquals(Duration.ofSeconds(5), standard.getInitialDelay());
        assertTrue(standard.isEnableJitter());

        // Audio processing backoff
        RetryStrategy.RetryConfig audio = RetryStrategy.AUDIO_PROCESSING_BACKOFF;
        assertEquals(RetryStrategy.RetryPolicy.EXPONENTIAL_BACKOFF, audio.getPolicy());
        assertEquals(5, audio.getMaxRetries());
        assertEquals(Duration.ofSeconds(2), audio.getInitialDelay());

        // Text processing backoff
        RetryStrategy.RetryConfig text = RetryStrategy.TEXT_PROCESSING_BACKOFF;
        assertEquals(RetryStrategy.RetryPolicy.EXPONENTIAL_BACKOFF, text.getPolicy());
        assertEquals(3, text.getMaxRetries());
        assertEquals(Duration.ofSeconds(10), text.getInitialDelay());

        // Network error backoff
        RetryStrategy.RetryConfig network = RetryStrategy.NETWORK_ERROR_BACKOFF;
        assertEquals(RetryStrategy.RetryPolicy.EXPONENTIAL_BACKOFF, network.getPolicy());
        assertEquals(4, network.getMaxRetries());
        assertEquals(Duration.ofSeconds(1), network.getInitialDelay());
    }

    @Test
    void testGetRetryConfigForJobType() {
        // Test audio processing job type
        RetryStrategy.RetryConfig audioConfig = RetryStrategy.getRetryConfigForJobType(
                "audio_processing", null);
        assertEquals(RetryStrategy.AUDIO_PROCESSING_BACKOFF.getMaxRetries(), 
                audioConfig.getMaxRetries());

        // Test text processing job type
        RetryStrategy.RetryConfig textConfig = RetryStrategy.getRetryConfigForJobType(
                "text_processing", null);
        assertEquals(RetryStrategy.TEXT_PROCESSING_BACKOFF.getMaxRetries(), 
                textConfig.getMaxRetries());

        // Test cache warming job type
        RetryStrategy.RetryConfig cacheConfig = RetryStrategy.getRetryConfigForJobType(
                "cache_warming", null);
        assertEquals(RetryStrategy.NETWORK_ERROR_BACKOFF.getMaxRetries(), 
                cacheConfig.getMaxRetries());

        // Test unknown job type (should return standard)
        RetryStrategy.RetryConfig unknownConfig = RetryStrategy.getRetryConfigForJobType(
                "unknown_job_type", null);
        assertEquals(RetryStrategy.STANDARD_BACKOFF.getMaxRetries(), 
                unknownConfig.getMaxRetries());
    }

    @Test
    void testAudioProcessingErrorTypeHandling() {
        // Test network error
        RetryStrategy.RetryConfig networkConfig = RetryStrategy.getRetryConfigForJobType(
                "audio_processing", "network connection failed");
        assertEquals(RetryStrategy.NETWORK_ERROR_BACKOFF.getMaxRetries(), 
                networkConfig.getMaxRetries());

        // Test transcription error
        RetryStrategy.RetryConfig transcriptionConfig = RetryStrategy.getRetryConfigForJobType(
                "audio_processing", "whisper transcription failed");
        assertEquals(RetryStrategy.AUDIO_PROCESSING_BACKOFF.getMaxRetries(), 
                transcriptionConfig.getMaxRetries());

        // Test memory error (should get longer delays)
        RetryStrategy.RetryConfig memoryConfig = RetryStrategy.getRetryConfigForJobType(
                "audio_processing", "memory exceeded");
        assertEquals(3, memoryConfig.getMaxRetries());
        assertEquals(Duration.ofSeconds(30), memoryConfig.getInitialDelay());
    }

    @Test
    void testRetrySummaryCreation() {
        // Given
        String jobType = "audio_processing";
        int totalAttempts = 5;
        int successfulRetries = 3;
        RetryStrategy.RetryConfig config = RetryStrategy.STANDARD_BACKOFF;

        // When
        java.util.Map<String, Object> summary = RetryStrategy.createRetrySummary(
                jobType, totalAttempts, successfulRetries, config);

        // Then
        assertNotNull(summary);
        assertEquals(jobType, summary.get("jobType"));
        assertEquals(config.getPolicy().name(), summary.get("retryPolicy"));
        assertEquals(config.getMaxRetries(), summary.get("maxRetries"));
        assertEquals(totalAttempts, summary.get("totalAttempts"));
        assertEquals(successfulRetries, summary.get("successfulRetries"));
        assertEquals(0.6, (Double) summary.get("retrySuccessRate"), 0.001); // 3/5 = 0.6
        assertEquals(config.getInitialDelay().toMillis(), summary.get("initialDelayMs"));
        assertEquals(config.getMaxDelay().toMillis(), summary.get("maxDelayMs"));
        assertEquals(config.getBackoffMultiplier(), summary.get("backoffMultiplier"));
        assertEquals(config.isEnableJitter(), summary.get("jitterEnabled"));
    }

    @Test
    void testZeroAttemptsRetrySummary() {
        // Given
        String jobType = "test_job";
        int totalAttempts = 0;
        int successfulRetries = 0;
        RetryStrategy.RetryConfig config = RetryStrategy.STANDARD_BACKOFF;

        // When
        java.util.Map<String, Object> summary = RetryStrategy.createRetrySummary(
                jobType, totalAttempts, successfulRetries, config);

        // Then
        assertEquals(0.0, (Double) summary.get("retrySuccessRate"), 0.001);
    }
}
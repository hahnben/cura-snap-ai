package ai.curasnap.backend.service;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Advanced retry strategy implementation with various backoff algorithms
 * Supports exponential backoff, linear backoff, and custom retry policies
 */
@Slf4j
public class RetryStrategy {

    private static final Random RANDOM = new Random();

    /**
     * Retry policy types supported by the system
     */
    public enum RetryPolicy {
        EXPONENTIAL_BACKOFF,
        LINEAR_BACKOFF,
        FIXED_DELAY,
        IMMEDIATE,
        CUSTOM
    }

    /**
     * Configuration for retry behavior
     */
    public static class RetryConfig {
        private final RetryPolicy policy;
        private final int maxRetries;
        private final Duration initialDelay;
        private final Duration maxDelay;
        private final double backoffMultiplier;
        private final double jitterFactor;
        private final boolean enableJitter;

        private RetryConfig(Builder builder) {
            this.policy = builder.policy;
            this.maxRetries = builder.maxRetries;
            this.initialDelay = builder.initialDelay;
            this.maxDelay = builder.maxDelay;
            this.backoffMultiplier = builder.backoffMultiplier;
            this.jitterFactor = builder.jitterFactor;
            this.enableJitter = builder.enableJitter;
        }

        public static Builder builder() {
            return new Builder();
        }

        // Getters
        public RetryPolicy getPolicy() { return policy; }
        public int getMaxRetries() { return maxRetries; }
        public Duration getInitialDelay() { return initialDelay; }
        public Duration getMaxDelay() { return maxDelay; }
        public double getBackoffMultiplier() { return backoffMultiplier; }
        public double getJitterFactor() { return jitterFactor; }
        public boolean isEnableJitter() { return enableJitter; }

        public static class Builder {
            private RetryPolicy policy = RetryPolicy.EXPONENTIAL_BACKOFF;
            private int maxRetries = 3;
            private Duration initialDelay = Duration.ofSeconds(5);
            private Duration maxDelay = Duration.ofMinutes(10);
            private double backoffMultiplier = 2.0;
            private double jitterFactor = 0.1;
            private boolean enableJitter = true;

            public Builder policy(RetryPolicy policy) { this.policy = policy; return this; }
            public Builder maxRetries(int maxRetries) { this.maxRetries = maxRetries; return this; }
            public Builder initialDelay(Duration initialDelay) { this.initialDelay = initialDelay; return this; }
            public Builder maxDelay(Duration maxDelay) { this.maxDelay = maxDelay; return this; }
            public Builder backoffMultiplier(double multiplier) { this.backoffMultiplier = multiplier; return this; }
            public Builder jitterFactor(double jitter) { this.jitterFactor = jitter; return this; }
            public Builder enableJitter(boolean enable) { this.enableJitter = enable; return this; }

            public RetryConfig build() {
                return new RetryConfig(this);
            }
        }
    }

    /**
     * Result of retry calculation
     */
    public static class RetryResult {
        private final boolean shouldRetry;
        private final Duration delayUntilNextTry;
        private final Instant nextRetryAt;
        private final String reason;

        public RetryResult(boolean shouldRetry, Duration delayUntilNextTry, String reason) {
            this.shouldRetry = shouldRetry;
            this.delayUntilNextTry = delayUntilNextTry;
            this.nextRetryAt = shouldRetry ? Instant.now().plus(delayUntilNextTry) : null;
            this.reason = reason;
        }

        public boolean isShouldRetry() { return shouldRetry; }
        public Duration getDelayUntilNextTry() { return delayUntilNextTry; }
        public Instant getNextRetryAt() { return nextRetryAt; }
        public String getReason() { return reason; }
    }

    // Pre-configured retry strategies for common scenarios

    /**
     * Standard exponential backoff for general job failures
     * 5s, 10s, 20s, max 3 retries
     */
    public static final RetryConfig STANDARD_BACKOFF = RetryConfig.builder()
            .policy(RetryPolicy.EXPONENTIAL_BACKOFF)
            .maxRetries(3)
            .initialDelay(Duration.ofSeconds(5))
            .maxDelay(Duration.ofSeconds(60))
            .backoffMultiplier(2.0)
            .enableJitter(true)
            .jitterFactor(0.1)
            .build();

    /**
     * Aggressive retry for critical audio processing jobs  
     * 2s, 5s, 10s, 20s, 40s, max 5 retries
     */
    public static final RetryConfig AUDIO_PROCESSING_BACKOFF = RetryConfig.builder()
            .policy(RetryPolicy.EXPONENTIAL_BACKOFF)
            .maxRetries(5)
            .initialDelay(Duration.ofSeconds(2))
            .maxDelay(Duration.ofMinutes(1))
            .backoffMultiplier(2.5)
            .enableJitter(true)
            .jitterFactor(0.2)
            .build();

    /**
     * Conservative retry for text processing (less critical)
     * 10s, 30s, 60s, max 3 retries
     */
    public static final RetryConfig TEXT_PROCESSING_BACKOFF = RetryConfig.builder()
            .policy(RetryPolicy.EXPONENTIAL_BACKOFF)
            .maxRetries(3)
            .initialDelay(Duration.ofSeconds(10))
            .maxDelay(Duration.ofMinutes(2))
            .backoffMultiplier(3.0)
            .enableJitter(false)
            .build();

    /**
     * Fast retry for transient network errors
     * 1s, 2s, 4s, 8s, max 4 retries
     */
    public static final RetryConfig NETWORK_ERROR_BACKOFF = RetryConfig.builder()
            .policy(RetryPolicy.EXPONENTIAL_BACKOFF)
            .maxRetries(4)
            .initialDelay(Duration.ofSeconds(1))
            .maxDelay(Duration.ofSeconds(30))
            .backoffMultiplier(2.0)
            .enableJitter(true)
            .jitterFactor(0.3)
            .build();

    /**
     * Calculate the next retry attempt based on the retry strategy
     *
     * @param config retry configuration
     * @param currentAttempt current retry attempt number (0-based)
     * @param lastFailure timestamp of last failure
     * @param errorType type of error that occurred (for adaptive strategies)
     * @return retry result indicating if retry should happen and when
     */
    public static RetryResult calculateNextRetry(RetryConfig config, int currentAttempt, 
                                               Instant lastFailure, String errorType) {
        
        // Check if max retries exceeded
        if (currentAttempt >= config.getMaxRetries()) {
            return new RetryResult(false, Duration.ZERO, 
                    String.format("Max retries (%d) exceeded", config.getMaxRetries()));
        }

        Duration delay;
        
        switch (config.getPolicy()) {
            case EXPONENTIAL_BACKOFF:
                delay = calculateExponentialBackoff(config, currentAttempt);
                break;
                
            case LINEAR_BACKOFF:
                delay = calculateLinearBackoff(config, currentAttempt);
                break;
                
            case FIXED_DELAY:
                delay = config.getInitialDelay();
                break;
                
            case IMMEDIATE:
                delay = Duration.ZERO;
                break;
                
            case CUSTOM:
                delay = calculateCustomBackoff(config, currentAttempt, errorType);
                break;
                
            default:
                delay = config.getInitialDelay();
                break;
        }

        // Apply jitter if enabled
        if (config.isEnableJitter() && delay.toMillis() > 0) {
            delay = applyJitter(delay, config.getJitterFactor());
        }

        // Ensure delay doesn't exceed maximum
        if (delay.compareTo(config.getMaxDelay()) > 0) {
            delay = config.getMaxDelay();
        }

        String reason = String.format("Retry %d/%d with %s policy, delay: %dms", 
                currentAttempt + 1, config.getMaxRetries(), 
                config.getPolicy().name(), delay.toMillis());

        return new RetryResult(true, delay, reason);
    }

    /**
     * Get retry configuration based on job type and error type
     *
     * @param jobType the type of job that failed
     * @param errorType the type of error encountered
     * @return appropriate retry configuration
     */
    public static RetryConfig getRetryConfigForJobType(String jobType, String errorType) {
        // Determine retry strategy based on job type
        switch (jobType.toLowerCase()) {
            case "audio_processing":
                return getAudioProcessingRetryConfig(errorType);
                
            case "text_processing":
                return TEXT_PROCESSING_BACKOFF;
                
            case "cache_warming":
                return NETWORK_ERROR_BACKOFF;
                
            default:
                return STANDARD_BACKOFF;
        }
    }

    /**
     * Get specialized retry config for audio processing based on error type
     */
    private static RetryConfig getAudioProcessingRetryConfig(String errorType) {
        if (errorType == null) {
            return AUDIO_PROCESSING_BACKOFF;
        }

        String errorLower = errorType.toLowerCase();
        
        if (errorLower.contains("network") || errorLower.contains("connection") || 
            errorLower.contains("timeout")) {
            // Network errors - retry more aggressively
            return NETWORK_ERROR_BACKOFF;
        }
        
        if (errorLower.contains("transcription") || errorLower.contains("whisper")) {
            // Transcription service errors - standard backoff
            return AUDIO_PROCESSING_BACKOFF;
        }
        
        if (errorLower.contains("memory") || errorLower.contains("resource")) {
            // Resource errors - longer delays
            return RetryConfig.builder()
                    .policy(RetryPolicy.EXPONENTIAL_BACKOFF)
                    .maxRetries(3)
                    .initialDelay(Duration.ofSeconds(30))
                    .maxDelay(Duration.ofMinutes(5))
                    .backoffMultiplier(2.0)
                    .enableJitter(true)
                    .build();
        }
        
        return AUDIO_PROCESSING_BACKOFF;
    }

    // Private calculation methods

    private static Duration calculateExponentialBackoff(RetryConfig config, int attempt) {
        double delayMs = config.getInitialDelay().toMillis() * 
                        Math.pow(config.getBackoffMultiplier(), attempt);
        return Duration.ofMillis((long) delayMs);
    }

    private static Duration calculateLinearBackoff(RetryConfig config, int attempt) {
        long delayMs = config.getInitialDelay().toMillis() * (attempt + 1);
        return Duration.ofMillis(delayMs);
    }

    private static Duration calculateCustomBackoff(RetryConfig config, int attempt, String errorType) {
        // Implement custom logic based on error type
        // For now, fall back to exponential backoff
        return calculateExponentialBackoff(config, attempt);
    }

    private static Duration applyJitter(Duration baseDelay, double jitterFactor) {
        if (jitterFactor <= 0.0 || baseDelay.toMillis() == 0) {
            return baseDelay;
        }

        long baseMs = baseDelay.toMillis();
        double jitterRange = baseMs * jitterFactor;
        double jitter = (RANDOM.nextDouble() - 0.5) * 2 * jitterRange; // -jitterRange to +jitterRange
        
        long newDelayMs = Math.max(0, baseMs + (long) jitter);
        return Duration.ofMillis(newDelayMs);
    }

    /**
     * Create a summary of retry statistics for monitoring
     *
     * @param jobType the job type
     * @param totalAttempts total retry attempts made
     * @param successfulRetries number of successful retries
     * @param config the retry config used
     * @return summary map
     */
    public static Map<String, Object> createRetrySummary(String jobType, int totalAttempts, 
                                                        int successfulRetries, RetryConfig config) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("jobType", jobType);
        summary.put("retryPolicy", config.getPolicy().name());
        summary.put("maxRetries", config.getMaxRetries());
        summary.put("totalAttempts", totalAttempts);
        summary.put("successfulRetries", successfulRetries);
        summary.put("retrySuccessRate", totalAttempts > 0 ? (double) successfulRetries / totalAttempts : 0.0);
        summary.put("initialDelayMs", config.getInitialDelay().toMillis());
        summary.put("maxDelayMs", config.getMaxDelay().toMillis());
        summary.put("backoffMultiplier", config.getBackoffMultiplier());
        summary.put("jitterEnabled", config.isEnableJitter());
        return summary;
    }
}
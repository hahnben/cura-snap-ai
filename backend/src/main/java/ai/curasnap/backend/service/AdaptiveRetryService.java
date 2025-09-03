package ai.curasnap.backend.service;

import ai.curasnap.backend.model.dto.JobData;
import ai.curasnap.backend.service.interfaces.WorkerMetricsProvider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Adaptive Retry Service that adjusts retry strategies based on system health,
 * error patterns, and circuit breaker states.
 * 
 * Features:
 * - Dynamic retry delay adjustment based on system load
 * - Error-type specific retry strategies
 * - Circuit breaker integration
 * - Backoff strategies with jitter
 * - Retry pattern learning
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.retry.adaptive.enabled", havingValue = "true", matchIfMissing = true)
public class AdaptiveRetryService {

    private final ErrorClassificationService errorClassificationService;
    private final CircuitBreakerService circuitBreakerService;
    private final WorkerMetricsProvider workerMetricsProvider;
    private final RedisTemplate<String, Object> redisTemplate;

    // Redis keys for adaptive retry state
    private static final String RETRY_PATTERN_KEY_PREFIX = "retry_patterns:";
    private static final String SYSTEM_LOAD_KEY = "system_load";
    private static final String ERROR_FREQUENCY_KEY_PREFIX = "error_frequency:";
    
    // Adaptive parameters
    private final Map<String, RetryPatternMetrics> retryPatterns = new HashMap<>();
    private volatile double currentSystemLoad = 0.0;
    private volatile Instant lastSystemLoadUpdate = Instant.now();

    @Autowired
    public AdaptiveRetryService(ErrorClassificationService errorClassificationService,
                               CircuitBreakerService circuitBreakerService,
                               WorkerMetricsProvider workerMetricsProvider,
                               RedisTemplate<String, Object> redisTemplate) {
        this.errorClassificationService = errorClassificationService;
        this.circuitBreakerService = circuitBreakerService;
        this.workerMetricsProvider = workerMetricsProvider;
        this.redisTemplate = redisTemplate;
        
        loadRetryPatterns();
        log.info("AdaptiveRetryService initialized with intelligent retry strategies");
    }

    /**
     * Enhanced retry configuration that adapts to system conditions
     */
    public static class AdaptiveRetryConfig {
        private final ErrorClassificationService.ErrorCategory errorCategory;
        private final Duration baseDelay;
        private final Duration maxDelay;
        private final int maxRetries;
        private final BackoffStrategy backoffStrategy;
        private final double jitterFactor;
        private final boolean respectsCircuitBreaker;
        private final double systemLoadThreshold;

        public AdaptiveRetryConfig(ErrorClassificationService.ErrorCategory errorCategory,
                                  Duration baseDelay,
                                  Duration maxDelay, 
                                  int maxRetries,
                                  BackoffStrategy backoffStrategy,
                                  double jitterFactor,
                                  boolean respectsCircuitBreaker,
                                  double systemLoadThreshold) {
            this.errorCategory = errorCategory;
            this.baseDelay = baseDelay;
            this.maxDelay = maxDelay;
            this.maxRetries = maxRetries;
            this.backoffStrategy = backoffStrategy;
            this.jitterFactor = jitterFactor;
            this.respectsCircuitBreaker = respectsCircuitBreaker;
            this.systemLoadThreshold = systemLoadThreshold;
        }

        // Getters
        public ErrorClassificationService.ErrorCategory getErrorCategory() { return errorCategory; }
        public Duration getBaseDelay() { return baseDelay; }
        public Duration getMaxDelay() { return maxDelay; }
        public int getMaxRetries() { return maxRetries; }
        public BackoffStrategy getBackoffStrategy() { return backoffStrategy; }
        public double getJitterFactor() { return jitterFactor; }
        public boolean isRespectsCircuitBreaker() { return respectsCircuitBreaker; }
        public double getSystemLoadThreshold() { return systemLoadThreshold; }
    }

    public enum BackoffStrategy {
        LINEAR,
        EXPONENTIAL,
        FIBONACCI,
        ADAPTIVE_EXPONENTIAL  // Adjusts based on success rate
    }

    /**
     * Calculate adaptive retry configuration based on error type and system state
     */
    public AdaptiveRetryResult calculateAdaptiveRetry(String serviceName, 
                                                     String jobId,
                                                     JobData.JobType jobType,
                                                     Throwable error,
                                                     int currentRetryCount) {
        try {
            // Classify error
            ErrorClassificationService.ErrorCategory errorCategory = 
                errorClassificationService.classifyError(serviceName, error);

            // Check circuit breaker state
            CircuitBreakerService.CircuitBreakerState circuitState = 
                circuitBreakerService.getCircuitBreakerState(serviceName);

            // Update system load
            updateSystemLoad();

            // Get adaptive retry configuration
            AdaptiveRetryConfig config = getAdaptiveRetryConfig(errorCategory, jobType);

            // Check if retry should be attempted
            if (!shouldAttemptRetry(config, serviceName, errorCategory, currentRetryCount, circuitState)) {
                return new AdaptiveRetryResult(false, null, Duration.ZERO, 
                    "Max retries exceeded or circuit breaker prevents retry");
            }

            // Calculate adaptive delay
            Duration adaptiveDelay = calculateAdaptiveDelay(
                config, serviceName, errorCategory, currentRetryCount, circuitState);

            // Calculate next retry time
            Instant nextRetryAt = Instant.now().plus(adaptiveDelay);

            // Record retry pattern for learning
            recordRetryPattern(serviceName, errorCategory, currentRetryCount, adaptiveDelay);

            log.debug("Calculated adaptive retry for job {}: delay={}ms, category={}, attempt={}", 
                     jobId, adaptiveDelay.toMillis(), errorCategory, currentRetryCount + 1);

            return new AdaptiveRetryResult(true, nextRetryAt, adaptiveDelay, 
                String.format("Adaptive retry scheduled: %s error with %s backoff", 
                             errorCategory, config.getBackoffStrategy()));

        } catch (Exception e) {
            log.error("Error calculating adaptive retry for job {}: {}", jobId, e.getMessage(), e);
            // Fallback to simple exponential backoff
            Duration fallbackDelay = Duration.ofSeconds((long) Math.pow(2, currentRetryCount));
            return new AdaptiveRetryResult(currentRetryCount < 3, 
                                         Instant.now().plus(fallbackDelay),
                                         fallbackDelay,
                                         "Fallback retry strategy due to calculation error");
        }
    }

    /**
     * Get adaptive retry configuration based on error category and job type
     */
    private AdaptiveRetryConfig getAdaptiveRetryConfig(ErrorClassificationService.ErrorCategory errorCategory, 
                                                      JobData.JobType jobType) {
        switch (errorCategory) {
            case TRANSIENT_NETWORK:
                return new AdaptiveRetryConfig(errorCategory,
                    Duration.ofSeconds(2), Duration.ofMinutes(5), 5,
                    BackoffStrategy.EXPONENTIAL, 0.1, true, 0.8);

            case RATE_LIMITED:
                return new AdaptiveRetryConfig(errorCategory,
                    Duration.ofMinutes(1), Duration.ofMinutes(30), 3,
                    BackoffStrategy.LINEAR, 0.2, true, 0.9);

            case SERVICE_UNAVAILABLE:
                return new AdaptiveRetryConfig(errorCategory,
                    Duration.ofSeconds(10), Duration.ofMinutes(10), 4,
                    BackoffStrategy.FIBONACCI, 0.15, true, 0.7);

            case AUTHENTICATION_ERROR:
                return new AdaptiveRetryConfig(errorCategory,
                    Duration.ofMinutes(5), Duration.ofMinutes(30), 2,
                    BackoffStrategy.LINEAR, 0.0, false, 0.5);

            case VALIDATION_ERROR:
            case PERMANENT_ERROR:
                return new AdaptiveRetryConfig(errorCategory,
                    Duration.ZERO, Duration.ZERO, 0,
                    BackoffStrategy.LINEAR, 0.0, false, 1.0);

            case RESOURCE_EXHAUSTION:
                return new AdaptiveRetryConfig(errorCategory,
                    Duration.ofMinutes(2), Duration.ofMinutes(15), 3,
                    BackoffStrategy.ADAPTIVE_EXPONENTIAL, 0.3, true, 0.6);

            case UNKNOWN_ERROR:
            default:
                return new AdaptiveRetryConfig(errorCategory,
                    Duration.ofSeconds(5), Duration.ofMinutes(5), 3,
                    BackoffStrategy.EXPONENTIAL, 0.1, true, 0.8);
        }
    }

    /**
     * Determine if retry should be attempted based on various factors
     */
    private boolean shouldAttemptRetry(AdaptiveRetryConfig config,
                                      String serviceName,
                                      ErrorClassificationService.ErrorCategory errorCategory,
                                      int currentRetryCount,
                                      CircuitBreakerService.CircuitBreakerState circuitState) {
        // Check max retries
        if (currentRetryCount >= config.getMaxRetries()) {
            return false;
        }

        // Check error category
        if (errorCategory == ErrorClassificationService.ErrorCategory.PERMANENT_ERROR ||
            errorCategory == ErrorClassificationService.ErrorCategory.VALIDATION_ERROR) {
            return false;
        }

        // Check circuit breaker
        if (config.isRespectsCircuitBreaker() && 
            circuitState == CircuitBreakerService.CircuitBreakerState.OPEN) {
            log.debug("Retry prevented by open circuit breaker for service: {}", serviceName);
            return false;
        }

        // Check system load
        if (currentSystemLoad > config.getSystemLoadThreshold()) {
            log.debug("Retry delayed due to high system load: {} > {}", 
                     currentSystemLoad, config.getSystemLoadThreshold());
            // Still allow retry but with increased delay
        }

        return true;
    }

    /**
     * Calculate adaptive delay based on multiple factors
     */
    private Duration calculateAdaptiveDelay(AdaptiveRetryConfig config,
                                           String serviceName,
                                           ErrorClassificationService.ErrorCategory errorCategory,
                                           int currentRetryCount,
                                           CircuitBreakerService.CircuitBreakerState circuitState) {
        Duration baseDelay = config.getBaseDelay();
        
        // Apply backoff strategy
        Duration calculatedDelay = applyBackoffStrategy(
            config.getBackoffStrategy(), baseDelay, currentRetryCount, serviceName, errorCategory);

        // Apply system load multiplier
        double loadMultiplier = calculateLoadMultiplier(config.getSystemLoadThreshold());
        calculatedDelay = calculatedDelay.multipliedBy((long) (loadMultiplier * 100)).dividedBy(100);

        // Apply circuit breaker delay
        if (circuitState == CircuitBreakerService.CircuitBreakerState.HALF_OPEN) {
            calculatedDelay = calculatedDelay.multipliedBy(2); // Be more cautious when half-open
        }

        // Apply jitter
        if (config.getJitterFactor() > 0) {
            calculatedDelay = applyJitter(calculatedDelay, config.getJitterFactor());
        }

        // Ensure within bounds
        return calculatedDelay.compareTo(config.getMaxDelay()) > 0 ? config.getMaxDelay() : calculatedDelay;
    }

    /**
     * Apply backoff strategy
     */
    private Duration applyBackoffStrategy(BackoffStrategy strategy, 
                                        Duration baseDelay,
                                        int retryCount,
                                        String serviceName,
                                        ErrorClassificationService.ErrorCategory errorCategory) {
        switch (strategy) {
            case LINEAR:
                return baseDelay.multipliedBy(retryCount + 1);

            case EXPONENTIAL:
                return baseDelay.multipliedBy((long) Math.pow(2, retryCount));

            case FIBONACCI:
                return baseDelay.multipliedBy(fibonacci(retryCount + 1));

            case ADAPTIVE_EXPONENTIAL:
                double successRate = getRetryPatternSuccessRate(serviceName, errorCategory);
                double adaptiveFactor = Math.max(1.0, 3.0 - (2.0 * successRate)); // 1.0 to 3.0 based on success
                return baseDelay.multipliedBy((long) (Math.pow(adaptiveFactor, retryCount)));

            default:
                return baseDelay.multipliedBy((long) Math.pow(2, retryCount));
        }
    }

    /**
     * Calculate Fibonacci number
     */
    private long fibonacci(int n) {
        if (n <= 1) return 1;
        long a = 1, b = 1;
        for (int i = 2; i <= n; i++) {
            long temp = a + b;
            a = b;
            b = temp;
        }
        return b;
    }

    /**
     * Apply jitter to delay
     */
    private Duration applyJitter(Duration delay, double jitterFactor) {
        double jitter = ThreadLocalRandom.current().nextDouble(-jitterFactor, jitterFactor);
        long jitterMs = (long) (delay.toMillis() * jitter);
        return delay.plusMillis(jitterMs);
    }

    /**
     * Calculate load multiplier based on system load
     */
    private double calculateLoadMultiplier(double loadThreshold) {
        if (currentSystemLoad <= loadThreshold) {
            return 1.0; // Normal delay
        }
        
        // Exponential increase in delay as load exceeds threshold
        double excessLoad = currentSystemLoad - loadThreshold;
        return 1.0 + (excessLoad * 3.0); // Up to 4x delay when fully loaded
    }

    /**
     * Update current system load based on worker health
     */
    private void updateSystemLoad() {
        if (Duration.between(lastSystemLoadUpdate, Instant.now()).compareTo(Duration.ofMinutes(1)) < 0) {
            return; // Don't update too frequently
        }

        try {
            // Calculate system load using available interface methods
            int activeWorkerCount = workerMetricsProvider.getActiveWorkerCount();
            
            // Simple load calculation based on active workers
            // If no active workers, system is heavily loaded
            double workerLoad = activeWorkerCount > 0 ? Math.min(1.0, (double) activeWorkerCount / 10.0) : 1.0;
            
            // Use current load as primary indicator since we don't have full health report
            currentSystemLoad = Math.min(1.0, workerLoad);
            lastSystemLoadUpdate = Instant.now();
            
            // Store in Redis for other services
            redisTemplate.opsForValue().set(SYSTEM_LOAD_KEY, currentSystemLoad, Duration.ofMinutes(5));
            
            log.debug("Updated system load: {:.2f} (workers: {})", currentSystemLoad, activeWorkerCount);
                     
        } catch (Exception e) {
            log.warn("Failed to update system load: {}", e.getMessage());
        }
    }


    /**
     * Record retry pattern for learning
     */
    private void recordRetryPattern(String serviceName, 
                                   ErrorClassificationService.ErrorCategory errorCategory,
                                   int retryCount, 
                                   Duration delay) {
        try {
            String patternKey = serviceName + ":" + errorCategory.name();
            RetryPatternMetrics metrics = retryPatterns.computeIfAbsent(patternKey, 
                k -> new RetryPatternMetrics());
            
            metrics.recordAttempt(retryCount, delay);
            
            // Persist to Redis periodically
            if (metrics.getTotalAttempts() % 10 == 0) {
                String redisKey = RETRY_PATTERN_KEY_PREFIX + patternKey;
                redisTemplate.opsForValue().set(redisKey, metrics, Duration.ofDays(7));
            }
            
        } catch (Exception e) {
            log.debug("Failed to record retry pattern: {}", e.getMessage());
        }
    }

    /**
     * Get retry pattern success rate for adaptive strategies
     */
    private double getRetryPatternSuccessRate(String serviceName, 
                                            ErrorClassificationService.ErrorCategory errorCategory) {
        try {
            String patternKey = serviceName + ":" + errorCategory.name();
            RetryPatternMetrics metrics = retryPatterns.get(patternKey);
            return metrics != null ? metrics.getSuccessRate() : 0.5; // Default to 50%
        } catch (Exception e) {
            return 0.5; // Default fallback
        }
    }

    /**
     * Load historical retry patterns from Redis
     */
    private void loadRetryPatterns() {
        try {
            Set<String> patternKeys = redisTemplate.keys(RETRY_PATTERN_KEY_PREFIX + "*");
            if (patternKeys != null) {
                for (String key : patternKeys) {
                    RetryPatternMetrics metrics = (RetryPatternMetrics) redisTemplate.opsForValue().get(key);
                    if (metrics != null) {
                        String patternKey = key.substring(RETRY_PATTERN_KEY_PREFIX.length());
                        retryPatterns.put(patternKey, metrics);
                    }
                }
                log.info("Loaded {} retry patterns from Redis", retryPatterns.size());
            }
        } catch (Exception e) {
            log.warn("Failed to load retry patterns: {}", e.getMessage());
        }
    }

    /**
     * Retry pattern metrics for learning
     */
    public static class RetryPatternMetrics {
        private long totalAttempts = 0;
        private long successfulRetries = 0;
        private Duration averageDelay = Duration.ZERO;
        private Map<Integer, Long> retryCountDistribution = new HashMap<>();

        public void recordAttempt(int retryCount, Duration delay) {
            totalAttempts++;
            averageDelay = averageDelay.plus(delay).dividedBy(2);
            retryCountDistribution.merge(retryCount, 1L, Long::sum);
        }

        public void recordSuccess() {
            successfulRetries++;
        }

        public double getSuccessRate() {
            return totalAttempts > 0 ? (double) successfulRetries / totalAttempts : 0.0;
        }

        // Getters
        public long getTotalAttempts() { return totalAttempts; }
        public long getSuccessfulRetries() { return successfulRetries; }
        public Duration getAverageDelay() { return averageDelay; }
        public Map<Integer, Long> getRetryCountDistribution() { return retryCountDistribution; }
    }

    /**
     * Result of adaptive retry calculation
     */
    public static class AdaptiveRetryResult {
        private final boolean shouldRetry;
        private final Instant nextRetryAt;
        private final Duration delayUntilNextTry;
        private final String reason;

        public AdaptiveRetryResult(boolean shouldRetry, Instant nextRetryAt, 
                                  Duration delayUntilNextTry, String reason) {
            this.shouldRetry = shouldRetry;
            this.nextRetryAt = nextRetryAt;
            this.delayUntilNextTry = delayUntilNextTry;
            this.reason = reason;
        }

        // Getters
        public boolean isShouldRetry() { return shouldRetry; }
        public Instant getNextRetryAt() { return nextRetryAt; }
        public Duration getDelayUntilNextTry() { return delayUntilNextTry; }
        public String getReason() { return reason; }
    }
}
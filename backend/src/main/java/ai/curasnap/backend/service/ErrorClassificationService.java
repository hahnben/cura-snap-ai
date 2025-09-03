package ai.curasnap.backend.service;

import ai.curasnap.backend.service.interfaces.WorkerMetricsProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Service for intelligent error classification and retry strategy determination
 * Analyzes errors and determines appropriate handling based on error patterns
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.error-handling.classification.enabled", havingValue = "true", matchIfMissing = true)
public class ErrorClassificationService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final WorkerMetricsProvider workerMetricsProvider;
    
    // Error pattern cache for performance
    private final Map<String, ErrorCategory> errorPatternCache = new ConcurrentHashMap<>();
    
    // Redis keys for error statistics
    private static final String ERROR_STATS_PREFIX = "error_stats:";
    private static final String ERROR_PATTERNS_KEY = "error_patterns";
    
    // Error classification patterns
    private static final Map<Pattern, ErrorCategory> ERROR_PATTERNS = new HashMap<>();
    
    static {
        // Network and connectivity errors - usually transient
        ERROR_PATTERNS.put(Pattern.compile("(?i).*(connection|network|timeout|unreachable|refused).*"), ErrorCategory.TRANSIENT_NETWORK);
        ERROR_PATTERNS.put(Pattern.compile("(?i).*(socket|host|dns).*"), ErrorCategory.TRANSIENT_NETWORK);
        
        // Rate limiting - should back off exponentially
        ERROR_PATTERNS.put(Pattern.compile("(?i).*(rate.?limit|too.?many.?requests|429|quota|throttl).*"), ErrorCategory.RATE_LIMITED);
        
        // Service unavailable - circuit breaker candidate
        ERROR_PATTERNS.put(Pattern.compile("(?i).*(service.?unavailable|502|503|504|bad.?gateway).*"), ErrorCategory.SERVICE_UNAVAILABLE);
        ERROR_PATTERNS.put(Pattern.compile("(?i).*(maintenance|overload|busy).*"), ErrorCategory.SERVICE_UNAVAILABLE);
        
        // Authentication/authorization - usually permanent until fixed
        ERROR_PATTERNS.put(Pattern.compile("(?i).*(auth|unauthorized|forbidden|401|403|invalid.?token|expired.?token).*"), ErrorCategory.AUTHENTICATION_ERROR);
        
        // Resource exhaustion - need adaptive handling
        ERROR_PATTERNS.put(Pattern.compile("(?i).*(memory|disk.?space|quota.?exceeded|resource|capacity).*"), ErrorCategory.RESOURCE_EXHAUSTION);
        
        // Input validation errors - permanent for that input
        ERROR_PATTERNS.put(Pattern.compile("(?i).*(validation|invalid.?input|malformed|format|parse|syntax).*"), ErrorCategory.VALIDATION_ERROR);
        ERROR_PATTERNS.put(Pattern.compile("(?i).*(unsupported|not.?supported|incompatible).*"), ErrorCategory.VALIDATION_ERROR);
        
        // File/data errors - may be permanent or transient
        ERROR_PATTERNS.put(Pattern.compile("(?i).*(file.?not.?found|corrupted|incomplete|truncated).*"), ErrorCategory.DATA_ERROR);
        
        // External service specific errors
        ERROR_PATTERNS.put(Pattern.compile("(?i).*(transcription|whisper).*"), ErrorCategory.TRANSCRIPTION_ERROR);
        ERROR_PATTERNS.put(Pattern.compile("(?i).*(openai|gpt|agent|llm).*"), ErrorCategory.AGENT_SERVICE_ERROR);
    }

    @Autowired
    public ErrorClassificationService(RedisTemplate<String, Object> redisTemplate, 
                                    WorkerMetricsProvider workerMetricsProvider) {
        this.redisTemplate = redisTemplate;
        this.workerMetricsProvider = workerMetricsProvider;
        log.info("Error Classification Service initialized with {} patterns", ERROR_PATTERNS.size());
    }

    /**
     * Classify an error and determine its category
     *
     * @param serviceName the service where error occurred
     * @param error the error/exception
     * @return error category
     */
    public ErrorCategory classifyError(String serviceName, Throwable error) {
        String errorKey = generateErrorKey(serviceName, error);
        
        // Check cache first
        ErrorCategory cached = errorPatternCache.get(errorKey);
        if (cached != null) {
            return cached;
        }

        ErrorCategory category = analyzeError(serviceName, error);
        
        // Cache the result
        errorPatternCache.put(errorKey, category);
        
        // Record error statistics
        recordErrorStats(serviceName, category, error.getMessage());
        
        log.debug("Classified error for service {} as {}: {}", serviceName, category, error.getMessage());
        return category;
    }

    /**
     * Get retry strategy based on error category and current system health
     *
     * @param category the error category
     * @param systemHealth current system health status
     * @return appropriate retry strategy
     */
    public RetryStrategy.RetryConfig getRetryStrategy(ErrorCategory category, SystemHealth systemHealth) {
        RetryStrategy.RetryConfig baseConfig = getBaseRetryConfigForCategory(category);
        
        // Adapt strategy based on system health
        return adaptRetryStrategyForSystemHealth(baseConfig, systemHealth);
    }

    /**
     * Determine if a job should be retried based on error category and current attempt
     *
     * @param category error category
     * @param currentAttempt current retry attempt (0-based)
     * @param maxRetries maximum allowed retries
     * @return true if should retry
     */
    public boolean shouldRetry(ErrorCategory category, int currentAttempt, int maxRetries) {
        if (currentAttempt >= maxRetries) {
            return false;
        }

        switch (category) {
            case PERMANENT_ERROR:
            case VALIDATION_ERROR:
            case AUTHENTICATION_ERROR:
                return false; // Never retry permanent errors
                
            case TRANSIENT_NETWORK:
            case SERVICE_UNAVAILABLE:
                return currentAttempt < Math.min(maxRetries, 5); // More retries for transient issues
                
            case RATE_LIMITED:
                return currentAttempt < Math.min(maxRetries, 3); // Fewer retries for rate limiting
                
            case RESOURCE_EXHAUSTION:
                return currentAttempt < 2; // Very few retries for resource issues
                
            case DATA_ERROR:
            case TRANSCRIPTION_ERROR:
            case AGENT_SERVICE_ERROR:
                return currentAttempt < Math.min(maxRetries, 2); // Limited retries for data/service errors
                
            case UNKNOWN_ERROR:
            default:
                return currentAttempt < maxRetries; // Standard retry logic
        }
    }

    /**
     * Get error statistics for a service
     *
     * @param serviceName service name (optional, null for all services)
     * @return error statistics
     */
    public ErrorStatistics getErrorStatistics(String serviceName) {
        try {
            if (serviceName != null) {
                return getServiceErrorStatistics(serviceName);
            } else {
                return getAggregateErrorStatistics();
            }
        } catch (Exception e) {
            log.error("Failed to get error statistics for {}: {}", serviceName, e.getMessage());
            return new ErrorStatistics(serviceName, new HashMap<>(), 0, 0, null);
        }
    }

    /**
     * Get trending error patterns for analysis
     *
     * @param timeWindow time window to analyze
     * @return trending error patterns
     */
    public List<ErrorPattern> getTrendingErrorPatterns(Duration timeWindow) {
        try {
            // This would typically analyze error patterns over time
            // For now, return basic pattern analysis
            Set<String> errorKeys = redisTemplate.keys(ERROR_STATS_PREFIX + "*");
            if (errorKeys == null) {
                return Collections.emptyList();
            }

            List<ErrorPattern> patterns = new ArrayList<>();
            for (String key : errorKeys) {
                Map<Object, Object> stats = redisTemplate.opsForHash().entries(key);
                if (!stats.isEmpty()) {
                    String serviceName = key.substring(ERROR_STATS_PREFIX.length());
                    ErrorPattern pattern = analyzeErrorPattern(serviceName, stats);
                    if (pattern != null) {
                        patterns.add(pattern);
                    }
                }
            }

            return patterns;

        } catch (Exception e) {
            log.error("Failed to get trending error patterns: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // Private methods

    private ErrorCategory analyzeError(String serviceName, Throwable error) {
        String errorMessage = error.getMessage();
        String errorClass = error.getClass().getSimpleName();
        String fullErrorText = errorClass + ": " + (errorMessage != null ? errorMessage : "");

        // Try to match against known patterns
        for (Map.Entry<Pattern, ErrorCategory> entry : ERROR_PATTERNS.entrySet()) {
            if (entry.getKey().matcher(fullErrorText).matches()) {
                return entry.getValue();
            }
        }

        // Service-specific classification
        if ("TranscriptionService".equalsIgnoreCase(serviceName)) {
            return ErrorCategory.TRANSCRIPTION_ERROR;
        } else if ("AgentService".equalsIgnoreCase(serviceName)) {
            return ErrorCategory.AGENT_SERVICE_ERROR;
        }

        // Default classification
        return ErrorCategory.UNKNOWN_ERROR;
    }

    private RetryStrategy.RetryConfig getBaseRetryConfigForCategory(ErrorCategory category) {
        switch (category) {
            case TRANSIENT_NETWORK:
                return RetryStrategy.NETWORK_ERROR_BACKOFF;
                
            case RATE_LIMITED:
                return RetryStrategy.RetryConfig.builder()
                        .policy(RetryStrategy.RetryPolicy.EXPONENTIAL_BACKOFF)
                        .maxRetries(3)
                        .initialDelay(Duration.ofSeconds(30))
                        .maxDelay(Duration.ofMinutes(10))
                        .backoffMultiplier(3.0)
                        .enableJitter(true)
                        .build();
                
            case SERVICE_UNAVAILABLE:
                return RetryStrategy.RetryConfig.builder()
                        .policy(RetryStrategy.RetryPolicy.EXPONENTIAL_BACKOFF)
                        .maxRetries(4)
                        .initialDelay(Duration.ofSeconds(10))
                        .maxDelay(Duration.ofMinutes(5))
                        .backoffMultiplier(2.5)
                        .enableJitter(true)
                        .build();
                
            case RESOURCE_EXHAUSTION:
                return RetryStrategy.RetryConfig.builder()
                        .policy(RetryStrategy.RetryPolicy.EXPONENTIAL_BACKOFF)
                        .maxRetries(2)
                        .initialDelay(Duration.ofMinutes(1))
                        .maxDelay(Duration.ofMinutes(15))
                        .backoffMultiplier(4.0)
                        .enableJitter(false)
                        .build();
                
            case TRANSCRIPTION_ERROR:
                return RetryStrategy.AUDIO_PROCESSING_BACKOFF;
                
            case AGENT_SERVICE_ERROR:
                return RetryStrategy.TEXT_PROCESSING_BACKOFF;
                
            case VALIDATION_ERROR:
            case AUTHENTICATION_ERROR:
            case PERMANENT_ERROR:
                return RetryStrategy.RetryConfig.builder()
                        .maxRetries(0) // No retries for permanent errors
                        .build();
                
            case DATA_ERROR:
                return RetryStrategy.RetryConfig.builder()
                        .policy(RetryStrategy.RetryPolicy.FIXED_DELAY)
                        .maxRetries(1)
                        .initialDelay(Duration.ofSeconds(5))
                        .build();
                
            case UNKNOWN_ERROR:
            default:
                return RetryStrategy.STANDARD_BACKOFF;
        }
    }

    private RetryStrategy.RetryConfig adaptRetryStrategyForSystemHealth(
            RetryStrategy.RetryConfig baseConfig, SystemHealth systemHealth) {
        
        if (systemHealth.getHealthScore() < 50) {
            // System unhealthy - reduce retry aggressiveness
            return RetryStrategy.RetryConfig.builder()
                    .policy(baseConfig.getPolicy())
                    .maxRetries(Math.max(1, baseConfig.getMaxRetries() - 1))
                    .initialDelay(baseConfig.getInitialDelay().multipliedBy(2))
                    .maxDelay(baseConfig.getMaxDelay())
                    .backoffMultiplier(baseConfig.getBackoffMultiplier())
                    .enableJitter(true)
                    .build();
                    
        } else if (systemHealth.getHealthScore() > 90) {
            // System very healthy - can be more aggressive
            return RetryStrategy.RetryConfig.builder()
                    .policy(baseConfig.getPolicy())
                    .maxRetries(Math.min(baseConfig.getMaxRetries() + 1, 6))
                    .initialDelay(baseConfig.getInitialDelay().dividedBy(2))
                    .maxDelay(baseConfig.getMaxDelay())
                    .backoffMultiplier(baseConfig.getBackoffMultiplier())
                    .enableJitter(baseConfig.isEnableJitter())
                    .build();
        }
        
        return baseConfig; // Use base config for normal health
    }

    private void recordErrorStats(String serviceName, ErrorCategory category, String errorMessage) {
        try {
            String statsKey = ERROR_STATS_PREFIX + serviceName;
            
            // Increment category counter
            redisTemplate.opsForHash().increment(statsKey, category.name(), 1);
            
            // Record last occurrence
            redisTemplate.opsForHash().put(statsKey, "lastError", errorMessage);
            redisTemplate.opsForHash().put(statsKey, "lastErrorTime", Instant.now().toString());
            
            // Set TTL for stats cleanup
            redisTemplate.expire(statsKey, Duration.ofDays(1));
            
        } catch (Exception e) {
            log.debug("Failed to record error stats: {}", e.getMessage());
        }
    }

    private ErrorStatistics getServiceErrorStatistics(String serviceName) {
        String statsKey = ERROR_STATS_PREFIX + serviceName;
        Map<Object, Object> stats = redisTemplate.opsForHash().entries(statsKey);
        
        Map<ErrorCategory, Long> categoryStats = new HashMap<>();
        long totalErrors = 0;
        String lastErrorTime = null;

        for (Map.Entry<Object, Object> entry : stats.entrySet()) {
            String key = entry.getKey().toString();
            if ("lastErrorTime".equals(key)) {
                lastErrorTime = entry.getValue().toString();
            } else if (!"lastError".equals(key)) {
                try {
                    ErrorCategory category = ErrorCategory.valueOf(key);
                    Long count = Long.valueOf(entry.getValue().toString());
                    categoryStats.put(category, count);
                    totalErrors += count;
                } catch (Exception e) {
                    // Ignore invalid entries
                }
            }
        }

        Instant lastError = null;
        if (lastErrorTime != null) {
            try {
                lastError = Instant.parse(lastErrorTime);
            } catch (Exception e) {
                // Ignore invalid timestamp
            }
        }

        return new ErrorStatistics(serviceName, categoryStats, totalErrors, totalErrors, lastError);
    }

    private ErrorStatistics getAggregateErrorStatistics() {
        Set<String> errorKeys = redisTemplate.keys(ERROR_STATS_PREFIX + "*");
        if (errorKeys == null) {
            return new ErrorStatistics(null, new HashMap<>(), 0, 0, null);
        }

        Map<ErrorCategory, Long> aggregateStats = new HashMap<>();
        long totalErrors = 0;
        Instant lastError = null;

        for (String key : errorKeys) {
            Map<Object, Object> stats = redisTemplate.opsForHash().entries(key);
            for (Map.Entry<Object, Object> entry : stats.entrySet()) {
                String statKey = entry.getKey().toString();
                if ("lastErrorTime".equals(statKey)) {
                    try {
                        Instant errorTime = Instant.parse(entry.getValue().toString());
                        if (lastError == null || errorTime.isAfter(lastError)) {
                            lastError = errorTime;
                        }
                    } catch (Exception e) {
                        // Ignore invalid timestamp
                    }
                } else if (!"lastError".equals(statKey)) {
                    try {
                        ErrorCategory category = ErrorCategory.valueOf(statKey);
                        Long count = Long.valueOf(entry.getValue().toString());
                        aggregateStats.merge(category, count, Long::sum);
                        totalErrors += count;
                    } catch (Exception e) {
                        // Ignore invalid entries
                    }
                }
            }
        }

        return new ErrorStatistics(null, aggregateStats, totalErrors, totalErrors, lastError);
    }

    private ErrorPattern analyzeErrorPattern(String serviceName, Map<Object, Object> stats) {
        // Basic pattern analysis - could be more sophisticated
        ErrorCategory mostCommon = null;
        long maxCount = 0;

        for (Map.Entry<Object, Object> entry : stats.entrySet()) {
            String key = entry.getKey().toString();
            if (!"lastError".equals(key) && !"lastErrorTime".equals(key)) {
                try {
                    Long count = Long.valueOf(entry.getValue().toString());
                    if (count > maxCount) {
                        maxCount = count;
                        mostCommon = ErrorCategory.valueOf(key);
                    }
                } catch (Exception e) {
                    // Ignore invalid entries
                }
            }
        }

        if (mostCommon != null && maxCount > 0) {
            return new ErrorPattern(serviceName, mostCommon, maxCount, Instant.now());
        }

        return null;
    }

    private String generateErrorKey(String serviceName, Throwable error) {
        return serviceName + ":" + error.getClass().getSimpleName();
    }

    // Data classes

    public enum ErrorCategory {
        TRANSIENT_NETWORK,      // Network connectivity issues
        RATE_LIMITED,           // Rate limiting/throttling
        SERVICE_UNAVAILABLE,    // External service down
        AUTHENTICATION_ERROR,   // Auth/authorization issues
        RESOURCE_EXHAUSTION,    // Memory/disk/quota exceeded
        VALIDATION_ERROR,       // Input validation failures
        DATA_ERROR,            // File/data corruption issues
        TRANSCRIPTION_ERROR,    // Transcription service specific
        AGENT_SERVICE_ERROR,   // Agent service specific
        PERMANENT_ERROR,       // Unrecoverable errors
        UNKNOWN_ERROR          // Unclassified errors
    }

    public static class ErrorStatistics {
        private final String serviceName;
        private final Map<ErrorCategory, Long> categoryStats;
        private final long totalErrors;
        private final long uniqueErrors;
        private final Instant lastErrorTime;

        public ErrorStatistics(String serviceName, Map<ErrorCategory, Long> categoryStats,
                             long totalErrors, long uniqueErrors, Instant lastErrorTime) {
            this.serviceName = serviceName;
            this.categoryStats = categoryStats;
            this.totalErrors = totalErrors;
            this.uniqueErrors = uniqueErrors;
            this.lastErrorTime = lastErrorTime;
        }

        // Getters
        public String getServiceName() { return serviceName; }
        public Map<ErrorCategory, Long> getCategoryStats() { return categoryStats; }
        public long getTotalErrors() { return totalErrors; }
        public long getUniqueErrors() { return uniqueErrors; }
        public Instant getLastErrorTime() { return lastErrorTime; }
    }

    public static class ErrorPattern {
        private final String serviceName;
        private final ErrorCategory category;
        private final long occurrences;
        private final Instant firstSeen;

        public ErrorPattern(String serviceName, ErrorCategory category, long occurrences, Instant firstSeen) {
            this.serviceName = serviceName;
            this.category = category;
            this.occurrences = occurrences;
            this.firstSeen = firstSeen;
        }

        // Getters
        public String getServiceName() { return serviceName; }
        public ErrorCategory getCategory() { return category; }
        public long getOccurrences() { return occurrences; }
        public Instant getFirstSeen() { return firstSeen; }
    }

    public static class SystemHealth {
        private final int healthScore;
        private final int activeWorkers;
        private final long queueSize;
        private final double failureRate;

        public SystemHealth(int healthScore, int activeWorkers, long queueSize, double failureRate) {
            this.healthScore = healthScore;
            this.activeWorkers = activeWorkers;
            this.queueSize = queueSize;
            this.failureRate = failureRate;
        }

        // Getters
        public int getHealthScore() { return healthScore; }
        public int getActiveWorkers() { return activeWorkers; }
        public long getQueueSize() { return queueSize; }
        public double getFailureRate() { return failureRate; }
    }
}
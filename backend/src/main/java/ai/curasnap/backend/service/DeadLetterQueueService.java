package ai.curasnap.backend.service;

import ai.curasnap.backend.model.dto.JobData;
import ai.curasnap.backend.service.interfaces.WorkerMetricsProvider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Enhanced Dead Letter Queue Service for comprehensive failed job management
 * 
 * Features:
 * - Intelligent job categorization and analysis
 * - Retry eligibility assessment
 * - Automatic reprocessing of recovered jobs
 * - Failed job analytics and reporting
 * - DLQ maintenance and cleanup
 * - Pattern recognition for failure analysis
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.dlq.enhanced.enabled", havingValue = "true", matchIfMissing = true)
public class DeadLetterQueueService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ErrorClassificationService errorClassificationService;
    private final CircuitBreakerService circuitBreakerService;
    private final WorkerMetricsProvider workerMetricsProvider;

    // Redis keys for DLQ management
    private static final String DLQ_KEY_PREFIX = "dlq:";
    private static final String DLQ_STATS_KEY = "dlq_stats";
    private static final String DLQ_PATTERNS_KEY = "dlq_patterns";
    private static final String DLQ_RETRY_CANDIDATES_KEY = "dlq_retry_candidates";
    private static final String DLQ_ANALYSIS_KEY_PREFIX = "dlq_analysis:";

    // Configuration
    private static final int MAX_DLQ_SIZE_PER_QUEUE = 2000;
    private static final Duration DLQ_RETENTION_PERIOD = Duration.ofDays(14);
    private static final Duration RETRY_ELIGIBILITY_COOLDOWN = Duration.ofMinutes(30);

    @Autowired
    public DeadLetterQueueService(RedisTemplate<String, Object> redisTemplate,
                                 ErrorClassificationService errorClassificationService,
                                 CircuitBreakerService circuitBreakerService,
                                 WorkerMetricsProvider workerMetricsProvider) {
        this.redisTemplate = redisTemplate;
        this.errorClassificationService = errorClassificationService;
        this.circuitBreakerService = circuitBreakerService;
        this.workerMetricsProvider = workerMetricsProvider;
        
        log.info("Enhanced DeadLetterQueueService initialized with intelligent failure management");
    }

    /**
     * Enhanced method to move job to Dead Letter Queue with detailed analysis
     */
    public void moveJobToDeadLetterQueue(JobData jobData, String reason, Throwable error) {
        try {
            // Analyze the failure
            DLQEntry dlqEntry = createEnhancedDLQEntry(jobData, reason, error);
            
            // Update job status
            jobData.setStatus(JobData.JobStatus.FAILED);
            jobData.setCompletedAt(Instant.now());
            jobData.setErrorMessage("Moved to Dead Letter Queue: " + reason);

            // Save job with extended retention for analysis
            String jobKey = "jobs:" + jobData.getJobId();
            redisTemplate.opsForValue().set(jobKey, jobData, DLQ_RETENTION_PERIOD);

            // Add to Dead Letter Queue with enhanced metadata
            String dlqKey = DLQ_KEY_PREFIX + jobData.getQueueName();
            redisTemplate.opsForList().rightPush(dlqKey, dlqEntry);
            
            // Maintain queue size limits
            maintainDLQSize(dlqKey);

            // Update DLQ statistics
            updateDLQStatistics(jobData.getQueueName(), dlqEntry);

            // Analyze failure patterns
            analyzeFailurePattern(jobData.getQueueName(), dlqEntry);

            // Check for retry eligibility
            assessRetryEligibility(dlqEntry);

            log.warn("Enhanced DLQ: Moved job {} to {} with classification: {} - {}", 
                    jobData.getJobId(), dlqKey, dlqEntry.getErrorCategory(), reason);

        } catch (Exception e) {
            log.error("Failed to move job {} to enhanced DLQ: {}", 
                    jobData.getJobId(), e.getMessage(), e);
        }
    }

    /**
     * Create enhanced DLQ entry with failure analysis
     */
    private DLQEntry createEnhancedDLQEntry(JobData jobData, String reason, Throwable error) {
        // Classify the error
        String serviceName = determineServiceName(jobData.getJobType());
        ErrorClassificationService.ErrorCategory errorCategory = 
            error != null ? errorClassificationService.classifyError(serviceName, error) :
                          ErrorClassificationService.ErrorCategory.UNKNOWN_ERROR;

        // Get circuit breaker state
        CircuitBreakerService.CircuitBreakerState circuitState = 
            circuitBreakerService.getCircuitBreakerState(serviceName);

        // Create enhanced entry
        DLQEntry dlqEntry = new DLQEntry();
        dlqEntry.setJobId(jobData.getJobId());
        dlqEntry.setUserId(jobData.getUserId());
        dlqEntry.setJobType(jobData.getJobType().getValue());
        dlqEntry.setQueueName(jobData.getQueueName());
        dlqEntry.setFailedAt(Instant.now());
        dlqEntry.setRetryAttempts(jobData.getRetryCount());
        dlqEntry.setMaxRetries(jobData.getMaxRetries());
        dlqEntry.setReason(reason);
        dlqEntry.setOriginalError(jobData.getErrorMessage());
        dlqEntry.setErrorCategory(errorCategory);
        dlqEntry.setServiceName(serviceName);
        dlqEntry.setCircuitBreakerState(circuitState);
        
        // Add error details if available
        if (error != null) {
            dlqEntry.setErrorClass(error.getClass().getSimpleName());
            dlqEntry.setErrorMessage(error.getMessage());
            dlqEntry.setStackTrace(getCompactStackTrace(error));
        }

        // Calculate retry eligibility
        dlqEntry.setRetryEligible(calculateRetryEligibility(errorCategory, circuitState));
        dlqEntry.setNextRetryEligibleAt(calculateNextRetryTime(errorCategory));

        // Add job context
        if (jobData.getInputData() != null) {
            Map<String, Object> context = new HashMap<>();
            context.put("originalFileName", jobData.getInputData().get("originalFileName"));
            context.put("fileSize", jobData.getInputData().get("fileSize"));
            context.put("contentType", jobData.getInputData().get("contentType"));
            context.put("sessionId", jobData.getSessionId());
            dlqEntry.setJobContext(context);
        }

        return dlqEntry;
    }

    /**
     * Calculate if job is eligible for retry based on error type and conditions
     */
    private boolean calculateRetryEligibility(ErrorClassificationService.ErrorCategory errorCategory,
                                            CircuitBreakerService.CircuitBreakerState circuitState) {
        // Never retry certain error types
        if (errorCategory == ErrorClassificationService.ErrorCategory.VALIDATION_ERROR ||
            errorCategory == ErrorClassificationService.ErrorCategory.PERMANENT_ERROR ||
            errorCategory == ErrorClassificationService.ErrorCategory.AUTHENTICATION_ERROR) {
            return false;
        }

        // Don't retry if circuit breaker is open
        if (circuitState == CircuitBreakerService.CircuitBreakerState.OPEN) {
            return false;
        }

        // Retry eligible for transient errors when system is healthy
        return errorCategory == ErrorClassificationService.ErrorCategory.TRANSIENT_NETWORK ||
               errorCategory == ErrorClassificationService.ErrorCategory.RATE_LIMITED ||
               errorCategory == ErrorClassificationService.ErrorCategory.SERVICE_UNAVAILABLE ||
               errorCategory == ErrorClassificationService.ErrorCategory.RESOURCE_EXHAUSTION;
    }

    /**
     * Calculate next retry time based on error category
     */
    private Instant calculateNextRetryTime(ErrorClassificationService.ErrorCategory errorCategory) {
        switch (errorCategory) {
            case TRANSIENT_NETWORK:
                return Instant.now().plus(RETRY_ELIGIBILITY_COOLDOWN);
            case RATE_LIMITED:
                return Instant.now().plus(Duration.ofHours(1)); // Wait longer for rate limits
            case SERVICE_UNAVAILABLE:
            case RESOURCE_EXHAUSTION:
                return Instant.now().plus(Duration.ofMinutes(15));
            default:
                return Instant.now().plus(Duration.ofDays(1)); // Far future for non-retryable
        }
    }

    /**
     * Get DLQ entries for a specific queue with optional filtering
     */
    public List<DLQEntry> getDLQEntries(String queueName, 
                                       ErrorClassificationService.ErrorCategory filterCategory,
                                       Boolean retryEligibleOnly,
                                       int limit) {
        try {
            String dlqKey = DLQ_KEY_PREFIX + queueName;
            List<Object> entries = redisTemplate.opsForList().range(dlqKey, 0, -1);
            
            if (entries == null) {
                return Collections.emptyList();
            }

            return entries.stream()
                    .map(obj -> (DLQEntry) obj)
                    .filter(entry -> filterCategory == null || entry.getErrorCategory() == filterCategory)
                    .filter(entry -> !retryEligibleOnly || entry.isRetryEligible())
                    .sorted((a, b) -> b.getFailedAt().compareTo(a.getFailedAt())) // Newest first
                    .limit(limit)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Failed to get DLQ entries for queue {}: {}", queueName, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get retry-eligible jobs that are ready for reprocessing
     */
    public List<DLQEntry> getRetryEligibleJobs(String queueName) {
        try {
            List<DLQEntry> entries = getDLQEntries(queueName, null, true, 100);
            Instant now = Instant.now();
            
            return entries.stream()
                    .filter(entry -> entry.getNextRetryEligibleAt().isBefore(now))
                    .filter(entry -> isServiceHealthy(entry.getServiceName()))
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Failed to get retry eligible jobs: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Check if service is healthy enough for retry
     */
    private boolean isServiceHealthy(String serviceName) {
        CircuitBreakerService.CircuitBreakerState state = 
            circuitBreakerService.getCircuitBreakerState(serviceName);
        return state != CircuitBreakerService.CircuitBreakerState.OPEN;
    }

    /**
     * Requeue jobs that are eligible for retry
     */
    public int requeueEligibleJobs(String queueName) {
        try {
            List<DLQEntry> eligibleJobs = getRetryEligibleJobs(queueName);
            int requeuedCount = 0;

            for (DLQEntry dlqEntry : eligibleJobs) {
                if (requeueJobFromDLQ(dlqEntry)) {
                    requeuedCount++;
                }
            }

            if (requeuedCount > 0) {
                log.info("Requeued {} jobs from DLQ {} for retry", requeuedCount, queueName);
            }

            return requeuedCount;

        } catch (Exception e) {
            log.error("Failed to requeue eligible jobs from DLQ {}: {}", queueName, e.getMessage());
            return 0;
        }
    }

    /**
     * Requeue individual job from DLQ back to main queue
     */
    private boolean requeueJobFromDLQ(DLQEntry dlqEntry) {
        try {
            // Get original job data
            String jobKey = "jobs:" + dlqEntry.getJobId();
            JobData jobData = (JobData) redisTemplate.opsForValue().get(jobKey);
            
            if (jobData == null) {
                log.warn("Cannot requeue job {} - job data not found", dlqEntry.getJobId());
                return false;
            }

            // Reset job for retry
            jobData.setStatus(JobData.JobStatus.QUEUED);
            jobData.setRetryCount(0); // Reset retry count for DLQ retry
            jobData.setErrorMessage(null);
            jobData.setCompletedAt(null);

            // Update job
            redisTemplate.opsForValue().set(jobKey, jobData, Duration.ofDays(1));

            // Add back to main queue
            String mainQueueKey = "queue:" + dlqEntry.getQueueName();
            redisTemplate.opsForList().rightPush(mainQueueKey, dlqEntry.getJobId());

            // Remove from DLQ
            String dlqKey = DLQ_KEY_PREFIX + dlqEntry.getQueueName();
            redisTemplate.opsForList().remove(dlqKey, 1, dlqEntry);

            log.info("Requeued job {} from DLQ back to main queue", dlqEntry.getJobId());
            return true;

        } catch (Exception e) {
            log.error("Failed to requeue job {} from DLQ: {}", dlqEntry.getJobId(), e.getMessage());
            return false;
        }
    }

    /**
     * Scheduled cleanup of old DLQ entries
     */
    @Scheduled(fixedDelay = 3600000) // Every hour
    public void cleanupExpiredDLQEntries() {
        try {
            Set<String> dlqKeys = redisTemplate.keys(DLQ_KEY_PREFIX + "*");
            if (dlqKeys == null) return;

            long totalCleaned = 0;
            for (String dlqKey : dlqKeys) {
                totalCleaned += cleanupDLQQueue(dlqKey);
            }

            if (totalCleaned > 0) {
                log.info("Cleaned up {} expired DLQ entries", totalCleaned);
            }

        } catch (Exception e) {
            log.error("Failed to cleanup expired DLQ entries: {}", e.getMessage());
        }
    }

    /**
     * Clean up specific DLQ queue
     */
    private long cleanupDLQQueue(String dlqKey) {
        try {
            List<Object> entries = redisTemplate.opsForList().range(dlqKey, 0, -1);
            if (entries == null) return 0;

            long cleaned = 0;
            Instant cutoffTime = Instant.now().minus(DLQ_RETENTION_PERIOD);

            for (Object obj : entries) {
                DLQEntry entry = (DLQEntry) obj;
                if (entry.getFailedAt().isBefore(cutoffTime)) {
                    redisTemplate.opsForList().remove(dlqKey, 1, entry);
                    cleaned++;
                }
            }

            return cleaned;

        } catch (Exception e) {
            log.error("Failed to cleanup DLQ queue {}: {}", dlqKey, e.getMessage());
            return 0;
        }
    }

    /**
     * Get DLQ statistics
     */
    public DLQStatistics getDLQStatistics(String queueName) {
        try {
            String dlqKey = DLQ_KEY_PREFIX + queueName;
            List<Object> entries = redisTemplate.opsForList().range(dlqKey, 0, -1);
            
            if (entries == null) {
                return new DLQStatistics(queueName, 0, new HashMap<>(), 0);
            }

            Map<ErrorClassificationService.ErrorCategory, Long> categoryCounts = new HashMap<>();
            long retryEligibleCount = 0;

            for (Object obj : entries) {
                DLQEntry entry = (DLQEntry) obj;
                categoryCounts.merge(entry.getErrorCategory(), 1L, Long::sum);
                if (entry.isRetryEligible()) {
                    retryEligibleCount++;
                }
            }

            return new DLQStatistics(queueName, entries.size(), categoryCounts, retryEligibleCount);

        } catch (Exception e) {
            log.error("Failed to get DLQ statistics for {}: {}", queueName, e.getMessage());
            return new DLQStatistics(queueName, 0, new HashMap<>(), 0);
        }
    }

    // Helper methods

    private void maintainDLQSize(String dlqKey) {
        try {
            Long size = redisTemplate.opsForList().size(dlqKey);
            if (size != null && size > MAX_DLQ_SIZE_PER_QUEUE) {
                redisTemplate.opsForList().trim(dlqKey, -MAX_DLQ_SIZE_PER_QUEUE, -1);
            }
        } catch (Exception e) {
            log.warn("Failed to maintain DLQ size for {}: {}", dlqKey, e.getMessage());
        }
    }

    private void updateDLQStatistics(String queueName, DLQEntry dlqEntry) {
        try {
            String statsKey = DLQ_STATS_KEY + ":" + queueName;
            Map<String, Object> stats = new HashMap<>();
            stats.put("lastFailure", dlqEntry.getFailedAt().toString());
            stats.put("totalFailures", redisTemplate.opsForValue().increment(statsKey + ":count"));
            stats.put("errorCategory", dlqEntry.getErrorCategory().name());
            
            redisTemplate.opsForHash().putAll(statsKey, stats);
            redisTemplate.expire(statsKey, Duration.ofDays(30));
        } catch (Exception e) {
            log.debug("Failed to update DLQ statistics: {}", e.getMessage());
        }
    }

    private void analyzeFailurePattern(String queueName, DLQEntry dlqEntry) {
        // Implementation for failure pattern analysis
        // Could identify recurring failures, problematic file types, etc.
    }

    private void assessRetryEligibility(DLQEntry dlqEntry) {
        if (dlqEntry.isRetryEligible()) {
            String candidateKey = DLQ_RETRY_CANDIDATES_KEY + ":" + dlqEntry.getQueueName();
            redisTemplate.opsForZSet().add(candidateKey, 
                dlqEntry.getJobId(), 
                dlqEntry.getNextRetryEligibleAt().getEpochSecond());
            redisTemplate.expire(candidateKey, Duration.ofDays(7));
        }
    }

    private String determineServiceName(JobData.JobType jobType) {
        switch (jobType) {
            case AUDIO_PROCESSING:
                return "transcription-service";
            case TEXT_PROCESSING:
                return "agent-service";
            default:
                return "unknown-service";
        }
    }

    private String getCompactStackTrace(Throwable error) {
        if (error == null) return null;
        
        // Return first few lines of stack trace for diagnostics
        String fullTrace = Arrays.toString(error.getStackTrace());
        return fullTrace.length() > 500 ? fullTrace.substring(0, 500) + "..." : fullTrace;
    }

    // Data classes

    /**
     * Enhanced DLQ Entry with detailed failure analysis
     */
    public static class DLQEntry {
        private String jobId;
        private String userId;
        private String jobType;
        private String queueName;
        private Instant failedAt;
        private int retryAttempts;
        private int maxRetries;
        private String reason;
        private String originalError;
        private ErrorClassificationService.ErrorCategory errorCategory;
        private String serviceName;
        private CircuitBreakerService.CircuitBreakerState circuitBreakerState;
        private String errorClass;
        private String errorMessage;
        private String stackTrace;
        private boolean retryEligible;
        private Instant nextRetryEligibleAt;
        private Map<String, Object> jobContext;

        // Constructors, getters, and setters
        public DLQEntry() {}

        // Getters and setters
        public String getJobId() { return jobId; }
        public void setJobId(String jobId) { this.jobId = jobId; }
        
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        
        public String getJobType() { return jobType; }
        public void setJobType(String jobType) { this.jobType = jobType; }
        
        public String getQueueName() { return queueName; }
        public void setQueueName(String queueName) { this.queueName = queueName; }
        
        public Instant getFailedAt() { return failedAt; }
        public void setFailedAt(Instant failedAt) { this.failedAt = failedAt; }
        
        public int getRetryAttempts() { return retryAttempts; }
        public void setRetryAttempts(int retryAttempts) { this.retryAttempts = retryAttempts; }
        
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
        
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
        
        public String getOriginalError() { return originalError; }
        public void setOriginalError(String originalError) { this.originalError = originalError; }
        
        public ErrorClassificationService.ErrorCategory getErrorCategory() { return errorCategory; }
        public void setErrorCategory(ErrorClassificationService.ErrorCategory errorCategory) { this.errorCategory = errorCategory; }
        
        public String getServiceName() { return serviceName; }
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }
        
        public CircuitBreakerService.CircuitBreakerState getCircuitBreakerState() { return circuitBreakerState; }
        public void setCircuitBreakerState(CircuitBreakerService.CircuitBreakerState circuitBreakerState) { this.circuitBreakerState = circuitBreakerState; }
        
        public String getErrorClass() { return errorClass; }
        public void setErrorClass(String errorClass) { this.errorClass = errorClass; }
        
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        
        public String getStackTrace() { return stackTrace; }
        public void setStackTrace(String stackTrace) { this.stackTrace = stackTrace; }
        
        public boolean isRetryEligible() { return retryEligible; }
        public void setRetryEligible(boolean retryEligible) { this.retryEligible = retryEligible; }
        
        public Instant getNextRetryEligibleAt() { return nextRetryEligibleAt; }
        public void setNextRetryEligibleAt(Instant nextRetryEligibleAt) { this.nextRetryEligibleAt = nextRetryEligibleAt; }
        
        public Map<String, Object> getJobContext() { return jobContext; }
        public void setJobContext(Map<String, Object> jobContext) { this.jobContext = jobContext; }
    }

    /**
     * DLQ Statistics
     */
    public static class DLQStatistics {
        private final String queueName;
        private final int totalEntries;
        private final Map<ErrorClassificationService.ErrorCategory, Long> errorCategoryCount;
        private final long retryEligibleCount;

        public DLQStatistics(String queueName, int totalEntries, 
                           Map<ErrorClassificationService.ErrorCategory, Long> errorCategoryCount,
                           long retryEligibleCount) {
            this.queueName = queueName;
            this.totalEntries = totalEntries;
            this.errorCategoryCount = errorCategoryCount;
            this.retryEligibleCount = retryEligibleCount;
        }

        // Getters
        public String getQueueName() { return queueName; }
        public int getTotalEntries() { return totalEntries; }
        public Map<ErrorClassificationService.ErrorCategory, Long> getErrorCategoryCount() { return errorCategoryCount; }
        public long getRetryEligibleCount() { return retryEligibleCount; }
    }
}
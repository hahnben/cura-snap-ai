package ai.curasnap.backend.service;

import ai.curasnap.backend.model.dto.JobData;
import ai.curasnap.backend.model.dto.JobRequest;
import ai.curasnap.backend.model.dto.JobResponse;
import ai.curasnap.backend.model.dto.JobStatusResponse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Set;

/**
 * Redis-based implementation of JobService
 * Provides async job management with persistence and queue functionality
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "spring.data.redis.enabled", havingValue = "true", matchIfMissing = true)
public class JobServiceImpl implements JobService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    // Redis key prefixes for different data types
    private static final String JOB_KEY_PREFIX = "jobs:";
    private static final String USER_JOBS_KEY_PREFIX = "user_jobs:";
    private static final String QUEUE_KEY_PREFIX = "queue:";
    private static final String JOB_EXPIRY_SET_KEY = "job_expiry_set";
    private static final String DEAD_LETTER_QUEUE_PREFIX = "dlq:";
    private static final String RETRY_SCHEDULE_KEY = "retry_schedule";
    private static final String RETRY_STATS_KEY = "retry_stats";

    // Default values
    private static final Duration DEFAULT_JOB_EXPIRY = Duration.ofDays(1);
    private static final int DEFAULT_MAX_RETRIES = 3;

    @Autowired
    public JobServiceImpl(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.findAndRegisterModules(); // Support for Java 8 time types
    }

    @Override
    public JobResponse createJob(String userId, JobRequest jobRequest) {
        try {
            // Generate unique job ID
            String jobId = UUID.randomUUID().toString();
            Instant now = Instant.now();

            // Build job data
            JobData jobData = JobData.builder()
                    .jobId(jobId)
                    .jobType(jobRequest.getJobType())
                    .status(JobData.JobStatus.QUEUED)
                    .userId(userId)
                    .inputData(jobRequest.getInputData())
                    .sessionId(jobRequest.getSessionId())
                    .createdAt(now)
                    .retryCount(0)
                    .maxRetries(jobRequest.getMaxRetries() != null ? jobRequest.getMaxRetries() : DEFAULT_MAX_RETRIES)
                    .queueName(getQueueNameForJobType(jobRequest.getJobType()))
                    .build();

            // Store job in Redis
            String jobKey = JOB_KEY_PREFIX + jobId;
            redisTemplate.opsForValue().set(jobKey, jobData, DEFAULT_JOB_EXPIRY);

            // Add job to user's job set
            String userJobsKey = USER_JOBS_KEY_PREFIX + userId;
            redisTemplate.opsForSet().add(userJobsKey, jobId);
            redisTemplate.expire(userJobsKey, DEFAULT_JOB_EXPIRY);

            // Add job to processing queue
            String queueKey = QUEUE_KEY_PREFIX + jobData.getQueueName();
            redisTemplate.opsForList().rightPush(queueKey, jobId);

            // Add to expiry tracking set (for cleanup)
            redisTemplate.opsForZSet().add(JOB_EXPIRY_SET_KEY, jobId, now.plus(DEFAULT_JOB_EXPIRY).toEpochMilli());

            log.info("Created job {} for user {} with type {}", jobId, userId, jobRequest.getJobType());

            // Build response
            return JobResponse.builder()
                    .jobId(jobId)
                    .jobType(jobRequest.getJobType())
                    .status(JobData.JobStatus.QUEUED)
                    .createdAt(now)
                    .statusUrl("/api/v1/async/jobs/" + jobId)
                    .message("Job queued successfully")
                    .build();

        } catch (Exception e) {
            log.error("Failed to create job for user {}: {}", userId, e.getMessage(), e);
            throw new JobServiceException("Failed to create job", e);
        }
    }

    @Override
    public Optional<JobStatusResponse> getJobStatus(String jobId, String userId) {
        try {
            JobData jobData = getJobData(jobId);
            if (jobData == null || !Objects.equals(jobData.getUserId(), userId)) {
                log.warn("Job {} not found or access denied for user {}", jobId, userId);
                return Optional.empty();
            }

            return Optional.of(buildJobStatusResponse(jobData));

        } catch (Exception e) {
            log.error("Failed to get job status for job {} and user {}: {}", jobId, userId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean updateJobStatus(String jobId, JobData.JobStatus status, 
                                  Map<String, Object> result, String errorMessage) {
        try {
            JobData jobData = getJobData(jobId);
            if (jobData == null) {
                log.warn("Cannot update status - job {} not found", jobId);
                return false;
            }

            Instant now = Instant.now();
            jobData.setStatus(status);

            if (result != null) {
                jobData.setResult(result);
            }

            if (errorMessage != null) {
                jobData.setErrorMessage(errorMessage);
            }

            if (status.isTerminal()) {
                jobData.setCompletedAt(now);
                // Remove from processing queue if it's there
                String queueKey = QUEUE_KEY_PREFIX + jobData.getQueueName();
                redisTemplate.opsForList().remove(queueKey, 0, jobId);
            }

            // Save updated job data
            String jobKey = JOB_KEY_PREFIX + jobId;
            redisTemplate.opsForValue().set(jobKey, jobData, DEFAULT_JOB_EXPIRY);

            log.info("Updated job {} status to {} for user {}", jobId, status, jobData.getUserId());
            return true;

        } catch (Exception e) {
            log.error("Failed to update job status for job {}: {}", jobId, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public boolean markJobAsStarted(String jobId) {
        try {
            JobData jobData = getJobData(jobId);
            if (jobData == null || jobData.getStatus() != JobData.JobStatus.QUEUED) {
                log.warn("Cannot mark as started - job {} not found or not queued", jobId);
                return false;
            }

            Instant now = Instant.now();
            jobData.setStatus(JobData.JobStatus.PROCESSING);
            jobData.setStartedAt(now);

            String jobKey = JOB_KEY_PREFIX + jobId;
            redisTemplate.opsForValue().set(jobKey, jobData, DEFAULT_JOB_EXPIRY);

            log.info("Marked job {} as started for user {}", jobId, jobData.getUserId());
            return true;

        } catch (Exception e) {
            log.error("Failed to mark job as started for job {}: {}", jobId, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public List<JobStatusResponse> getJobsByUser(String userId, Integer limit, Integer offset) {
        try {
            String userJobsKey = USER_JOBS_KEY_PREFIX + userId;
            Set<Object> jobIds = redisTemplate.opsForSet().members(userJobsKey);

            if (jobIds == null || jobIds.isEmpty()) {
                return Collections.emptyList();
            }

            List<JobStatusResponse> jobs = jobIds.stream()
                    .map(jobId -> getJobData(jobId.toString()))
                    .filter(Objects::nonNull)
                    .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt())) // Latest first
                    .map(this::buildJobStatusResponse)
                    .collect(Collectors.toList());

            // Apply pagination
            if (offset != null && offset > 0) {
                jobs = jobs.stream().skip(offset).collect(Collectors.toList());
            }
            if (limit != null && limit > 0) {
                jobs = jobs.stream().limit(limit).collect(Collectors.toList());
            }

            return jobs;

        } catch (Exception e) {
            log.error("Failed to get jobs for user {}: {}", userId, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<JobData> getJobsByStatus(JobData.JobStatus status, Integer limit) {
        try {
            // This is a simple implementation - in production, you might want to maintain status-specific sets
            Set<String> allJobKeys = redisTemplate.keys(JOB_KEY_PREFIX + "*");
            if (allJobKeys == null) {
                return Collections.emptyList();
            }

            List<JobData> jobs = allJobKeys.stream()
                    .map(key -> getJobData(key.substring(JOB_KEY_PREFIX.length())))
                    .filter(Objects::nonNull)
                    .filter(job -> job.getStatus() == status)
                    .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt())) // Oldest first for processing
                    .collect(Collectors.toList());

            if (limit != null && limit > 0) {
                jobs = jobs.stream().limit(limit).collect(Collectors.toList());
            }

            return jobs;

        } catch (Exception e) {
            log.error("Failed to get jobs by status {}: {}", status, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public boolean cancelJob(String jobId, String userId) {
        try {
            JobData jobData = getJobData(jobId);
            if (jobData == null || !Objects.equals(jobData.getUserId(), userId)) {
                log.warn("Cannot cancel - job {} not found or access denied for user {}", jobId, userId);
                return false;
            }

            if (jobData.getStatus() != JobData.JobStatus.QUEUED) {
                log.warn("Cannot cancel job {} - status is {}, only QUEUED jobs can be cancelled", 
                        jobId, jobData.getStatus());
                return false;
            }

            return updateJobStatus(jobId, JobData.JobStatus.CANCELLED, null, "Cancelled by user");

        } catch (Exception e) {
            log.error("Failed to cancel job {} for user {}: {}", jobId, userId, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public Optional<JobData> getNextJobFromQueue(String queueName) {
        try {
            String queueKey = QUEUE_KEY_PREFIX + queueName;
            Object jobId = redisTemplate.opsForList().leftPop(queueKey);
            
            if (jobId == null) {
                return Optional.empty();
            }

            JobData jobData = getJobData(jobId.toString());
            if (jobData == null) {
                log.warn("Job {} from queue {} not found in storage", jobId, queueName);
                return Optional.empty();
            }

            return Optional.of(jobData);

        } catch (Exception e) {
            log.error("Failed to get next job from queue {}: {}", queueName, e.getMessage(), e);
            return Optional.empty();
        }
    }

    @Override
    public boolean incrementRetryCount(String jobId) {
        return incrementRetryCountWithStrategy(jobId, null);
    }

    /**
     * Enhanced retry with intelligent strategy and Dead Letter Queue support
     *
     * @param jobId the job ID to retry
     * @param errorType the type of error that occurred (for adaptive retry strategy)
     * @return true if retry scheduled, false if max retries exceeded and job moved to DLQ
     */
    public boolean incrementRetryCountWithStrategy(String jobId, String errorType) {
        try {
            JobData jobData = getJobData(jobId);
            if (jobData == null) {
                log.warn("Cannot increment retry count - job {} not found", jobId);
                return false;
            }

            // Get appropriate retry strategy for this job type
            RetryStrategy.RetryConfig retryConfig = RetryStrategy.getRetryConfigForJobType(
                    jobData.getJobType().getValue(), errorType);

            // Calculate next retry attempt
            RetryStrategy.RetryResult retryResult = RetryStrategy.calculateNextRetry(
                    retryConfig, 
                    jobData.getRetryCount(), 
                    Instant.now(), 
                    errorType);

            if (!retryResult.isShouldRetry()) {
                // Max retries exceeded - move to Dead Letter Queue
                moveJobToDeadLetterQueue(jobData, retryResult.getReason());
                recordRetryStats(jobData.getJobType().getValue(), jobData.getRetryCount(), false);
                return false;
            }

            // Schedule retry
            int newRetryCount = jobData.getRetryCount() + 1;
            jobData.setRetryCount(newRetryCount);
            jobData.setStatus(JobData.JobStatus.QUEUED);
            
            // Store retry metadata
            Map<String, Object> retryMetadata = new HashMap<>();
            retryMetadata.put("retryAttempt", newRetryCount);
            retryMetadata.put("scheduledFor", retryResult.getNextRetryAt().toString());
            retryMetadata.put("errorType", errorType);
            retryMetadata.put("retryPolicy", retryConfig.getPolicy().name());
            retryMetadata.put("delayMs", retryResult.getDelayUntilNextTry().toMillis());
            jobData.getInputData().put("retryMetadata", retryMetadata);

            // Update job data
            String jobKey = JOB_KEY_PREFIX + jobId;
            redisTemplate.opsForValue().set(jobKey, jobData, DEFAULT_JOB_EXPIRY);

            // Schedule delayed retry using Redis sorted set
            scheduleDelayedRetry(jobId, retryResult.getNextRetryAt(), jobData.getQueueName());
            
            // Record retry statistics
            recordRetryStats(jobData.getJobType().getValue(), newRetryCount, true);

            log.info("Scheduled retry for job {} (attempt {}/{}): {} - next retry at {}", 
                    jobId, newRetryCount, jobData.getMaxRetries(), retryResult.getReason(), 
                    retryResult.getNextRetryAt());
            
            return true;

        } catch (Exception e) {
            log.error("Failed to increment retry count for job {}: {}", jobId, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public long cleanupExpiredJobs(Duration retentionPeriod) {
        try {
            long cutoffTime = Instant.now().minus(retentionPeriod).toEpochMilli();
            Set<Object> expiredJobIds = redisTemplate.opsForZSet().rangeByScore(JOB_EXPIRY_SET_KEY, 0, cutoffTime);
            
            if (expiredJobIds == null || expiredJobIds.isEmpty()) {
                return 0;
            }

            long cleanedUp = 0;
            for (Object jobId : expiredJobIds) {
                String jobIdStr = jobId.toString();
                JobData jobData = getJobData(jobIdStr);
                
                if (jobData != null) {
                    // Remove from all Redis structures
                    redisTemplate.delete(JOB_KEY_PREFIX + jobIdStr);
                    redisTemplate.opsForSet().remove(USER_JOBS_KEY_PREFIX + jobData.getUserId(), jobIdStr);
                    redisTemplate.opsForZSet().remove(JOB_EXPIRY_SET_KEY, jobIdStr);
                    cleanedUp++;
                }
            }

            log.info("Cleaned up {} expired jobs", cleanedUp);
            return cleanedUp;

        } catch (Exception e) {
            log.error("Failed to cleanup expired jobs: {}", e.getMessage(), e);
            return 0;
        }
    }

    @Override
    public Map<String, Object> getQueueStats(String queueName) {
        try {
            String queueKey = QUEUE_KEY_PREFIX + queueName;
            Long queueSize = redisTemplate.opsForList().size(queueKey);
            
            Map<String, Object> stats = new HashMap<>();
            stats.put("queueName", queueName);
            stats.put("size", queueSize != null ? queueSize : 0);
            stats.put("timestamp", Instant.now());

            // Get oldest job in queue (if any)
            Object oldestJobId = redisTemplate.opsForList().index(queueKey, 0);
            if (oldestJobId != null) {
                JobData oldestJob = getJobData(oldestJobId.toString());
                if (oldestJob != null) {
                    stats.put("oldestJobCreatedAt", oldestJob.getCreatedAt());
                    stats.put("waitTimeSeconds", 
                            Duration.between(oldestJob.getCreatedAt(), Instant.now()).getSeconds());
                }
            }

            return stats;

        } catch (Exception e) {
            log.error("Failed to get queue stats for {}: {}", queueName, e.getMessage(), e);
            return Collections.singletonMap("error", e.getMessage());
        }
    }

    // Helper methods

    private JobData getJobData(String jobId) {
        try {
            String jobKey = JOB_KEY_PREFIX + jobId;
            Object jobDataObj = redisTemplate.opsForValue().get(jobKey);
            
            if (jobDataObj == null) {
                return null;
            }

            if (jobDataObj instanceof JobData) {
                return (JobData) jobDataObj;
            }

            // Handle potential JSON string case
            if (jobDataObj instanceof String) {
                return objectMapper.readValue((String) jobDataObj, JobData.class);
            }

            // Handle Map case (from JSON deserialization)
            return objectMapper.convertValue(jobDataObj, JobData.class);

        } catch (Exception e) {
            log.error("Failed to get job data for job {}: {}", jobId, e.getMessage());
            return null;
        }
    }

    private JobStatusResponse buildJobStatusResponse(JobData jobData) {
        Long processingTimeMs = null;
        if (jobData.getStartedAt() != null) {
            Instant endTime = jobData.getCompletedAt() != null ? 
                    jobData.getCompletedAt() : Instant.now();
            processingTimeMs = Duration.between(jobData.getStartedAt(), endTime).toMillis();
        }

        return JobStatusResponse.builder()
                .jobId(jobData.getJobId())
                .jobType(jobData.getJobType())
                .status(jobData.getStatus())
                .createdAt(jobData.getCreatedAt())
                .startedAt(jobData.getStartedAt())
                .completedAt(jobData.getCompletedAt())
                .result(jobData.getResult())
                .errorMessage(jobData.getErrorMessage())
                .retryCount(jobData.getRetryCount())
                .maxRetries(jobData.getMaxRetries())
                .processingTimeMs(processingTimeMs)
                .build();
    }

    private String getQueueNameForJobType(JobData.JobType jobType) {
        switch (jobType) {
            case TEXT_PROCESSING:
                return "text_processing";
            case AUDIO_PROCESSING:
                return "audio_processing";
            case CACHE_WARMING:
                return "cache_warming";
            default:
                return "default";
        }
    }

    /**
     * Move failed job to Dead Letter Queue for manual inspection
     *
     * @param jobData the job that exceeded max retries
     * @param reason reason for moving to DLQ
     */
    private void moveJobToDeadLetterQueue(JobData jobData, String reason) {
        try {
            // Update job status to FAILED
            jobData.setStatus(JobData.JobStatus.FAILED);
            jobData.setCompletedAt(Instant.now());
            jobData.setErrorMessage("Moved to Dead Letter Queue: " + reason);

            // Save updated job data
            String jobKey = JOB_KEY_PREFIX + jobData.getJobId();
            redisTemplate.opsForValue().set(jobKey, jobData, Duration.ofDays(7)); // Keep DLQ jobs for 7 days

            // Add to Dead Letter Queue
            String dlqKey = DEAD_LETTER_QUEUE_PREFIX + jobData.getQueueName();
            Map<String, Object> dlqEntry = new HashMap<>();
            dlqEntry.put("jobId", jobData.getJobId());
            dlqEntry.put("userId", jobData.getUserId());
            dlqEntry.put("jobType", jobData.getJobType().getValue());
            dlqEntry.put("failedAt", Instant.now().toString());
            dlqEntry.put("retryAttempts", jobData.getRetryCount());
            dlqEntry.put("reason", reason);
            dlqEntry.put("originalError", jobData.getErrorMessage());

            redisTemplate.opsForList().rightPush(dlqKey, dlqEntry);
            
            // Keep DLQ bounded (max 1000 entries per queue)
            Long dlqSize = redisTemplate.opsForList().size(dlqKey);
            if (dlqSize != null && dlqSize > 1000) {
                redisTemplate.opsForList().trim(dlqKey, -1000, -1);
            }

            log.warn("Moved job {} to Dead Letter Queue {}: {}", 
                    jobData.getJobId(), dlqKey, reason);

        } catch (Exception e) {
            log.error("Failed to move job {} to Dead Letter Queue: {}", 
                    jobData.getJobId(), e.getMessage(), e);
        }
    }

    /**
     * Schedule a delayed retry using Redis sorted set
     *
     * @param jobId the job to retry
     * @param retryAt when to retry the job
     * @param queueName the target queue name
     */
    private void scheduleDelayedRetry(String jobId, Instant retryAt, String queueName) {
        try {
            // Use Redis sorted set to schedule delayed retry
            // Score is the timestamp when the job should be retried
            double score = retryAt.toEpochMilli();
            
            Map<String, Object> retryEntry = new HashMap<>();
            retryEntry.put("jobId", jobId);
            retryEntry.put("queueName", queueName);
            retryEntry.put("scheduledFor", retryAt.toString());

            redisTemplate.opsForZSet().add(RETRY_SCHEDULE_KEY, retryEntry, score);
            
            log.debug("Scheduled delayed retry for job {} at {}", jobId, retryAt);

        } catch (Exception e) {
            log.error("Failed to schedule delayed retry for job {}: {}", jobId, e.getMessage(), e);
            
            // Fallback: add directly to queue (immediate retry)
            String queueKey = QUEUE_KEY_PREFIX + queueName;
            redisTemplate.opsForList().rightPush(queueKey, jobId);
            log.info("Fallback: Added job {} directly to queue {} for immediate retry", jobId, queueName);
        }
    }

    /**
     * Process scheduled retries - called by background scheduler
     * Moves jobs from retry schedule to actual queues when their time comes
     */
    @Scheduled(fixedDelay = 10000) // Every 10 seconds
    public void processScheduledRetries() {
        try {
            double now = Instant.now().toEpochMilli();
            
            // Get all jobs scheduled for retry up to now
            Set<Object> readyRetries = redisTemplate.opsForZSet().rangeByScore(RETRY_SCHEDULE_KEY, 0, now);
            
            if (readyRetries == null || readyRetries.isEmpty()) {
                return;
            }

            int processedRetries = 0;
            for (Object retryEntry : readyRetries) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> entry = (Map<String, Object>) retryEntry;
                    String jobId = (String) entry.get("jobId");
                    String queueName = (String) entry.get("queueName");

                    // Move job to active queue
                    String queueKey = QUEUE_KEY_PREFIX + queueName;
                    redisTemplate.opsForList().rightPush(queueKey, jobId);
                    
                    // Remove from retry schedule
                    redisTemplate.opsForZSet().remove(RETRY_SCHEDULE_KEY, retryEntry);
                    
                    processedRetries++;
                    log.debug("Moved scheduled retry job {} to queue {}", jobId, queueName);

                } catch (Exception e) {
                    log.error("Failed to process scheduled retry: {}", e.getMessage());
                }
            }

            if (processedRetries > 0) {
                log.info("Processed {} scheduled retries", processedRetries);
            }

        } catch (Exception e) {
            log.error("Error processing scheduled retries: {}", e.getMessage(), e);
        }
    }

    /**
     * Record retry statistics for monitoring
     *
     * @param jobType the job type
     * @param retryAttempt the retry attempt number
     * @param success whether the retry was scheduled successfully
     */
    private void recordRetryStats(String jobType, int retryAttempt, boolean success) {
        try {
            String statsKey = RETRY_STATS_KEY + ":" + jobType;
            
            // Increment counters
            redisTemplate.opsForHash().increment(statsKey, "totalRetries", 1);
            if (success) {
                redisTemplate.opsForHash().increment(statsKey, "successfulRetries", 1);
            } else {
                redisTemplate.opsForHash().increment(statsKey, "failedRetries", 1);
            }
            
            // Track max retry attempt
            Object currentMax = redisTemplate.opsForHash().get(statsKey, "maxRetryAttempt");
            int currentMaxInt = currentMax instanceof Number ? ((Number) currentMax).intValue() : 0;
            if (retryAttempt > currentMaxInt) {
                redisTemplate.opsForHash().put(statsKey, "maxRetryAttempt", retryAttempt);
            }

            // Set expiry for stats (keep for 24 hours)
            redisTemplate.expire(statsKey, Duration.ofHours(24));

        } catch (Exception e) {
            log.debug("Failed to record retry stats: {}", e.getMessage());
        }
    }

    /**
     * Get Dead Letter Queue entries for monitoring
     *
     * @param queueName the queue name
     * @param limit maximum number of entries to return
     * @return list of DLQ entries
     */
    public List<Map<String, Object>> getDeadLetterQueueEntries(String queueName, Integer limit) {
        try {
            String dlqKey = DEAD_LETTER_QUEUE_PREFIX + queueName;
            int limitValue = limit != null ? limit : 50;
            
            List<Object> entries = redisTemplate.opsForList().range(dlqKey, -limitValue, -1);
            if (entries == null) {
                return Collections.emptyList();
            }

            return entries.stream()
                    .filter(entry -> entry instanceof Map)
                    .map(entry -> (Map<String, Object>) entry)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Failed to get DLQ entries for queue {}: {}", queueName, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get retry statistics for monitoring
     *
     * @param jobType the job type (optional)
     * @return retry statistics
     */
    public Map<String, Object> getRetryStats(String jobType) {
        try {
            if (jobType != null) {
                String statsKey = RETRY_STATS_KEY + ":" + jobType;
                Map<Object, Object> stats = redisTemplate.opsForHash().entries(statsKey);
                return stats.entrySet().stream()
                        .collect(Collectors.toMap(
                                e -> e.getKey().toString(),
                                Map.Entry::getValue
                        ));
            } else {
                // Get aggregate stats for all job types
                Map<String, Object> aggregateStats = new HashMap<>();
                Set<String> keys = redisTemplate.keys(RETRY_STATS_KEY + ":*");
                
                long totalRetries = 0;
                long successfulRetries = 0;
                long failedRetries = 0;
                int maxRetryAttempt = 0;

                if (keys != null) {
                    for (String key : keys) {
                        Map<Object, Object> stats = redisTemplate.opsForHash().entries(key);
                        totalRetries += getLongFromHashMap(stats, "totalRetries");
                        successfulRetries += getLongFromHashMap(stats, "successfulRetries");
                        failedRetries += getLongFromHashMap(stats, "failedRetries");
                        maxRetryAttempt = Math.max(maxRetryAttempt, getIntFromHashMap(stats, "maxRetryAttempt"));
                    }
                }

                aggregateStats.put("totalRetries", totalRetries);
                aggregateStats.put("successfulRetries", successfulRetries);
                aggregateStats.put("failedRetries", failedRetries);
                aggregateStats.put("maxRetryAttempt", maxRetryAttempt);
                aggregateStats.put("successRate", totalRetries > 0 ? (double) successfulRetries / totalRetries : 0.0);

                return aggregateStats;
            }

        } catch (Exception e) {
            log.error("Failed to get retry stats for {}: {}", jobType, e.getMessage());
            return Collections.emptyMap();
        }
    }

    private long getLongFromHashMap(Map<Object, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return 0L;
    }

    private int getIntFromHashMap(Map<Object, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }
}
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
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

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
        try {
            JobData jobData = getJobData(jobId);
            if (jobData == null) {
                return false;
            }

            int newRetryCount = jobData.getRetryCount() + 1;
            if (newRetryCount >= jobData.getMaxRetries()) {
                // Max retries exceeded, mark as failed
                return updateJobStatus(jobId, JobData.JobStatus.FAILED, null, 
                        "Max retries (" + jobData.getMaxRetries() + ") exceeded");
            }

            jobData.setRetryCount(newRetryCount);
            jobData.setStatus(JobData.JobStatus.QUEUED); // Reset to queued for retry

            String jobKey = JOB_KEY_PREFIX + jobId;
            redisTemplate.opsForValue().set(jobKey, jobData, DEFAULT_JOB_EXPIRY);

            // Add back to queue for retry
            String queueKey = QUEUE_KEY_PREFIX + jobData.getQueueName();
            redisTemplate.opsForList().rightPush(queueKey, jobId);

            log.info("Incremented retry count for job {} to {}/{}", jobId, newRetryCount, jobData.getMaxRetries());
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
}
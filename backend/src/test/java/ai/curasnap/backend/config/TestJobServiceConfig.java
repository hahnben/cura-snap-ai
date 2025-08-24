package ai.curasnap.backend.config;

import ai.curasnap.backend.model.dto.JobData;
import ai.curasnap.backend.model.dto.JobRequest;
import ai.curasnap.backend.model.dto.JobResponse;
import ai.curasnap.backend.model.dto.JobStatusResponse;
import ai.curasnap.backend.service.JobService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Test configuration that provides a mock JobService implementation
 * when Redis is disabled in test environments.
 */
@TestConfiguration
public class TestJobServiceConfig {

    @Bean
    @Primary
    public JobService testJobService() {
        return new TestJobService();
    }

    /**
     * Simple in-memory implementation of JobService for testing
     */
    public static class TestJobService implements JobService {
        private final ConcurrentHashMap<String, JobData> jobs = new ConcurrentHashMap<>();

        @Override
        public JobResponse createJob(String userId, JobRequest jobRequest) {
            String jobId = UUID.randomUUID().toString();
            JobData jobData = new JobData();
            jobData.setJobId(jobId);
            jobData.setUserId(userId);
            jobData.setJobType(jobRequest.getJobType());
            jobData.setStatus(JobData.JobStatus.QUEUED);
            jobData.setInputData(jobRequest.getInputData());
            jobData.setCreatedAt(Instant.now());
            jobs.put(jobId, jobData);
            
            JobResponse response = new JobResponse();
            response.setJobId(jobId);
            response.setStatus(JobData.JobStatus.QUEUED);
            response.setMessage("Job created successfully");
            return response;
        }

        @Override
        public Optional<JobStatusResponse> getJobStatus(String jobId, String userId) {
            JobData job = jobs.get(jobId);
            if (job == null || !job.getUserId().equals(userId)) {
                return Optional.empty();
            }
            
            JobStatusResponse response = new JobStatusResponse();
            response.setJobId(jobId);
            response.setStatus(job.getStatus());
            response.setProgressPercentage(0); // Default progress
            response.setResult(job.getResult());
            response.setErrorMessage(job.getErrorMessage());
            response.setCreatedAt(job.getCreatedAt());
            response.setStartedAt(job.getStartedAt());
            response.setCompletedAt(job.getCompletedAt());
            return Optional.of(response);
        }

        @Override
        public boolean updateJobStatus(String jobId, JobData.JobStatus status, Map<String, Object> result, String errorMessage) {
            JobData job = jobs.get(jobId);
            if (job != null) {
                job.setStatus(status);
                job.setResult(result);
                job.setErrorMessage(errorMessage);
                if (status == JobData.JobStatus.COMPLETED || status == JobData.JobStatus.FAILED) {
                    job.setCompletedAt(Instant.now());
                }
                return true;
            }
            return false;
        }

        @Override
        public boolean markJobAsStarted(String jobId) {
            JobData job = jobs.get(jobId);
            if (job != null && job.getStatus() == JobData.JobStatus.QUEUED) {
                job.setStatus(JobData.JobStatus.PROCESSING);
                job.setStartedAt(Instant.now());
                return true;
            }
            return false;
        }

        @Override
        public List<JobStatusResponse> getJobsByUser(String userId, Integer limit, Integer offset) {
            return jobs.values().stream()
                    .filter(job -> job.getUserId().equals(userId))
                    .map(job -> {
                        JobStatusResponse response = new JobStatusResponse();
                        response.setJobId(job.getJobId());
                        response.setStatus(job.getStatus());
                        response.setResult(job.getResult());
                        response.setCreatedAt(job.getCreatedAt());
                        return response;
                    })
                    .limit(limit != null ? limit : Long.MAX_VALUE)
                    .collect(Collectors.toList());
        }

        @Override
        public List<JobData> getJobsByStatus(JobData.JobStatus status, Integer limit) {
            return jobs.values().stream()
                    .filter(job -> job.getStatus() == status)
                    .limit(limit != null ? limit : Long.MAX_VALUE)
                    .collect(Collectors.toList());
        }

        @Override
        public boolean cancelJob(String jobId, String userId) {
            JobData job = jobs.get(jobId);
            if (job != null && job.getUserId().equals(userId) && job.getStatus() == JobData.JobStatus.QUEUED) {
                job.setStatus(JobData.JobStatus.CANCELLED);
                job.setCompletedAt(Instant.now());
                return true;
            }
            return false;
        }

        @Override
        public Optional<JobData> getNextJobFromQueue(String queueName) {
            return jobs.values().stream()
                    .filter(job -> job.getStatus() == JobData.JobStatus.QUEUED)
                    .findFirst();
        }

        @Override
        public boolean incrementRetryCount(String jobId) {
            JobData job = jobs.get(jobId);
            if (job != null) {
                job.setRetryCount(job.getRetryCount() + 1);
                return job.getRetryCount() <= 3; // Max 3 retries
            }
            return false;
        }

        @Override
        public long cleanupExpiredJobs(Duration retentionPeriod) {
            Instant cutoff = Instant.now().minus(retentionPeriod);
            int sizeBefore = jobs.size();
            jobs.values().removeIf(job -> 
                job.getCreatedAt().isBefore(cutoff));
            return sizeBefore - jobs.size();
        }

        @Override
        public Map<String, Object> getQueueStats(String queueName) {
            Map<String, Object> stats = new HashMap<>();
            long queuedJobs = jobs.values().stream()
                    .filter(job -> job.getStatus() == JobData.JobStatus.QUEUED)
                    .count();
            stats.put("queueSize", queuedJobs);
            stats.put("totalJobs", jobs.size());
            return stats;
        }
    }
}
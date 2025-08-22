package ai.curasnap.backend.service;

import ai.curasnap.backend.model.dto.JobData;
import ai.curasnap.backend.model.dto.JobRequest;
import ai.curasnap.backend.model.dto.JobResponse;
import ai.curasnap.backend.model.dto.JobStatusResponse;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service interface for async job management with Redis backend
 * Provides job lifecycle management and status tracking
 */
public interface JobService {

    /**
     * Creates a new async job and queues it for processing
     *
     * @param userId the authenticated user's ID
     * @param jobRequest the job creation request
     * @return job response with job ID and initial status
     * @throws JobServiceException if job creation fails
     */
    JobResponse createJob(String userId, JobRequest jobRequest);

    /**
     * Retrieves the current status of a job
     * Performs authorization check - user can only access their own jobs
     *
     * @param jobId the unique job identifier
     * @param userId the authenticated user's ID (for authorization)
     * @return job status response if authorized, empty if not found or unauthorized
     */
    Optional<JobStatusResponse> getJobStatus(String jobId, String userId);

    /**
     * Updates job status and processing information
     * Used by background workers to report progress and results
     *
     * @param jobId the unique job identifier
     * @param status the new job status
     * @param result the processing result (null for non-terminal statuses)
     * @param errorMessage error message for failed jobs (null for success)
     * @return true if update succeeded, false if job not found
     * @throws JobServiceException if update fails
     */
    boolean updateJobStatus(String jobId, JobData.JobStatus status, 
                           Map<String, Object> result, String errorMessage);

    /**
     * Marks job as started and updates processing timestamps
     *
     * @param jobId the unique job identifier
     * @return true if update succeeded, false if job not found or already started
     */
    boolean markJobAsStarted(String jobId);

    /**
     * Retrieves all jobs for a specific user
     *
     * @param userId the authenticated user's ID
     * @param limit maximum number of jobs to return (null for no limit)
     * @param offset offset for pagination (null for no offset)
     * @return list of job status responses for the user
     */
    List<JobStatusResponse> getJobsByUser(String userId, Integer limit, Integer offset);

    /**
     * Retrieves jobs by status for monitoring purposes
     *
     * @param status the job status to filter by
     * @param limit maximum number of jobs to return
     * @return list of jobs with the specified status
     */
    List<JobData> getJobsByStatus(JobData.JobStatus status, Integer limit);

    /**
     * Cancels a pending job (only allowed for QUEUED jobs)
     *
     * @param jobId the unique job identifier
     * @param userId the authenticated user's ID (for authorization)
     * @return true if cancellation succeeded, false if job not found, unauthorized, or not cancellable
     */
    boolean cancelJob(String jobId, String userId);

    /**
     * Retrieves the next job from the processing queue
     * Used by background workers to get work
     *
     * @param queueName the queue to pull from
     * @return job data if available, empty if queue is empty
     */
    Optional<JobData> getNextJobFromQueue(String queueName);

    /**
     * Increments retry count for a failed job
     *
     * @param jobId the unique job identifier
     * @return true if increment succeeded, false if max retries exceeded or job not found
     */
    boolean incrementRetryCount(String jobId);

    /**
     * Cleans up expired jobs (older than retention period)
     *
     * @param retentionPeriod jobs older than this will be deleted
     * @return number of jobs cleaned up
     */
    long cleanupExpiredJobs(java.time.Duration retentionPeriod);

    /**
     * Gets queue statistics for monitoring
     *
     * @param queueName the queue name
     * @return map with queue statistics (size, oldest job timestamp, etc.)
     */
    Map<String, Object> getQueueStats(String queueName);

    /**
     * Exception thrown by JobService operations
     */
    class JobServiceException extends RuntimeException {
        public JobServiceException(String message) {
            super(message);
        }

        public JobServiceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
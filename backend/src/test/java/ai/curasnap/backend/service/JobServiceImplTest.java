package ai.curasnap.backend.service;

import ai.curasnap.backend.model.dto.JobData;
import ai.curasnap.backend.model.dto.JobRequest;
import ai.curasnap.backend.model.dto.JobResponse;
import ai.curasnap.backend.model.dto.JobStatusResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for JobServiceImpl
 * Tests job lifecycle management, authorization, and error handling
 */
@ExtendWith(MockitoExtension.class)
class JobServiceImplTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private SetOperations<String, Object> setOperations;

    @Mock
    private ListOperations<String, Object> listOperations;

    @Mock
    private ZSetOperations<String, Object> zSetOperations;

    private JobServiceImpl jobService;

    @BeforeEach
    void setUp() {
        // Mock Redis template operations
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

        jobService = new JobServiceImpl(redisTemplate);
    }

    @Test
    void createJob_ValidRequest_ShouldCreateJobSuccessfully() {
        // Given
        String userId = "test-user";
        JobRequest jobRequest = JobRequest.builder()
                .jobType(JobData.JobType.TEXT_PROCESSING)
                .inputData(Map.of("textRaw", "Test medical note"))
                .sessionId("session-123")
                .build();

        // Mock Redis operations
        doNothing().when(valueOperations).set(anyString(), any(JobData.class), any());
        when(setOperations.add(anyString(), anyString())).thenReturn(1L);
        when(redisTemplate.expire(anyString(), any())).thenReturn(true);
        when(listOperations.rightPush(anyString(), anyString())).thenReturn(1L);
        when(zSetOperations.add(anyString(), anyString(), anyDouble())).thenReturn(true);

        // When
        JobResponse response = jobService.createJob(userId, jobRequest);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getJobId()).isNotNull();
        assertThat(response.getJobType()).isEqualTo(JobData.JobType.TEXT_PROCESSING);
        assertThat(response.getStatus()).isEqualTo(JobData.JobStatus.QUEUED);
        assertThat(response.getStatusUrl()).contains(response.getJobId());
        assertThat(response.getMessage()).isEqualTo("Job queued successfully");

        // Verify Redis interactions
        verify(valueOperations).set(startsWith("jobs:"), any(JobData.class), any());
        verify(setOperations).add(eq("user_jobs:" + userId), anyString());
        verify(listOperations).rightPush(eq("queue:text_processing"), anyString());
    }

    @Test
    void createJob_NullInput_ShouldThrowException() {
        // Given
        String userId = "test-user";
        JobRequest jobRequest = JobRequest.builder()
                .jobType(JobData.JobType.TEXT_PROCESSING)
                .inputData(null) // Invalid null input
                .build();

        // When/Then
        assertThatThrownBy(() -> jobService.createJob(userId, jobRequest))
                .isInstanceOf(JobService.JobServiceException.class);
    }

    @Test
    void getJobStatus_ValidJobAndUser_ShouldReturnStatus() {
        // Given
        String jobId = "test-job-id";
        String userId = "test-user";
        JobData jobData = JobData.builder()
                .jobId(jobId)
                .userId(userId)
                .jobType(JobData.JobType.TEXT_PROCESSING)
                .status(JobData.JobStatus.COMPLETED)
                .createdAt(Instant.now())
                .result(Map.of("noteId", "note-123"))
                .build();

        when(valueOperations.get("jobs:" + jobId)).thenReturn(jobData);

        // When
        Optional<JobStatusResponse> response = jobService.getJobStatus(jobId, userId);

        // Then
        assertThat(response).isPresent();
        assertThat(response.get().getJobId()).isEqualTo(jobId);
        assertThat(response.get().getStatus()).isEqualTo(JobData.JobStatus.COMPLETED);
        assertThat(response.get().getResult()).containsEntry("noteId", "note-123");
    }

    @Test
    void getJobStatus_WrongUser_ShouldReturnEmpty() {
        // Given
        String jobId = "test-job-id";
        String correctUserId = "correct-user";
        String wrongUserId = "wrong-user";
        
        JobData jobData = JobData.builder()
                .jobId(jobId)
                .userId(correctUserId)
                .jobType(JobData.JobType.TEXT_PROCESSING)
                .status(JobData.JobStatus.COMPLETED)
                .createdAt(Instant.now())
                .build();

        when(valueOperations.get("jobs:" + jobId)).thenReturn(jobData);

        // When
        Optional<JobStatusResponse> response = jobService.getJobStatus(jobId, wrongUserId);

        // Then
        assertThat(response).isEmpty();
    }

    @Test
    void getJobStatus_NonExistentJob_ShouldReturnEmpty() {
        // Given
        String jobId = "non-existent-job";
        String userId = "test-user";
        
        when(valueOperations.get("jobs:" + jobId)).thenReturn(null);

        // When
        Optional<JobStatusResponse> response = jobService.getJobStatus(jobId, userId);

        // Then
        assertThat(response).isEmpty();
    }

    @Test
    void updateJobStatus_ValidJob_ShouldUpdateSuccessfully() {
        // Given
        String jobId = "test-job-id";
        JobData jobData = JobData.builder()
                .jobId(jobId)
                .userId("test-user")
                .jobType(JobData.JobType.TEXT_PROCESSING)
                .status(JobData.JobStatus.PROCESSING)
                .queueName("text_processing")
                .createdAt(Instant.now())
                .build();

        Map<String, Object> result = Map.of("noteId", "note-123");
        
        when(valueOperations.get("jobs:" + jobId)).thenReturn(jobData);
        doNothing().when(valueOperations).set(anyString(), any(JobData.class), any());
        when(listOperations.remove(anyString(), anyInt(), anyString())).thenReturn(1L);

        // When
        boolean updated = jobService.updateJobStatus(jobId, JobData.JobStatus.COMPLETED, result, null);

        // Then
        assertThat(updated).isTrue();
        verify(valueOperations).set(eq("jobs:" + jobId), any(JobData.class), any());
        verify(listOperations).remove(eq("queue:text_processing"), eq(0), eq(jobId));
    }

    @Test
    void updateJobStatus_NonExistentJob_ShouldReturnFalse() {
        // Given
        String jobId = "non-existent-job";
        when(valueOperations.get("jobs:" + jobId)).thenReturn(null);

        // When
        boolean updated = jobService.updateJobStatus(jobId, JobData.JobStatus.COMPLETED, null, null);

        // Then
        assertThat(updated).isFalse();
        verify(valueOperations, never()).set(anyString(), any(), any());
    }

    @Test
    void markJobAsStarted_QueuedJob_ShouldUpdateToProcessing() {
        // Given
        String jobId = "test-job-id";
        JobData jobData = JobData.builder()
                .jobId(jobId)
                .userId("test-user")
                .jobType(JobData.JobType.TEXT_PROCESSING)
                .status(JobData.JobStatus.QUEUED)
                .createdAt(Instant.now())
                .build();

        when(valueOperations.get("jobs:" + jobId)).thenReturn(jobData);
        doNothing().when(valueOperations).set(anyString(), any(JobData.class), any());

        // When
        boolean started = jobService.markJobAsStarted(jobId);

        // Then
        assertThat(started).isTrue();
        verify(valueOperations).set(eq("jobs:" + jobId), any(JobData.class), any());
    }

    @Test
    void markJobAsStarted_AlreadyProcessingJob_ShouldReturnFalse() {
        // Given
        String jobId = "test-job-id";
        JobData jobData = JobData.builder()
                .jobId(jobId)
                .userId("test-user")
                .jobType(JobData.JobType.TEXT_PROCESSING)
                .status(JobData.JobStatus.PROCESSING) // Already processing
                .createdAt(Instant.now())
                .build();

        when(valueOperations.get("jobs:" + jobId)).thenReturn(jobData);

        // When
        boolean started = jobService.markJobAsStarted(jobId);

        // Then
        assertThat(started).isFalse();
        verify(valueOperations, never()).set(anyString(), any(), any());
    }

    @Test
    void getJobsByUser_ValidUser_ShouldReturnUserJobs() {
        // Given
        String userId = "test-user";
        String jobId1 = "job-1";
        String jobId2 = "job-2";

        Set<Object> userJobIds = Set.of(jobId1, jobId2);
        when(setOperations.members("user_jobs:" + userId)).thenReturn(userJobIds);

        JobData jobData1 = JobData.builder()
                .jobId(jobId1)
                .userId(userId)
                .jobType(JobData.JobType.TEXT_PROCESSING)
                .status(JobData.JobStatus.COMPLETED)
                .createdAt(Instant.now().minusSeconds(60))
                .build();

        JobData jobData2 = JobData.builder()
                .jobId(jobId2)
                .userId(userId)
                .jobType(JobData.JobType.AUDIO_PROCESSING)
                .status(JobData.JobStatus.PROCESSING)
                .createdAt(Instant.now())
                .build();

        when(valueOperations.get("jobs:" + jobId1)).thenReturn(jobData1);
        when(valueOperations.get("jobs:" + jobId2)).thenReturn(jobData2);

        // When
        List<JobStatusResponse> jobs = jobService.getJobsByUser(userId, null, null);

        // Then
        assertThat(jobs).hasSize(2);
        // Should be sorted by creation time (latest first)
        assertThat(jobs.get(0).getJobId()).isEqualTo(jobId2);
        assertThat(jobs.get(1).getJobId()).isEqualTo(jobId1);
    }

    @Test
    void cancelJob_QueuedJobByOwner_ShouldCancelSuccessfully() {
        // Given
        String jobId = "test-job-id";
        String userId = "test-user";
        JobData jobData = JobData.builder()
                .jobId(jobId)
                .userId(userId)
                .jobType(JobData.JobType.TEXT_PROCESSING)
                .status(JobData.JobStatus.QUEUED)
                .queueName("text_processing")
                .createdAt(Instant.now())
                .build();

        when(valueOperations.get("jobs:" + jobId)).thenReturn(jobData);
        doNothing().when(valueOperations).set(anyString(), any(JobData.class), any());
        when(listOperations.remove(anyString(), anyInt(), anyString())).thenReturn(1L);

        // When
        boolean cancelled = jobService.cancelJob(jobId, userId);

        // Then
        assertThat(cancelled).isTrue();
    }

    @Test
    void cancelJob_ProcessingJob_ShouldReturnFalse() {
        // Given
        String jobId = "test-job-id";
        String userId = "test-user";
        JobData jobData = JobData.builder()
                .jobId(jobId)
                .userId(userId)
                .jobType(JobData.JobType.TEXT_PROCESSING)
                .status(JobData.JobStatus.PROCESSING) // Cannot cancel processing job
                .createdAt(Instant.now())
                .build();

        when(valueOperations.get("jobs:" + jobId)).thenReturn(jobData);

        // When
        boolean cancelled = jobService.cancelJob(jobId, userId);

        // Then
        assertThat(cancelled).isFalse();
    }

    @Test
    void getNextJobFromQueue_QueueWithJob_ShouldReturnJob() {
        // Given
        String queueName = "text_processing";
        String jobId = "test-job-id";
        JobData jobData = JobData.builder()
                .jobId(jobId)
                .userId("test-user")
                .jobType(JobData.JobType.TEXT_PROCESSING)
                .status(JobData.JobStatus.QUEUED)
                .createdAt(Instant.now())
                .build();

        when(listOperations.leftPop("queue:" + queueName)).thenReturn(jobId);
        when(valueOperations.get("jobs:" + jobId)).thenReturn(jobData);

        // When
        Optional<JobData> nextJob = jobService.getNextJobFromQueue(queueName);

        // Then
        assertThat(nextJob).isPresent();
        assertThat(nextJob.get().getJobId()).isEqualTo(jobId);
    }

    @Test
    void getNextJobFromQueue_EmptyQueue_ShouldReturnEmpty() {
        // Given
        String queueName = "text_processing";
        when(listOperations.leftPop("queue:" + queueName)).thenReturn(null);

        // When
        Optional<JobData> nextJob = jobService.getNextJobFromQueue(queueName);

        // Then
        assertThat(nextJob).isEmpty();
    }

    @Test
    void incrementRetryCount_BelowMaxRetries_ShouldIncrementAndRequeue() {
        // Given
        String jobId = "test-job-id";
        JobData jobData = JobData.builder()
                .jobId(jobId)
                .userId("test-user")
                .jobType(JobData.JobType.TEXT_PROCESSING)
                .status(JobData.JobStatus.FAILED)
                .queueName("text_processing")
                .retryCount(1)
                .maxRetries(3)
                .createdAt(Instant.now())
                .build();

        when(valueOperations.get("jobs:" + jobId)).thenReturn(jobData);
        doNothing().when(valueOperations).set(anyString(), any(JobData.class), any());
        when(listOperations.rightPush(anyString(), anyString())).thenReturn(1L);

        // When
        boolean incremented = jobService.incrementRetryCount(jobId);

        // Then
        assertThat(incremented).isTrue();
        verify(valueOperations).set(eq("jobs:" + jobId), any(JobData.class), any());
        verify(listOperations).rightPush(eq("queue:text_processing"), eq(jobId));
    }

    @Test
    void incrementRetryCount_MaxRetriesExceeded_ShouldMarkAsFailed() {
        // Given
        String jobId = "test-job-id";
        JobData jobData = JobData.builder()
                .jobId(jobId)
                .userId("test-user")
                .jobType(JobData.JobType.TEXT_PROCESSING)
                .status(JobData.JobStatus.FAILED)
                .queueName("text_processing")
                .retryCount(2)
                .maxRetries(3)
                .createdAt(Instant.now())
                .build();

        when(valueOperations.get("jobs:" + jobId)).thenReturn(jobData);
        doNothing().when(valueOperations).set(anyString(), any(JobData.class), any());
        when(listOperations.remove(anyString(), anyInt(), anyString())).thenReturn(1L);

        // When
        boolean incremented = jobService.incrementRetryCount(jobId);

        // Then
        assertThat(incremented).isTrue();
        // Should not requeue when max retries exceeded
        verify(listOperations, never()).rightPush(anyString(), anyString());
    }
}
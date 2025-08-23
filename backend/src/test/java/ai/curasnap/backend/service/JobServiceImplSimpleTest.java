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
 * Simplified unit tests for JobServiceImpl
 * Focuses on core functionality without complex mocking scenarios
 */
@ExtendWith(MockitoExtension.class)
class JobServiceImplSimpleTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    
    @Mock
    private AdaptiveRetryService adaptiveRetryService;
    
    @Mock
    private DeadLetterQueueService deadLetterQueueService;

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
        // Setup basic template operations
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOperations);
        lenient().when(redisTemplate.opsForList()).thenReturn(listOperations);
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

        jobService = new JobServiceImpl(redisTemplate, adaptiveRetryService, deadLetterQueueService);
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

        // Setup basic mocks for job creation
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
    void createJob_NullJobRequest_ShouldThrowException() {
        // Given
        String userId = "test-user";
        JobRequest jobRequest = null;

        // When/Then
        assertThatThrownBy(() -> jobService.createJob(userId, jobRequest))
                .isInstanceOf(JobService.JobServiceException.class);
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
    }
}
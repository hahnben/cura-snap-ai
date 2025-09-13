package ai.curasnap.backend.service;

import ai.curasnap.backend.model.dto.JobData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ManagedTranscriptWorkerTest {

    @Mock
    private JobService jobService;

    @Mock
    private TranscriptionService transcriptionService;

    @Mock
    private WorkerHealthService workerHealthService;

    @Mock
    private MultipartFile mockMultipartFile;

    private ManagedTranscriptWorker worker;
    private final String workerId = "test-transcript-worker-1";

    @BeforeEach
    void setUp() {
        worker = new ManagedTranscriptWorker(
                workerId,
                jobService,
                transcriptionService,
                workerHealthService
        );
    }

    @Test
    void constructor_ShouldInitializeCorrectly() {
        // Then
        assertEquals(workerId, worker.getWorkerId());
        assertTrue(worker.isRunning());
        assertFalse(worker.isFailed());
        assertEquals(0, worker.getTotalJobsProcessed());
        assertEquals(0, worker.getTotalJobsFailed());

        // Verify worker registration
        verify(workerHealthService).registerWorker(workerId, "transcription_only");
    }

    @Test
    void processJobs_NoJobsAvailable_ShouldUpdateHeartbeatAndReturn() {
        // Given
        when(jobService.getNextJobFromQueue("transcription_only"))
                .thenReturn(Optional.empty());

        // When
        worker.processJobs();

        // Then
        verify(workerHealthService).updateWorkerHeartbeat(workerId);
        verify(jobService).getNextJobFromQueue("transcription_only");
        verifyNoMoreInteractions(transcriptionService);
    }

    @Test
    void processJobs_WithValidJob_ShouldProcessSuccessfully() throws Exception {
        // Given
        String jobId = "test-job-123";
        String userId = "test-user-456";
        String transcript = "Test transcript content";

        JobData jobData = createTestJobData(jobId, userId);

        when(jobService.getNextJobFromQueue("transcription_only"))
                .thenReturn(Optional.of(jobData));
        when(jobService.markJobAsStarted(jobId))
                .thenReturn(true);
        when(transcriptionService.transcribe(any(MultipartFile.class)))
                .thenReturn(transcript);
        when(jobService.updateJobStatus(eq(jobId), eq(JobData.JobStatus.COMPLETED), any(), eq(null)))
                .thenReturn(true);

        // When
        worker.processJobs();

        // Then
        verify(workerHealthService).updateWorkerHeartbeat(workerId);
        verify(jobService).getNextJobFromQueue("transcription_only");
        verify(jobService).markJobAsStarted(jobId);
        verify(transcriptionService).transcribe(any(MultipartFile.class));
        verify(jobService).updateJobStatus(eq(jobId), eq(JobData.JobStatus.COMPLETED), any(), eq(null));
        verify(workerHealthService).recordJobProcessing(eq(workerId), eq(true), anyLong());

        assertEquals(1, worker.getTotalJobsProcessed());
        assertEquals(0, worker.getTotalJobsFailed());
        assertNotNull(worker.getLastSuccessfulJob());
    }

    @Test
    void processJobs_TranscriptionFails_ShouldHandleError() throws Exception {
        // Given
        String jobId = "test-job-123";
        String userId = "test-user-456";

        JobData jobData = createTestJobData(jobId, userId);
        TranscriptionService.TranscriptionException transcriptionException =
                new TranscriptionService.TranscriptionException("Transcription failed");

        when(jobService.getNextJobFromQueue("transcription_only"))
                .thenReturn(Optional.of(jobData));
        when(jobService.markJobAsStarted(jobId))
                .thenReturn(true);
        when(transcriptionService.transcribe(any(MultipartFile.class)))
                .thenThrow(transcriptionException);

        // When
        worker.processJobs();

        // Then
        verify(transcriptionService).transcribe(any(MultipartFile.class));
        verify(workerHealthService).recordJobProcessing(eq(workerId), eq(false), anyLong());

        assertEquals(0, worker.getTotalJobsProcessed());
        assertEquals(1, worker.getTotalJobsFailed());
        assertEquals(1, worker.getConsecutiveFailures());
    }

    @Test
    void processJobs_EmptyTranscript_ShouldHandleAsFailure() throws Exception {
        // Given
        String jobId = "test-job-123";
        String userId = "test-user-456";

        JobData jobData = createTestJobData(jobId, userId);

        when(jobService.getNextJobFromQueue("transcription_only"))
                .thenReturn(Optional.of(jobData));
        when(jobService.markJobAsStarted(jobId))
                .thenReturn(true);
        when(transcriptionService.transcribe(any(MultipartFile.class)))
                .thenReturn(""); // Empty transcript

        // When
        worker.processJobs();

        // Then
        verify(transcriptionService).transcribe(any(MultipartFile.class));

        assertEquals(0, worker.getTotalJobsProcessed());
        assertEquals(1, worker.getTotalJobsFailed());
        assertEquals(1, worker.getConsecutiveFailures());
    }

    @Test
    void processJobs_MarkJobAsStartedFails_ShouldSkipProcessing() {
        // Given
        String jobId = "test-job-123";
        String userId = "test-user-456";

        JobData jobData = createTestJobData(jobId, userId);

        when(jobService.getNextJobFromQueue("transcription_only"))
                .thenReturn(Optional.of(jobData));
        when(jobService.markJobAsStarted(jobId))
                .thenReturn(false);

        // When
        worker.processJobs();

        // Then
        verify(jobService).markJobAsStarted(jobId);
        verifyNoInteractions(transcriptionService);

        assertEquals(0, worker.getTotalJobsProcessed());
        assertEquals(0, worker.getTotalJobsFailed());
    }

    @Test
    void shutdown_ShouldDeactivateWorker() {
        // When
        worker.shutdown();

        // Then
        assertFalse(worker.isRunning());
        verify(workerHealthService).deactivateWorker(workerId);
    }

    @Test
    void processJobs_WhenNotRunning_ShouldReturn() {
        // Given
        worker.shutdown();

        // When
        worker.processJobs();

        // Then
        verifyNoInteractions(jobService, transcriptionService, workerHealthService);
    }

    @Test
    void processJobs_WhenFailed_ShouldReturn() {
        // Given - Force worker to fail by setting consecutive failures
        // This is a bit of a hack since consecutiveFailures is private
        // In a real test, we might expose it via a package-private method
        // For now, we'll test the behavior through exception handling

        String jobId = "test-job-123";
        String userId = "test-user-456";
        JobData jobData = createTestJobData(jobId, userId);

        when(jobService.getNextJobFromQueue("transcription_only"))
                .thenReturn(Optional.of(jobData));

        // Create a runtime exception to trigger worker failure
        RuntimeException exception = new RuntimeException("Critical error");
        when(jobService.markJobAsStarted(jobId))
                .thenThrow(exception);

        // When - Process jobs multiple times to trigger failure threshold
        for (int i = 0; i < 6; i++) { // MAX_CONSECUTIVE_FAILURES is 5
            worker.processJobs();
        }

        // Then
        assertTrue(worker.isFailed());
        assertFalse(worker.isRunning());
    }

    private JobData createTestJobData(String jobId, String userId) {
        Map<String, Object> inputData = new HashMap<>();
        inputData.put("originalFileName", "test.mp3");
        inputData.put("contentType", "audio/mpeg");

        // Create a simple Base64 encoded audio data
        byte[] dummyAudioData = "dummy audio content".getBytes();
        String audioBase64 = Base64.getEncoder().encodeToString(dummyAudioData);
        inputData.put("audioData", audioBase64);

        return JobData.builder()
                .jobId(jobId)
                .jobType(JobData.JobType.TRANSCRIPTION_ONLY)
                .status(JobData.JobStatus.QUEUED)
                .userId(userId)
                .inputData(inputData)
                .createdAt(Instant.now())
                .build();
    }
}
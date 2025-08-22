package ai.curasnap.backend.service;

import ai.curasnap.backend.model.dto.JobData;
import ai.curasnap.backend.model.dto.NoteResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AudioJobWorker
 * Tests background job processing logic
 */
@ExtendWith(MockitoExtension.class)
class AudioJobWorkerTest {

    @Mock
    private JobService jobService;

    @Mock
    private TranscriptionService transcriptionService;

    @Mock
    private NoteService noteService;

    private AudioJobWorker audioJobWorker;

    @BeforeEach
    void setUp() {
        audioJobWorker = new AudioJobWorker(jobService, transcriptionService, noteService);
    }

    @Test
    void processAudioJobs_NoJobsInQueue_ShouldDoNothing() {
        // Given
        when(jobService.getNextJobFromQueue("audio_processing"))
                .thenReturn(Optional.empty());

        // When
        audioJobWorker.processAudioJobs();

        // Then
        verify(jobService).getNextJobFromQueue("audio_processing");
        verify(jobService, never()).markJobAsStarted(anyString());
    }

    @Test
    void processAudioJobs_ValidJob_ShouldProcessSuccessfully() throws Exception {
        // Given
        JobData jobData = createSampleJobData();
        when(jobService.getNextJobFromQueue("audio_processing"))
                .thenReturn(Optional.of(jobData));
        when(jobService.markJobAsStarted(jobData.getJobId())).thenReturn(true);
        when(transcriptionService.transcribe(any(MultipartFile.class)))
                .thenReturn("Transcribed text from audio");
        when(noteService.formatNote(eq(jobData.getUserId()), any()))
                .thenReturn(createSampleNoteResponse());
        when(jobService.updateJobStatus(eq(jobData.getJobId()), 
                eq(JobData.JobStatus.COMPLETED), any(), isNull())).thenReturn(true);

        // When
        audioJobWorker.processAudioJobs();

        // Then
        verify(jobService).markJobAsStarted(jobData.getJobId());
        verify(transcriptionService).transcribe(any(MultipartFile.class));
        verify(noteService).formatNote(eq(jobData.getUserId()), any());
        verify(jobService).updateJobStatus(eq(jobData.getJobId()), 
                eq(JobData.JobStatus.COMPLETED), any(), isNull());
    }

    @Test
    void processAudioJobs_TranscriptionFails_ShouldHandleRetry() throws Exception {
        // Given
        JobData jobData = createSampleJobData();
        when(jobService.getNextJobFromQueue("audio_processing"))
                .thenReturn(Optional.of(jobData));
        when(jobService.markJobAsStarted(jobData.getJobId())).thenReturn(true);
        when(transcriptionService.transcribe(any(MultipartFile.class)))
                .thenThrow(new TranscriptionService.TranscriptionException("Service unavailable"));
        when(jobService.incrementRetryCount(jobData.getJobId())).thenReturn(true);

        // When
        audioJobWorker.processAudioJobs();

        // Then
        verify(jobService).incrementRetryCount(jobData.getJobId());
        verify(jobService, never()).updateJobStatus(anyString(), 
                eq(JobData.JobStatus.COMPLETED), any(), any());
    }

    @Test
    void processAudioJobs_MaxRetriesExceeded_ShouldMarkAsFailed() throws Exception {
        // Given
        JobData jobData = createSampleJobData();
        when(jobService.getNextJobFromQueue("audio_processing"))
                .thenReturn(Optional.of(jobData));
        when(jobService.markJobAsStarted(jobData.getJobId())).thenReturn(true);
        when(transcriptionService.transcribe(any(MultipartFile.class)))
                .thenThrow(new TranscriptionService.TranscriptionException("Persistent error"));
        when(jobService.incrementRetryCount(jobData.getJobId())).thenReturn(false); // Max retries exceeded
        when(jobService.updateJobStatus(eq(jobData.getJobId()), 
                eq(JobData.JobStatus.FAILED), isNull(), anyString())).thenReturn(true);

        // When
        audioJobWorker.processAudioJobs();

        // Then
        verify(jobService).incrementRetryCount(jobData.getJobId());
        verify(jobService).updateJobStatus(eq(jobData.getJobId()), 
                eq(JobData.JobStatus.FAILED), isNull(), contains("Transcription failed"));
    }

    @Test
    void processAudioJobs_EmptyTranscript_ShouldFailJob() throws Exception {
        // Given
        JobData jobData = createSampleJobData();
        when(jobService.getNextJobFromQueue("audio_processing"))
                .thenReturn(Optional.of(jobData));
        when(jobService.markJobAsStarted(jobData.getJobId())).thenReturn(true);
        when(transcriptionService.transcribe(any(MultipartFile.class)))
                .thenReturn(""); // Empty transcript
        when(jobService.incrementRetryCount(jobData.getJobId())).thenReturn(false);
        when(jobService.updateJobStatus(eq(jobData.getJobId()), 
                eq(JobData.JobStatus.FAILED), isNull(), anyString())).thenReturn(true);

        // When
        audioJobWorker.processAudioJobs();

        // Then
        verify(jobService).updateJobStatus(eq(jobData.getJobId()), 
                eq(JobData.JobStatus.FAILED), isNull(), 
                eq("Transcription returned empty result"));
    }

    @Test
    void processAudioJobs_JobAlreadyStarted_ShouldSkip() throws Exception {
        // Given
        JobData jobData = createSampleJobData();
        when(jobService.getNextJobFromQueue("audio_processing"))
                .thenReturn(Optional.of(jobData));
        when(jobService.markJobAsStarted(jobData.getJobId())).thenReturn(false); // Already started

        // When
        audioJobWorker.processAudioJobs();

        // Then
        verify(jobService).markJobAsStarted(jobData.getJobId());
        verify(transcriptionService, never()).transcribe(any());
        verify(noteService, never()).formatNote(anyString(), any());
    }

    @Test
    void processJobsManually_MultipleJobs_ShouldProcessAll() throws Exception {
        // Given
        JobData job1 = createSampleJobData();
        job1.setJobId("job-1");
        JobData job2 = createSampleJobData();
        job2.setJobId("job-2");

        when(jobService.getNextJobFromQueue("audio_processing"))
                .thenReturn(Optional.of(job1))
                .thenReturn(Optional.of(job2))
                .thenReturn(Optional.empty());
        
        // Mock successful processing for both jobs
        when(jobService.markJobAsStarted(anyString())).thenReturn(true);
        when(transcriptionService.transcribe(any(MultipartFile.class)))
                .thenReturn("Transcribed text");
        when(noteService.formatNote(anyString(), any()))
                .thenReturn(createSampleNoteResponse());
        when(jobService.updateJobStatus(anyString(), 
                eq(JobData.JobStatus.COMPLETED), any(), isNull())).thenReturn(true);

        // When
        int processed = audioJobWorker.processJobsManually();

        // Then
        assertThat(processed).isEqualTo(2);
        verify(jobService, times(3)).getNextJobFromQueue("audio_processing");
        verify(jobService, times(2)).markJobAsStarted(anyString());
    }

    @Test
    void getWorkerStats_ShouldReturnCurrentStats() {
        // When
        Map<String, Object> stats = audioJobWorker.getWorkerStats();

        // Then
        assertThat(stats).containsKey("totalJobsProcessed");
        assertThat(stats).containsKey("totalJobsFailed");
        assertThat(stats).containsKey("lastProcessingTime");
        assertThat(stats).containsKey("workerEnabled");
        assertThat(stats).containsKey("queueName");
        assertThat(stats.get("workerEnabled")).isEqualTo(true);
        assertThat(stats.get("queueName")).isEqualTo("audio_processing");
    }

    // Helper methods

    private JobData createSampleJobData() {
        Map<String, Object> inputData = new HashMap<>();
        inputData.put("originalFileName", "test.mp3");
        inputData.put("contentType", "audio/mpeg");
        inputData.put("fileSize", 1024L);
        inputData.put("audioData", Base64.getEncoder().encodeToString("fake-audio".getBytes()));

        return JobData.builder()
                .jobId("test-job-id")
                .userId("test-user")
                .jobType(JobData.JobType.AUDIO_PROCESSING)
                .status(JobData.JobStatus.QUEUED)
                .inputData(inputData)
                .createdAt(Instant.now())
                .retryCount(0)
                .maxRetries(3)
                .queueName("audio_processing")
                .build();
    }

    private NoteResponse createSampleNoteResponse() {
        NoteResponse response = new NoteResponse();
        response.setId(UUID.randomUUID());
        response.setTextRaw("Transcribed text");
        response.setTextStructured("S: Patient presents with...\nO: Examination shows...\nA: Assessment...\nP: Plan...");
        response.setCreatedAt(Instant.now());
        return response;
    }
}
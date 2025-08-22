package ai.curasnap.backend.controller;

import ai.curasnap.backend.model.dto.*;
import ai.curasnap.backend.service.JobService;
import ai.curasnap.backend.service.TranscriptionService;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Test class for AsyncNoteController
 * Tests all async endpoints and their behavior
 */
@WebMvcTest(AsyncNoteController.class)
class AsyncNoteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JobService jobService;

    @MockBean
    private TranscriptionService transcriptionService;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMultipartFile validAudioFile;
    private JobResponse sampleJobResponse;
    private JobStatusResponse sampleJobStatusResponse;

    @BeforeEach
    void setUp() {
        // Create sample audio file
        validAudioFile = new MockMultipartFile(
            "audio",
            "test.mp3",
            "audio/mpeg",
            "fake-audio-content".getBytes()
        );

        // Create sample job response
        sampleJobResponse = JobResponse.builder()
                .jobId("test-job-id")
                .jobType(JobData.JobType.AUDIO_PROCESSING)
                .status(JobData.JobStatus.QUEUED)
                .createdAt(Instant.now())
                .statusUrl("/api/v1/async/jobs/test-job-id")
                .message("Job queued successfully")
                .build();

        // Create sample job status response
        sampleJobStatusResponse = JobStatusResponse.builder()
                .jobId("test-job-id")
                .jobType(JobData.JobType.AUDIO_PROCESSING)
                .status(JobData.JobStatus.COMPLETED)
                .createdAt(Instant.now().minusSeconds(30))
                .completedAt(Instant.now())
                .result(Map.of("noteId", "note-123"))
                .processingTimeMs(25000L)
                .build();
    }

    @Test
    @WithMockUser
    void formatAudioAsync_ValidFile_ShouldReturnJobResponse() throws Exception {
        // Given
        when(transcriptionService.isTranscriptionServiceAvailable()).thenReturn(true);
        when(jobService.createJob(eq("user"), any(JobRequest.class))).thenReturn(sampleJobResponse);

        // When & Then
        mockMvc.perform(multipart("/api/v1/async/notes/format-audio")
                .file(validAudioFile)
                .with(jwt().jwt(jwt -> jwt.subject("user")))
                .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("test-job-id"))
                .andExpect(jsonPath("$.jobType").value("AUDIO_PROCESSING"))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.statusUrl").value("/api/v1/async/jobs/test-job-id"))
                .andExpect(jsonPath("$.message").value("Job queued successfully"));

        verify(jobService).createJob(eq("user"), any(JobRequest.class));
    }

    @Test
    @WithMockUser
    void formatAudioAsync_EmptyFile_ShouldReturnBadRequest() throws Exception {
        // Given
        MockMultipartFile emptyFile = new MockMultipartFile(
            "audio", "empty.mp3", "audio/mpeg", new byte[0]
        );

        // When & Then
        mockMvc.perform(multipart("/api/v1/async/notes/format-audio")
                .file(emptyFile)
                .with(jwt().jwt(jwt -> jwt.subject("user")))
                .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isBadRequest());

        verify(jobService, never()).createJob(anyString(), any(JobRequest.class));
    }

    @Test
    @WithMockUser
    void formatAudioAsync_WithSessionId_ShouldIncludeSessionId() throws Exception {
        // Given
        when(transcriptionService.isTranscriptionServiceAvailable()).thenReturn(true);
        when(jobService.createJob(eq("user"), any(JobRequest.class))).thenReturn(sampleJobResponse);

        // When & Then
        mockMvc.perform(multipart("/api/v1/async/notes/format-audio")
                .file(validAudioFile)
                .param("sessionId", "session-123")
                .with(jwt().jwt(jwt -> jwt.subject("user")))
                .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isOk());

        verify(jobService).createJob(eq("user"), argThat(jobRequest -> 
            "session-123".equals(jobRequest.getSessionId())
        ));
    }

    @Test
    @WithMockUser
    void formatAudioAsync_JobServiceException_ShouldReturnServerError() throws Exception {
        // Given
        when(transcriptionService.isTranscriptionServiceAvailable()).thenReturn(true);
        when(jobService.createJob(anyString(), any(JobRequest.class)))
                .thenThrow(new JobService.JobServiceException("Redis error"));

        // When & Then
        mockMvc.perform(multipart("/api/v1/async/notes/format-audio")
                .file(validAudioFile)
                .with(jwt().jwt(jwt -> jwt.subject("user")))
                .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isInternalServerError());
    }

    @Test
    @WithMockUser
    void getJobStatus_ExistingJob_ShouldReturnJobStatus() throws Exception {
        // Given
        when(jobService.getJobStatus("test-job-id", "user"))
                .thenReturn(Optional.of(sampleJobStatusResponse));

        // When & Then
        mockMvc.perform(get("/api/v1/async/jobs/test-job-id")
                .with(jwt().jwt(jwt -> jwt.subject("user"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("test-job-id"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.result.noteId").value("note-123"))
                .andExpect(jsonPath("$.processingTimeMs").value(25000));
    }

    @Test
    @WithMockUser
    void getJobStatus_NonExistentJob_ShouldReturnNotFound() throws Exception {
        // Given
        when(jobService.getJobStatus("non-existent", "user"))
                .thenReturn(Optional.empty());

        // When & Then
        mockMvc.perform(get("/api/v1/async/jobs/non-existent")
                .with(jwt().jwt(jwt -> jwt.subject("user"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void getUserJobs_ValidRequest_ShouldReturnJobList() throws Exception {
        // Given
        List<JobStatusResponse> userJobs = Arrays.asList(sampleJobStatusResponse);
        when(jobService.getJobsByUser("user", 20, 0)).thenReturn(userJobs);

        // When & Then
        mockMvc.perform(get("/api/v1/async/jobs")
                .with(jwt().jwt(jwt -> jwt.subject("user"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].jobId").value("test-job-id"));
    }

    @Test
    @WithMockUser
    void getUserJobs_WithPagination_ShouldPassParameters() throws Exception {
        // Given
        when(jobService.getJobsByUser("user", 5, 10)).thenReturn(Collections.emptyList());

        // When & Then
        mockMvc.perform(get("/api/v1/async/jobs")
                .param("limit", "5")
                .param("offset", "10")
                .with(jwt().jwt(jwt -> jwt.subject("user"))))
                .andExpect(status().isOk());

        verify(jobService).getJobsByUser("user", 5, 10);
    }

    @Test
    @WithMockUser
    void getUserJobs_InvalidLimit_ShouldReturnBadRequest() throws Exception {
        // When & Then
        mockMvc.perform(get("/api/v1/async/jobs")
                .param("limit", "150") // Over limit of 100
                .with(jwt().jwt(jwt -> jwt.subject("user"))))
                .andExpect(status().isBadRequest());

        verify(jobService, never()).getJobsByUser(anyString(), anyInt(), anyInt());
    }

    @Test
    @WithMockUser
    void getUserJobs_NegativeOffset_ShouldReturnBadRequest() throws Exception {
        // When & Then
        mockMvc.perform(get("/api/v1/async/jobs")
                .param("offset", "-5")
                .with(jwt().jwt(jwt -> jwt.subject("user"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void cancelJob_ExistingJob_ShouldReturnNoContent() throws Exception {
        // Given
        when(jobService.cancelJob("test-job-id", "user")).thenReturn(true);

        // When & Then
        mockMvc.perform(delete("/api/v1/async/jobs/test-job-id")
                .with(jwt().jwt(jwt -> jwt.subject("user"))))
                .andExpect(status().isNoContent());

        verify(jobService).cancelJob("test-job-id", "user");
    }

    @Test
    @WithMockUser
    void cancelJob_NonExistentJob_ShouldReturnNotFound() throws Exception {
        // Given
        when(jobService.cancelJob("non-existent", "user")).thenReturn(false);

        // When & Then
        mockMvc.perform(delete("/api/v1/async/jobs/non-existent")
                .with(jwt().jwt(jwt -> jwt.subject("user"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void getQueueStats_ValidQueue_ShouldReturnStats() throws Exception {
        // Given
        Map<String, Object> stats = Map.of(
            "queueName", "audio_processing",
            "size", 5,
            "oldestJobCreatedAt", Instant.now().toString()
        );
        when(jobService.getQueueStats("audio_processing")).thenReturn(stats);

        // When & Then
        mockMvc.perform(get("/api/v1/async/admin/queues/audio_processing/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queueName").value("audio_processing"))
                .andExpect(jsonPath("$.size").value(5));
    }

    @Test
    void health_ShouldReturnHealthStatus() throws Exception {
        // Given
        when(transcriptionService.isTranscriptionServiceAvailable()).thenReturn(true);

        // When & Then
        mockMvc.perform(get("/api/v1/async/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("async-note-processing"))
                .andExpect(jsonPath("$.transcriptionService").value("UP"));
    }

    @Test
    void health_TranscriptionServiceDown_ShouldShowDownStatus() throws Exception {
        // Given
        when(transcriptionService.isTranscriptionServiceAvailable()).thenReturn(false);

        // When & Then
        mockMvc.perform(get("/api/v1/async/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.transcriptionService").value("DOWN"));
    }

    // Security tests

    @Test
    void formatAudioAsync_NoAuthentication_ShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(multipart("/api/v1/async/notes/format-audio")
                .file(validAudioFile))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getJobStatus_NoAuthentication_ShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/async/jobs/test-job-id"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getUserJobs_NoAuthentication_ShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/async/jobs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cancelJob_NoAuthentication_ShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(delete("/api/v1/async/jobs/test-job-id"))
                .andExpect(status().isUnauthorized());
    }

    // Edge case tests

    @Test
    @WithMockUser
    void formatAudioAsync_LargeFile_ShouldReturnBadRequest() throws Exception {
        // Given
        MockMultipartFile largeFile = new MockMultipartFile(
            "audio", "large.mp3", "audio/mpeg", new byte[26 * 1024 * 1024] // 26MB
        );
        when(transcriptionService.isTranscriptionServiceAvailable()).thenReturn(false);

        // When & Then
        mockMvc.perform(multipart("/api/v1/async/notes/format-audio")
                .file(largeFile)
                .with(jwt().jwt(jwt -> jwt.subject("user"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser  
    void formatAudioAsync_UnsupportedFileType_ShouldReturnBadRequest() throws Exception {
        // Given
        MockMultipartFile unsupportedFile = new MockMultipartFile(
            "audio", "test.txt", "text/plain", "not-audio".getBytes()
        );
        when(transcriptionService.isTranscriptionServiceAvailable()).thenReturn(false);

        // When & Then
        mockMvc.perform(multipart("/api/v1/async/notes/format-audio")
                .file(unsupportedFile)
                .with(jwt().jwt(jwt -> jwt.subject("user"))))
                .andExpect(status().isBadRequest());
    }
}
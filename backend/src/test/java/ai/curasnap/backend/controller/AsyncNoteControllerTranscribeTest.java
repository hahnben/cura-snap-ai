package ai.curasnap.backend.controller;

import ai.curasnap.backend.model.dto.JobData;
import ai.curasnap.backend.model.dto.JobResponse;
import ai.curasnap.backend.service.JobService;
import ai.curasnap.backend.service.RequestLatencyMetricsService;
import ai.curasnap.backend.service.ServiceDegradationService;
import ai.curasnap.backend.service.TranscriptionService;
import ai.curasnap.backend.service.WorkerHealthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AsyncNoteController.class)
class AsyncNoteControllerTranscribeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JobService jobService;

    @MockBean
    private TranscriptionService transcriptionService;

    @MockBean
    private WorkerHealthService workerHealthService;

    @MockBean
    private ServiceDegradationService serviceDegradationService;

    @MockBean
    private RequestLatencyMetricsService metricsService;

    @MockBean
    private JwtDecoder jwtDecoder;

    private MockMultipartFile mockAudioFile;
    private final String testUserId = "test-user-123";

    @BeforeEach
    void setUp() {
        // Create mock audio file
        mockAudioFile = new MockMultipartFile(
                "audio",
                "test.mp3",
                "audio/mpeg",
                "dummy audio content".getBytes()
        );

        // Default behavior - no system degradation
        when(serviceDegradationService.isSystemDegraded()).thenReturn(false);
    }

    @Test
    void transcribeAsync_ValidRequest_ShouldReturnJobResponse() throws Exception {
        // Given
        String jobId = UUID.randomUUID().toString();
        JobResponse expectedResponse = JobResponse.builder()
                .jobId(jobId)
                .status(JobData.JobStatus.QUEUED)
                .build();

        when(jobService.createJob(eq(testUserId), any()))
                .thenReturn(expectedResponse);

        // Mock metrics service
        when(metricsService.timeAsyncJob(eq("transcription_only"), any()))
                .thenAnswer(invocation -> {
                    return invocation.getArgument(1, java.util.function.Supplier.class).get();
                });

        // When & Then
        MvcResult result = mockMvc.perform(multipart("/api/v1/async/transcribe")
                .file(mockAudioFile)
                .param("sessionId", "session-123")
                .with(jwt().jwt(createMockJwt(testUserId)))
                .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.jobId").value(jobId))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andReturn();

        // Verify service interactions
        verify(jobService).createJob(eq(testUserId), argThat(jobRequest ->
                jobRequest.getJobType() == JobData.JobType.TRANSCRIPTION_ONLY &&
                jobRequest.getSessionId().equals("session-123")
        ));

        verify(metricsService).timeAsyncJob(eq("transcription_only"), any());
        verify(metricsService).incrementCounter(eq("async_jobs_created_total"),
                eq("job_type"), eq("transcription_only"),
                eq("user_id"), eq(testUserId),
                eq("status"), eq("success"));
    }

    @Test
    void transcribeAsync_WithoutSessionId_ShouldReturnJobResponse() throws Exception {
        // Given
        String jobId = UUID.randomUUID().toString();
        JobResponse expectedResponse = JobResponse.builder()
                .jobId(jobId)
                .status(JobData.JobStatus.QUEUED)
                .build();

        when(jobService.createJob(eq(testUserId), any()))
                .thenReturn(expectedResponse);

        when(metricsService.timeAsyncJob(eq("transcription_only"), any()))
                .thenAnswer(invocation -> {
                    return invocation.getArgument(1, java.util.function.Supplier.class).get();
                });

        // When & Then
        mockMvc.perform(multipart("/api/v1/async/transcribe")
                .file(mockAudioFile)
                .with(jwt().jwt(createMockJwt(testUserId)))
                .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.jobId").value(jobId))
                .andExpect(jsonPath("$.status").value("QUEUED"));

        // Verify service interactions
        verify(jobService).createJob(eq(testUserId), argThat(jobRequest ->
                jobRequest.getJobType() == JobData.JobType.TRANSCRIPTION_ONLY &&
                jobRequest.getSessionId() == null
        ));
    }

    @Test
    void transcribeAsync_EmptyAudioFile_ShouldReturnBadRequest() throws Exception {
        // Given
        MockMultipartFile emptyAudioFile = new MockMultipartFile(
                "audio",
                "empty.mp3",
                "audio/mpeg",
                new byte[0]
        );

        // When & Then
        mockMvc.perform(multipart("/api/v1/async/transcribe")
                .file(emptyAudioFile)
                .with(jwt().jwt(createMockJwt(testUserId)))
                .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isBadRequest());

        // Verify no job creation
        verifyNoInteractions(jobService);
    }

    @Test
    void transcribeAsync_NoAudioFile_ShouldReturnBadRequest() throws Exception {
        // When & Then
        mockMvc.perform(multipart("/api/v1/async/transcribe")
                .param("sessionId", "session-123")
                .with(jwt().jwt(createMockJwt(testUserId)))
                .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isBadRequest());

        // Verify no job creation
        verifyNoInteractions(jobService);
    }

    @Test
    void transcribeAsync_TranscriptionValidationFailure_ShouldReturnBadRequest() throws Exception {
        // Given
        TranscriptionService.TranscriptionException validationException =
                new TranscriptionService.TranscriptionException("Unsupported file format");

        doThrow(validationException)
                .when(transcriptionService)
                .transcribe(any());

        // When & Then
        mockMvc.perform(multipart("/api/v1/async/transcribe")
                .file(mockAudioFile)
                .with(jwt().jwt(createMockJwt(testUserId)))
                .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isBadRequest());

        // Verify metrics for validation failure
        verify(metricsService).incrementCounter(eq("async_jobs_created_total"),
                eq("job_type"), eq("transcription_only"),
                eq("user_id"), eq(testUserId),
                eq("status"), eq("validation_error"),
                eq("error_type"), eq("TranscriptionException"));

        // Verify no job creation
        verifyNoInteractions(jobService);
    }

    @Test
    void transcribeAsync_JobServiceException_ShouldReturnInternalServerError() throws Exception {
        // Given
        JobService.JobServiceException jobException =
                new JobService.JobServiceException("Job creation failed");

        when(jobService.createJob(eq(testUserId), any()))
                .thenThrow(jobException);

        when(metricsService.timeAsyncJob(eq("transcription_only"), any()))
                .thenAnswer(invocation -> {
                    return invocation.getArgument(1, java.util.function.Supplier.class).get();
                });

        // When & Then
        mockMvc.perform(multipart("/api/v1/async/transcribe")
                .file(mockAudioFile)
                .with(jwt().jwt(createMockJwt(testUserId)))
                .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isInternalServerError());

        // Verify metrics for job creation failure
        verify(metricsService).incrementCounter(eq("async_jobs_created_total"),
                eq("job_type"), eq("transcription_only"),
                eq("user_id"), eq(testUserId),
                eq("status"), eq("job_creation_error"),
                eq("error_type"), eq("JobServiceException"));
    }

    @Test
    void transcribeAsync_SystemDegraded_ShouldHandleGracefully() throws Exception {
        // Given
        when(serviceDegradationService.isSystemDegraded()).thenReturn(true);
        when(serviceDegradationService.getCurrentDegradationLevel())
                .thenReturn(ServiceDegradationService.DegradationLevel.MINOR_DEGRADATION);

        String jobId = UUID.randomUUID().toString();
        JobResponse expectedResponse = JobResponse.builder()
                .jobId(jobId)
                .status(JobData.JobStatus.QUEUED)
                .build();

        when(jobService.createJob(eq(testUserId), any()))
                .thenReturn(expectedResponse);

        // When & Then
        mockMvc.perform(multipart("/api/v1/async/transcribe")
                .file(mockAudioFile)
                .with(jwt().jwt(createMockJwt(testUserId)))
                .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isOk())
                .andExpect(header().string("X-System-Status", "degraded"))
                .andExpect(header().string("X-Degradation-Level", "MINOR_DEGRADATION"));
    }

    @Test
    void transcribeAsync_WithoutAuthentication_ShouldReturnUnauthorized() throws Exception {
        // When & Then
        mockMvc.perform(multipart("/api/v1/async/transcribe")
                .file(mockAudioFile)
                .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isUnauthorized());

        // Verify no service interactions
        verifyNoInteractions(jobService, transcriptionService);
    }

    @Test
    void transcribeAsync_UnexpectedException_ShouldReturnInternalServerError() throws Exception {
        // Given
        RuntimeException unexpectedException = new RuntimeException("Unexpected error");

        when(jobService.createJob(eq(testUserId), any()))
                .thenThrow(unexpectedException);

        when(metricsService.timeAsyncJob(eq("transcription_only"), any()))
                .thenAnswer(invocation -> {
                    return invocation.getArgument(1, java.util.function.Supplier.class).get();
                });

        // When & Then
        mockMvc.perform(multipart("/api/v1/async/transcribe")
                .file(mockAudioFile)
                .with(jwt().jwt(createMockJwt(testUserId)))
                .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isInternalServerError());

        // Verify metrics for unexpected error
        verify(metricsService).incrementCounter(eq("async_jobs_created_total"),
                eq("job_type"), eq("transcription_only"),
                eq("user_id"), eq(testUserId),
                eq("status"), eq("unexpected_error"),
                eq("error_type"), eq("RuntimeException"));
    }

    private Jwt createMockJwt(String subject) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }
}
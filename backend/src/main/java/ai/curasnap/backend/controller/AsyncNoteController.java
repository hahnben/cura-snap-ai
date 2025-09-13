package ai.curasnap.backend.controller;

import ai.curasnap.backend.model.dto.*;
import ai.curasnap.backend.service.JobService;
import ai.curasnap.backend.service.RequestLatencyMetricsService;
import ai.curasnap.backend.service.ServiceDegradationService;
import ai.curasnap.backend.service.TranscriptionService;
import ai.curasnap.backend.service.WorkerHealthService;
import ai.curasnap.backend.util.SecurityUtils;

import io.micrometer.core.annotation.Timed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;
import java.util.*;
import java.util.Base64;

/**
 * REST controller for async job management and processing
 * Provides non-blocking endpoints that return job IDs immediately
 * Supports both audio processing and text processing workflows
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/async")
public class AsyncNoteController {

    private final JobService jobService;
    private final TranscriptionService transcriptionService;
    private final WorkerHealthService workerHealthService;
    private final ServiceDegradationService serviceDegradationService;
    private final RequestLatencyMetricsService metricsService;

    @Autowired
    public AsyncNoteController(JobService jobService, 
                              TranscriptionService transcriptionService,
                              WorkerHealthService workerHealthService,
                              ServiceDegradationService serviceDegradationService,
                              RequestLatencyMetricsService metricsService) {
        this.jobService = jobService;
        this.transcriptionService = transcriptionService;
        this.workerHealthService = workerHealthService;
        this.serviceDegradationService = serviceDegradationService;
        this.metricsService = metricsService;
    }

    /**
     * Async audio upload endpoint - returns job ID immediately
     * Audio processing happens in background
     *
     * @param jwt the decoded JWT token containing user identity
     * @param audioFile the uploaded audio file
     * @param sessionId optional session ID for tracking
     * @return ResponseEntity containing job information
     */
    @Timed(value = "http_request_duration_seconds", description = "Async audio upload request duration", extraTags = {"endpoint", "/api/v1/async/notes/format-audio", "operation", "async_audio_upload"})
    @PostMapping("/notes/format-audio")
    public ResponseEntity<JobResponse> formatAudioAsync(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("audio") MultipartFile audioFile,
            @RequestParam(value = "sessionId", required = false) String sessionId
    ) {
        String userId = (jwt != null) ? jwt.getSubject() : "test-user";
        log.info("Received async audio upload request from user: {}", userId);

        try {
            // Check for system degradation
            if (serviceDegradationService.isSystemDegraded()) {
                return handleDegradedAudioUpload(userId, audioFile, sessionId);
            }

            // Basic input validation
            if (audioFile == null || audioFile.isEmpty()) {
                log.warn("Invalid request received: empty or missing audio file");
                return ResponseEntity.badRequest().build();
            }

            // Validate audio file using existing transcription service validation
            validateAudioFileForAsync(audioFile);

            // Prepare job input data with audio metadata
            Map<String, Object> inputData = new HashMap<>();
            inputData.put("originalFileName", 
                SecurityUtils.sanitizeFilenameForLogging(audioFile.getOriginalFilename()));
            inputData.put("contentType", audioFile.getContentType());
            inputData.put("fileSize", audioFile.getSize());
            
            // Store audio data as Base64 for Redis storage (Phase 1.3.2)
            try {
                byte[] audioBytes = audioFile.getBytes();
                String audioBase64 = Base64.getEncoder().encodeToString(audioBytes);
                inputData.put("audioData", audioBase64);
                
                log.debug("Audio file encoded for job processing: {} bytes", audioBytes.length);
            } catch (Exception e) {
                log.error("Failed to read audio file for user {}: {}", userId, e.getMessage());
                return ResponseEntity.internalServerError().build();
            }

            // Create job request
            JobRequest jobRequest = JobRequest.builder()
                    .jobType(JobData.JobType.AUDIO_PROCESSING)
                    .inputData(inputData)
                    .sessionId(sessionId)
                    .build();

            // Create async job with metrics timing
            JobResponse jobResponse = metricsService.timeAsyncJob("audio_processing", () -> jobService.createJob(userId, jobRequest));
            
            // Track async job creation metrics
            metricsService.incrementCounter("async_jobs_created_total", "job_type", "audio_processing", "user_id", userId, "status", "success");
            
            log.info("Created async audio processing job {} for user {}", 
                    jobResponse.getJobId(), userId);

            return ResponseEntity.ok(jobResponse);

        } catch (TranscriptionService.TranscriptionException e) {
            log.warn("Audio validation failed for user {}: {}", userId, e.getMessage());
            // Track validation failures
            metricsService.incrementCounter("async_jobs_created_total", "job_type", "audio_processing", "user_id", userId, "status", "validation_error", "error_type", "TranscriptionException");
            return ResponseEntity.badRequest().build();
        } catch (JobService.JobServiceException e) {
            log.error("Failed to create async job for user {}: {}", userId, e.getMessage());
            // Track job creation failures
            metricsService.incrementCounter("async_jobs_created_total", "job_type", "audio_processing", "user_id", userId, "status", "job_creation_error", "error_type", "JobServiceException");
            return ResponseEntity.internalServerError().build();
        } catch (Exception e) {
            log.error("Unexpected error processing async audio upload for user {}: {}", 
                    userId, e.getMessage(), e);
            // Track unexpected errors
            metricsService.incrementCounter("async_jobs_created_total", "job_type", "audio_processing", "user_id", userId, "status", "unexpected_error", "error_type", e.getClass().getSimpleName());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Async transcription-only endpoint - returns job ID immediately
     * Audio transcription happens in background without SOAP generation
     *
     * @param jwt the decoded JWT token containing user identity
     * @param audioFile the uploaded audio file
     * @param sessionId optional session ID for tracking
     * @return ResponseEntity containing job information
     */
    @Timed(value = "http_request_duration_seconds", description = "Async transcription-only request duration", extraTags = {"endpoint", "/api/v1/async/transcribe", "operation", "async_transcription_only"})
    @PostMapping("/transcribe")
    public ResponseEntity<JobResponse> transcribeAsync(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("audio") MultipartFile audioFile,
            @RequestParam(value = "sessionId", required = false) String sessionId
    ) {
        String userId = (jwt != null) ? jwt.getSubject() : "test-user";
        log.info("Received async transcription-only request from user: {}", userId);

        try {
            // Check for system degradation
            if (serviceDegradationService.isSystemDegraded()) {
                return handleDegradedTranscriptionUpload(userId, audioFile, sessionId);
            }

            // Basic input validation
            if (audioFile == null || audioFile.isEmpty()) {
                log.warn("Invalid request received: empty or missing audio file");
                return ResponseEntity.badRequest().build();
            }

            // Validate audio file using existing transcription service validation
            validateAudioFileForAsync(audioFile);

            // Prepare job input data with audio metadata
            Map<String, Object> inputData = new HashMap<>();
            inputData.put("originalFileName",
                SecurityUtils.sanitizeFilenameForLogging(audioFile.getOriginalFilename()));
            inputData.put("contentType", audioFile.getContentType());
            inputData.put("fileSize", audioFile.getSize());

            // Store audio data as Base64 for Redis storage
            try {
                byte[] audioBytes = audioFile.getBytes();
                String audioBase64 = Base64.getEncoder().encodeToString(audioBytes);
                inputData.put("audioData", audioBase64);

                log.debug("Audio file encoded for transcription job processing: {} bytes", audioBytes.length);
            } catch (Exception e) {
                log.error("Failed to read audio file for user {}: {}", userId, e.getMessage());
                return ResponseEntity.internalServerError().build();
            }

            // Create job request for transcription-only processing
            JobRequest jobRequest = JobRequest.builder()
                    .jobType(JobData.JobType.TRANSCRIPTION_ONLY)
                    .inputData(inputData)
                    .sessionId(sessionId)
                    .build();

            // Create async job with metrics timing
            JobResponse jobResponse = metricsService.timeAsyncJob("transcription_only", () -> jobService.createJob(userId, jobRequest));

            // Track async job creation metrics
            metricsService.incrementCounter("async_jobs_created_total", "job_type", "transcription_only", "user_id", userId, "status", "success");

            log.info("Created async transcription-only job {} for user {}",
                    jobResponse.getJobId(), userId);

            return ResponseEntity.ok(jobResponse);

        } catch (TranscriptionService.TranscriptionException e) {
            log.warn("Audio validation failed for user {}: {}", userId, e.getMessage());
            // Track validation failures
            metricsService.incrementCounter("async_jobs_created_total", "job_type", "transcription_only", "user_id", userId, "status", "validation_error", "error_type", "TranscriptionException");
            return ResponseEntity.badRequest().build();
        } catch (JobService.JobServiceException e) {
            log.error("Failed to create async transcription job for user {}: {}", userId, e.getMessage());
            // Track job creation failures
            metricsService.incrementCounter("async_jobs_created_total", "job_type", "transcription_only", "user_id", userId, "status", "job_creation_error", "error_type", "JobServiceException");
            return ResponseEntity.internalServerError().build();
        } catch (Exception e) {
            log.error("Unexpected error processing async transcription upload for user {}: {}",
                    userId, e.getMessage(), e);
            // Track unexpected errors
            metricsService.incrementCounter("async_jobs_created_total", "job_type", "transcription_only", "user_id", userId, "status", "unexpected_error", "error_type", e.getClass().getSimpleName());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Async text processing endpoint - returns job ID immediately
     * Text processing happens in background
     *
     * @param jwt the decoded JWT token containing user identity
     * @param request the text processing request
     * @return ResponseEntity containing job information
     */
    @Timed(value = "http_request_duration_seconds", description = "Async text processing request duration", extraTags = {"endpoint", "/api/v1/async/notes/format", "operation", "async_text_processing"})
    @PostMapping("/notes/format")
    public ResponseEntity<JobResponse> formatTextAsync(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody NoteRequest request
    ) {
        String userId = (jwt != null) ? jwt.getSubject() : "test-user";
        log.info("Received async text processing request from user: {}", userId);

        try {
            // Check for system degradation
            if (serviceDegradationService.isSystemDegraded()) {
                return handleDegradedTextProcessing(userId, request);
            }

            // Basic input validation
            if (request == null || request.getTextRaw() == null || request.getTextRaw().trim().isEmpty()) {
                log.warn("Invalid request received: empty or missing text input from user {}", userId);
                return ResponseEntity.badRequest().build();
            }

            // Prepare job input data with text content
            Map<String, Object> inputData = new HashMap<>();
            inputData.put("textRaw", request.getTextRaw().trim());
            
            // Add optional fields if present
            if (request.getSessionId() != null && !request.getSessionId().trim().isEmpty()) {
                inputData.put("sessionId", request.getSessionId().trim());
            }
            if (request.getTranscriptId() != null && !request.getTranscriptId().trim().isEmpty()) {
                inputData.put("transcriptId", request.getTranscriptId().trim());
            }
            
            // Add metadata for processing
            inputData.put("textLength", request.getTextRaw().trim().length());
            inputData.put("submissionTime", java.time.Instant.now().toString());
            
            log.debug("Text processing job prepared for user {}: {} characters", userId, request.getTextRaw().length());

            // Create job request
            JobRequest jobRequest = JobRequest.builder()
                    .jobType(JobData.JobType.TEXT_PROCESSING)
                    .inputData(inputData)
                    .sessionId(request.getSessionId())
                    .build();

            // Create async job with metrics timing
            JobResponse jobResponse = metricsService.timeAsyncJob("text_processing", () -> jobService.createJob(userId, jobRequest));
            
            // Track async job creation metrics
            metricsService.incrementCounter("async_jobs_created_total", "job_type", "text_processing", "user_id", userId, "status", "success");
            
            log.info("Created async text processing job {} for user {}", 
                    jobResponse.getJobId(), userId);

            return ResponseEntity.ok(jobResponse);

        } catch (JobService.JobServiceException e) {
            log.error("Failed to create async text processing job for user {}: {}", userId, e.getMessage());
            // Track job creation failures
            metricsService.incrementCounter("async_jobs_created_total", "job_type", "text_processing", "user_id", userId, "status", "job_creation_error", "error_type", "JobServiceException");
            return ResponseEntity.internalServerError().build();
        } catch (Exception e) {
            log.error("Unexpected error processing async text request for user {}: {}", 
                    userId, e.getMessage(), e);
            // Track unexpected errors
            metricsService.incrementCounter("async_jobs_created_total", "job_type", "text_processing", "user_id", userId, "status", "unexpected_error", "error_type", e.getClass().getSimpleName());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get job status by job ID
     * Used for polling job completion status
     *
     * @param jwt the decoded JWT token containing user identity
     * @param jobId the unique job identifier
     * @return ResponseEntity containing job status
     */
    @Timed(value = "http_request_duration_seconds", description = "Job status polling request duration", extraTags = {"endpoint", "/api/v1/async/jobs/{jobId}", "operation", "job_status_polling"})
    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<JobStatusResponse> getJobStatus(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String jobId
    ) {
        String userId = (jwt != null) ? jwt.getSubject() : "test-user";
        
        try {
            // Time job status retrieval operation
            Optional<JobStatusResponse> jobStatus = metricsService.timeDatabaseOperation("get_job_status", () -> jobService.getJobStatus(jobId, userId));
            
            if (jobStatus.isPresent()) {
                // Track successful status polling
                metricsService.incrementCounter("job_status_polls_total", "user_id", userId, "status", "success", "found", "true");
                return ResponseEntity.ok(jobStatus.get());
            } else {
                log.warn("Job {} not found or access denied for user {}", jobId, userId);
                // Track not found polls
                metricsService.incrementCounter("job_status_polls_total", "user_id", userId, "status", "not_found", "found", "false");
                return ResponseEntity.notFound().build();
            }
            
        } catch (Exception e) {
            log.error("Failed to get job status for job {} and user {}: {}", 
                    jobId, userId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get all jobs for authenticated user with pagination
     *
     * @param jwt the decoded JWT token containing user identity
     * @param limit maximum number of jobs to return (default: 20)
     * @param offset offset for pagination (default: 0)
     * @return ResponseEntity containing list of user jobs
     */
    @Timed(value = "http_request_duration_seconds", description = "Get user jobs request duration", extraTags = {"endpoint", "/api/v1/async/jobs", "operation", "get_user_jobs"})
    @GetMapping("/jobs")
    public ResponseEntity<List<JobStatusResponse>> getUserJobs(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "limit", defaultValue = "20") Integer limit,
            @RequestParam(value = "offset", defaultValue = "0") Integer offset
    ) {
        String userId = (jwt != null) ? jwt.getSubject() : "test-user";
        
        try {
            // Validate pagination parameters
            if (limit < 1 || limit > 100) {
                log.warn("Invalid limit parameter for user {}: {}", userId, limit);
                return ResponseEntity.badRequest().build();
            }
            
            if (offset < 0) {
                log.warn("Invalid offset parameter for user {}: {}", userId, offset);
                return ResponseEntity.badRequest().build();
            }

            // Time user jobs retrieval with pagination metrics
            List<JobStatusResponse> userJobs = metricsService.timeDatabaseOperation("get_user_jobs", () -> jobService.getJobsByUser(userId, limit, offset));
            
            // Track job retrieval metrics
            metricsService.incrementCounter("user_jobs_retrieved_total", "user_id", userId);
            metricsService.recordGauge("user_jobs_count", userJobs.size(), "user_id", userId, "limit", String.valueOf(limit));
            
            log.debug("Retrieved {} jobs for user {} (limit: {}, offset: {})", 
                    userJobs.size(), userId, limit, offset);
            
            return ResponseEntity.ok(userJobs);
            
        } catch (Exception e) {
            log.error("Failed to get jobs for user {}: {}", userId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Cancel a queued job
     * Only jobs in QUEUED status can be cancelled
     *
     * @param jwt the decoded JWT token containing user identity
     * @param jobId the unique job identifier
     * @return ResponseEntity indicating cancellation success
     */
    @Timed(value = "http_request_duration_seconds", description = "Job cancellation request duration", extraTags = {"endpoint", "/api/v1/async/jobs/{jobId}", "operation", "cancel_job"})
    @DeleteMapping("/jobs/{jobId}")
    public ResponseEntity<Void> cancelJob(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String jobId
    ) {
        String userId = (jwt != null) ? jwt.getSubject() : "test-user";
        
        try {
            // Time job cancellation operation
            boolean cancelled = metricsService.timeDatabaseOperation("cancel_job", () -> jobService.cancelJob(jobId, userId));
            
            if (cancelled) {
                log.info("Successfully cancelled job {} for user {}", jobId, userId);
                // Track successful cancellations
                metricsService.incrementCounter("job_cancellations_total", "user_id", userId, "status", "success", "cancelled", "true");
                return ResponseEntity.noContent().build();
            } else {
                log.warn("Failed to cancel job {} for user {} (not found, unauthorized, or not cancellable)", 
                        jobId, userId);
                // Track failed cancellations
                metricsService.incrementCounter("job_cancellations_total", "user_id", userId, "status", "not_cancellable", "cancelled", "false");
                return ResponseEntity.notFound().build();
            }
            
        } catch (Exception e) {
            log.error("Failed to cancel job {} for user {}: {}", jobId, userId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get queue statistics for monitoring (admin endpoint in future)
     *
     * @param queueName the queue name to get statistics for
     * @return ResponseEntity containing queue statistics
     */
    @Timed(value = "http_request_duration_seconds", description = "Queue statistics request duration", extraTags = {"endpoint", "/api/v1/async/admin/queues/{queueName}/stats", "operation", "get_queue_stats"})
    @GetMapping("/admin/queues/{queueName}/stats")
    public ResponseEntity<Map<String, Object>> getQueueStats(
            @PathVariable String queueName
    ) {
        try {
            // TODO: Add admin authorization check
            // Time queue stats retrieval
            Map<String, Object> stats = metricsService.timeExternalService("redis_queue", () -> jobService.getQueueStats(queueName));
            
            // Track queue stats requests
            metricsService.incrementCounter("queue_stats_requests_total", "queue_name", queueName, "status", "success");
            
            return ResponseEntity.ok(stats);
            
        } catch (Exception e) {
            log.error("Failed to get queue stats for {}: {}", queueName, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Health check endpoint for async processing
     *
     * @return ResponseEntity indicating service health
     */
    @Timed(value = "http_request_duration_seconds", description = "Async health check request duration", extraTags = {"endpoint", "/api/v1/async/health", "operation", "health_check"})
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> health = new HashMap<>();
        health.put("status", "UP");
        health.put("service", "async-note-processing");
        health.put("timestamp", java.time.Instant.now().toString());
        
        // Check transcription service availability with metrics
        boolean transcriptionAvailable = metricsService.timeExternalService("transcription_health", () -> transcriptionService.isTranscriptionServiceAvailable());
        health.put("transcriptionService", transcriptionAvailable ? "UP" : "DOWN");
        
        // Track health check metrics
        metricsService.incrementCounter("health_checks_total", "service", "async_processing", "transcription_available", String.valueOf(transcriptionAvailable));
        
        return ResponseEntity.ok(health);
    }

    /**
     * Get comprehensive system health report
     *
     * @return ResponseEntity containing detailed system health
     */
    @Timed(value = "http_request_duration_seconds", description = "System health report request duration", extraTags = {"endpoint", "/api/v1/async/admin/health/system", "operation", "get_system_health"})
    @GetMapping("/admin/health/system")
    public ResponseEntity<WorkerHealthService.SystemHealthReport> getSystemHealth() {
        try {
            // Time system health report generation
            WorkerHealthService.SystemHealthReport report = metricsService.timeOperation("system_health_report", "/api/v1/async/admin/health/system", "GET", () -> workerHealthService.getSystemHealthReport());
            
            // Track system health metrics
            metricsService.incrementCounter("system_health_reports_total", "status", "success");
            
            return ResponseEntity.ok(report);
            
        } catch (Exception e) {
            log.error("Failed to get system health report: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get active workers information
     *
     * @return ResponseEntity containing active workers list
     */
    @Timed(value = "http_request_duration_seconds", description = "Get active workers request duration", extraTags = {"endpoint", "/api/v1/async/admin/workers/active", "operation", "get_active_workers"})
    @GetMapping("/admin/workers/active")
    public ResponseEntity<List<WorkerHealthService.WorkerHealth>> getActiveWorkers() {
        try {
            // Time active workers retrieval
            List<WorkerHealthService.WorkerHealth> activeWorkers = metricsService.timeOperation("get_active_workers", "/api/v1/async/admin/workers/active", "GET", () -> workerHealthService.getActiveWorkers());
            
            // Track active workers metrics
            metricsService.incrementCounter("active_workers_requests_total", "status", "success");
            metricsService.recordGauge("active_workers_count", activeWorkers.size());
            
            return ResponseEntity.ok(activeWorkers);
            
        } catch (Exception e) {
            log.error("Failed to get active workers: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get specific worker health information
     *
     * @param workerId the worker ID to check
     * @return ResponseEntity containing worker health data
     */
    @Timed(value = "http_request_duration_seconds", description = "Get worker health request duration", extraTags = {"endpoint", "/api/v1/async/admin/workers/{workerId}", "operation", "get_worker_health"})
    @GetMapping("/admin/workers/{workerId}")
    public ResponseEntity<WorkerHealthService.WorkerHealth> getWorkerHealth(
            @PathVariable String workerId
    ) {
        try {
            // Time worker health retrieval
            Optional<WorkerHealthService.WorkerHealth> workerHealth = metricsService.timeOperation("get_worker_health", "/api/v1/async/admin/workers/" + workerId, "GET", () -> workerHealthService.getWorkerHealth(workerId));
            
            if (workerHealth.isPresent()) {
                // Track successful worker health requests
                metricsService.incrementCounter("worker_health_requests_total", "worker_id", workerId, "status", "success", "found", "true");
                return ResponseEntity.ok(workerHealth.get());
            } else {
                // Track worker not found
                metricsService.incrementCounter("worker_health_requests_total", "worker_id", workerId, "status", "not_found", "found", "false");
                return ResponseEntity.notFound().build();
            }
                    
        } catch (Exception e) {
            log.error("Failed to get worker health for {}: {}", workerId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    // Private helper methods

    /**
     * Validates audio file using TranscriptionService validation logic
     * Throws TranscriptionException if validation fails
     */
    private void validateAudioFileForAsync(MultipartFile audioFile) 
            throws TranscriptionService.TranscriptionException {
        
        // Use TranscriptionService validation through a temporary transcription attempt
        // This reuses all existing validation logic including MIME types, magic numbers, etc.
        if (!transcriptionService.isTranscriptionServiceAvailable()) {
            // If transcription service is not available, perform basic validation
            validateAudioFileBasic(audioFile);
        } else {
            // Let TranscriptionService validate but don't actually transcribe
            // We'll do a dry-run validation by catching the service call
            try {
                // This will validate the file but might fail at actual transcription
                // The validation happens first, so we catch validation errors separately
                transcriptionService.transcribe(audioFile);
            } catch (TranscriptionService.TranscriptionException e) {
                // Re-throw validation errors
                if (e.getMessage().contains("Audio file") || 
                    e.getMessage().contains("Unsupported") ||
                    e.getMessage().contains("Invalid") ||
                    e.getMessage().contains("too large")) {
                    throw e;
                }
                // If it's a service communication error, that's OK for async processing
                log.debug("Transcription service communication error during validation (expected for async): {}", 
                        e.getMessage());
            }
        }
    }

    /**
     * Basic audio file validation when TranscriptionService is unavailable
     */
    private void validateAudioFileBasic(MultipartFile audioFile) 
            throws TranscriptionService.TranscriptionException {
        
        if (audioFile == null || audioFile.isEmpty()) {
            throw new TranscriptionService.TranscriptionException("Audio file is empty or null");
        }

        // Check file size (25MB limit)
        long maxFileSize = 25 * 1024 * 1024;
        if (audioFile.getSize() > maxFileSize) {
            throw new TranscriptionService.TranscriptionException(
                String.format("Audio file too large: %d bytes (max: %d bytes)", 
                    audioFile.getSize(), maxFileSize)
            );
        }

        // Check filename
        String originalFilename = audioFile.getOriginalFilename();
        if (originalFilename == null || originalFilename.isEmpty()) {
            throw new TranscriptionService.TranscriptionException("Audio file has no filename");
        }

        // Basic extension check
        String extension = getFileExtension(originalFilename).toLowerCase();
        List<String> allowedExtensions = Arrays.asList(".mp3", ".wav", ".webm", ".m4a", ".ogg", ".flac");
        if (!allowedExtensions.contains(extension)) {
            throw new TranscriptionService.TranscriptionException(
                String.format("Unsupported audio file format: %s", extension)
            );
        }
    }

    /**
     * Extract file extension from filename
     */
    private String getFileExtension(String filename) {
        int lastDotIndex = filename.lastIndexOf('.');
        return lastDotIndex > 0 ? filename.substring(lastDotIndex) : "";
    }

    /**
     * Handle text processing during system degradation
     */
    private ResponseEntity<JobResponse> handleDegradedTextProcessing(String userId, NoteRequest request) {
        try {
            ServiceDegradationService.DegradationLevel degradationLevel = 
                serviceDegradationService.getCurrentDegradationLevel();

            log.warn("Text processing during degradation level: {} for user: {}", degradationLevel, userId);

            switch (degradationLevel) {
                case MINOR_DEGRADATION:
                case MODERATE_DEGRADATION:
                    // Allow processing but inform user of potential delays
                    return processTextWithDegradationWarning(userId, request);

                case MAJOR_DEGRADATION:
                    // Check if agent service specifically is degraded
                    if (serviceDegradationService.getServiceDegradationState("agent-service").isDegraded()) {
                        return returnAgentServiceDegraded();
                    }
                    return processTextWithDegradationWarning(userId, request);

                case CRITICAL_DEGRADATION:
                case MAINTENANCE_MODE:
                    // Return maintenance mode response
                    return returnMaintenanceMode();

                default:
                    // Fallback to normal processing
                    return processNormalTextProcessing(userId, request);
            }

        } catch (Exception e) {
            log.error("Error handling degraded text processing for user {}: {}", userId, e.getMessage(), e);
            return ResponseEntity.status(503) // Service Unavailable
                    .build();
        }
    }

    /**
     * Handle transcription upload during system degradation
     */
    private ResponseEntity<JobResponse> handleDegradedTranscriptionUpload(String userId, MultipartFile audioFile, String sessionId) {
        try {
            ServiceDegradationService.DegradationLevel degradationLevel =
                serviceDegradationService.getCurrentDegradationLevel();

            log.warn("Transcription upload during degradation level: {} for user: {}", degradationLevel, userId);

            switch (degradationLevel) {
                case MINOR_DEGRADATION:
                case MODERATE_DEGRADATION:
                    // Allow upload but inform user of potential delays
                    return processTranscriptionWithDegradationWarning(userId, audioFile, sessionId);

                case MAJOR_DEGRADATION:
                    // Check if transcription service specifically is degraded
                    if (serviceDegradationService.getServiceDegradationState("transcription-service").isDegraded()) {
                        return returnTranscriptionServiceDegraded();
                    }
                    return processTranscriptionWithDegradationWarning(userId, audioFile, sessionId);

                case CRITICAL_DEGRADATION:
                case MAINTENANCE_MODE:
                    // Return maintenance mode response
                    return returnMaintenanceMode();

                default:
                    // Fallback to normal processing
                    return processNormalTranscriptionUpload(userId, audioFile, sessionId);
            }

        } catch (Exception e) {
            log.error("Error handling degraded transcription upload for user {}: {}", userId, e.getMessage(), e);
            return ResponseEntity.status(503) // Service Unavailable
                    .build();
        }
    }

    /**
     * Handle audio upload during system degradation
     */
    private ResponseEntity<JobResponse> handleDegradedAudioUpload(String userId, MultipartFile audioFile, String sessionId) {
        try {
            ServiceDegradationService.DegradationLevel degradationLevel = 
                serviceDegradationService.getCurrentDegradationLevel();

            log.warn("Audio upload during degradation level: {} for user: {}", degradationLevel, userId);

            switch (degradationLevel) {
                case MINOR_DEGRADATION:
                case MODERATE_DEGRADATION:
                    // Allow upload but inform user of potential delays
                    return processWithDegradationWarning(userId, audioFile, sessionId);

                case MAJOR_DEGRADATION:
                    // Check if transcription service specifically is degraded
                    if (serviceDegradationService.getServiceDegradationState("transcription-service").isDegraded()) {
                        return returnTranscriptionServiceDegraded();
                    }
                    return processWithDegradationWarning(userId, audioFile, sessionId);

                case CRITICAL_DEGRADATION:
                case MAINTENANCE_MODE:
                    // Return maintenance mode response
                    return returnMaintenanceMode();

                default:
                    // Fallback to normal processing
                    return processNormalAudioUpload(userId, audioFile, sessionId);
            }

        } catch (Exception e) {
            log.error("Error handling degraded audio upload for user {}: {}", userId, e.getMessage(), e);
            return ResponseEntity.status(503) // Service Unavailable
                    .build();
        }
    }

    /**
     * Process text with degradation warning
     */
    private ResponseEntity<JobResponse> processTextWithDegradationWarning(String userId, NoteRequest request) {
        try {
            // Validate text input
            if (request == null || request.getTextRaw() == null || request.getTextRaw().trim().isEmpty()) {
                log.warn("Invalid text request during degradation for user {}", userId);
                return ResponseEntity.badRequest().build();
            }

            // Create job with degradation context
            JobRequest jobRequest = createDegradedTextJobRequest(request);
            JobResponse jobResponse = jobService.createJob(userId, jobRequest);

            // Add degradation warning to response
            log.info("Created degraded text processing job {} for user {} with warning", 
                    jobResponse.getJobId(), userId);

            return ResponseEntity.ok()
                    .header("X-System-Status", "degraded")
                    .header("X-Degradation-Level", serviceDegradationService.getCurrentDegradationLevel().name())
                    .body(jobResponse);

        } catch (Exception e) {
            log.error("Failed to process degraded text request: {}", e.getMessage());
            return ResponseEntity.status(503).build();
        }
    }

    /**
     * Return agent service degraded response
     */
    private ResponseEntity<JobResponse> returnAgentServiceDegraded() {
        String message = serviceDegradationService.getServiceDegradationMessage("agent-service");
        
        return ResponseEntity.status(503) // Service Unavailable
                .header("X-Service-Status", "agent-service-degraded")
                .header("X-Degradation-Message", message)
                .header("Retry-After", "1800") // Suggest retry after 30 minutes
                .build();
    }

    /**
     * Process normal text processing (fallback)
     */
    private ResponseEntity<JobResponse> processNormalTextProcessing(String userId, NoteRequest request) {
        try {
            // Basic input validation
            if (request == null || request.getTextRaw() == null || request.getTextRaw().trim().isEmpty()) {
                return ResponseEntity.badRequest().build();
            }

            // Create normal job request
            Map<String, Object> inputData = new HashMap<>();
            inputData.put("textRaw", request.getTextRaw().trim());
            if (request.getSessionId() != null) {
                inputData.put("sessionId", request.getSessionId().trim());
            }
            if (request.getTranscriptId() != null) {
                inputData.put("transcriptId", request.getTranscriptId().trim());
            }

            JobRequest jobRequest = JobRequest.builder()
                    .jobType(JobData.JobType.TEXT_PROCESSING)
                    .inputData(inputData)
                    .sessionId(request.getSessionId())
                    .build();

            JobResponse jobResponse = jobService.createJob(userId, jobRequest);
            return ResponseEntity.ok(jobResponse);

        } catch (Exception e) {
            log.error("Failed to process normal text request: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Process audio upload with degradation warning
     */
    private ResponseEntity<JobResponse> processWithDegradationWarning(String userId, MultipartFile audioFile, String sessionId) {
        try {
            // Validate audio file
            validateAudioFileForAsync(audioFile);

            // Create job with degradation context
            JobRequest jobRequest = createDegradedJobRequest(audioFile, sessionId);
            JobResponse jobResponse = jobService.createJob(userId, jobRequest);

            // Add degradation warning to response
            Map<String, Object> enhancedResponse = new HashMap<>();
            enhancedResponse.put("jobId", jobResponse.getJobId());
            enhancedResponse.put("status", jobResponse.getStatus());
            enhancedResponse.put("degradationWarning", serviceDegradationService.getDegradationMessage());
            enhancedResponse.put("estimatedDelayMinutes", calculateEstimatedDelay());

            log.info("Created degraded audio processing job {} for user {} with warning", 
                    jobResponse.getJobId(), userId);

            return ResponseEntity.ok()
                    .header("X-System-Status", "degraded")
                    .header("X-Degradation-Level", serviceDegradationService.getCurrentDegradationLevel().name())
                    .body(jobResponse);

        } catch (Exception e) {
            log.error("Failed to process degraded audio upload: {}", e.getMessage());
            return ResponseEntity.status(503).build();
        }
    }

    /**
     * Return transcription service degraded response
     */
    private ResponseEntity<JobResponse> returnTranscriptionServiceDegraded() {
        String message = serviceDegradationService.getServiceDegradationMessage("transcription-service");
        
        return ResponseEntity.status(503) // Service Unavailable
                .header("X-Service-Status", "transcription-degraded")
                .header("X-Degradation-Message", message)
                .header("Retry-After", "1800") // Suggest retry after 30 minutes
                .build();
    }

    /**
     * Return maintenance mode response
     */
    private ResponseEntity<JobResponse> returnMaintenanceMode() {
        String message = serviceDegradationService.getDegradationMessage();
        
        return ResponseEntity.status(503) // Service Unavailable
                .header("X-Service-Status", "maintenance")
                .header("X-Degradation-Message", message)
                .header("Retry-After", "3600") // Suggest retry after 1 hour
                .build();
    }

    /**
     * Process normal audio upload (fallback)
     */
    private ResponseEntity<JobResponse> processNormalAudioUpload(String userId, MultipartFile audioFile, String sessionId) {
        // This would contain the original processing logic
        // For now, we'll just return a simple response
        return ResponseEntity.ok().build();
    }

    /**
     * Create job request with degradation context for text processing
     */
    private JobRequest createDegradedTextJobRequest(NoteRequest request) {
        Map<String, Object> inputData = new HashMap<>();
        inputData.put("textRaw", request.getTextRaw().trim());
        inputData.put("textLength", request.getTextRaw().trim().length());
        inputData.put("degradationContext", true);
        inputData.put("degradationLevel", serviceDegradationService.getCurrentDegradationLevel().name());
        inputData.put("submissionTime", java.time.Instant.now().toString());
        
        // Add optional fields if present
        if (request.getSessionId() != null && !request.getSessionId().trim().isEmpty()) {
            inputData.put("sessionId", request.getSessionId().trim());
        }
        if (request.getTranscriptId() != null && !request.getTranscriptId().trim().isEmpty()) {
            inputData.put("transcriptId", request.getTranscriptId().trim());
        }

        return JobRequest.builder()
                .jobType(JobData.JobType.TEXT_PROCESSING)
                .inputData(inputData)
                .sessionId(request.getSessionId())
                .build();
    }

    /**
     * Create job request with degradation context
     */
    private JobRequest createDegradedJobRequest(MultipartFile audioFile, String sessionId) {
        Map<String, Object> inputData = new HashMap<>();
        inputData.put("originalFileName", 
            SecurityUtils.sanitizeFilenameForLogging(audioFile.getOriginalFilename()));
        inputData.put("contentType", audioFile.getContentType());
        inputData.put("fileSize", audioFile.getSize());
        inputData.put("degradationContext", true);
        inputData.put("degradationLevel", serviceDegradationService.getCurrentDegradationLevel().name());
        
        // Store audio data as Base64
        try {
            byte[] audioBytes = audioFile.getBytes();
            String audioBase64 = Base64.getEncoder().encodeToString(audioBytes);
            inputData.put("audioData", audioBase64);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode audio data", e);
        }

        return JobRequest.builder()
                .jobType(JobData.JobType.AUDIO_PROCESSING)
                .inputData(inputData)
                .sessionId(sessionId)
                .build();
    }

    /**
     * Process transcription with degradation warning
     */
    private ResponseEntity<JobResponse> processTranscriptionWithDegradationWarning(String userId, MultipartFile audioFile, String sessionId) {
        try {
            // Validate audio file
            validateAudioFileForAsync(audioFile);

            // Create job with degradation context
            JobRequest jobRequest = createDegradedTranscriptionJobRequest(audioFile, sessionId);
            JobResponse jobResponse = jobService.createJob(userId, jobRequest);

            // Add degradation warning to response
            log.info("Created degraded transcription job {} for user {} with warning",
                    jobResponse.getJobId(), userId);

            return ResponseEntity.ok()
                    .header("X-System-Status", "degraded")
                    .header("X-Degradation-Level", serviceDegradationService.getCurrentDegradationLevel().name())
                    .body(jobResponse);

        } catch (Exception e) {
            log.error("Failed to process degraded transcription upload: {}", e.getMessage());
            return ResponseEntity.status(503).build();
        }
    }

    /**
     * Process normal transcription upload (fallback)
     */
    private ResponseEntity<JobResponse> processNormalTranscriptionUpload(String userId, MultipartFile audioFile, String sessionId) {
        try {
            // Validate audio file
            validateAudioFileForAsync(audioFile);

            // Create normal job request
            Map<String, Object> inputData = new HashMap<>();
            inputData.put("originalFileName",
                SecurityUtils.sanitizeFilenameForLogging(audioFile.getOriginalFilename()));
            inputData.put("contentType", audioFile.getContentType());
            inputData.put("fileSize", audioFile.getSize());

            // Store audio data as Base64
            byte[] audioBytes = audioFile.getBytes();
            String audioBase64 = Base64.getEncoder().encodeToString(audioBytes);
            inputData.put("audioData", audioBase64);

            JobRequest jobRequest = JobRequest.builder()
                    .jobType(JobData.JobType.TRANSCRIPTION_ONLY)
                    .inputData(inputData)
                    .sessionId(sessionId)
                    .build();

            JobResponse jobResponse = jobService.createJob(userId, jobRequest);
            return ResponseEntity.ok(jobResponse);

        } catch (Exception e) {
            log.error("Failed to process normal transcription request: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Create job request with degradation context for transcription processing
     */
    private JobRequest createDegradedTranscriptionJobRequest(MultipartFile audioFile, String sessionId) {
        Map<String, Object> inputData = new HashMap<>();
        inputData.put("originalFileName",
            SecurityUtils.sanitizeFilenameForLogging(audioFile.getOriginalFilename()));
        inputData.put("contentType", audioFile.getContentType());
        inputData.put("fileSize", audioFile.getSize());
        inputData.put("degradationContext", true);
        inputData.put("degradationLevel", serviceDegradationService.getCurrentDegradationLevel().name());

        // Store audio data as Base64
        try {
            byte[] audioBytes = audioFile.getBytes();
            String audioBase64 = Base64.getEncoder().encodeToString(audioBytes);
            inputData.put("audioData", audioBase64);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode audio data", e);
        }

        return JobRequest.builder()
                .jobType(JobData.JobType.TRANSCRIPTION_ONLY)
                .inputData(inputData)
                .sessionId(sessionId)
                .build();
    }

    /**
     * Calculate estimated delay based on degradation level
     */
    private int calculateEstimatedDelay() {
        ServiceDegradationService.DegradationLevel level =
            serviceDegradationService.getCurrentDegradationLevel();

        switch (level) {
            case MINOR_DEGRADATION:
                return 5; // 5 minutes additional delay
            case MODERATE_DEGRADATION:
                return 15; // 15 minutes additional delay
            case MAJOR_DEGRADATION:
                return 30; // 30 minutes additional delay
            default:
                return 0;
        }
    }
}
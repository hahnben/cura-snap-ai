package ai.curasnap.backend.controller;

import ai.curasnap.backend.model.dto.*;
import ai.curasnap.backend.service.JobService;
import ai.curasnap.backend.service.TranscriptionService;
import ai.curasnap.backend.util.SecurityUtils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;
import java.util.*;

/**
 * REST controller for async job management and audio processing
 * Provides non-blocking endpoints that return job IDs immediately
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/async")
public class AsyncNoteController {

    private final JobService jobService;
    private final TranscriptionService transcriptionService;

    @Autowired
    public AsyncNoteController(JobService jobService, TranscriptionService transcriptionService) {
        this.jobService = jobService;
        this.transcriptionService = transcriptionService;
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
    @PostMapping("/notes/format-audio")
    public ResponseEntity<JobResponse> formatAudioAsync(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("audio") MultipartFile audioFile,
            @RequestParam(value = "sessionId", required = false) String sessionId
    ) {
        String userId = (jwt != null) ? jwt.getSubject() : "test-user";
        log.info("Received async audio upload request from user: {}", userId);

        try {
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

            // Create async job
            JobResponse jobResponse = jobService.createJob(userId, jobRequest);
            
            log.info("Created async audio processing job {} for user {}", 
                    jobResponse.getJobId(), userId);

            return ResponseEntity.ok(jobResponse);

        } catch (TranscriptionService.TranscriptionException e) {
            log.warn("Audio validation failed for user {}: {}", userId, e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (JobService.JobServiceException e) {
            log.error("Failed to create async job for user {}: {}", userId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        } catch (Exception e) {
            log.error("Unexpected error processing async audio upload for user {}: {}", 
                    userId, e.getMessage(), e);
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
    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<JobStatusResponse> getJobStatus(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String jobId
    ) {
        String userId = (jwt != null) ? jwt.getSubject() : "test-user";
        
        try {
            Optional<JobStatusResponse> jobStatus = jobService.getJobStatus(jobId, userId);
            
            if (jobStatus.isPresent()) {
                return ResponseEntity.ok(jobStatus.get());
            } else {
                log.warn("Job {} not found or access denied for user {}", jobId, userId);
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

            List<JobStatusResponse> userJobs = jobService.getJobsByUser(userId, limit, offset);
            
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
    @DeleteMapping("/jobs/{jobId}")
    public ResponseEntity<Void> cancelJob(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String jobId
    ) {
        String userId = (jwt != null) ? jwt.getSubject() : "test-user";
        
        try {
            boolean cancelled = jobService.cancelJob(jobId, userId);
            
            if (cancelled) {
                log.info("Successfully cancelled job {} for user {}", jobId, userId);
                return ResponseEntity.noContent().build();
            } else {
                log.warn("Failed to cancel job {} for user {} (not found, unauthorized, or not cancellable)", 
                        jobId, userId);
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
    @GetMapping("/admin/queues/{queueName}/stats")
    public ResponseEntity<Map<String, Object>> getQueueStats(
            @PathVariable String queueName
    ) {
        try {
            // TODO: Add admin authorization check
            Map<String, Object> stats = jobService.getQueueStats(queueName);
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
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> health = new HashMap<>();
        health.put("status", "UP");
        health.put("service", "async-note-processing");
        health.put("timestamp", java.time.Instant.now().toString());
        
        // Check transcription service availability
        boolean transcriptionAvailable = transcriptionService.isTranscriptionServiceAvailable();
        health.put("transcriptionService", transcriptionAvailable ? "UP" : "DOWN");
        
        return ResponseEntity.ok(health);
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
}
package ai.curasnap.backend.service;

import ai.curasnap.backend.model.dto.JobData;
import ai.curasnap.backend.model.dto.NoteRequest;
import ai.curasnap.backend.model.dto.NoteResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Managed worker instance for processing audio jobs in a worker pool
 * Handles job processing with health monitoring and error recovery
 */
@Slf4j
public class ManagedAudioWorker {

    private final String workerId;
    private final JobService jobService;
    private final TranscriptionService transcriptionService;
    private final NoteService noteService;
    private final WorkerHealthService workerHealthService;

    // Worker state management
    private final AtomicBoolean isRunning = new AtomicBoolean(true);
    private final AtomicBoolean isFailed = new AtomicBoolean(false);
    private ScheduledFuture<?> workerTask;

    // Statistics
    private final AtomicLong totalJobsProcessed = new AtomicLong(0);
    private final AtomicLong totalJobsFailed = new AtomicLong(0);
    private volatile Instant lastProcessingTime = Instant.now();
    private volatile Instant lastSuccessfulJob = null;

    // Error tracking
    private volatile int consecutiveFailures = 0;
    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    public ManagedAudioWorker(String workerId,
                             JobService jobService,
                             TranscriptionService transcriptionService,
                             NoteService noteService,
                             WorkerHealthService workerHealthService) {
        this.workerId = workerId;
        this.jobService = jobService;
        this.transcriptionService = transcriptionService;
        this.noteService = noteService;
        this.workerHealthService = workerHealthService;

        // Register with health service
        workerHealthService.registerWorker(workerId, "audio_processing");
        log.info("ManagedAudioWorker {} initialized", workerId);
    }

    /**
     * Main job processing method called by scheduler
     */
    public void processJobs() {
        if (!isRunning.get() || isFailed.get()) {
            return;
        }

        try {
            // Update heartbeat
            workerHealthService.updateWorkerHeartbeat(workerId);
            lastProcessingTime = Instant.now();

            // Get next job from queue
            Optional<JobData> nextJob = jobService.getNextJobFromQueue("audio_processing");
            
            if (nextJob.isPresent()) {
                processAudioJob(nextJob.get());
                resetFailureCounter();
            }

        } catch (Exception e) {
            handleWorkerError(e);
        }
    }

    /**
     * Process a single audio job
     */
    private void processAudioJob(JobData jobData) {
        String jobId = jobData.getJobId();
        String userId = jobData.getUserId();
        Instant jobStartTime = Instant.now();
        
        log.info("Worker {} processing audio job {} for user {}", workerId, jobId, userId);
        
        try {
            // Mark job as started
            if (!jobService.markJobAsStarted(jobId)) {
                log.warn("Worker {} - Failed to mark job {} as started, skipping", workerId, jobId);
                return;
            }

            // Extract audio data from job
            MultipartFile audioFile = createMultipartFileFromJobData(jobData);
            
            // Step 1: Transcribe audio to text
            log.debug("Worker {} - Starting transcription for job {}", workerId, jobId);
            String transcript = transcriptionService.transcribe(audioFile);
            
            if (transcript == null || transcript.trim().isEmpty()) {
                failJob(jobId, "Transcription returned empty result", "transcription_empty");
                return;
            }
            
            log.debug("Worker {} - Transcription completed for job {}: {} characters", 
                    workerId, jobId, transcript.length());

            // Step 2: Create NoteRequest for SOAP generation
            NoteRequest noteRequest = new NoteRequest();
            noteRequest.setTextRaw(transcript);
            noteRequest.setSessionId(jobData.getSessionId());
            noteRequest.setTranscriptId(jobData.getTranscriptId());

            // Step 3: Generate SOAP note
            log.debug("Worker {} - Starting SOAP generation for job {}", workerId, jobId);
            NoteResponse noteResponse = noteService.formatNote(userId, noteRequest);
            
            log.debug("Worker {} - SOAP generation completed for job {}", workerId, jobId);

            // Step 4: Update job with results
            Map<String, Object> result = new HashMap<>();
            result.put("noteResponse", Map.of(
                "id", noteResponse.getId().toString(),
                "textRaw", noteResponse.getTextRaw(),
                "textStructured", noteResponse.getTextStructured(),
                "createdAt", noteResponse.getCreatedAt().toString()
            ));
            result.put("transcriptText", transcript);
            result.put("processingTimeMs", 
                Duration.between(jobStartTime, Instant.now()).toMillis());
            result.put("workerId", workerId);

            boolean updated = jobService.updateJobStatus(
                jobId, 
                JobData.JobStatus.COMPLETED, 
                result, 
                null
            );

            if (updated) {
                long processingTime = (Long) result.get("processingTimeMs");
                totalJobsProcessed.incrementAndGet();
                lastSuccessfulJob = Instant.now();
                
                // Record success metrics
                workerHealthService.recordJobProcessing(workerId, true, processingTime);
                
                log.info("Worker {} successfully completed audio job {} for user {} in {}ms", 
                        workerId, jobId, userId, processingTime);
            } else {
                log.error("Worker {} - Failed to update job status for completed job {}", workerId, jobId);
            }

        } catch (TranscriptionService.TranscriptionException e) {
            log.error("Worker {} - Transcription failed for job {}: {}", workerId, jobId, e.getMessage());
            long processingTime = Duration.between(jobStartTime, Instant.now()).toMillis();
            workerHealthService.recordJobProcessing(workerId, false, processingTime);
            failJob(jobId, "Transcription failed: " + e.getMessage(), "transcription_error");
            
        } catch (Exception e) {
            log.error("Worker {} - Unexpected error processing audio job {}: {}", workerId, jobId, e.getMessage(), e);
            long processingTime = Duration.between(jobStartTime, Instant.now()).toMillis();
            workerHealthService.recordJobProcessing(workerId, false, processingTime);
            failJob(jobId, "Processing failed: " + e.getMessage(), "unexpected_error");
        }
    }

    /**
     * Handle job failure with intelligent retry
     */
    private void failJob(String jobId, String errorMessage, String errorType) {
        try {
            totalJobsFailed.incrementAndGet();
            incrementFailureCounter();
            
            // Try to increment retry count with intelligent strategy
            boolean retryScheduled;
            if (jobService instanceof JobServiceImpl) {
                retryScheduled = ((JobServiceImpl) jobService).incrementRetryCountWithStrategy(jobId, errorType);
            } else {
                retryScheduled = jobService.incrementRetryCount(jobId);
            }
            
            if (!retryScheduled) {
                // Max retries exceeded, mark as permanently failed
                boolean updated = jobService.updateJobStatus(
                    jobId, 
                    JobData.JobStatus.FAILED, 
                    null, 
                    errorMessage
                );
                
                if (updated) {
                    log.error("Worker {} - Job {} permanently failed after max retries: {}", 
                            workerId, jobId, errorMessage);
                } else {
                    log.error("Worker {} - Failed to mark job {} as failed", workerId, jobId);
                }
            } else {
                log.info("Worker {} - Job {} scheduled for retry due to: {}", 
                        workerId, jobId, errorMessage);
            }
            
        } catch (Exception e) {
            log.error("Worker {} - Error handling job failure for job {}: {}", 
                    workerId, jobId, e.getMessage(), e);
            incrementFailureCounter();
        }
    }

    /**
     * Create MultipartFile from job data for transcription service
     */
    private MultipartFile createMultipartFileFromJobData(JobData jobData) throws Exception {
        Map<String, Object> inputData = jobData.getInputData();
        
        // Extract audio metadata
        String originalFileName = (String) inputData.get("originalFileName");
        String contentType = (String) inputData.get("contentType");
        String audioBase64 = (String) inputData.get("audioData");
        
        if (audioBase64 == null) {
            throw new Exception("No audio data found in job");
        }
        
        // Decode Base64 audio data
        byte[] audioBytes = Base64.getDecoder().decode(audioBase64);
        
        // Create custom MultipartFile implementation for transcription service
        return new ByteArrayMultipartFile(originalFileName, contentType, audioBytes);
    }

    /**
     * Handle worker-level errors
     */
    private void handleWorkerError(Exception e) {
        log.error("Worker {} encountered error: {}", workerId, e.getMessage(), e);
        incrementFailureCounter();
        
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            log.error("Worker {} failed {} consecutive times, marking as failed", 
                    workerId, consecutiveFailures);
            markAsFailed();
        }
    }

    /**
     * Mark worker as failed
     */
    private void markAsFailed() {
        isFailed.set(true);
        isRunning.set(false);
        workerHealthService.deactivateWorker(workerId);
        log.error("Worker {} marked as failed", workerId);
    }

    /**
     * Increment failure counter and check health threshold
     */
    private void incrementFailureCounter() {
        consecutiveFailures++;
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            markAsFailed();
        }
    }

    /**
     * Reset failure counter on successful processing
     */
    private void resetFailureCounter() {
        if (consecutiveFailures > 0) {
            log.debug("Worker {} - Resetting failure counter after successful processing", workerId);
            consecutiveFailures = 0;
        }
    }

    /**
     * Graceful shutdown
     */
    public void shutdown() {
        log.info("Shutting down worker {}", workerId);
        isRunning.set(false);
        
        if (workerTask != null && !workerTask.isCancelled()) {
            workerTask.cancel(false);
        }
        
        workerHealthService.deactivateWorker(workerId);
    }

    // Getters for monitoring
    public String getWorkerId() { return workerId; }
    public boolean isRunning() { return isRunning.get(); }
    public boolean isFailed() { return isFailed.get(); }
    public long getTotalJobsProcessed() { return totalJobsProcessed.get(); }
    public long getTotalJobsFailed() { return totalJobsFailed.get(); }
    public Instant getLastProcessingTime() { return lastProcessingTime; }
    public Instant getLastSuccessfulJob() { return lastSuccessfulJob; }
    public int getConsecutiveFailures() { return consecutiveFailures; }

    public void setWorkerTask(ScheduledFuture<?> workerTask) {
        this.workerTask = workerTask;
    }

    /**
     * Custom MultipartFile implementation for audio data from Redis
     */
    private static class ByteArrayMultipartFile implements MultipartFile {
        private final String originalFilename;
        private final String contentType;
        private final byte[] content;

        public ByteArrayMultipartFile(String originalFilename, String contentType, byte[] content) {
            this.originalFilename = originalFilename;
            this.contentType = contentType;
            this.content = content;
        }

        @Override
        public String getName() { return "file"; }

        @Override
        public String getOriginalFilename() { return originalFilename; }

        @Override
        public String getContentType() { return contentType; }

        @Override
        public boolean isEmpty() { return content.length == 0; }

        @Override
        public long getSize() { return content.length; }

        @Override
        public byte[] getBytes() throws IOException { return content; }

        @Override
        public InputStream getInputStream() throws IOException {
            return new ByteArrayInputStream(content);
        }

        @Override
        public void transferTo(File dest) throws IOException, IllegalStateException {
            throw new UnsupportedOperationException("Transfer to file not supported");
        }
    }
}
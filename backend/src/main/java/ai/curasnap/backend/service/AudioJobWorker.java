package ai.curasnap.backend.service;

import ai.curasnap.backend.model.dto.JobData;
import ai.curasnap.backend.model.dto.NoteRequest;
import ai.curasnap.backend.model.dto.NoteResponse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Background worker for processing audio jobs
 * Polls the audio_processing queue and processes jobs asynchronously
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.audio.worker.enabled", havingValue = "true", matchIfMissing = true)
public class AudioJobWorker {

    private final JobService jobService;
    private final TranscriptionService transcriptionService;
    private final NoteService noteService;
    private final WorkerHealthService workerHealthService;

    // Worker identification
    private final String workerId;
    private final String workerType = "audio_processing";

    // Processing statistics
    private volatile long totalJobsProcessed = 0;
    private volatile long totalJobsFailed = 0;
    private volatile Instant lastProcessingTime = Instant.now();

    @Autowired
    public AudioJobWorker(JobService jobService, 
                         TranscriptionService transcriptionService,
                         NoteService noteService,
                         WorkerHealthService workerHealthService) {
        this.jobService = jobService;
        this.transcriptionService = transcriptionService;
        this.noteService = noteService;
        this.workerHealthService = workerHealthService;
        
        // Generate unique worker ID
        this.workerId = "audio-worker-" + System.currentTimeMillis() + "-" + 
                       Integer.toHexString(System.identityHashCode(this));
        
        // Register with health service
        workerHealthService.registerWorker(workerId, workerType);
        
        log.info("AudioJobWorker initialized with ID: {}", workerId);
    }

    /**
     * Scheduled method to process audio jobs from the queue
     * Runs every 5 seconds to check for new jobs
     */
    @Scheduled(fixedDelay = 5000) // 5 seconds
    public void processAudioJobs() {
        try {
            // Update worker heartbeat
            workerHealthService.updateWorkerHeartbeat(workerId);
            
            Optional<JobData> nextJob = jobService.getNextJobFromQueue("audio_processing");
            
            if (nextJob.isPresent()) {
                processAudioJob(nextJob.get());
            }
            
        } catch (Exception e) {
            log.error("Error in audio job worker {}: {}", workerId, e.getMessage(), e);
        }
    }

    /**
     * Process a single audio job
     * Converts audio to text, then generates SOAP note
     *
     * @param jobData the job to process
     */
    private void processAudioJob(JobData jobData) {
        String jobId = jobData.getJobId();
        String userId = jobData.getUserId();
        Instant jobStartTime = Instant.now();
        
        log.info("Worker {} processing audio job {} for user {}", workerId, jobId, userId);
        
        try {
            // Mark job as started
            if (!jobService.markJobAsStarted(jobId)) {
                log.warn("Failed to mark job {} as started, skipping", jobId);
                return;
            }

            lastProcessingTime = jobStartTime;

            // Extract audio data from job
            MultipartFile audioFile = createMultipartFileFromJobData(jobData);
            
            // Step 1: Transcribe audio to text
            log.debug("Starting transcription for job {}", jobId);
            String transcript = transcriptionService.transcribe(audioFile);
            
            if (transcript == null || transcript.trim().isEmpty()) {
                failJob(jobId, "Transcription returned empty result");
                return;
            }
            
            log.debug("Transcription completed for job {}: {} characters", 
                    jobId, transcript.length());

            // Step 2: Create NoteRequest for SOAP generation
            NoteRequest noteRequest = new NoteRequest();
            noteRequest.setTextRaw(transcript);
            noteRequest.setSessionId(jobData.getSessionId());
            noteRequest.setTranscriptId(jobData.getTranscriptId());

            // Step 3: Generate SOAP note
            log.debug("Starting SOAP generation for job {}", jobId);
            NoteResponse noteResponse = noteService.formatNote(userId, noteRequest);
            
            log.debug("SOAP generation completed for job {}", jobId);

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
                java.time.Duration.between(jobData.getCreatedAt(), Instant.now()).toMillis());

            boolean updated = jobService.updateJobStatus(
                jobId, 
                JobData.JobStatus.COMPLETED, 
                result, 
                null
            );

            if (updated) {
                totalJobsProcessed++;
                long processingTime = (Long) result.get("processingTimeMs");
                
                // Record success metrics
                workerHealthService.recordJobProcessing(workerId, true, processingTime);
                
                log.info("Worker {} successfully completed audio job {} for user {} in {}ms", 
                        workerId, jobId, userId, processingTime);
            } else {
                log.error("Failed to update job status for completed job {}", jobId);
            }

        } catch (TranscriptionService.TranscriptionException e) {
            log.error("Worker {} - Transcription failed for job {}: {}", workerId, jobId, e.getMessage());
            long processingTime = Duration.between(jobStartTime, Instant.now()).toMillis();
            workerHealthService.recordJobProcessing(workerId, false, processingTime);
            failJobWithErrorType(jobId, "Transcription failed: " + e.getMessage(), "transcription_error", e);
            
        } catch (Exception e) {
            log.error("Worker {} - Unexpected error processing audio job {}: {}", workerId, jobId, e.getMessage(), e);
            long processingTime = Duration.between(jobStartTime, Instant.now()).toMillis();
            workerHealthService.recordJobProcessing(workerId, false, processingTime);
            failJobWithErrorType(jobId, "Processing failed: " + e.getMessage(), "unexpected_error", e);
        }
    }

    /**
     * Mark job as failed and handle retry logic
     *
     * @param jobId the job ID
     * @param errorMessage the error message
     */
    private void failJob(String jobId, String errorMessage) {
        failJobWithErrorType(jobId, errorMessage, null);
    }

    /**
     * Mark job as failed with specific error type for intelligent retry
     *
     * @param jobId the job ID
     * @param errorMessage the error message
     * @param errorType the type of error for retry strategy selection
     */
    private void failJobWithErrorType(String jobId, String errorMessage, String errorType) {
        failJobWithErrorType(jobId, errorMessage, errorType, null);
    }

    /**
     * Mark job as failed with specific error type and exception for adaptive retry
     */
    private void failJobWithErrorType(String jobId, String errorMessage, String errorType, Throwable error) {
        try {
            // Try to increment retry count with adaptive intelligent strategy
            boolean retryScheduled;
            if (jobService instanceof JobServiceImpl) {
                // Use the new adaptive retry method if error is available
                retryScheduled = ((JobServiceImpl) jobService).incrementRetryCountWithStrategy(jobId, errorType, error);
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
                    totalJobsFailed++;
                    log.error("Worker {} - Job {} permanently failed after max retries: {}", 
                            workerId, jobId, errorMessage);
                } else {
                    log.error("Failed to mark job {} as failed", jobId);
                }
            } else {
                log.info("Worker {} - Job {} scheduled for retry due to: {}", 
                        workerId, jobId, errorMessage);
            }
            
        } catch (Exception e) {
            log.error("Error handling job failure for job {}: {}", jobId, e.getMessage(), e);
        }
    }

    /**
     * Create MultipartFile from job data for transcription service
     *
     * @param jobData the job containing audio data
     * @return MultipartFile for transcription
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
        return new ByteArrayMultipartFile(
            originalFileName,
            contentType,
            audioBytes
        );
    }

    /**
     * Get worker statistics for monitoring
     *
     * @return map containing worker statistics
     */
    public Map<String, Object> getWorkerStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("workerId", workerId);
        stats.put("workerType", workerType);
        stats.put("totalJobsProcessed", totalJobsProcessed);
        stats.put("totalJobsFailed", totalJobsFailed);
        stats.put("lastProcessingTime", lastProcessingTime.toString());
        stats.put("workerEnabled", true);
        stats.put("queueName", "audio_processing");
        
        // Success rate calculation
        long totalJobs = totalJobsProcessed + totalJobsFailed;
        double successRate = totalJobs > 0 ? (double) totalJobsProcessed / totalJobs : 1.0;
        stats.put("successRate", successRate);
        stats.put("totalJobsHandled", totalJobs);
        
        // Add transcription service availability
        stats.put("transcriptionServiceAvailable", 
                transcriptionService.isTranscriptionServiceAvailable());
        
        // Add uptime information
        stats.put("uptimeSeconds", Duration.between(lastProcessingTime, Instant.now()).getSeconds());
        
        // Add health service data if available
        workerHealthService.getWorkerHealth(workerId).ifPresent(health -> {
            stats.put("healthStatus", health.getStatus().name());
            stats.put("startTime", health.getStartTime().toString());
            stats.put("lastHeartbeat", health.getLastHeartbeat().toString());
            stats.put("healthProcessedJobs", health.getProcessedJobs());
            stats.put("healthFailedJobs", health.getFailedJobs());
        });
        
        return stats;
    }

    /**
     * Manual trigger for processing jobs (useful for testing)
     * 
     * @return number of jobs processed
     */
    public int processJobsManually() {
        log.info("Manual job processing triggered");
        int jobsProcessed = 0;
        
        try {
            // Process up to 10 jobs in manual mode
            for (int i = 0; i < 10; i++) {
                Optional<JobData> nextJob = jobService.getNextJobFromQueue("audio_processing");
                
                if (nextJob.isPresent()) {
                    processAudioJob(nextJob.get());
                    jobsProcessed++;
                } else {
                    break; // No more jobs in queue
                }
            }
            
        } catch (Exception e) {
            log.error("Error in manual job processing: {}", e.getMessage(), e);
        }
        
        log.info("Manual processing completed: {} jobs processed", jobsProcessed);
        return jobsProcessed;
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
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return content.length == 0;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public byte[] getBytes() throws IOException {
            return content;
        }

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
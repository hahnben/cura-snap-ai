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

    // Processing statistics
    private volatile long totalJobsProcessed = 0;
    private volatile long totalJobsFailed = 0;
    private volatile Instant lastProcessingTime = Instant.now();

    @Autowired
    public AudioJobWorker(JobService jobService, 
                         TranscriptionService transcriptionService,
                         NoteService noteService) {
        this.jobService = jobService;
        this.transcriptionService = transcriptionService;
        this.noteService = noteService;
    }

    /**
     * Scheduled method to process audio jobs from the queue
     * Runs every 5 seconds to check for new jobs
     */
    @Scheduled(fixedDelay = 5000) // 5 seconds
    public void processAudioJobs() {
        try {
            Optional<JobData> nextJob = jobService.getNextJobFromQueue("audio_processing");
            
            if (nextJob.isPresent()) {
                processAudioJob(nextJob.get());
            }
            
        } catch (Exception e) {
            log.error("Error in audio job worker: {}", e.getMessage(), e);
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
        
        log.info("Processing audio job {} for user {}", jobId, userId);
        
        try {
            // Mark job as started
            if (!jobService.markJobAsStarted(jobId)) {
                log.warn("Failed to mark job {} as started, skipping", jobId);
                return;
            }

            lastProcessingTime = Instant.now();

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
                log.info("Successfully completed audio job {} for user {} in {}ms", 
                        jobId, userId, result.get("processingTimeMs"));
            } else {
                log.error("Failed to update job status for completed job {}", jobId);
            }

        } catch (TranscriptionService.TranscriptionException e) {
            log.error("Transcription failed for job {}: {}", jobId, e.getMessage());
            failJob(jobId, "Transcription failed: " + e.getMessage());
            
        } catch (Exception e) {
            log.error("Unexpected error processing audio job {}: {}", jobId, e.getMessage(), e);
            failJob(jobId, "Processing failed: " + e.getMessage());
        }
    }

    /**
     * Mark job as failed and handle retry logic
     *
     * @param jobId the job ID
     * @param errorMessage the error message
     */
    private void failJob(String jobId, String errorMessage) {
        try {
            // Try to increment retry count first
            boolean retryScheduled = jobService.incrementRetryCount(jobId);
            
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
                    log.error("Job {} permanently failed after max retries: {}", jobId, errorMessage);
                } else {
                    log.error("Failed to mark job {} as failed", jobId);
                }
            } else {
                log.info("Job {} scheduled for retry due to: {}", jobId, errorMessage);
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
        stats.put("totalJobsProcessed", totalJobsProcessed);
        stats.put("totalJobsFailed", totalJobsFailed);
        stats.put("lastProcessingTime", lastProcessingTime.toString());
        stats.put("workerEnabled", true);
        stats.put("queueName", "audio_processing");
        
        // Add transcription service availability
        stats.put("transcriptionServiceAvailable", 
                transcriptionService.isTranscriptionServiceAvailable());
        
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
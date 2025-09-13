package ai.curasnap.backend.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * DTO for job data stored in Redis queues
 * Contains all information needed for async job processing
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class JobData {
    
    /**
     * Unique job identifier (UUID)
     */
    private String jobId;
    
    /**
     * Job type: TEXT_PROCESSING, AUDIO_PROCESSING, etc.
     */
    private JobType jobType;
    
    /**
     * Current job status
     */
    private JobStatus status;
    
    /**
     * User ID from JWT token
     */
    private String userId;
    
    /**
     * Input data for the job (varies by type)
     */
    private Map<String, Object> inputData;
    
    /**
     * Processing result (populated when completed)
     */
    private Map<String, Object> result;
    
    /**
     * Error message if job failed
     */
    private String errorMessage;
    
    /**
     * Job creation timestamp
     */
    private Instant createdAt;
    
    /**
     * Job start processing timestamp
     */
    private Instant startedAt;
    
    /**
     * Job completion timestamp
     */
    private Instant completedAt;
    
    /**
     * Number of retry attempts
     */
    @Builder.Default
    private int retryCount = 0;
    
    /**
     * Maximum retry attempts allowed
     */
    @Builder.Default
    private int maxRetries = 3;
    
    /**
     * Processing queue name
     */
    private String queueName;
    
    /**
     * Session ID (optional, for tracking related jobs)
     */
    private String sessionId;
    
    /**
     * Transcript ID (optional, for jobs that create transcripts)
     */
    private String transcriptId;
    
    /**
     * Job types supported by the system
     */
    public enum JobType {
        TEXT_PROCESSING("text_processing"),
        AUDIO_PROCESSING("audio_processing"),
        TRANSCRIPTION_ONLY("transcription_only"),
        CACHE_WARMING("cache_warming");
        
        private final String value;
        
        JobType(String value) {
            this.value = value;
        }
        
        public String getValue() {
            return value;
        }
    }
    
    /**
     * Job processing status
     */
    public enum JobStatus {
        QUEUED("queued"),
        PROCESSING("processing"), 
        COMPLETED("completed"),
        FAILED("failed"),
        CANCELLED("cancelled");
        
        private final String value;
        
        JobStatus(String value) {
            this.value = value;
        }
        
        public String getValue() {
            return value;
        }
        
        public boolean isTerminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED;
        }
    }
}
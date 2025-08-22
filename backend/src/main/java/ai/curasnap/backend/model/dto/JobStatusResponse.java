package ai.curasnap.backend.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * DTO for job status polling response
 * Contains current job status and results if completed
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class JobStatusResponse {
    
    /**
     * Job identifier
     */
    private String jobId;
    
    /**
     * Job type
     */
    private JobData.JobType jobType;
    
    /**
     * Current job status
     */
    private JobData.JobStatus status;
    
    /**
     * Job creation timestamp
     */
    private Instant createdAt;
    
    /**
     * Job processing start timestamp (null if not started)
     */
    private Instant startedAt;
    
    /**
     * Job completion timestamp (null if not completed)
     */
    private Instant completedAt;
    
    /**
     * Processing result (only populated when status is COMPLETED)
     * Structure varies by job type:
     * - TEXT_PROCESSING: {"noteResponse": {...}, "transcriptId": "..."}
     * - AUDIO_PROCESSING: {"noteResponse": {...}, "transcriptId": "...", "audioProcessingTime": 5432}
     */
    private Map<String, Object> result;
    
    /**
     * Error message (only populated when status is FAILED)
     */
    private String errorMessage;
    
    /**
     * Progress percentage (0-100, future enhancement)
     */
    private Integer progressPercentage;
    
    /**
     * Progress message for user feedback (future enhancement)
     * e.g., "Transcribing audio...", "Generating SOAP note...", etc.
     */
    private String progressMessage;
    
    /**
     * Number of retry attempts made
     */
    private int retryCount;
    
    /**
     * Maximum retry attempts allowed
     */
    private int maxRetries;
    
    /**
     * Processing time in milliseconds (populated when completed or failed)
     */
    private Long processingTimeMs;
    
    /**
     * Queue position (future enhancement)
     */
    private Integer queuePosition;
    
    /**
     * Estimated completion time (future enhancement)
     */
    private Instant estimatedCompletionAt;
}
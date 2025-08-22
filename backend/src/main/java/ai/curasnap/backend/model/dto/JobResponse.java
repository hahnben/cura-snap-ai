package ai.curasnap.backend.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * DTO for job creation response
 * Returned to client when a job is successfully queued
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class JobResponse {
    
    /**
     * Unique job identifier for status polling
     */
    private String jobId;
    
    /**
     * Job type that was requested
     */
    private JobData.JobType jobType;
    
    /**
     * Initial job status (usually QUEUED)
     */
    private JobData.JobStatus status;
    
    /**
     * Job creation timestamp
     */
    private Instant createdAt;
    
    /**
     * Estimated completion time (future enhancement)
     * Can be calculated based on queue length and processing time
     */
    private Instant estimatedCompletionAt;
    
    /**
     * Queue position (future enhancement)
     * Position in processing queue
     */
    private Integer queuePosition;
    
    /**
     * Status polling URL for convenience
     * Format: /api/v1/async/jobs/{jobId}
     */
    private String statusUrl;
    
    /**
     * Message for client information
     */
    private String message;
}
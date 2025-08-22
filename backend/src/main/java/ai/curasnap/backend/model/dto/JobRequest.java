package ai.curasnap.backend.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * DTO for creating new async jobs
 * Used by clients to request job processing
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class JobRequest {
    
    /**
     * Type of job to be processed
     */
    @NotNull(message = "Job type is required")
    private JobData.JobType jobType;
    
    /**
     * Input data for job processing
     * Structure varies by job type:
     * - TEXT_PROCESSING: {"textRaw": "...", "sessionId": "...", "transcriptId": "..."}
     * - AUDIO_PROCESSING: {"audioFileName": "...", "sessionId": "...", "audioSize": 123456}
     */
    @NotNull(message = "Input data is required")
    @Size(min = 1, message = "Input data cannot be empty")
    private Map<String, Object> inputData;
    
    /**
     * Optional session ID for tracking related jobs
     */
    private String sessionId;
    
    /**
     * Optional priority for job processing (future use)
     * Default priority is 0 (normal)
     */
    @Builder.Default
    private int priority = 0;
    
    /**
     * Optional custom timeout in milliseconds
     * If not specified, uses system default
     */
    private Long timeoutMs;
    
    /**
     * Optional custom retry count override
     * If not specified, uses system default (3)
     */
    private Integer maxRetries;
    
    /**
     * Optional metadata for job tracking
     * Can contain additional context information
     */
    private Map<String, String> metadata;
}
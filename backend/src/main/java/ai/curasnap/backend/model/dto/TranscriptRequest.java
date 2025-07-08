package ai.curasnap.backend.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

/**
 * DTO representing a request to create a new transcript.
 */
public class TranscriptRequest {

    /**
     * The raw input text or audio content.
     */
    @NotBlank(message = "Text content is required")
    @Size(max = 10000, message = "Text content must not exceed 10000 characters")
    private String textRaw;

    /**
     * The type of input (e.g., "text", "audio").
     */
    @NotBlank(message = "Input type is required")
    @Pattern(regexp = "^(text|audio)$", message = "Input type must be 'text' or 'audio'")
    private String inputType;

    /**
     * ID of the patient session this transcript belongs to.
     */
    @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$", 
             message = "Session ID must be a valid UUID format")
    private String sessionId;

    /**
     * Default constructor.
     */
    public TranscriptRequest() {
    }

    /**
     * Constructor with all fields.
     *
     * @param textRaw   raw input text
     * @param inputType type of input
     * @param sessionId related session ID
     */
    public TranscriptRequest(String textRaw, String inputType, String sessionId) {
        this.textRaw = textRaw;
        this.inputType = inputType;
        this.sessionId = sessionId;
    }

    public String getTextRaw() {
        return textRaw;
    }

    public void setTextRaw(String textRaw) {
        this.textRaw = textRaw;
    }

    public String getInputType() {
        return inputType;
    }

    public void setInputType(String inputType) {
        this.inputType = inputType;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
}
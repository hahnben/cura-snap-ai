package ai.curasnap.backend.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

/**
 * DTO representing a request to format a raw transcript into a structured medical note.
 */
public class NoteRequest {

    /**
     * The raw input text provided by the user.
     */
    @NotBlank(message = "Text content is required")
    @Size(max = 10000, message = "Text content must not exceed 10000 characters")
    private String textRaw;

    /**
     * ID of the related transcript.
     */
    @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$", 
             message = "Transcript ID must be a valid UUID format")
    private String transcriptId;

    /**
     * ID of the patient session this note belongs to.
     */
    @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$", 
             message = "Session ID must be a valid UUID format")
    private String sessionId;

    /**
     * Default constructor.
     */
    public NoteRequest() {
    }

    /**
     * Constructor with all fields.
     *
     * @param textRaw      raw input text
     * @param transcriptId related transcript ID
     * @param sessionId    related session ID
     */
    public NoteRequest(String textRaw, String transcriptId, String sessionId) {
        this.textRaw = textRaw;
        this.transcriptId = transcriptId;
        this.sessionId = sessionId;
    }

    public String getTextRaw() {
        return textRaw;
    }

    public void setTextRaw(String textRaw) {
        this.textRaw = textRaw;
    }

    public String getTranscriptId() {
        return transcriptId;
    }

    public void setTranscriptId(String transcriptId) {
        this.transcriptId = transcriptId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
}

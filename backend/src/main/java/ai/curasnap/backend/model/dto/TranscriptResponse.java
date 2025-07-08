package ai.curasnap.backend.model.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO representing a transcript response.
 */
public class TranscriptResponse {

    /**
     * The unique identifier of the transcript.
     */
    private UUID id;

    /**
     * The raw input text or audio content.
     */
    private String textRaw;

    /**
     * The type of input (e.g., "text", "audio").
     */
    private String inputType;

    /**
     * ID of the patient session this transcript belongs to.
     */
    private UUID sessionId;

    /**
     * The timestamp when the transcript was created.
     */
    private Instant createdAt;

    /**
     * Default constructor.
     */
    public TranscriptResponse() {
    }

    /**
     * Constructor with all fields.
     *
     * @param id        transcript ID
     * @param textRaw   raw input text
     * @param inputType type of input
     * @param sessionId related session ID
     * @param createdAt creation timestamp
     */
    public TranscriptResponse(UUID id, String textRaw, String inputType, UUID sessionId, Instant createdAt) {
        this.id = id;
        this.textRaw = textRaw;
        this.inputType = inputType;
        this.sessionId = sessionId;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
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

    public UUID getSessionId() {
        return sessionId;
    }

    public void setSessionId(UUID sessionId) {
        this.sessionId = sessionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
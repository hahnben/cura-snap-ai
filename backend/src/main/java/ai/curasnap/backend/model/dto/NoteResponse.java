package ai.curasnap.backend.model.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO representing a formatted medical note response.
 */
public class NoteResponse {

    /**
     * Unique identifier of the note.
     */
    private UUID id;

    /**
     * The raw input text that was formatted.
     */
    private String textRaw;

    /**
     * The structured output text (e.g., in SOAP format).
     */
    private String textStructured;

    /**
     * Timestamp of when the note was created.
     */
    private Instant createdAt;

    /**
     * Default constructor.
     */
    public NoteResponse() {
    }

    /**
     * Full constructor for creating a response.
     *
     * @param id              note ID
     * @param textRaw         original input
     * @param textStructured  structured version
     * @param createdAt       creation time
     */
    public NoteResponse(UUID id, String textRaw, String textStructured, Instant createdAt) {
        this.id = id;
        this.textRaw = textRaw;
        this.textStructured = textStructured;
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

    public String getTextStructured() {
        return textStructured;
    }

    public void setTextStructured(String textStructured) {
        this.textStructured = textStructured;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

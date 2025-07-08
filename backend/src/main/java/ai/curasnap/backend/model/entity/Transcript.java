package ai.curasnap.backend.model.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents a transcript - the raw input (text or audio) from a user session.
 */
@Entity
@Table(name = "transcript")
public class Transcript {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "session_id", nullable = true)
    private UUID sessionId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "input_type", nullable = false)
    private String inputType;

    @Column(name = "text_raw", columnDefinition = "TEXT", nullable = false)
    private String textRaw;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    // Getters and setters

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getInputType() { return inputType; }
    public void setInputType(String inputType) { this.inputType = inputType; }

    public String getTextRaw() { return textRaw; }
    public void setTextRaw(String textRaw) { this.textRaw = textRaw; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
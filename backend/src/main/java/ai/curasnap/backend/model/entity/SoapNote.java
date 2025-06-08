package ai.curasnap.backend.model.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents a structured SOAP medical note generated from a transcript.
 */
@Entity
@Table(name = "soap_note") 
public class SoapNote {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "transcript_id", nullable = true)
    private UUID transcriptId;

    @Column(name = "session_id", nullable = true)
    private UUID sessionId;

    @Column(name = "text_structured", columnDefinition = "TEXT") // later JSONB
    private String textStructured;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    // Getters and setters

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public UUID getTranscriptId() { return transcriptId; }
    public void setTranscriptId(UUID transcriptId) { this.transcriptId = transcriptId; }

    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }

    public String getTextStructured() { return textStructured; }
    public void setTextStructured(String textStructured) { this.textStructured = textStructured; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

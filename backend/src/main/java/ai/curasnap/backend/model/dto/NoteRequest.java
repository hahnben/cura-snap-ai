package ai.curasnap.backend.model.dto;

/**
 * DTO representing a request to format a raw transcript into a structured medical note.
 */
public class NoteRequest {

    /**
     * The raw input text provided by the user.
     */
    private String textRaw;

    /**
     * ID of the related transcript.
     */
    private String transcriptId;

    /**
     * ID of the patient session this note belongs to.
     */
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

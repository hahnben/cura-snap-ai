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
     * Default constructor.
     */
    public NoteRequest() {
    }

    /**
     * Constructor with field.
     *
     * @param textRaw the raw transcript or unstructured text
     */
    public NoteRequest(String textRaw) {
        this.textRaw = textRaw;
    }

    /**
     * Returns the raw input text.
     *
     * @return raw transcript
     */
    public String getTextRaw() {
        return textRaw;
    }

    /**
     * Sets the raw input text.
     *
     * @param textRaw transcript to be formatted
     */
    public void setTextRaw(String textRaw) {
        this.textRaw = textRaw;
    }
}

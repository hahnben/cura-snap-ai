package ai.curasnap.backend.service.exception;

/**
 * Custom exception for TranscriptService operations.
 * Used to prevent information disclosure through generic exception messages.
 */
public class TranscriptServiceException extends RuntimeException {

    public TranscriptServiceException(String message) {
        super(message);
    }

    public TranscriptServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
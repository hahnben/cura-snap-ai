package ai.curasnap.backend.service;

import ai.curasnap.backend.model.entity.Transcript;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Interface for handling business logic related to transcripts.
 */
public interface TranscriptService {

    /**
     * Creates and persists a new transcript.
     *
     * @param userId     the authenticated user's ID
     * @param sessionId  the session ID (optional)
     * @param inputType  the type of input (e.g., "text", "audio")
     * @param textRaw    the raw text content
     * @return the created transcript
     */
    Transcript createTranscript(UUID userId, UUID sessionId, String inputType, String textRaw);

    /**
     * Returns all transcripts associated with the authenticated user.
     *
     * @param userId the authenticated user's ID
     * @return a list of transcripts
     */
    List<Transcript> getTranscriptsByUser(UUID userId);

    /**
     * Returns all transcripts associated with a specific session.
     *
     * @param sessionId the session ID
     * @return a list of transcripts
     */
    List<Transcript> getTranscriptsBySession(UUID sessionId);

    /**
     * Returns all transcripts associated with a specific session for a user.
     * This method ensures the user has access to the session.
     *
     * @param sessionId the session ID
     * @param userId the user ID to verify session access
     * @return a list of transcripts
     */
    List<Transcript> getTranscriptsBySessionAndUser(UUID sessionId, UUID userId);

    /**
     * Returns all transcripts for a user, ordered by creation date (newest first).
     * Optimized for chronological display.
     *
     * @param userId the authenticated user's ID
     * @return a list of transcripts, chronologically ordered
     */
    List<Transcript> getTranscriptsChronological(UUID userId);

    /**
     * Returns all transcripts for a user within a specific date range.
     * Useful for generating reports or filtering by time period.
     *
     * @param userId the authenticated user's ID
     * @param startDate the start of the date range (inclusive)
     * @param endDate the end of the date range (inclusive)
     * @return a list of transcripts within the date range
     */
    List<Transcript> getTranscriptsByDateRange(UUID userId, Instant startDate, Instant endDate);

    /**
     * Returns transcripts by input type for analytics purposes.
     *
     * @param userId the authenticated user's ID
     * @param inputType the input type (e.g., "audio", "text")
     * @return a list of transcripts of the specified type
     */
    List<Transcript> getTranscriptsByInputType(UUID userId, String inputType);
}
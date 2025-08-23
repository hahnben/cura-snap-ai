package ai.curasnap.backend.service;

import ai.curasnap.backend.model.dto.NoteRequest;
import ai.curasnap.backend.model.dto.NoteResponse;

import java.time.Instant;
import java.util.List;

/**
 * Interface for handling business logic related to medical notes.
 */
public interface NoteService {

    /**
     * Formats a raw text input into a structured note (e.g., SOAP format).
     *
     * @param userId  the authenticated user's ID
     * @param request the raw input text from the client
     * @return a structured note response
     */
    NoteResponse formatNote(String userId, NoteRequest request);

    /**
     * Returns all structured notes associated with the authenticated user.
     *
     * @param userId the authenticated user's ID
     * @return a list of structured notes
     */
    List<NoteResponse> getNotes(String userId);

    /**
     * Returns all structured notes for a user, ordered by creation date (newest first).
     * Optimized for chronological display.
     *
     * @param userId the authenticated user's ID
     * @return a list of structured notes, chronologically ordered
     */
    List<NoteResponse> getNotesChronological(String userId);

    /**
     * Returns all structured notes for a user within a specific date range.
     * Useful for generating reports or filtering by time period.
     *
     * @param userId the authenticated user's ID
     * @param startDate the start of the date range (inclusive)
     * @param endDate the end of the date range (inclusive)
     * @return a list of structured notes within the date range
     */
    List<NoteResponse> getNotesByDateRange(String userId, Instant startDate, Instant endDate);
}

package ai.curasnap.backend.service;

import ai.curasnap.backend.model.dto.NoteRequest;
import ai.curasnap.backend.model.dto.NoteResponse;

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
}

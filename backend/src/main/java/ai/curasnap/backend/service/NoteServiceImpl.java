package ai.curasnap.backend.service;

import ai.curasnap.backend.model.dto.NoteRequest;
import ai.curasnap.backend.model.dto.NoteResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Dummy implementation of NoteService.
 * This version does not interact with any real AI service or database.
 */
@Service
public class NoteServiceImpl implements NoteService {

    // Classic SLF4J logger declaration
    private static final Logger logger = LoggerFactory.getLogger(NoteServiceImpl.class);

    /**
     * Simulates formatting of a raw note using a placeholder logic.
     *
     * @param userId  the authenticated user's ID
     * @param request the raw input text from the client
     * @return a formatted note with dummy content
     */
    @Override
    public NoteResponse formatNote(String userId, NoteRequest request) {
        logger.info("Formatting note for user {}", userId);

        // Create dummy structured note using a static SOAP template
        String dummySoap = """
                S: %s
                O: (objective findings placeholder)
                A: (assessment placeholder)
                P: (plan placeholder)
                """.formatted(request.getTextRaw());

        NoteResponse response = new NoteResponse(
                UUID.randomUUID(),
                request.getTextRaw(),
                dummySoap,
                Instant.now()
        );

        logger.debug("Generated note: {}", response.getTextStructured());
        return response;
    }

    /**
     * Returns a hard-coded list of notes as placeholder.
     *
     * @param userId the authenticated user's ID
     * @return a list with one dummy note
     */
    @Override
    public List<NoteResponse> getNotes(String userId) {
        logger.info("Fetching notes for user {}", userId);

        NoteResponse dummyNote = new NoteResponse(
                UUID.randomUUID(),
                "Example input text from user.",
                """
                S: Example input text from user.
                O: (objective findings placeholder)
                A: (assessment placeholder)
                P: (plan placeholder)
                """,
                Instant.now()
        );

        logger.debug("Returning dummy note for user {}: {}", userId, dummyNote.getTextStructured());
        return List.of(dummyNote);
    }
}

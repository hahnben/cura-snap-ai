package ai.curasnap.backend.service;

import ai.curasnap.backend.model.dto.NoteRequest;
import ai.curasnap.backend.model.dto.NoteResponse;
import ai.curasnap.backend.model.entity.SoapNote;
import ai.curasnap.backend.repository.SoapNoteRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Default implementation of the NoteService.
 *
 * This service is responsible for:
 * <ul>
 *     <li>Formatting raw medical notes into a structured SOAP format (currently dummy logic)</li>
 *     <li>Persisting the formatted note in the Supabase database</li>
 * </ul>
 *
 * In a later phase, the formatting logic will be delegated to a Python-based AI service.
 */
@Service
public class NoteServiceImpl implements NoteService {

    private static final Logger logger = LoggerFactory.getLogger(NoteServiceImpl.class);

    private final SoapNoteRepository soapNoteRepository;
    private final AgentServiceClient agentServiceClient;

    /**
     * Constructs the service with a reference to the SoapNoteRepository and AgentServiceClient.
     *
     * @param soapNoteRepository repository used for persisting structured SOAP notes
     * @param agentServiceClient client for communicating with the Agent Service
     */
    @Autowired
    public NoteServiceImpl(SoapNoteRepository soapNoteRepository, AgentServiceClient agentServiceClient) {
        this.soapNoteRepository = soapNoteRepository;
        this.agentServiceClient = agentServiceClient;
    }

    /**
     * Formats a raw transcript using the Agent Service or falls back to dummy placeholders.
     *
     * @param userId  the authenticated user's ID
     * @param request the raw note content from the client
     * @return a NoteResponse containing the formatted note
     */
    @Override
    public NoteResponse formatNote(String userId, NoteRequest request) {
        logger.info("Formatting and saving note for user {}", userId);

        String structuredText;
        
        // Try to use the Agent Service first
        if (agentServiceClient.isAgentServiceAvailable()) {
            logger.debug("Attempting to use Agent Service for SOAP note generation");
            structuredText = agentServiceClient.formatTranscriptToSoap(request.getTextRaw());
        } else {
            structuredText = null;
        }
        
        // Fallback to dummy SOAP structure if Agent Service is unavailable
        if (structuredText == null) {
            logger.debug("Agent Service unavailable, using dummy SOAP structure");
            structuredText = """
                    S: %s
                    O: (objective findings placeholder)
                    A: (assessment placeholder)
                    P: (plan placeholder)
                    """.formatted(request.getTextRaw());
        }

        Instant now = Instant.now();

        SoapNote note = new SoapNote();
        note.setUserId(UUID.fromString(userId));
        note.setTextStructured(structuredText);
        note.setCreatedAt(now);

        // Currently no transcriptId or sessionId is available in request → leave null (DB must allow it)
        note.setTranscriptId(null);
        note.setSessionId(null);

        soapNoteRepository.save(note);
        logger.debug("Persisted SoapNote with ID {}", note.getId());

        return new NoteResponse(
                note.getId(),
                request.getTextRaw(),
                structuredText,
                now
        );
    }

    /**
     * Returns a hard-coded dummy note for testing purposes.
     *
     * @param userId the authenticated user's ID
     * @return a list containing one static example note
     */
    @Override
    public List<NoteResponse> getNotes(String userId) {
        logger.info("Fetching dummy notes for user {}", userId);

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

        logger.debug("Returning dummy note: {}", dummyNote.getTextStructured());
        return List.of(dummyNote);
    }
}

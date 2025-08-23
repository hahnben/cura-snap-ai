package ai.curasnap.backend.service;

import ai.curasnap.backend.model.dto.NoteRequest;
import ai.curasnap.backend.model.dto.NoteResponse;
import ai.curasnap.backend.model.entity.SoapNote;
import ai.curasnap.backend.model.entity.Transcript;
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
    private final AgentServiceClientInterface agentServiceClient;
    private final TranscriptService transcriptService;

    /**
     * Constructs the service with a reference to the SoapNoteRepository, AgentServiceClient, and TranscriptService.
     *
     * @param soapNoteRepository repository used for persisting structured SOAP notes
     * @param agentServiceClient client for communicating with the Agent Service (may be cached or direct)
     * @param transcriptService service for managing transcripts
     */
    @Autowired
    public NoteServiceImpl(SoapNoteRepository soapNoteRepository, AgentServiceClientInterface agentServiceClient, TranscriptService transcriptService) {
        this.soapNoteRepository = soapNoteRepository;
        this.agentServiceClient = agentServiceClient;
        this.transcriptService = transcriptService;
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
        logger.info("Formatting and saving note for authenticated user");

        UUID userUuid = UUID.fromString(userId);
        UUID sessionId = null;
        
        // Parse sessionId if provided
        if (request.getSessionId() != null && !request.getSessionId().isEmpty()) {
            try {
                sessionId = UUID.fromString(request.getSessionId());
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid sessionId format provided");
                // Continue with null sessionId - this is not a fatal error
            }
        }

        // Create transcript record
        Transcript transcript = transcriptService.createTranscript(
                userUuid, 
                sessionId, 
                "text", 
                request.getTextRaw()
        );

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
        note.setUserId(userUuid);
        note.setTranscriptId(transcript.getId());
        note.setSessionId(sessionId);
        note.setTextStructured(structuredText);
        note.setCreatedAt(now);

        soapNoteRepository.save(note);
        logger.debug("Persisted SoapNote successfully linked to transcript");

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
        logger.info("Fetching notes for authenticated user");

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

    /**
     * Returns all structured notes for a user, ordered chronologically (newest first).
     * Uses optimized database query with idx_soap_note_user_created index.
     */
    @Override
    public List<NoteResponse> getNotesChronological(String userId) {
        logger.info("Fetching notes for user in chronological order");
        UUID userUuid = UUID.fromString(userId);
        
        List<SoapNote> notes = soapNoteRepository.findByUserIdOrderByCreatedAtDesc(userUuid);
        return notes.stream()
                .map(this::convertToNoteResponse)
                .toList();
    }

    /**
     * Returns all structured notes for a user within a date range.
     * Uses optimized database query with idx_soap_note_user_created index.
     */
    @Override
    public List<NoteResponse> getNotesByDateRange(String userId, Instant startDate, Instant endDate) {
        logger.info("Fetching notes for user within date range: {} to {}", startDate, endDate);
        UUID userUuid = UUID.fromString(userId);
        
        List<SoapNote> notes = soapNoteRepository.findByUserIdAndCreatedAtBetween(userUuid, startDate, endDate);
        return notes.stream()
                .map(this::convertToNoteResponse)
                .toList();
    }

    /**
     * Converts a SoapNote entity to a NoteResponse DTO.
     * Helper method to avoid code duplication.
     */
    private NoteResponse convertToNoteResponse(SoapNote note) {
        return new NoteResponse(
                note.getId(),
                "", // textRaw not stored in SoapNote, could be retrieved from linked Transcript if needed
                note.getTextStructured(),
                note.getCreatedAt()
        );
    }
}

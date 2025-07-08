package ai.curasnap.backend.service;

import ai.curasnap.backend.model.entity.Transcript;
import ai.curasnap.backend.repository.TranscriptRepository;
import ai.curasnap.backend.service.exception.TranscriptServiceException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Default implementation of the TranscriptService.
 *
 * This service is responsible for:
 * <ul>
 *     <li>Creating and persisting transcript records</li>
 *     <li>Retrieving transcripts by user or session</li>
 * </ul>
 */
@Service
public class TranscriptServiceImpl implements TranscriptService {

    private static final Logger logger = LoggerFactory.getLogger(TranscriptServiceImpl.class);

    private final TranscriptRepository transcriptRepository;

    /**
     * Constructs the service with a reference to the TranscriptRepository.
     *
     * @param transcriptRepository repository used for persisting transcripts
     */
    @Autowired
    public TranscriptServiceImpl(TranscriptRepository transcriptRepository) {
        this.transcriptRepository = transcriptRepository;
    }

    /**
     * Creates and persists a new transcript.
     *
     * @param userId     the authenticated user's ID
     * @param sessionId  the session ID (optional)
     * @param inputType  the type of input (e.g., "text", "audio")
     * @param textRaw    the raw text content
     * @return the created transcript
     */
    @Override
    public Transcript createTranscript(UUID userId, UUID sessionId, String inputType, String textRaw) {
        logger.info("Creating transcript with inputType {}", inputType);

        try {
            Transcript transcript = new Transcript();
            transcript.setUserId(userId);
            transcript.setSessionId(sessionId);
            transcript.setInputType(inputType);
            transcript.setTextRaw(textRaw);
            transcript.setCreatedAt(Instant.now());

            Transcript savedTranscript = transcriptRepository.save(transcript);
            logger.debug("Persisted transcript successfully");

            return savedTranscript;
        } catch (DataAccessException e) {
            logger.error("Failed to create transcript", e);
            throw new TranscriptServiceException("Unable to create transcript at this time");
        } catch (Exception e) {
            logger.error("Unexpected error during transcript creation", e);
            throw new TranscriptServiceException("Service temporarily unavailable");
        }
    }

    /**
     * Returns all transcripts associated with the authenticated user.
     *
     * @param userId the authenticated user's ID
     * @return a list of transcripts
     */
    @Override
    public List<Transcript> getTranscriptsByUser(UUID userId) {
        logger.info("Fetching transcripts for authenticated user");
        return transcriptRepository.findAllByUserId(userId);
    }

    /**
     * Returns all transcripts associated with a specific session.
     * Note: This method should only be called after verifying that the user has access to the session.
     *
     * @param sessionId the session ID
     * @return a list of transcripts
     */
    @Override
    public List<Transcript> getTranscriptsBySession(UUID sessionId) {
        logger.info("Fetching transcripts for session");
        return transcriptRepository.findAllBySessionId(sessionId);
    }

    /**
     * Returns all transcripts associated with a specific session for a user.
     * This method ensures the user has access to the session by filtering by both sessionId and userId.
     *
     * @param sessionId the session ID
     * @param userId the user ID to verify session access
     * @return a list of transcripts
     */
    @Override
    public List<Transcript> getTranscriptsBySessionAndUser(UUID sessionId, UUID userId) {
        logger.info("Fetching transcripts for session with user authorization");
        return transcriptRepository.findAllBySessionId(sessionId)
                .stream()
                .filter(transcript -> transcript.getUserId().equals(userId))
                .toList();
    }
}
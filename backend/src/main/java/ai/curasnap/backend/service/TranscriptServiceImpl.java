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
    private final DatabasePerformanceMetricsService metricsService;

    /**
     * Constructs the service with a reference to the TranscriptRepository and metrics service.
     *
     * @param transcriptRepository repository used for persisting transcripts
     * @param metricsService service for recording performance metrics
     */
    @Autowired
    public TranscriptServiceImpl(TranscriptRepository transcriptRepository, 
                                DatabasePerformanceMetricsService metricsService) {
        this.transcriptRepository = transcriptRepository;
        this.metricsService = metricsService;
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
     * OPTIMIZATION: Uses database composite query instead of stream filtering for better performance.
     * Utilizes idx_transcript_session_user composite index.
     * INSTRUMENTED: Records performance metrics for composite query monitoring.
     *
     * @param sessionId the session ID
     * @param userId the user ID to verify session access
     * @return a list of transcripts
     */
    @Override
    public List<Transcript> getTranscriptsBySessionAndUser(UUID sessionId, UUID userId) {
        logger.info("Fetching transcripts for session with user authorization using optimized query");
        
        return metricsService.timeQuery(
            DatabasePerformanceMetricsService.QueryType.COMPOSITE,
            () -> transcriptRepository.findBySessionIdAndUserId(sessionId, userId)
        );
    }

    /**
     * Returns all transcripts for a user, ordered chronologically (newest first).
     * Uses optimized database query with idx_transcript_user_created index.
     * INSTRUMENTED: Records performance metrics for monitoring.
     */
    @Override
    public List<Transcript> getTranscriptsChronological(UUID userId) {
        logger.info("Fetching transcripts for user in chronological order");
        
        return metricsService.timeQuery(
            DatabasePerformanceMetricsService.QueryType.USER,
            () -> transcriptRepository.findByUserIdOrderByCreatedAtDesc(userId)
        );
    }

    /**
     * Returns all transcripts for a user within a date range.
     * Uses optimized database query with idx_transcript_user_created index.
     */
    @Override
    public List<Transcript> getTranscriptsByDateRange(UUID userId, Instant startDate, Instant endDate) {
        logger.info("Fetching transcripts for user within date range: {} to {}", startDate, endDate);
        return transcriptRepository.findByUserIdAndCreatedAtBetween(userId, startDate, endDate);
    }

    /**
     * Returns transcripts by input type for analytics.
     * Uses optimized database query with user filtering for security.
     */
    @Override
    public List<Transcript> getTranscriptsByInputType(UUID userId, String inputType) {
        logger.info("Fetching transcripts for user by input type: {}", inputType);
        return transcriptRepository.findByUserIdAndInputType(userId, inputType);
    }
}
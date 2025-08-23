package ai.curasnap.backend.repository;

import ai.curasnap.backend.model.entity.Transcript;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Provides CRUD operations for Transcript entities.
 * Spring automatically implements this interface at runtime.
 */
@Repository
public interface TranscriptRepository extends JpaRepository<Transcript, UUID> {

    /**
     * Returns all transcripts belonging to the specified user.
     *
     * @param userId the Supabase user ID (from JWT)
     * @return list of transcripts for that user
     */
    List<Transcript> findAllByUserId(UUID userId);

    /**
     * Returns all transcripts belonging to the specified session.
     *
     * @param sessionId the session ID
     * @return list of transcripts for that session
     */
    List<Transcript> findAllBySessionId(UUID sessionId);

    /**
     * Returns all transcripts belonging to the specified user, ordered by creation date (newest first).
     * Optimized with idx_transcript_user_created index.
     *
     * @param userId the Supabase user ID (from JWT)
     * @return list of transcripts for that user, chronologically ordered
     */
    List<Transcript> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /**
     * Returns all transcripts belonging to the specified user within a date range.
     * Optimized with idx_transcript_user_created index.
     *
     * @param userId the Supabase user ID (from JWT)
     * @param startDate the start of the date range (inclusive)
     * @param endDate the end of the date range (inclusive)
     * @return list of transcripts for that user within the date range
     */
    List<Transcript> findByUserIdAndCreatedAtBetween(UUID userId, Instant startDate, Instant endDate);

    /**
     * Returns all transcripts belonging to the specified session and user.
     * Optimized with idx_transcript_session_user composite index.
     * Replaces stream filtering in service layer for better performance.
     *
     * @param sessionId the session ID
     * @param userId the Supabase user ID (from JWT) for authorization
     * @return list of transcripts for that session and user
     */
    List<Transcript> findBySessionIdAndUserId(UUID sessionId, UUID userId);

    /**
     * Returns recent transcripts across all users (for admin/analytics purposes).
     * Optimized with idx_transcript_created_at index.
     *
     * @param since the timestamp after which to fetch transcripts
     * @return list of recent transcripts
     */
    List<Transcript> findByCreatedAtAfterOrderByCreatedAtDesc(Instant since);

    /**
     * Returns transcripts by input type for analytics.
     * Combined with user filtering for security.
     *
     * @param userId the Supabase user ID (from JWT)
     * @param inputType the input type (e.g., "audio", "text")
     * @return list of transcripts of the specified type for that user
     */
    List<Transcript> findByUserIdAndInputType(UUID userId, String inputType);
}
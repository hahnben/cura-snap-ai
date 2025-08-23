package ai.curasnap.backend.repository;

import ai.curasnap.backend.model.entity.SoapNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Provides CRUD operations for SoapNote entities.
 * Spring automatically implements this interface at runtime.
 */
@Repository
public interface SoapNoteRepository extends JpaRepository<SoapNote, UUID> {

    /**
     * Returns all notes belonging to the specified user.
     *
     * @param userId the Supabase user ID (from JWT)
     * @return list of SOAP notes for that user
     */
    List<SoapNote> findAllByUserId(UUID userId);

    /**
     * Returns all notes belonging to the specified user, ordered by creation date (newest first).
     * Optimized with idx_soap_note_user_created index.
     *
     * @param userId the Supabase user ID (from JWT)
     * @return list of SOAP notes for that user, chronologically ordered
     */
    List<SoapNote> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /**
     * Returns all notes belonging to the specified user within a date range.
     * Optimized with idx_soap_note_user_created index.
     *
     * @param userId the Supabase user ID (from JWT)
     * @param startDate the start of the date range (inclusive)
     * @param endDate the end of the date range (inclusive)
     * @return list of SOAP notes for that user within the date range
     */
    List<SoapNote> findByUserIdAndCreatedAtBetween(UUID userId, Instant startDate, Instant endDate);

    /**
     * Returns all notes belonging to the specified session and user.
     * Optimized with idx_soap_note_session_user composite index.
     * Replaces stream filtering in service layer.
     *
     * @param sessionId the session ID
     * @param userId the Supabase user ID (from JWT) for authorization
     * @return list of SOAP notes for that session and user
     */
    List<SoapNote> findBySessionIdAndUserId(UUID sessionId, UUID userId);

    /**
     * Returns recent notes across all users (for admin/analytics purposes).
     * Optimized with idx_soap_note_created_at index.
     *
     * @param since the timestamp after which to fetch notes
     * @return list of recent SOAP notes
     */
    List<SoapNote> findByCreatedAtAfterOrderByCreatedAtDesc(Instant since);
}


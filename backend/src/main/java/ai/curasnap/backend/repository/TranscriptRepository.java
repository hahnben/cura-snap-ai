package ai.curasnap.backend.repository;

import ai.curasnap.backend.model.entity.Transcript;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
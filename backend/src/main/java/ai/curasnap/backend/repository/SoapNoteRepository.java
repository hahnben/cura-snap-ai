package ai.curasnap.backend.repository;

import ai.curasnap.backend.model.entity.SoapNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}


package ai.curasnap.backend.service;

import ai.curasnap.backend.model.dto.NoteRequest;
import ai.curasnap.backend.model.dto.NoteResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for NoteServiceImpl.
 */
class NoteServiceImplTest {

    private final NoteServiceImpl noteService = new NoteServiceImpl();

    @Test
    void shouldFormatNoteWithValidInput() {
        // Arrange
        String userId = "test-user-id";
        NoteRequest request = new NoteRequest();
        request.setTextRaw("Patient klagt über Rückenschmerzen seit zwei Tagen.");

        // Act
        NoteResponse response = noteService.formatNote(userId, request);

        // Assert
        assertNotNull(response);
        assertEquals("Patient klagt über Rückenschmerzen seit zwei Tagen.", response.getTextRaw());
        assertTrue(response.getTextStructured().contains("S: Patient klagt über Rückenschmerzen"));
        assertTrue(response.getTextStructured().contains("A:"));
        assertNotNull(response.getCreatedAt());
        assertNotNull(response.getId());
    }

    @Test
    void shouldReturnDummyNoteList() {
        // Arrange
        String userId = "test-user-id";

        // Act
        List<NoteResponse> result = noteService.getNotes(userId);

        // Assert
        assertNotNull(result);
        assertEquals(1, result.size());

        NoteResponse note = result.get(0);
        assertEquals("Example input text from user.", note.getTextRaw());
        assertTrue(note.getTextStructured().contains("S: Example input text from user."));
        assertNotNull(note.getId());
        assertNotNull(note.getCreatedAt());
    }
}

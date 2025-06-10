package ai.curasnap.backend.service;

import ai.curasnap.backend.model.dto.NoteRequest;
import ai.curasnap.backend.model.dto.NoteResponse;
import ai.curasnap.backend.model.entity.SoapNote;
import ai.curasnap.backend.repository.SoapNoteRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for NoteServiceImpl.
 */
@ExtendWith(MockitoExtension.class)
class NoteServiceImplTest {

    @Mock
    private SoapNoteRepository soapNoteRepository;

    @InjectMocks
    private NoteServiceImpl noteService;

    /**
     * Tests the formatNote method with valid input data.
     * Verifies that a note is correctly structured and persisted.
     */
    @Test
    void shouldFormatAndPersistNoteWithValidInput() {
        // Arrange
        String userId = UUID.randomUUID().toString();
        UUID transcriptId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        NoteRequest request = new NoteRequest();
        request.setTextRaw("Patient reports dizziness.");
        request.setTranscriptId(transcriptId.toString());
        request.setSessionId(sessionId.toString());

        // Mock the repository to return the same object it receives
     // Mock the repository to simulate JPA setting the ID
        when(soapNoteRepository.save(any(SoapNote.class)))
            .thenAnswer(invocation -> {
                SoapNote saved = invocation.getArgument(0);
                saved.setId(UUID.randomUUID()); // simulate generated ID from DB
                return saved;
            });


        // Act
        NoteResponse result = noteService.formatNote(userId, request);

        // Assert
        assertNotNull(result);
        assertEquals("Patient reports dizziness.", result.getTextRaw());
        assertTrue(result.getTextStructured().contains("S: Patient reports dizziness."));
        assertNotNull(result.getCreatedAt());
        assertNotNull(result.getId());

        // Verify that the repository's save method was called
        verify(soapNoteRepository, times(1)).save(any(SoapNote.class));
    }
}

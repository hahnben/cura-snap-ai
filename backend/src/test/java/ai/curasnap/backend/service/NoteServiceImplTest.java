package ai.curasnap.backend.service;

import ai.curasnap.backend.model.dto.NoteRequest;
import ai.curasnap.backend.model.dto.NoteResponse;
import ai.curasnap.backend.model.entity.SoapNote;
import ai.curasnap.backend.model.entity.Transcript;
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

    @Mock
    private AgentServiceClient agentServiceClient;

    @Mock
    private TranscriptService transcriptService;

    @InjectMocks
    private NoteServiceImpl noteService;

    /**
     * Tests the formatNote method with Agent Service enabled and working.
     * Verifies that Agent Service is used for SOAP note generation.
     */
    @Test
    void shouldFormatAndPersistNoteWithAgentService() {
        // Arrange
        String userId = UUID.randomUUID().toString();
        UUID transcriptId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        NoteRequest request = new NoteRequest();
        request.setTextRaw("Patient reports dizziness.");
        request.setTranscriptId(transcriptId.toString());
        request.setSessionId(sessionId.toString());

        String agentServiceResponse = "ANAMNESE:\nPatient reports dizziness.\n\nUNTERSUCHUNG:\n...\n\nBEURTEILUNG:\n...\n\nTHERAPIE:\n...";

        // Mock TranscriptService
        Transcript mockTranscript = new Transcript();
        mockTranscript.setId(transcriptId);
        mockTranscript.setUserId(UUID.fromString(userId));
        mockTranscript.setSessionId(sessionId);
        mockTranscript.setInputType("text");
        mockTranscript.setTextRaw("Patient reports dizziness.");
        when(transcriptService.createTranscript(UUID.fromString(userId), sessionId, "text", "Patient reports dizziness."))
            .thenReturn(mockTranscript);

        // Mock Agent Service
        when(agentServiceClient.isAgentServiceAvailable()).thenReturn(true);
        when(agentServiceClient.formatTranscriptToSoap("Patient reports dizziness.")).thenReturn(agentServiceResponse);

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
        assertEquals(agentServiceResponse, result.getTextStructured());
        assertNotNull(result.getCreatedAt());
        assertNotNull(result.getId());

        // Verify that services were called correctly
        verify(transcriptService, times(1)).createTranscript(UUID.fromString(userId), sessionId, "text", "Patient reports dizziness.");
        verify(agentServiceClient, times(1)).isAgentServiceAvailable();
        verify(agentServiceClient, times(1)).formatTranscriptToSoap("Patient reports dizziness.");
        verify(soapNoteRepository, times(1)).save(any(SoapNote.class));
    }

    /**
     * Tests the formatNote method with Agent Service unavailable.
     * Verifies that fallback to dummy SOAP structure works.
     */
    @Test
    void shouldFallbackToDummySoapWhenAgentServiceUnavailable() {
        // Arrange
        String userId = UUID.randomUUID().toString();
        UUID transcriptId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        NoteRequest request = new NoteRequest();
        request.setTextRaw("Patient reports dizziness.");
        request.setTranscriptId(transcriptId.toString());
        request.setSessionId(sessionId.toString());

        // Mock TranscriptService
        Transcript mockTranscript = new Transcript();
        mockTranscript.setId(transcriptId);
        mockTranscript.setUserId(UUID.fromString(userId));
        mockTranscript.setSessionId(sessionId);
        mockTranscript.setInputType("text");
        mockTranscript.setTextRaw("Patient reports dizziness.");
        when(transcriptService.createTranscript(UUID.fromString(userId), sessionId, "text", "Patient reports dizziness."))
            .thenReturn(mockTranscript);

        // Mock Agent Service as unavailable
        when(agentServiceClient.isAgentServiceAvailable()).thenReturn(false);

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
        assertTrue(result.getTextStructured().contains("O: (objective findings placeholder)"));
        assertNotNull(result.getCreatedAt());
        assertNotNull(result.getId());

        // Verify that services were called correctly
        verify(transcriptService, times(1)).createTranscript(UUID.fromString(userId), sessionId, "text", "Patient reports dizziness.");
        verify(agentServiceClient, times(1)).isAgentServiceAvailable();
        verify(agentServiceClient, never()).formatTranscriptToSoap(any());
        verify(soapNoteRepository, times(1)).save(any(SoapNote.class));
    }

    /**
     * Tests the formatNote method when Agent Service returns null.
     * Verifies that fallback to dummy SOAP structure works.
     */
    @Test
    void shouldFallbackToDummySoapWhenAgentServiceReturnsNull() {
        // Arrange
        String userId = UUID.randomUUID().toString();
        UUID transcriptId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        NoteRequest request = new NoteRequest();
        request.setTextRaw("Patient reports dizziness.");
        request.setTranscriptId(transcriptId.toString());
        request.setSessionId(sessionId.toString());

        // Mock TranscriptService
        Transcript mockTranscript = new Transcript();
        mockTranscript.setId(transcriptId);
        mockTranscript.setUserId(UUID.fromString(userId));
        mockTranscript.setSessionId(sessionId);
        mockTranscript.setInputType("text");
        mockTranscript.setTextRaw("Patient reports dizziness.");
        when(transcriptService.createTranscript(UUID.fromString(userId), sessionId, "text", "Patient reports dizziness."))
            .thenReturn(mockTranscript);

        // Mock Agent Service as available but returning null (e.g., network error)
        when(agentServiceClient.isAgentServiceAvailable()).thenReturn(true);
        when(agentServiceClient.formatTranscriptToSoap("Patient reports dizziness.")).thenReturn(null);

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
        assertTrue(result.getTextStructured().contains("O: (objective findings placeholder)"));
        assertNotNull(result.getCreatedAt());
        assertNotNull(result.getId());

        // Verify that services were called correctly
        verify(transcriptService, times(1)).createTranscript(UUID.fromString(userId), sessionId, "text", "Patient reports dizziness.");
        verify(agentServiceClient, times(1)).isAgentServiceAvailable();
        verify(agentServiceClient, times(1)).formatTranscriptToSoap("Patient reports dizziness.");
        verify(soapNoteRepository, times(1)).save(any(SoapNote.class));
    }
}

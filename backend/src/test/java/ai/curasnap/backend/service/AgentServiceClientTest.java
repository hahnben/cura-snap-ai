package ai.curasnap.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AgentServiceClient using MockWebServer.
 */
class AgentServiceClientTest {

    private MockWebServer mockWebServer;
    private AgentServiceClient agentServiceClient;
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        
        String baseUrl = mockWebServer.url("").toString();
        agentServiceClient = new AgentServiceClient(
                new RestTemplate(),
                baseUrl.substring(0, baseUrl.length() - 1), // Remove trailing slash
                true // enabled
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void shouldSuccessfullyFormatTranscriptToSoap() throws Exception {
        // Arrange
        String transcript = "Patient reports dizziness.";
        String expectedSoapNote = "ANAMNESE:\nPatient reports dizziness.\n\nUNTERSUCHUNG und BEFUNDE:\n...\n\nBEURTEILUNG:\n...\n\nPROZEDERE und THERAPIE:\n...";
        
        AgentServiceClient.SoapResponse mockResponse = new AgentServiceClient.SoapResponse();
        mockResponse.setStructuredText(expectedSoapNote);
        
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(objectMapper.writeValueAsString(mockResponse)));

        // Act
        String result = agentServiceClient.formatTranscriptToSoap(transcript);

        // Assert
        assertNotNull(result);
        assertEquals(expectedSoapNote, result);
        
        // Verify the request
        RecordedRequest request = mockWebServer.takeRequest();
        assertEquals("/soap/format_note", request.getPath());
        assertEquals("POST", request.getMethod());
        assertEquals("application/json", request.getHeader("Content-Type"));
        
        // Verify request body
        AgentServiceClient.TranscriptRequest requestBody = objectMapper.readValue(
                request.getBody().readUtf8(), AgentServiceClient.TranscriptRequest.class);
        assertEquals(transcript, requestBody.getTranscript());
    }

    @Test
    void shouldReturnNullWhenServerReturnsError() {
        // Arrange
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error"));

        // Act
        String result = agentServiceClient.formatTranscriptToSoap("test transcript");

        // Assert
        assertNull(result);
    }

    @Test
    void shouldReturnNullWhenServerIsUnreachable() {
        // Arrange - create client with invalid URL
        AgentServiceClient unreachableClient = new AgentServiceClient(
                new RestTemplate(),
                "http://nonexistent:9999",
                true
        );

        // Act
        String result = unreachableClient.formatTranscriptToSoap("test transcript");

        // Assert
        assertNull(result);
    }

    @Test
    void shouldReturnNullWhenServiceIsDisabled() {
        // Arrange
        AgentServiceClient disabledClient = new AgentServiceClient(
                new RestTemplate(),
                "http://localhost:8001",
                false // disabled
        );

        // Act
        String result = disabledClient.formatTranscriptToSoap("test transcript");

        // Assert
        assertNull(result);
    }

    @Test
    void shouldReturnCorrectAvailabilityStatus() {
        // Test enabled service
        assertTrue(agentServiceClient.isAgentServiceAvailable());
        
        // Test disabled service
        AgentServiceClient disabledClient = new AgentServiceClient(
                new RestTemplate(),
                "http://localhost:8001",
                false
        );
        assertFalse(disabledClient.isAgentServiceAvailable());
    }
}
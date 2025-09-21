package ai.curasnap.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Client service for communicating with the Python Agent Service.
 * 
 * This service handles HTTP requests to the Agent Service for SOAP note generation.
 * It provides error handling and fallback mechanisms when the service is unavailable.
 */
@Service
public class AgentServiceClient implements AgentServiceClientInterface {

    private static final Logger logger = LoggerFactory.getLogger(AgentServiceClient.class);

    private final RestTemplate restTemplate;
    private final String agentServiceUrl;
    private final boolean agentServiceEnabled;

    @Autowired
    public AgentServiceClient(
            @Qualifier("internal") RestTemplate restTemplate,
            @Value("${agent.service.url}") String agentServiceUrl,
            @Value("${agent.service.enabled}") boolean agentServiceEnabled) {
        this.restTemplate = restTemplate;
        this.agentServiceUrl = agentServiceUrl;
        this.agentServiceEnabled = agentServiceEnabled;
    }

    /**
     * Sends a transcript to the Agent Service for SOAP note generation.
     * 
     * @param transcript the raw transcript text to be processed
     * @return the structured SOAP note text, or null if service is unavailable
     */
    public String formatTranscriptToSoap(String transcript) {
        if (!agentServiceEnabled) {
            logger.debug("Agent service is disabled, returning null for transcript formatting");
            return null;
        }

        try {
            String url = agentServiceUrl + "/api/soap/format_note";
            
            TranscriptRequest request = new TranscriptRequest(transcript);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<TranscriptRequest> entity = new HttpEntity<>(request, headers);
            
            logger.debug("Sending transcript to Agent Service at: {}", url);
            ResponseEntity<SoapResponse> response = restTemplate.postForEntity(url, entity, SoapResponse.class);
            
            if (response.getBody() != null) {
                String soapNote = response.getBody().getStructuredText();
                logger.debug("Successfully received SOAP note from Agent Service");
                return soapNote;
            } else {
                logger.warn("Agent Service returned empty response");
                return null;
            }
            
        } catch (RestClientException e) {
            logger.error("Failed to communicate with Agent Service: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Checks if the Agent Service is available and enabled.
     * 
     * @return true if the service is enabled and should be used
     */
    public boolean isAgentServiceAvailable() {
        return agentServiceEnabled;
    }

    /**
     * Request model for the Agent Service transcript input.
     */
    public static class TranscriptRequest {
        private String transcript;

        public TranscriptRequest() {
        }

        public TranscriptRequest(String transcript) {
            this.transcript = transcript;
        }

        public String getTranscript() {
            return transcript;
        }

        public void setTranscript(String transcript) {
            this.transcript = transcript;
        }
    }

    /**
     * Response model for the Agent Service SOAP note output.
     * Supports both old flat format and new nested format for backward compatibility.
     */
    public static class SoapResponse {
        // New nested format fields
        @JsonProperty("soap_note")
        private SoapNote soapNote;

        @JsonProperty("metadata")
        private Map<String, Object> metadata;

        @JsonProperty("timestamp")
        private String timestamp;

        // Legacy flat format field for backward compatibility
        @JsonProperty("structured_text")
        private String structuredText;

        /**
         * Gets the structured text from either new or old format.
         * @return the SOAP note structured text
         */
        public String getStructuredText() {
            // Try new format first
            if (soapNote != null && soapNote.getStructuredText() != null) {
                return soapNote.getStructuredText();
            }
            // Fallback to old format
            return structuredText;
        }

        public void setStructuredText(String structuredText) {
            this.structuredText = structuredText;
        }

        public SoapNote getSoapNote() {
            return soapNote;
        }

        public void setSoapNote(SoapNote soapNote) {
            this.soapNote = soapNote;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }

        public void setMetadata(Map<String, Object> metadata) {
            this.metadata = metadata;
        }

        public String getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(String timestamp) {
            this.timestamp = timestamp;
        }
    }

    /**
     * Nested SOAP note model matching the Agent Service response structure.
     */
    public static class SoapNote {
        @JsonProperty("structured_text")
        private String structuredText;

        public String getStructuredText() {
            return structuredText;
        }

        public void setStructuredText(String structuredText) {
            this.structuredText = structuredText;
        }
    }
}
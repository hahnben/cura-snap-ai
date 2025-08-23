package ai.curasnap.backend.service;

/**
 * Interface for Agent Service Client implementations.
 * 
 * This interface allows for multiple implementations of the Agent Service Client,
 * including cached and non-cached versions, following the Strategy pattern.
 */
public interface AgentServiceClientInterface {
    
    /**
     * Formats a transcript into a SOAP note using AI processing.
     * 
     * @param transcript the raw transcript text to be processed
     * @return the structured SOAP note text, or null if service is unavailable
     */
    String formatTranscriptToSoap(String transcript);
    
    /**
     * Checks if the Agent Service is available and enabled.
     * 
     * @return true if the service is enabled and should be used
     */
    boolean isAgentServiceAvailable();
}
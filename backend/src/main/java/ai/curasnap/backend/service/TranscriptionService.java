package ai.curasnap.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Client service for communicating with the Transcription Service.
 * 
 * This service handles HTTP requests to the Transcription Service for audio-to-text conversion.
 * It provides error handling and fallback mechanisms when the service is unavailable.
 */
@Service
public class TranscriptionService {

    private static final Logger logger = LoggerFactory.getLogger(TranscriptionService.class);

    private final RestTemplate restTemplate;
    private final String transcriptionServiceUrl;
    private final boolean transcriptionServiceEnabled;

    // Maximum file size (25MB)
    private static final long MAX_FILE_SIZE = 25 * 1024 * 1024;
    
    // Allowed audio file extensions
    private static final List<String> ALLOWED_EXTENSIONS = Arrays.asList(
        ".mp3", ".wav", ".webm", ".m4a", ".ogg", ".flac"
    );

    @Autowired
    public TranscriptionService(
            RestTemplate restTemplate,
            @Value("${transcription.service.url:http://localhost:8002}") String transcriptionServiceUrl,
            @Value("${transcription.service.enabled:true}") boolean transcriptionServiceEnabled) {
        this.restTemplate = restTemplate;
        this.transcriptionServiceUrl = transcriptionServiceUrl;
        this.transcriptionServiceEnabled = transcriptionServiceEnabled;
    }

    /**
     * Transcribes an audio file to text using the Transcription Service.
     * 
     * @param audioFile the audio file to be transcribed
     * @return the transcribed text, or null if service is unavailable
     * @throws TranscriptionException if transcription fails
     */
    public String transcribe(MultipartFile audioFile) throws TranscriptionException {
        if (!transcriptionServiceEnabled) {
            logger.debug("Transcription service is disabled, returning null for audio transcription");
            return null;
        }

        try {
            // Validate audio file
            validateAudioFile(audioFile);

            String url = transcriptionServiceUrl + "/transcribe";
            
            // Prepare multipart request
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new ByteArrayResource(audioFile.getBytes()) {
                @Override
                public String getFilename() {
                    return audioFile.getOriginalFilename();
                }
            });
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);
            
            logger.debug("Sending audio file to Transcription Service at: {}", url);
            ResponseEntity<TranscriptionResponse> response = restTemplate.postForEntity(url, entity, TranscriptionResponse.class);
            
            if (response.getBody() != null) {
                String transcript = response.getBody().transcript;
                logger.debug("Successfully received transcript from Transcription Service");
                return transcript;
            } else {
                logger.warn("Transcription Service returned empty response");
                throw new TranscriptionException("Empty response from transcription service");
            }
            
        } catch (RestClientException e) {
            logger.error("Failed to communicate with Transcription Service: {}", e.getMessage());
            throw new TranscriptionException("Failed to communicate with transcription service", e);
        } catch (IOException e) {
            logger.error("Failed to read audio file: {}", e.getMessage());
            throw new TranscriptionException("Failed to read audio file", e);
        }
    }

    /**
     * Validates the uploaded audio file.
     * 
     * @param audioFile the audio file to validate
     * @throws TranscriptionException if validation fails
     */
    private void validateAudioFile(MultipartFile audioFile) throws TranscriptionException {
        if (audioFile == null || audioFile.isEmpty()) {
            throw new TranscriptionException("Audio file is empty or null");
        }

        // Check file size
        if (audioFile.getSize() > MAX_FILE_SIZE) {
            throw new TranscriptionException(
                String.format("Audio file too large: %d bytes (max: %d bytes)", 
                    audioFile.getSize(), MAX_FILE_SIZE)
            );
        }

        // Check file extension
        String originalFilename = audioFile.getOriginalFilename();
        if (originalFilename == null || originalFilename.isEmpty()) {
            throw new TranscriptionException("Audio file has no filename");
        }

        String extension = getFileExtension(originalFilename).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new TranscriptionException(
                String.format("Unsupported audio file format: %s (allowed: %s)", 
                    extension, String.join(", ", ALLOWED_EXTENSIONS))
            );
        }
    }

    /**
     * Extracts file extension from filename.
     * 
     * @param filename the filename
     * @return the file extension including the dot
     */
    private String getFileExtension(String filename) {
        int lastDotIndex = filename.lastIndexOf('.');
        return lastDotIndex > 0 ? filename.substring(lastDotIndex) : "";
    }

    /**
     * Checks if the Transcription Service is available and enabled.
     * 
     * @return true if the service is enabled and should be used
     */
    public boolean isTranscriptionServiceAvailable() {
        return transcriptionServiceEnabled;
    }

    /**
     * Response model for the Transcription Service output.
     */
    public static class TranscriptionResponse {
        @JsonProperty("transcript")
        private String transcript;

        public String getTranscript() {
            return transcript;
        }

        public void setTranscript(String transcript) {
            this.transcript = transcript;
        }
    }

    /**
     * Exception thrown when transcription fails.
     */
    public static class TranscriptionException extends Exception {
        public TranscriptionException(String message) {
            super(message);
        }

        public TranscriptionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
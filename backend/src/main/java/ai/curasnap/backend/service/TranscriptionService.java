package ai.curasnap.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import java.io.InputStream;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.annotation.JsonProperty;

import ai.curasnap.backend.util.SecurityUtils;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

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
    private final int transcriptionServiceTimeout;

    // Maximum file size (25MB)
    private static final long MAX_FILE_SIZE = 25 * 1024 * 1024;
    
    // Allowed audio file extensions
    private static final List<String> ALLOWED_EXTENSIONS = Arrays.asList(
        ".mp3", ".wav", ".webm", ".m4a", ".ogg", ".flac"
    );
    
    // Allowed MIME types for audio files
    private static final List<String> ALLOWED_MIME_TYPES = Arrays.asList(
        "audio/mpeg", "audio/mp3", "audio/wav", "audio/wave", "audio/x-wav",
        "audio/webm", "audio/mp4", "audio/m4a", "audio/ogg", "audio/flac"
    );
    
    // Magic number patterns for audio file validation
    private static final Map<String, byte[]> AUDIO_MAGIC_NUMBERS = new HashMap<>();
    static {
        AUDIO_MAGIC_NUMBERS.put("mp3", new byte[]{(byte)0xFF, (byte)0xFB}); // MP3 frame header
        AUDIO_MAGIC_NUMBERS.put("wav", new byte[]{0x52, 0x49, 0x46, 0x46}); // "RIFF"
        AUDIO_MAGIC_NUMBERS.put("ogg", new byte[]{0x4F, 0x67, 0x67, 0x53}); // "OggS"
        AUDIO_MAGIC_NUMBERS.put("flac", new byte[]{0x66, 0x4C, 0x61, 0x43}); // "fLaC"
        AUDIO_MAGIC_NUMBERS.put("m4a", new byte[]{0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70}); // MP4 container
    }

    @Autowired
    public TranscriptionService(
            @Qualifier("internal") RestTemplate restTemplate,
            @Value("${transcription.service.url:http://localhost:8002}") String transcriptionServiceUrl,
            @Value("${transcription.service.enabled:true}") boolean transcriptionServiceEnabled,
            @Value("${transcription.service.timeout:30000}") int transcriptionServiceTimeout) {
        this.restTemplate = restTemplate;
        this.transcriptionServiceUrl = transcriptionServiceUrl;
        this.transcriptionServiceEnabled = transcriptionServiceEnabled;
        this.transcriptionServiceTimeout = transcriptionServiceTimeout;
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
            // Validate audio file (including MIME type and magic numbers)
            validateAudioFile(audioFile);

            String url = transcriptionServiceUrl + "/transcribe";
            
            // Prepare multipart request with streaming (memory efficient)
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new StreamingByteArrayResource(audioFile));
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);
            
            logger.debug("Sending validated audio file to Transcription Service at: {}", url);
            ResponseEntity<TranscriptionResponse> response = restTemplate.postForEntity(url, entity, TranscriptionResponse.class);

            // Detailed logging for debugging
            logger.debug("Transcription Service Response - Status: {}, Headers: {}",
                response.getStatusCode(), response.getHeaders());

            if (response.getBody() != null) {
                TranscriptionResponse responseBody = response.getBody();
                logger.debug("Response body object: {}", responseBody);

                String transcript = responseBody.transcript;
                logger.debug("Extracted transcript field: '{}' (length: {})",
                    transcript, transcript != null ? transcript.length() : "null");

                if (transcript != null && !transcript.trim().isEmpty()) {
                    logger.info("Successfully received transcript from Transcription Service: {} characters",
                        transcript.length());
                    return transcript;
                } else {
                    logger.warn("Transcription Service returned empty or null transcript. Response body: {}", responseBody);
                    // This is expected behavior for audio without speech - return null for graceful handling
                    return null;
                }
            } else {
                logger.warn("Transcription Service returned null response body. Status: {}, Headers: {}",
                    response.getStatusCode(), response.getHeaders());
                throw new TranscriptionException("Null response body from transcription service");
            }
            
        } catch (RestClientException e) {
            logger.error("Failed to communicate with Transcription Service: {}", e.getMessage());
            throw new TranscriptionException("Failed to communicate with transcription service", e);
        } catch (IOException e) {
            logger.error("Failed to process audio file: {}", e.getMessage());
            throw new TranscriptionException("Failed to process audio file", e);
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
            logger.warn("Unsupported file format uploaded: {}", 
                SecurityUtils.sanitizeFilenameForLogging(originalFilename));
            throw new TranscriptionException(
                String.format("Unsupported audio file format: %s (allowed: %s)", 
                    extension, String.join(", ", ALLOWED_EXTENSIONS))
            );
        }
        
        // Validate MIME type with RFC 2046-compliant parsing
        String contentType = audioFile.getContentType();
        String baseMimeType = parseBaseMimeType(contentType);

        if (baseMimeType == null || !ALLOWED_MIME_TYPES.contains(baseMimeType)) {
            logger.warn("Invalid MIME type for audio file {}: {} (parsed as: {})",
                SecurityUtils.sanitizeFilenameForLogging(originalFilename),
                SecurityUtils.sanitizeForLogging(contentType),
                SecurityUtils.sanitizeForLogging(baseMimeType));
            throw new TranscriptionException(
                String.format("Invalid MIME type: %s (allowed: %s)",
                    contentType, String.join(", ", ALLOWED_MIME_TYPES))
            );
        }

        // Debug logging for successful MIME type parsing
        if (contentType != null && !contentType.equals(baseMimeType)) {
            logger.debug("Successfully parsed MIME type for audio file {}: {} → {}",
                SecurityUtils.sanitizeFilenameForLogging(originalFilename),
                SecurityUtils.sanitizeForLogging(contentType),
                SecurityUtils.sanitizeForLogging(baseMimeType));
        }
        
        // Validate file content using magic numbers (only reads header, not full file)
        try {
            validateAudioContentHeader(audioFile, extension);
        } catch (IOException e) {
            logger.error("Failed to validate audio file content for {}: {}", 
                SecurityUtils.sanitizeFilenameForLogging(originalFilename), 
                e.getMessage());
            throw new TranscriptionException("Failed to validate audio file content", e);
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
     * Parses the base MIME type from a content type string according to RFC 2046.
     * Extracts the main type/subtype before any semicolon parameters.
     *
     * Examples:
     * - "audio/webm;codecs=opus" → "audio/webm"
     * - "audio/mp4; codecs=aac" → "audio/mp4"
     * - "audio/wav" → "audio/wav"
     * - null → null
     *
     * @param contentType the full content type string (may include parameters)
     * @return the base MIME type in lowercase, or null if input is null/empty
     */
    private String parseBaseMimeType(String contentType) {
        if (contentType == null || contentType.trim().isEmpty()) {
            return null;
        }

        // Extract base type before first semicolon (RFC 2046 compliant)
        int semicolonIndex = contentType.indexOf(';');
        String baseType = semicolonIndex > 0
            ? contentType.substring(0, semicolonIndex).trim()
            : contentType.trim();

        return baseType.toLowerCase();
    }
    
    /**
     * Validates audio file content using magic numbers (header-only validation).
     * This method only reads the file header to validate format without loading the entire file.
     * 
     * @param audioFile the audio file to validate
     * @param extension the file extension
     * @throws IOException if file reading fails
     * @throws TranscriptionException if content validation fails
     */
    private void validateAudioContentHeader(MultipartFile audioFile, String extension) throws IOException, TranscriptionException {
        String extensionKey = extension.substring(1); // Remove the dot
        byte[] magicNumbers = AUDIO_MAGIC_NUMBERS.get(extensionKey);
        
        if (magicNumbers == null) {
            // For extensions without specific magic numbers (like .webm), skip magic number validation
            logger.debug("No magic number validation available for extension: {}", extension);
            return;
        }
        
        // Read only the header bytes needed for validation (more memory efficient)
        int headerSize = Math.max(magicNumbers.length, 32); // Ensure we read enough for WAV validation
        byte[] fileHeader = new byte[headerSize];
        
        try (var inputStream = audioFile.getInputStream()) {
            int bytesRead = inputStream.read(fileHeader);
            if (bytesRead < magicNumbers.length) {
                throw new TranscriptionException("Audio file too small to contain valid header");
            }
        }
        
        // Check for exact magic number match
        boolean isValidAudio = false;
        if (extensionKey.equals("mp3")) {
            // MP3 can have different frame headers, check for common ones
            isValidAudio = checkMp3MagicNumbers(fileHeader);
        } else if (extensionKey.equals("wav")) {
            // WAV files start with "RIFF" and contain "WAVE" at offset 8
            isValidAudio = Arrays.equals(Arrays.copyOf(fileHeader, 4), magicNumbers) &&
                          fileHeader.length > 11 &&
                          fileHeader[8] == 0x57 && fileHeader[9] == 0x41 && // "WA"
                          fileHeader[10] == 0x56 && fileHeader[11] == 0x45; // "VE"
        } else {
            // For other formats, check exact magic number match
            isValidAudio = Arrays.equals(Arrays.copyOf(fileHeader, magicNumbers.length), magicNumbers);
        }
        
        if (!isValidAudio) {
            logger.warn("Invalid magic number for {} file: {}", 
                extensionKey, 
                SecurityUtils.sanitizeFilenameForLogging(audioFile.getOriginalFilename()));
            throw new TranscriptionException("File content does not match expected audio format");
        }
    }
    
    /**
     * Checks various MP3 magic number patterns.
     * 
     * @param fileHeader the file header bytes
     * @return true if valid MP3 magic numbers are found
     */
    private boolean checkMp3MagicNumbers(byte[] fileHeader) {
        if (fileHeader.length < 2) return false;
        
        // Common MP3 frame headers
        byte[][] mp3Headers = {
            {(byte)0xFF, (byte)0xFB}, // MPEG-1 Layer 3
            {(byte)0xFF, (byte)0xF3}, // MPEG-2 Layer 3
            {(byte)0xFF, (byte)0xF2}, // MPEG-2.5 Layer 3
            {(byte)0x49, (byte)0x44, (byte)0x33} // ID3 tag "ID3"
        };
        
        for (byte[] header : mp3Headers) {
            if (fileHeader.length >= header.length && 
                Arrays.equals(Arrays.copyOf(fileHeader, header.length), header)) {
                return true;
            }
        }
        return false;
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

        @Override
        public String toString() {
            return "TranscriptionResponse{" +
                    "transcript='" + (transcript != null ? transcript.substring(0, Math.min(transcript.length(), 100)) + (transcript.length() > 100 ? "..." : "") : "null") + '\'' +
                    '}';
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
    
    /**
     * Custom ByteArrayResource that streams from MultipartFile without loading entire file into memory.
     * This prevents memory exhaustion with large audio files.
     */
    private static class StreamingByteArrayResource extends ByteArrayResource {
        private final MultipartFile multipartFile;
        
        public StreamingByteArrayResource(MultipartFile multipartFile) throws IOException {
            super(new byte[0]); // Empty array, we override getInputStream
            this.multipartFile = multipartFile;
        }
        
        @Override
        public InputStream getInputStream() throws IOException {
            return multipartFile.getInputStream();
        }
        
        @Override
        public String getFilename() {
            return multipartFile.getOriginalFilename();
        }
        
        @Override
        public long contentLength() {
            return multipartFile.getSize();
        }
        
        @Override
        public String getDescription() {
            return "Streaming resource for file: " + SecurityUtils.sanitizeFilenameForLogging(multipartFile.getOriginalFilename());
        }
    }
}
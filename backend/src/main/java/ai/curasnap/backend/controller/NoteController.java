
package ai.curasnap.backend.controller;

import ai.curasnap.backend.model.dto.NoteRequest;
import ai.curasnap.backend.model.dto.NoteResponse;
import ai.curasnap.backend.service.NoteService;
import ai.curasnap.backend.service.RequestLatencyMetricsService;
import ai.curasnap.backend.service.TranscriptionService;
import ai.curasnap.backend.service.TranscriptionService.TranscriptionException;

import io.micrometer.core.annotation.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * REST controller that handles API endpoints related to medical notes.
 * This controller is part of API version 1 and requires JWT-based authentication.
 */
@RestController
@RequestMapping("/api/v1") // Versioning ensures backward compatibility in future updates
public class NoteController {

    private static final Logger logger = LoggerFactory.getLogger(NoteController.class);

    private final NoteService noteService;
    private final TranscriptionService transcriptionService;
    private final RequestLatencyMetricsService metricsService;

    /**
     * Constructs a NoteController with injected services.
     *
     * @param noteService the service used to handle note-related operations
     * @param transcriptionService the service used to handle audio transcription
     * @param metricsService the service used to track request latency and performance metrics
     */
    @Autowired
    public NoteController(NoteService noteService, TranscriptionService transcriptionService, RequestLatencyMetricsService metricsService) {
        this.noteService = noteService;
        this.transcriptionService = transcriptionService;
        this.metricsService = metricsService;
    }

    /**
     * Test endpoint to verify JWT authentication.
     * Returns a greeting that includes the authenticated user's ID.
     *
     * @param jwt the decoded JWT token of the authenticated user
     * @return a ResponseEntity containing a greeting message
     */
    @Timed(value = "http_request_duration_seconds", description = "Hello endpoint request duration", extraTags = {"endpoint", "/api/v1/hello", "operation", "hello"})
    @GetMapping("/hello")
    public ResponseEntity<String> hello(@AuthenticationPrincipal Jwt jwt) {
        // String userId = jwt.getSubject();
    	
    	// In a real authenticated request, Spring injects the Jwt token here.
        // However, in unit tests with MockMvc, there is no real security context,
        // so 'jwt' will be null unless explicitly simulated.

        // This line checks if jwt is null (e.g. during testing).
        // If so, it falls back to a dummy userId ("test-user").
        String userId = (jwt != null) ? jwt.getSubject() : "test-user";

        // Log access for monitoring
        logger.info("Accessed /hello endpoint by user: {}", userId);

        return ResponseEntity.ok("Hello, " + userId);
    }

    /**
     * Formats a raw transcript into a structured medical note (e.g., SOAP format).
     * The formatting is performed by a connected AI agent (Python service).
     *
     * @param jwt     the decoded JWT token containing user identity
     * @param request the incoming request with raw transcript text
     * @return a ResponseEntity containing the formatted note
     */
    @Timed(value = "http_request_duration_seconds", description = "SOAP note formatting request duration", extraTags = {"endpoint", "/api/v1/notes/format", "operation", "soap_generation"})
    @PostMapping("/notes/format")
    public ResponseEntity<NoteResponse> formatNote(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody NoteRequest request
    ) {
        // String userId = jwt.getSubject();
        
    	// This line checks if jwt is null (e.g. during testing).
        // If so, it falls back to a dummy userId ("test-user").
        String userId = (jwt != null) ? jwt.getSubject() : "test-user";
        logger.info("Received /notes/format request from user: {}", userId);

        // Basic input validation – prevents null or blank input
        if (request == null || request.getTextRaw() == null || request.getTextRaw().isBlank()) {
            logger.warn("Invalid request received: empty or missing textRaw field");
            return ResponseEntity.badRequest().build();
        }

        // Time the SOAP generation operation with detailed metrics
        NoteResponse response = metricsService.timeSoapGeneration(() -> noteService.formatNote(userId, request));
        return ResponseEntity.ok(response);
    }

    /**
     * Formats an audio file into a structured medical note (e.g., SOAP format).
     * The audio is first transcribed, then formatted by the AI agent.
     *
     * @param jwt the decoded JWT token containing user identity
     * @param audioFile the uploaded audio file
     * @return a ResponseEntity containing the formatted note
     */
    @Timed(value = "http_request_duration_seconds", description = "Audio processing and SOAP generation request duration", extraTags = {"endpoint", "/api/v1/notes/format-audio", "operation", "audio_processing"})
    @PostMapping("/notes/format-audio")
    public ResponseEntity<NoteResponse> formatAudio(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("audio") MultipartFile audioFile
    ) {
        String userId = (jwt != null) ? jwt.getSubject() : "test-user";
        logger.info("Received /notes/format-audio request from user: {}", userId);

        // Basic input validation
        if (audioFile == null || audioFile.isEmpty()) {
            logger.warn("Invalid request received: empty or missing audio file");
            return ResponseEntity.badRequest().build();
        }

        try {
            // Time the complete audio processing pipeline
            NoteResponse response = metricsService.timeAudioTranscription(() -> {
                try {
                    // Transcribe audio to text
                    String transcript = transcriptionService.transcribe(audioFile);
                    logger.info("Audio transcribed successfully for user: {}", userId);

                    // Create NoteRequest with transcribed text
                    NoteRequest request = new NoteRequest();
                    request.setTextRaw(transcript);

                    // Use existing note formatting pipeline
                    return noteService.formatNote(userId, request);
                } catch (TranscriptionException e) {
                    // Wrap checked exception as runtime exception to work with lambda
                    throw new RuntimeException("Transcription failed", e);
                }
            });
            
            // Track successful audio processing
            metricsService.incrementCounter("audio_files_processed_total", "status", "success", "user_id", userId);
            return ResponseEntity.ok(response);

        } catch (RuntimeException e) {
            // Check if this is a wrapped TranscriptionException
            if (e.getCause() instanceof TranscriptionException) {
                logger.error("Transcription failed for user {}: {}", userId, e.getCause().getMessage());
                // Track transcription failures
                metricsService.incrementCounter("audio_files_processed_total", "status", "transcription_error", "user_id", userId, "error_type", "TranscriptionException");
                return ResponseEntity.badRequest().build();
            } else {
                logger.error("Error processing audio file for user {}: {}", userId, e.getMessage());
                // Track general processing failures
                metricsService.incrementCounter("audio_files_processed_total", "status", "processing_error", "user_id", userId, "error_type", e.getClass().getSimpleName());
                return ResponseEntity.internalServerError().build();
            }
        } catch (Exception e) {
            logger.error("Unexpected error processing audio file for user {}: {}", userId, e.getMessage());
            // Track general processing failures
            metricsService.incrementCounter("audio_files_processed_total", "status", "processing_error", "user_id", userId, "error_type", e.getClass().getSimpleName());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Retrieves all notes associated with the authenticated user.
     * Currently returns dummy data; database integration follows in a later phase.
     *
     * @param jwt the decoded JWT token of the authenticated user
     * @return a ResponseEntity containing a list of notes
     */
    @Timed(value = "http_request_duration_seconds", description = "Get user notes request duration", extraTags = {"endpoint", "/api/v1/notes", "operation", "get_notes"})
    @GetMapping("/notes")
    public ResponseEntity<List<NoteResponse>> getNotes(@AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();
        logger.info("User {} requested their notes via /notes", userId);

        // Time the database retrieval operation
        List<NoteResponse> notes = metricsService.timeDatabaseOperation("get_notes", () -> noteService.getNotes(userId));
        
        // Track notes retrieval metrics
        metricsService.incrementCounter("notes_retrieved_total", "user_id", userId);
        metricsService.recordGauge("notes_count", notes.size(), "user_id", userId);
        
        return ResponseEntity.ok(notes);
    }

    /*
     * NOTE ON CORS CONFIGURATION:
     * CORS should be configured globally via WebSecurityConfigurer or CorsConfigurationSource
     * to allow frontend (e.g., http://localhost:5000) to access /api/v1 endpoints.
     *
     * NOTE ON CACHE CONTROL:
     * To prevent sensitive data from being cached in the browser, consider setting headers like:
     * Cache-Control: no-store
     * This should be done globally via an interceptor or filter, not per endpoint.
     */
}

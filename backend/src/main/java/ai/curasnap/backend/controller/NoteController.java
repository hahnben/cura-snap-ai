
package ai.curasnap.backend.controller;

import ai.curasnap.backend.model.dto.NoteRequest;
import ai.curasnap.backend.model.dto.NoteResponse;
import ai.curasnap.backend.service.NoteService;
import ai.curasnap.backend.service.TranscriptionService;
import ai.curasnap.backend.service.TranscriptionService.TranscriptionException;

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

    /**
     * Constructs a NoteController with injected services.
     *
     * @param noteService the service used to handle note-related operations
     * @param transcriptionService the service used to handle audio transcription
     */
    @Autowired
    public NoteController(NoteService noteService, TranscriptionService transcriptionService) {
        this.noteService = noteService;
        this.transcriptionService = transcriptionService;
    }

    /**
     * Test endpoint to verify JWT authentication.
     * Returns a greeting that includes the authenticated user's ID.
     *
     * @param jwt the decoded JWT token of the authenticated user
     * @return a ResponseEntity containing a greeting message
     */
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

        NoteResponse response = noteService.formatNote(userId, request);
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
            // Transcribe audio to text
            String transcript = transcriptionService.transcribe(audioFile);
            logger.info("Audio transcribed successfully for user: {}", userId);

            // Create NoteRequest with transcribed text
            NoteRequest request = new NoteRequest();
            request.setTextRaw(transcript);

            // Use existing note formatting pipeline
            NoteResponse response = noteService.formatNote(userId, request);
            return ResponseEntity.ok(response);

        } catch (TranscriptionException e) {
            logger.error("Transcription failed for user {}: {}", userId, e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            logger.error("Error processing audio file for user {}: {}", userId, e.getMessage());
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
    @GetMapping("/notes")
    public ResponseEntity<List<NoteResponse>> getNotes(@AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();
        logger.info("User {} requested their notes via /notes", userId);

        List<NoteResponse> notes = noteService.getNotes(userId);
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

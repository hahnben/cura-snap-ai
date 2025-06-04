package ai.curasnap.backend.controller;

import ai.curasnap.backend.model.dto.NoteRequest;
import ai.curasnap.backend.model.dto.NoteResponse;
import ai.curasnap.backend.service.NoteService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

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

    /**
     * Constructs a NoteController with injected NoteService.
     *
     * @param noteService the service used to handle note-related operations
     */
    @Autowired
    public NoteController(NoteService noteService) {
        this.noteService = noteService;
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
        String userId = jwt.getSubject();

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
        String userId = jwt.getSubject();
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

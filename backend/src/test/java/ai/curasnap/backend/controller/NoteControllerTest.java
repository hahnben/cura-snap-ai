package ai.curasnap.backend.controller;

import ai.curasnap.backend.model.dto.NoteRequest;
import ai.curasnap.backend.model.dto.NoteResponse;
import ai.curasnap.backend.service.NoteService;
import ai.curasnap.backend.config.TestSecurityConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;



/**
 * Unit tests for the NoteController using MockMvc.
 * Tests the REST API endpoints and interaction with NoteService.
 */
@WebMvcTest(NoteController.class)
@Import(TestSecurityConfig.class)
class NoteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @SuppressWarnings("removal")
	@MockBean
    private NoteService noteService;

    // Required to prevent Spring Security from trying to decode JWTs
    @SuppressWarnings("removal")
	@MockBean
    private JwtDecoder jwtDecoder;

    /**
     * Tests GET /api/v1/hello with a valid mock user.
     */
    @Test
    // @WithMockUser(username = "user123", roles = {"USER"})
    @DisplayName("GET /api/v1/hello returns greeting with user ID")
    void helloEndpointReturnsGreeting() throws Exception {
        mockMvc.perform(get("/api/v1/hello"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Hello, test-user")));
    }
    
    /**
     * Tests POST /api/v1/notes/format with valid NoteRequest.
     */
    @Test
    // @WithMockUser(username = "test-user", roles = {"USER"})
    @DisplayName("POST /api/v1/notes/format with valid text returns formatted note")
    void formatNoteWithValidInputReturnsResponse() throws Exception {
        NoteResponse dummyResponse = new NoteResponse(
                UUID.randomUUID(),
                "Patient reports dizziness.",
                "S: Patient reports dizziness.\nO: ...\nA: ...\nP: ...",
                Instant.now()
        );

        Mockito.when(noteService.formatNote(any(), any())).thenReturn(dummyResponse);

        String json = """
            {
              "textRaw": "Patient reports dizziness."
            }
            """;

        mockMvc.perform(post("/api/v1/notes/format")
                .with(jwtWithSubject("test-user")) // Simulating security context
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.textStructured").exists())
            .andExpect(jsonPath("$.textRaw").value("Patient reports dizziness."));

    }
    
    /**
     * Creates a mock JWT-based authentication with the given subject (user ID).
     */
    private static RequestPostProcessor jwtWithSubject(String subject) {
        Jwt jwt = Jwt.withTokenValue("mock-token")
                .header("alg", "none")
                .claim("sub", subject)
                .build();

        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt);
        return SecurityMockMvcRequestPostProcessors.authentication(authentication);
    }
    
    /**
     * Tests POST /api/v1/notes/format with blank text.
     * Expects 400 Bad Request due to validation logic.
     */
    @Test
    @DisplayName("POST /api/v1/notes/format with blank text returns 400")
    void formatNoteWithEmptyTextReturnsBadRequest() throws Exception {
        String json = """
            {
              "textRaw": "     "
            }
            """;

        mockMvc.perform(post("/api/v1/notes/format")
                .with(jwtWithSubject("test-user"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isBadRequest());
    }
    
    /**
     * Tests GET /api/v1/notes and expects a list of NoteResponse objects.
     */
    @Test
    @DisplayName("GET /api/v1/notes returns a list of notes")
    void getNotesReturnsList() throws Exception {
        NoteResponse note = new NoteResponse(
                UUID.randomUUID(),
                "Example input",
                "S: ...\nO: ...\nA: ...\nP: ...",
                Instant.now()
        );

        Mockito.when(noteService.getNotes(any())).thenReturn(List.of(note));

        mockMvc.perform(get("/api/v1/notes")
                .with(jwtWithSubject("test-user")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].textRaw").value("Example input"))
            .andExpect(jsonPath("$[0].textStructured").exists());
    }

  
}

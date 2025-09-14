package ai.curasnap.backend.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import ai.curasnap.backend.service.TranscriptionService.TranscriptionException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Method;

/**
 * Comprehensive unit tests for MIME type parsing functionality in TranscriptionService.
 *
 * Tests RFC 2046 compliant parsing of MIME types with parameters, edge cases,
 * and integration with the audio file validation workflow.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TranscriptionService MIME Type Parsing Tests")
class TranscriptionServiceMimeTypeTest {

    private TranscriptionService transcriptionService;

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private MultipartFile mockAudioFile;

    @BeforeEach
    void setUp() {
        transcriptionService = new TranscriptionService(
            restTemplate,
            "http://localhost:8002",
            true,
            30000
        );
    }

    /**
     * Tests the private parseBaseMimeType method using reflection.
     */
    private String invokeParseBaseMimeType(String contentType) throws Exception {
        Method method = TranscriptionService.class.getDeclaredMethod("parseBaseMimeType", String.class);
        method.setAccessible(true);
        return (String) method.invoke(transcriptionService, contentType);
    }

    @ParameterizedTest
    @CsvSource({
        "'audio/webm;codecs=opus', 'audio/webm'",
        "'audio/mp4;codecs=aac', 'audio/mp4'",
        "'audio/wav; charset=utf-8', 'audio/wav'",
        "'audio/mpeg;codecs=mp3', 'audio/mpeg'",
        "'audio/ogg; codecs=vorbis', 'audio/ogg'",
        "'AUDIO/WEBM;CODECS=OPUS', 'audio/webm'", // Test case sensitivity
        "'audio/webm ; codecs=opus', 'audio/webm'", // Test spaces
        "'audio/flac;quality=high;bitrate=1411', 'audio/flac'" // Test multiple parameters
    })
    @DisplayName("Should parse MIME types with parameters correctly")
    void shouldParseParametrizedMimeTypes(String input, String expected) throws Exception {
        String result = invokeParseBaseMimeType(input);
        assertEquals(expected, result,
            String.format("Failed to parse '%s' to '%s'", input, expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "audio/webm",
        "audio/mp4",
        "audio/wav",
        "audio/mpeg",
        "audio/ogg",
        "audio/flac",
        "AUDIO/WAV", // Test case conversion
        " audio/mp3 " // Test whitespace trimming
    })
    @DisplayName("Should handle MIME types without parameters")
    void shouldHandleSimpleMimeTypes(String input) throws Exception {
        String result = invokeParseBaseMimeType(input);
        assertEquals(input.trim().toLowerCase(), result,
            String.format("Failed to handle simple MIME type '%s'", input));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n"})
    @DisplayName("Should handle null, empty, and whitespace-only inputs")
    void shouldHandleInvalidInputs(String input) throws Exception {
        String result = invokeParseBaseMimeType(input);
        assertNull(result, String.format("Should return null for input: '%s'", input));
    }

    @Test
    @DisplayName("Should handle malformed MIME types gracefully")
    void shouldHandleMalformedMimeTypes() throws Exception {
        // MIME type with semicolon but no parameters
        assertEquals("audio/webm", invokeParseBaseMimeType("audio/webm;"));

        // MIME type with just semicolon
        assertEquals(";", invokeParseBaseMimeType(";"));

        // MIME type with multiple semicolons
        assertEquals("audio/webm", invokeParseBaseMimeType("audio/webm;codecs=opus;extra=value"));
    }

    @Test
    @DisplayName("Should accept valid WebM file with codec parameter")
    void shouldAcceptWebmWithCodecParameter() throws IOException {
        // Setup mock file
        when(mockAudioFile.isEmpty()).thenReturn(false);
        when(mockAudioFile.getSize()).thenReturn(1024L);
        when(mockAudioFile.getOriginalFilename()).thenReturn("test.webm");
        when(mockAudioFile.getContentType()).thenReturn("audio/webm;codecs=opus");
        when(mockAudioFile.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[32]));

        // Should not throw MIME type validation exception
        try {
            transcriptionService.transcribe(mockAudioFile);
        } catch (TranscriptionException e) {
            // Ensure it's not a MIME type validation error
            assertFalse(e.getMessage().contains("Invalid MIME type"),
                "Should not fail due to MIME type validation: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("Should accept valid MP4 file with codec parameter")
    void shouldAcceptMp4WithCodecParameter() throws IOException {
        // Setup mock file
        when(mockAudioFile.isEmpty()).thenReturn(false);
        when(mockAudioFile.getSize()).thenReturn(1024L);
        when(mockAudioFile.getOriginalFilename()).thenReturn("test.m4a");
        when(mockAudioFile.getContentType()).thenReturn("audio/mp4;codecs=aac");
        when(mockAudioFile.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[32]));

        // Should not throw exception for MIME validation
        // (May throw other exceptions for actual transcription, but not MIME-related)
        try {
            transcriptionService.transcribe(mockAudioFile);
        } catch (TranscriptionException e) {
            // Ensure it's not a MIME type validation error
            assertFalse(e.getMessage().contains("Invalid MIME type"),
                "Should not fail due to MIME type validation");
        }
    }

    @Test
    @DisplayName("Should reject unsupported MIME type even with parameters")
    void shouldRejectUnsupportedMimeTypeWithParameters() throws IOException {
        // Setup mock file with unsupported MIME type
        when(mockAudioFile.isEmpty()).thenReturn(false);
        when(mockAudioFile.getSize()).thenReturn(1024L);
        when(mockAudioFile.getOriginalFilename()).thenReturn("test.mov");
        when(mockAudioFile.getContentType()).thenReturn("video/quicktime;codecs=aac"); // Video MIME type
        when(mockAudioFile.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[32]));

        // Should throw TranscriptionException for invalid MIME type
        TranscriptionException exception = assertThrows(TranscriptionException.class, () -> {
            transcriptionService.transcribe(mockAudioFile);
        });

        assertTrue(exception.getMessage().contains("Invalid MIME type"),
            "Should fail with MIME type validation error");
    }

    @Test
    @DisplayName("Should handle mixed case MIME types with parameters")
    void shouldHandleMixedCaseMimeTypes() throws IOException {
        // Setup mock file with mixed case MIME type
        when(mockAudioFile.isEmpty()).thenReturn(false);
        when(mockAudioFile.getSize()).thenReturn(1024L);
        when(mockAudioFile.getOriginalFilename()).thenReturn("test.webm");
        when(mockAudioFile.getContentType()).thenReturn("AUDIO/WEBM;CODECS=OPUS");
        when(mockAudioFile.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[32]));

        // Should not throw exception (case should be handled)
        assertDoesNotThrow(() -> {
            transcriptionService.transcribe(mockAudioFile);
        });
    }

    @Test
    @DisplayName("Should handle MIME type with spaces around parameters")
    void shouldHandleMimeTypeWithSpaces() throws IOException {
        // Setup mock file with spaces in MIME type
        when(mockAudioFile.isEmpty()).thenReturn(false);
        when(mockAudioFile.getSize()).thenReturn(1024L);
        when(mockAudioFile.getOriginalFilename()).thenReturn("test.ogg");
        when(mockAudioFile.getContentType()).thenReturn("audio/ogg ; codecs=vorbis ; quality=high");
        when(mockAudioFile.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[32]));

        // Should not throw exception
        assertDoesNotThrow(() -> {
            transcriptionService.transcribe(mockAudioFile);
        });
    }

    @Test
    @DisplayName("Should maintain backward compatibility with simple MIME types")
    void shouldMaintainBackwardCompatibility() throws IOException {
        // Test all allowed MIME types without parameters
        String[] allowedTypes = {
            "audio/mpeg", "audio/mp3", "audio/wav", "audio/wave",
            "audio/x-wav", "audio/webm", "audio/mp4", "audio/m4a",
            "audio/ogg", "audio/flac"
        };

        for (String mimeType : allowedTypes) {
            when(mockAudioFile.isEmpty()).thenReturn(false);
            when(mockAudioFile.getSize()).thenReturn(1024L);
            when(mockAudioFile.getOriginalFilename()).thenReturn("test." + mimeType.split("/")[1]);
            when(mockAudioFile.getContentType()).thenReturn(mimeType);
            when(mockAudioFile.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[32]));

            // Should not throw MIME type validation exception
            assertDoesNotThrow(() -> {
                try {
                    transcriptionService.transcribe(mockAudioFile);
                } catch (TranscriptionException e) {
                    // Allow other exceptions, but not MIME type validation errors
                    assertFalse(e.getMessage().contains("Invalid MIME type"),
                        String.format("MIME type %s should be valid", mimeType));
                }
            }, String.format("Should accept MIME type: %s", mimeType));
        }
    }

    @Test
    @DisplayName("Should handle null content type gracefully")
    void shouldHandleNullContentType() throws IOException {
        // Setup mock file with null content type
        when(mockAudioFile.isEmpty()).thenReturn(false);
        when(mockAudioFile.getSize()).thenReturn(1024L);
        when(mockAudioFile.getOriginalFilename()).thenReturn("test.webm");
        when(mockAudioFile.getContentType()).thenReturn(null);

        // Should throw TranscriptionException for null MIME type
        TranscriptionException exception = assertThrows(TranscriptionException.class, () -> {
            transcriptionService.transcribe(mockAudioFile);
        });

        assertTrue(exception.getMessage().contains("Invalid MIME type"),
            "Should fail with MIME type validation error for null content type");
    }
}
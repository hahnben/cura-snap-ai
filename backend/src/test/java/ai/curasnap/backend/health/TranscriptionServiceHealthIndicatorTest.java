package ai.curasnap.backend.health;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TranscriptionServiceHealthIndicatorTest {

    @Mock
    private RestTemplate restTemplate;

    private TranscriptionServiceHealthIndicator healthIndicator;

    private final String healthCheckUrl = "http://localhost:8002/health";
    private final int timeout = 3000;

    @BeforeEach
    void setUp() {
        healthIndicator = new TranscriptionServiceHealthIndicator(
            restTemplate, healthCheckUrl, timeout, true
        );
    }

    @Test
    void health_WhenServiceIsDisabled_ShouldReturnDisabledStatus() {
        // Arrange
        TranscriptionServiceHealthIndicator disabledIndicator = 
            new TranscriptionServiceHealthIndicator(restTemplate, healthCheckUrl, timeout, false);

        // Act
        Health result = disabledIndicator.health();

        // Assert
        assertEquals("DISABLED", result.getStatus().getCode());
        assertEquals("Transcription Service", result.getDetails().get("service"));
        assertEquals("Service disabled via configuration", result.getDetails().get("status"));
        assertEquals(healthCheckUrl, result.getDetails().get("url"));
        assertEquals(false, result.getDetails().get("enabled"));
    }

    @Test
    void health_WhenServiceIsHealthy_ShouldReturnUpStatus() {
        // Arrange
        Map<String, Object> healthData = new HashMap<>();
        healthData.put("status", "healthy");
        healthData.put("version", "1.0.0");
        healthData.put("model_loaded", true);
        
        ResponseEntity<Map> response = new ResponseEntity<>(healthData, HttpStatus.OK);
        when(restTemplate.getForEntity(healthCheckUrl, Map.class)).thenReturn(response);

        // Act
        Health result = healthIndicator.health();

        // Assert
        assertEquals(Status.UP, result.getStatus());
        assertEquals("Transcription Service", result.getDetails().get("service"));
        assertEquals("Available", result.getDetails().get("status"));
        assertEquals(healthCheckUrl, result.getDetails().get("url"));
        assertEquals("1.0.0", result.getDetails().get("service_version"));
        assertEquals(true, result.getDetails().get("model_loaded"));
        assertEquals(true, result.getDetails().get("enabled"));
        assertTrue(result.getDetails().containsKey("response_time_ms"));
        assertTrue(result.getDetails().containsKey("performance_rating"));
        assertTrue(result.getDetails().containsKey("last_check"));
    }

    @Test
    void health_WhenServiceReportsUnhealthyStatus_ShouldReturnDownStatus() {
        // Arrange
        Map<String, Object> healthData = new HashMap<>();
        healthData.put("status", "unhealthy");
        healthData.put("version", "1.0.0");
        healthData.put("model_loaded", false);
        
        ResponseEntity<Map> response = new ResponseEntity<>(healthData, HttpStatus.OK);
        when(restTemplate.getForEntity(healthCheckUrl, Map.class)).thenReturn(response);

        // Act
        Health result = healthIndicator.health();

        // Assert
        assertEquals(Status.DOWN, result.getStatus());
        assertEquals("Transcription Service", result.getDetails().get("service"));
        assertEquals("Unavailable", result.getDetails().get("status"));
        assertEquals(healthCheckUrl, result.getDetails().get("url"));
        assertTrue(((String) result.getDetails().get("error")).contains("unhealthy"));
        assertEquals(true, result.getDetails().get("fallback_available"));
    }

    @Test
    void health_WhenServiceReturnsNon200Status_ShouldReturnDownStatus() {
        // Arrange
        ResponseEntity<Map> response = new ResponseEntity<>(null, HttpStatus.INTERNAL_SERVER_ERROR);
        when(restTemplate.getForEntity(healthCheckUrl, Map.class)).thenReturn(response);

        // Act
        Health result = healthIndicator.health();

        // Assert
        assertEquals(Status.DOWN, result.getStatus());
        assertEquals("Transcription Service", result.getDetails().get("service"));
        assertEquals("Unavailable", result.getDetails().get("status"));
        assertEquals("Invalid response from transcription service", result.getDetails().get("error"));
        assertEquals(true, result.getDetails().get("fallback_available"));
    }

    @Test
    void health_WhenServiceIsUnreachable_ShouldReturnDownStatus() {
        // Arrange
        when(restTemplate.getForEntity(healthCheckUrl, Map.class))
            .thenThrow(new RestClientException("Connection refused"));

        // Act
        Health result = healthIndicator.health();

        // Assert
        assertEquals(Status.DOWN, result.getStatus());
        assertEquals("Transcription Service", result.getDetails().get("service"));
        assertEquals("Unavailable", result.getDetails().get("status"));
        assertTrue(((String) result.getDetails().get("error")).contains("Connection failed"));
        assertEquals(true, result.getDetails().get("fallback_available"));
    }

    @Test
    void health_WhenResponseTimesAreSlow_ShouldReturnWarningStatus() throws InterruptedException {
        // Arrange
        Map<String, Object> healthData = new HashMap<>();
        healthData.put("status", "healthy");
        healthData.put("version", "1.0.0");
        healthData.put("model_loaded", true);
        
        ResponseEntity<Map> response = new ResponseEntity<>(healthData, HttpStatus.OK);
        when(restTemplate.getForEntity(healthCheckUrl, Map.class)).thenAnswer(invocation -> {
            Thread.sleep(1500); // Above GOOD_RESPONSE_TIME_MS (500ms) but below WARNING (2000ms)
            return response;
        });

        // Act
        Health result = healthIndicator.health();

        // Assert
        assertEquals("WARNING", result.getStatus().getCode());
        assertEquals("Transcription Service", result.getDetails().get("service"));
        assertEquals("Available", result.getDetails().get("status"));
        assertTrue((Long) result.getDetails().get("response_time_ms") > 1000);
        assertEquals("GOOD", result.getDetails().get("performance_rating"));
    }

    @Test
    void health_WhenUnexpectedExceptionOccurs_ShouldReturnDownStatus() {
        // Arrange
        when(restTemplate.getForEntity(healthCheckUrl, Map.class))
            .thenThrow(new RuntimeException("Unexpected error"));

        // Act
        Health result = healthIndicator.health();

        // Assert
        assertEquals(Status.DOWN, result.getStatus());
        assertEquals("Transcription Service", result.getDetails().get("service"));
        assertEquals("Error", result.getDetails().get("status"));
        assertEquals("Unexpected error during health check", result.getDetails().get("error"));
        assertEquals(true, result.getDetails().get("fallback_available"));
    }

    @Test
    void health_WhenServiceReturnsEmptyResponse_ShouldReturnDownStatus() {
        // Arrange
        ResponseEntity<Map> response = new ResponseEntity<>(null, HttpStatus.OK);
        when(restTemplate.getForEntity(healthCheckUrl, Map.class)).thenReturn(response);

        // Act
        Health result = healthIndicator.health();

        // Assert
        assertEquals(Status.DOWN, result.getStatus());
        assertEquals("Transcription Service", result.getDetails().get("service"));
        assertEquals("Unavailable", result.getDetails().get("status"));
        assertEquals("Invalid response from transcription service", result.getDetails().get("error"));
    }

    @Test
    void health_WhenServiceReturnsMinimalHealthData_ShouldHandleGracefully() {
        // Arrange
        Map<String, Object> healthData = new HashMap<>();
        healthData.put("status", "healthy");
        // Missing version and model_loaded fields
        
        ResponseEntity<Map> response = new ResponseEntity<>(healthData, HttpStatus.OK);
        when(restTemplate.getForEntity(healthCheckUrl, Map.class)).thenReturn(response);

        // Act
        Health result = healthIndicator.health();

        // Assert
        assertEquals(Status.UP, result.getStatus());
        assertEquals("Transcription Service", result.getDetails().get("service"));
        assertEquals("Available", result.getDetails().get("status"));
        assertEquals("unknown", result.getDetails().get("service_version"));
        assertEquals(false, result.getDetails().get("model_loaded"));
    }
}
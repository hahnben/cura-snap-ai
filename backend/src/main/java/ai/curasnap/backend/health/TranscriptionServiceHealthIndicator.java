package ai.curasnap.backend.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Custom health indicator for the Transcription Service (Whisper).
 * 
 * This health indicator:
 * - Checks connectivity to the Transcription Service
 * - Monitors service response times
 * - Provides graceful degradation when service is unavailable
 * - Supports fallback modes for audio transcription
 * - Tracks service recovery status
 */
@Component
public class TranscriptionServiceHealthIndicator implements HealthIndicator {

    private static final Logger logger = LoggerFactory.getLogger(TranscriptionServiceHealthIndicator.class);

    private final RestTemplate restTemplate;
    private final String transcriptionServiceUrl;
    private final int timeoutMs;
    private final boolean serviceEnabled;

    // Performance thresholds (milliseconds)
    private static final long GOOD_RESPONSE_TIME_MS = 500;
    private static final long WARNING_RESPONSE_TIME_MS = 2000;
    private static final long CRITICAL_RESPONSE_TIME_MS = 5000;

    public TranscriptionServiceHealthIndicator(
            RestTemplate restTemplate,
            @Value("${app.health.transcription.url:http://localhost:8002/health}") String healthCheckUrl,
            @Value("${app.health.transcription.timeout:3000}") int timeoutMs,
            @Value("${transcription.service.enabled:true}") boolean serviceEnabled) {
        this.restTemplate = restTemplate;
        this.transcriptionServiceUrl = healthCheckUrl;
        this.timeoutMs = timeoutMs;
        this.serviceEnabled = serviceEnabled;
    }

    @Override
    public Health health() {
        Health.Builder healthBuilder = new Health.Builder();

        if (!serviceEnabled) {
            return healthBuilder
                .status("DISABLED")
                .withDetail("service", "Transcription Service")
                .withDetail("status", "Service disabled via configuration")
                .withDetail("url", transcriptionServiceUrl)
                .withDetail("enabled", false)
                .build();
        }

        try {
            // Record start time for response time measurement
            Instant startTime = Instant.now();
            
            // Check transcription service health
            TranscriptionServiceHealthResult result = checkTranscriptionServiceHealth();
            
            // Calculate response time
            long responseTimeMs = Duration.between(startTime, Instant.now()).toMillis();
            
            // Build health status based on results
            if (result.isHealthy()) {
                // Determine status based on response time
                if (responseTimeMs <= GOOD_RESPONSE_TIME_MS) {
                    healthBuilder.up();
                } else if (responseTimeMs <= WARNING_RESPONSE_TIME_MS) {
                    healthBuilder.status("WARNING");
                } else if (responseTimeMs <= CRITICAL_RESPONSE_TIME_MS) {
                    healthBuilder.status("SLOW");
                } else {
                    healthBuilder.status("CRITICAL");
                }
                
                // Add service details
                healthBuilder
                    .withDetail("service", "Transcription Service")
                    .withDetail("status", "Available")
                    .withDetail("url", transcriptionServiceUrl)
                    .withDetail("response_time_ms", responseTimeMs)
                    .withDetail("performance_rating", getPerformanceRating(responseTimeMs))
                    .withDetail("service_version", result.getServiceVersion())
                    .withDetail("model_loaded", result.isModelLoaded())
                    .withDetail("enabled", serviceEnabled)
                    .withDetail("last_check", Instant.now().toString());
                    
            } else {
                healthBuilder.down()
                    .withDetail("service", "Transcription Service")
                    .withDetail("status", "Unavailable")
                    .withDetail("url", transcriptionServiceUrl)
                    .withDetail("error", result.getErrorMessage())
                    .withDetail("response_time_ms", responseTimeMs)
                    .withDetail("enabled", serviceEnabled)
                    .withDetail("fallback_available", true) // Audio processing can fall back to mock
                    .withDetail("last_check", Instant.now().toString());
            }
            
        } catch (Exception e) {
            logger.error("Transcription service health check failed with unexpected error", e);
            healthBuilder.down()
                .withDetail("service", "Transcription Service")
                .withDetail("status", "Error")
                .withDetail("url", transcriptionServiceUrl)
                .withDetail("error", "Unexpected error during health check")
                .withDetail("enabled", serviceEnabled)
                .withDetail("fallback_available", true)
                .withDetail("last_check", Instant.now().toString());
        }
        
        return healthBuilder.build();
    }

    /**
     * Performs the actual transcription service health check.
     * 
     * @return TranscriptionServiceHealthResult containing health status and details
     */
    private TranscriptionServiceHealthResult checkTranscriptionServiceHealth() {
        try {
            // Make HTTP request to transcription service health endpoint
            ResponseEntity<Map> response = restTemplate.getForEntity(transcriptionServiceUrl, Map.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> healthData = response.getBody();
                
                // Extract service information
                String status = (String) healthData.get("status");
                String version = (String) healthData.getOrDefault("version", "unknown");
                Boolean modelLoaded = (Boolean) healthData.getOrDefault("model_loaded", false);
                
                if ("healthy".equalsIgnoreCase(status)) {
                    return TranscriptionServiceHealthResult.healthy(version, modelLoaded);
                } else {
                    return TranscriptionServiceHealthResult.unhealthy("Service reported unhealthy status: " + status);
                }
            } else {
                return TranscriptionServiceHealthResult.unhealthy("Invalid response from transcription service");
            }
            
        } catch (RestClientException e) {
            logger.warn("Transcription service health check failed: {}", e.getMessage());
            return TranscriptionServiceHealthResult.unhealthy("Connection failed: " + e.getMessage());
        }
    }

    /**
     * Gets a human-readable performance rating based on response time.
     * 
     * @param responseTimeMs response time in milliseconds
     * @return performance rating string
     */
    private String getPerformanceRating(long responseTimeMs) {
        if (responseTimeMs <= GOOD_RESPONSE_TIME_MS) {
            return "EXCELLENT";
        } else if (responseTimeMs <= WARNING_RESPONSE_TIME_MS) {
            return "GOOD";
        } else if (responseTimeMs <= CRITICAL_RESPONSE_TIME_MS) {
            return "SLOW";
        } else {
            return "CRITICAL";
        }
    }

    /**
     * Result object for transcription service health checks.
     */
    private static class TranscriptionServiceHealthResult {
        private final boolean healthy;
        private final String errorMessage;
        private final String serviceVersion;
        private final boolean modelLoaded;

        private TranscriptionServiceHealthResult(boolean healthy, String errorMessage, String serviceVersion, boolean modelLoaded) {
            this.healthy = healthy;
            this.errorMessage = errorMessage;
            this.serviceVersion = serviceVersion;
            this.modelLoaded = modelLoaded;
        }

        public static TranscriptionServiceHealthResult healthy(String serviceVersion, boolean modelLoaded) {
            return new TranscriptionServiceHealthResult(true, null, serviceVersion, modelLoaded);
        }

        public static TranscriptionServiceHealthResult unhealthy(String errorMessage) {
            return new TranscriptionServiceHealthResult(false, errorMessage, "unknown", false);
        }

        public boolean isHealthy() {
            return healthy;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public String getServiceVersion() {
            return serviceVersion;
        }

        public boolean isModelLoaded() {
            return modelLoaded;
        }
    }
}
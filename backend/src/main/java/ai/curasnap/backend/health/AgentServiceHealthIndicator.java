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
 * Custom health indicator for the Agent Service (SOAP Note Generation).
 * 
 * This health indicator:
 * - Checks connectivity to the Agent Service
 * - Monitors service response times and AI model availability
 * - Provides graceful degradation when service is unavailable
 * - Supports cache-based fallback for SOAP generation
 * - Tracks service recovery and AI model health
 */
@Component
public class AgentServiceHealthIndicator implements HealthIndicator {

    private static final Logger logger = LoggerFactory.getLogger(AgentServiceHealthIndicator.class);

    private final RestTemplate restTemplate;
    private final String agentServiceUrl;
    private final int timeoutMs;
    private final boolean serviceEnabled;

    // Performance thresholds (milliseconds) - AI services typically slower
    private static final long GOOD_RESPONSE_TIME_MS = 1000;
    private static final long WARNING_RESPONSE_TIME_MS = 3000;
    private static final long CRITICAL_RESPONSE_TIME_MS = 8000;

    public AgentServiceHealthIndicator(
            RestTemplate restTemplate,
            @Value("${app.health.agent.url:http://localhost:8001/health}") String healthCheckUrl,
            @Value("${app.health.agent.timeout:3000}") int timeoutMs,
            @Value("${agent.service.enabled:true}") boolean serviceEnabled) {
        this.restTemplate = restTemplate;
        this.agentServiceUrl = healthCheckUrl;
        this.timeoutMs = timeoutMs;
        this.serviceEnabled = serviceEnabled;
    }

    @Override
    public Health health() {
        Health.Builder healthBuilder = new Health.Builder();

        if (!serviceEnabled) {
            return healthBuilder
                .status("DISABLED")
                .withDetail("service", "Agent Service")
                .withDetail("status", "Service disabled via configuration")
                .withDetail("url", agentServiceUrl)
                .withDetail("enabled", false)
                .build();
        }

        try {
            // Record start time for response time measurement
            Instant startTime = Instant.now();
            
            // Check agent service health
            AgentServiceHealthResult result = checkAgentServiceHealth();
            
            // Calculate response time
            long responseTimeMs = Duration.between(startTime, Instant.now()).toMillis();
            
            // Build health status based on results
            if (result.isHealthy()) {
                // Determine status based on response time and model availability
                String status = determineHealthStatus(responseTimeMs, result.isModelAvailable());
                healthBuilder.status(status);
                
                // Add service details
                healthBuilder
                    .withDetail("service", "Agent Service")
                    .withDetail("status", "Available")
                    .withDetail("url", agentServiceUrl)
                    .withDetail("response_time_ms", responseTimeMs)
                    .withDetail("performance_rating", getPerformanceRating(responseTimeMs))
                    .withDetail("service_version", result.getServiceVersion())
                    .withDetail("ai_model", result.getAiModel())
                    .withDetail("model_available", result.isModelAvailable())
                    .withDetail("model_loaded", result.isModelLoaded())
                    .withDetail("enabled", serviceEnabled)
                    .withDetail("cache_available", true) // Cache can provide fallback
                    .withDetail("last_check", Instant.now().toString());
                    
            } else {
                healthBuilder.down()
                    .withDetail("service", "Agent Service")
                    .withDetail("status", "Unavailable")
                    .withDetail("url", agentServiceUrl)
                    .withDetail("error", result.getErrorMessage())
                    .withDetail("response_time_ms", responseTimeMs)
                    .withDetail("enabled", serviceEnabled)
                    .withDetail("cache_available", true) // Cache can provide fallback
                    .withDetail("fallback_strategy", "Use cached SOAP notes if available")
                    .withDetail("last_check", Instant.now().toString());
            }
            
        } catch (Exception e) {
            logger.error("Agent service health check failed with unexpected error", e);
            healthBuilder.down()
                .withDetail("service", "Agent Service")
                .withDetail("status", "Error")
                .withDetail("url", agentServiceUrl)
                .withDetail("error", "Unexpected error during health check")
                .withDetail("enabled", serviceEnabled)
                .withDetail("cache_available", true)
                .withDetail("fallback_strategy", "Use cached SOAP notes if available")
                .withDetail("last_check", Instant.now().toString());
        }
        
        return healthBuilder.build();
    }

    /**
     * Performs the actual agent service health check.
     * 
     * @return AgentServiceHealthResult containing health status and details
     */
    private AgentServiceHealthResult checkAgentServiceHealth() {
        try {
            // Make HTTP request to agent service health endpoint
            ResponseEntity<Map> response = restTemplate.getForEntity(agentServiceUrl, Map.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> healthData = response.getBody();
                
                // Extract service information
                String status = (String) healthData.get("status");
                String version = (String) healthData.getOrDefault("version", "unknown");
                String aiModel = (String) healthData.getOrDefault("model", "unknown");
                Boolean modelAvailable = (Boolean) healthData.getOrDefault("model_available", false);
                Boolean modelLoaded = (Boolean) healthData.getOrDefault("model_loaded", false);
                
                if ("healthy".equalsIgnoreCase(status) || "ok".equalsIgnoreCase(status)) {
                    return AgentServiceHealthResult.healthy(version, aiModel, modelAvailable, modelLoaded);
                } else {
                    return AgentServiceHealthResult.unhealthy("Service reported unhealthy status: " + status);
                }
            } else {
                return AgentServiceHealthResult.unhealthy("Invalid response from agent service");
            }
            
        } catch (RestClientException e) {
            logger.warn("Agent service health check failed: {}", e.getMessage());
            return AgentServiceHealthResult.unhealthy("Connection failed: " + e.getMessage());
        }
    }

    /**
     * Determines overall health status based on response time and model availability.
     * 
     * @param responseTimeMs response time in milliseconds
     * @param modelAvailable whether the AI model is available
     * @return health status string
     */
    private String determineHealthStatus(long responseTimeMs, boolean modelAvailable) {
        if (!modelAvailable) {
            return "DEGRADED"; // Service works but AI model unavailable
        }
        
        if (responseTimeMs <= GOOD_RESPONSE_TIME_MS) {
            return "UP";
        } else if (responseTimeMs <= WARNING_RESPONSE_TIME_MS) {
            return "WARNING";
        } else if (responseTimeMs <= CRITICAL_RESPONSE_TIME_MS) {
            return "SLOW";
        } else {
            return "CRITICAL";
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
     * Result object for agent service health checks.
     */
    private static class AgentServiceHealthResult {
        private final boolean healthy;
        private final String errorMessage;
        private final String serviceVersion;
        private final String aiModel;
        private final boolean modelAvailable;
        private final boolean modelLoaded;

        private AgentServiceHealthResult(boolean healthy, String errorMessage, String serviceVersion, 
                                       String aiModel, boolean modelAvailable, boolean modelLoaded) {
            this.healthy = healthy;
            this.errorMessage = errorMessage;
            this.serviceVersion = serviceVersion;
            this.aiModel = aiModel;
            this.modelAvailable = modelAvailable;
            this.modelLoaded = modelLoaded;
        }

        public static AgentServiceHealthResult healthy(String serviceVersion, String aiModel, 
                                                     boolean modelAvailable, boolean modelLoaded) {
            return new AgentServiceHealthResult(true, null, serviceVersion, aiModel, modelAvailable, modelLoaded);
        }

        public static AgentServiceHealthResult unhealthy(String errorMessage) {
            return new AgentServiceHealthResult(false, errorMessage, "unknown", "unknown", false, false);
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

        public String getAiModel() {
            return aiModel;
        }

        public boolean isModelAvailable() {
            return modelAvailable;
        }

        public boolean isModelLoaded() {
            return modelLoaded;
        }
    }
}
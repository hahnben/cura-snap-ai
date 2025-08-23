package ai.curasnap.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service Degradation Handler for graceful system degradation during service outages
 * 
 * Features:
 * - Automatic degradation level detection based on service health
 * - Fallback mechanisms for critical services
 * - Progressive degradation with multiple levels
 * - Service restoration detection and recovery
 * - User-friendly error messages during degradation
 * - Performance optimization during degraded states
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.degradation.enabled", havingValue = "true", matchIfMissing = true)
public class ServiceDegradationService {

    private final CircuitBreakerService circuitBreakerService;
    private final WorkerHealthService workerHealthService;
    private final RedisTemplate<String, Object> redisTemplate;

    // Current system degradation state
    private final AtomicReference<DegradationLevel> currentDegradationLevel = 
        new AtomicReference<>(DegradationLevel.NORMAL);
    
    // Service-specific degradation tracking
    private final Map<String, ServiceDegradationState> serviceDegradationStates = new ConcurrentHashMap<>();
    
    // Degradation triggers and thresholds
    private final AtomicBoolean degradationActive = new AtomicBoolean(false);
    private volatile Instant lastDegradationCheck = Instant.now();
    
    // Redis keys for degradation state
    private static final String DEGRADATION_STATE_KEY = "system_degradation_state";
    private static final String SERVICE_DEGRADATION_KEY_PREFIX = "service_degradation:";

    @Autowired
    public ServiceDegradationService(CircuitBreakerService circuitBreakerService,
                                   WorkerHealthService workerHealthService,
                                   RedisTemplate<String, Object> redisTemplate) {
        this.circuitBreakerService = circuitBreakerService;
        this.workerHealthService = workerHealthService;
        this.redisTemplate = redisTemplate;
        
        // Initialize service degradation states
        initializeServiceStates();
        
        log.info("ServiceDegradationService initialized with graceful degradation handling");
    }

    /**
     * System degradation levels
     */
    public enum DegradationLevel {
        NORMAL(0, "All systems operational"),
        MINOR_DEGRADATION(1, "Minor performance impact - some features may be slower"),
        MODERATE_DEGRADATION(2, "Moderate degradation - reduced functionality available"),
        MAJOR_DEGRADATION(3, "Major degradation - core features only"),
        CRITICAL_DEGRADATION(4, "Critical degradation - essential services only"),
        MAINTENANCE_MODE(5, "System in maintenance mode");

        private final int severity;
        private final String description;

        DegradationLevel(int severity, String description) {
            this.severity = severity;
            this.description = description;
        }

        public int getSeverity() { return severity; }
        public String getDescription() { return description; }

        public boolean isWorseThan(DegradationLevel other) {
            return this.severity > other.severity;
        }
    }

    /**
     * Service-specific degradation state
     */
    public static class ServiceDegradationState {
        private final String serviceName;
        private volatile boolean isHealthy;
        private volatile boolean isDegraded;
        private volatile DegradationLevel degradationLevel;
        private volatile Instant lastHealthyTime;
        private volatile Instant lastCheckedTime;
        private volatile String degradationReason;
        private final Map<String, Object> fallbackConfig;

        public ServiceDegradationState(String serviceName) {
            this.serviceName = serviceName;
            this.isHealthy = true;
            this.isDegraded = false;
            this.degradationLevel = DegradationLevel.NORMAL;
            this.lastHealthyTime = Instant.now();
            this.lastCheckedTime = Instant.now();
            this.degradationReason = null;
            this.fallbackConfig = new HashMap<>();
        }

        // Getters and setters
        public String getServiceName() { return serviceName; }
        public boolean isHealthy() { return isHealthy; }
        public void setHealthy(boolean healthy) { this.isHealthy = healthy; }
        public boolean isDegraded() { return isDegraded; }
        public void setDegraded(boolean degraded) { this.isDegraded = degraded; }
        public DegradationLevel getDegradationLevel() { return degradationLevel; }
        public void setDegradationLevel(DegradationLevel degradationLevel) { this.degradationLevel = degradationLevel; }
        public Instant getLastHealthyTime() { return lastHealthyTime; }
        public void setLastHealthyTime(Instant lastHealthyTime) { this.lastHealthyTime = lastHealthyTime; }
        public Instant getLastCheckedTime() { return lastCheckedTime; }
        public void setLastCheckedTime(Instant lastCheckedTime) { this.lastCheckedTime = lastCheckedTime; }
        public String getDegradationReason() { return degradationReason; }
        public void setDegradationReason(String degradationReason) { this.degradationReason = degradationReason; }
        public Map<String, Object> getFallbackConfig() { return fallbackConfig; }
    }

    /**
     * Initialize service degradation states for known services
     */
    private void initializeServiceStates() {
        // Initialize states for core services
        serviceDegradationStates.put("transcription-service", new ServiceDegradationState("transcription-service"));
        serviceDegradationStates.put("agent-service", new ServiceDegradationState("agent-service"));
        serviceDegradationStates.put("redis", new ServiceDegradationState("redis"));
        serviceDegradationStates.put("database", new ServiceDegradationState("database"));

        // Configure fallback options
        configureFallbackOptions();
        
        log.info("Initialized degradation states for {} services", serviceDegradationStates.size());
    }

    /**
     * Configure fallback options for each service
     */
    private void configureFallbackOptions() {
        ServiceDegradationState transcriptionState = serviceDegradationStates.get("transcription-service");
        transcriptionState.getFallbackConfig().put("fallbackMessage", 
            "Transcription service temporarily unavailable. Please try again later or upload your audio file for processing when service is restored.");
        transcriptionState.getFallbackConfig().put("queueForLater", true);
        transcriptionState.getFallbackConfig().put("notifyWhenAvailable", true);

        ServiceDegradationState agentState = serviceDegradationStates.get("agent-service");
        agentState.getFallbackConfig().put("fallbackMessage", 
            "SOAP note generation temporarily unavailable. Your transcript has been saved and will be processed when service is restored.");
        agentState.getFallbackConfig().put("returnRawTranscript", true);
        agentState.getFallbackConfig().put("queueForProcessing", true);

        ServiceDegradationState redisState = serviceDegradationStates.get("redis");
        redisState.getFallbackConfig().put("fallbackMessage", 
            "Background processing unavailable. Jobs will be processed synchronously with reduced performance.");
        redisState.getFallbackConfig().put("synchronousMode", true);
        redisState.getFallbackConfig().put("disableJobQueue", true);
    }

    /**
     * Scheduled health check and degradation assessment
     */
    @Scheduled(fixedDelay = 30000) // Every 30 seconds
    public void assessSystemDegradation() {
        try {
            lastDegradationCheck = Instant.now();
            
            // Check individual service health
            checkServiceHealth();
            
            // Calculate overall system degradation level
            DegradationLevel newDegradationLevel = calculateSystemDegradationLevel();
            
            // Update degradation state if changed
            if (!newDegradationLevel.equals(currentDegradationLevel.get())) {
                updateDegradationLevel(newDegradationLevel);
            }
            
            // Store degradation state in Redis for other services
            persistDegradationState();
            
        } catch (Exception e) {
            log.error("Failed to assess system degradation: {}", e.getMessage(), e);
        }
    }

    /**
     * Check health of individual services
     */
    private void checkServiceHealth() {
        // Check transcription service via circuit breaker
        checkServiceThroughCircuitBreaker("transcription-service");
        
        // Check agent service via circuit breaker  
        checkServiceThroughCircuitBreaker("agent-service");
        
        // Check Redis health
        checkRedisHealth();
        
        // Check worker health
        checkWorkerHealth();
    }

    /**
     * Check service health through circuit breaker
     */
    private void checkServiceThroughCircuitBreaker(String serviceName) {
        try {
            ServiceDegradationState state = serviceDegradationStates.get(serviceName);
            if (state == null) return;

            CircuitBreakerService.CircuitBreakerState circuitState = 
                circuitBreakerService.getCircuitBreakerState(serviceName);

            state.setLastCheckedTime(Instant.now());

            if (circuitState == CircuitBreakerService.CircuitBreakerState.OPEN) {
                // Circuit breaker is open - service is unhealthy
                if (state.isHealthy()) {
                    state.setHealthy(false);
                    state.setDegraded(true);
                    state.setDegradationLevel(DegradationLevel.MAJOR_DEGRADATION);
                    state.setDegradationReason("Circuit breaker open - service unavailable");
                    log.warn("Service {} degraded: Circuit breaker open", serviceName);
                }
            } else if (circuitState == CircuitBreakerService.CircuitBreakerState.HALF_OPEN) {
                // Circuit breaker is half-open - service is recovering
                state.setDegraded(true);
                state.setDegradationLevel(DegradationLevel.MODERATE_DEGRADATION);
                state.setDegradationReason("Circuit breaker half-open - service recovering");
            } else {
                // Circuit breaker is closed - service appears healthy
                if (!state.isHealthy()) {
                    state.setHealthy(true);
                    state.setDegraded(false);
                    state.setDegradationLevel(DegradationLevel.NORMAL);
                    state.setLastHealthyTime(Instant.now());
                    state.setDegradationReason(null);
                    log.info("Service {} recovered: Circuit breaker closed", serviceName);
                }
            }

        } catch (Exception e) {
            log.error("Failed to check service {} through circuit breaker: {}", serviceName, e.getMessage());
        }
    }

    /**
     * Check Redis health
     */
    private void checkRedisHealth() {
        try {
            ServiceDegradationState state = serviceDegradationStates.get("redis");
            if (state == null) return;

            state.setLastCheckedTime(Instant.now());

            // Simple Redis ping test
            String testKey = "health_check_" + System.currentTimeMillis();
            redisTemplate.opsForValue().set(testKey, "test", Duration.ofSeconds(5));
            String result = (String) redisTemplate.opsForValue().get(testKey);
            redisTemplate.delete(testKey);

            if ("test".equals(result)) {
                if (!state.isHealthy()) {
                    state.setHealthy(true);
                    state.setDegraded(false);
                    state.setDegradationLevel(DegradationLevel.NORMAL);
                    state.setLastHealthyTime(Instant.now());
                    state.setDegradationReason(null);
                    log.info("Redis service recovered");
                }
            } else {
                throw new Exception("Redis health check failed - unexpected result");
            }

        } catch (Exception e) {
            ServiceDegradationState state = serviceDegradationStates.get("redis");
            if (state != null && state.isHealthy()) {
                state.setHealthy(false);
                state.setDegraded(true);
                state.setDegradationLevel(DegradationLevel.MAJOR_DEGRADATION);
                state.setDegradationReason("Redis connectivity issues: " + e.getMessage());
                log.error("Redis service degraded: {}", e.getMessage());
            }
        }
    }

    /**
     * Check worker health
     */
    private void checkWorkerHealth() {
        try {
            WorkerHealthService.SystemHealthReport healthReport = workerHealthService.getSystemHealthReport();
            
            // Determine worker degradation based on health report
            int healthScore = healthReport.getHealthScore();
            boolean workerDegraded = healthScore < 70; // Health score below 70% indicates degradation
            
            // For simplicity, we'll use the transcription-service state to represent worker health
            // In a real system, you might have a separate "workers" service state
            ServiceDegradationState transcriptionState = serviceDegradationStates.get("transcription-service");
            if (transcriptionState != null && workerDegraded) {
                if (transcriptionState.getDegradationLevel() == DegradationLevel.NORMAL) {
                    transcriptionState.setDegradationLevel(DegradationLevel.MINOR_DEGRADATION);
                    transcriptionState.setDegradationReason("Worker performance degraded - health score: " + healthScore);
                    log.warn("Worker degradation detected: health score {}", healthScore);
                }
            }

        } catch (Exception e) {
            log.error("Failed to check worker health: {}", e.getMessage());
        }
    }

    /**
     * Calculate overall system degradation level based on individual services
     */
    private DegradationLevel calculateSystemDegradationLevel() {
        DegradationLevel maxDegradation = DegradationLevel.NORMAL;
        
        for (ServiceDegradationState state : serviceDegradationStates.values()) {
            if (state.getDegradationLevel().isWorseThan(maxDegradation)) {
                maxDegradation = state.getDegradationLevel();
            }
        }
        
        return maxDegradation;
    }

    /**
     * Update system degradation level
     */
    private void updateDegradationLevel(DegradationLevel newLevel) {
        DegradationLevel previousLevel = currentDegradationLevel.getAndSet(newLevel);
        
        boolean wasNormal = previousLevel == DegradationLevel.NORMAL;
        boolean isNowNormal = newLevel == DegradationLevel.NORMAL;
        
        if (wasNormal && !isNowNormal) {
            // System entering degraded state
            degradationActive.set(true);
            log.warn("System degradation detected: {} -> {} ({})", 
                    previousLevel, newLevel, newLevel.getDescription());
        } else if (!wasNormal && isNowNormal) {
            // System recovering from degraded state
            degradationActive.set(false);
            log.info("System recovered from degradation: {} -> {} ({})", 
                    previousLevel, newLevel, newLevel.getDescription());
        } else if (!wasNormal && !isNowNormal) {
            // Degradation level changed but still degraded
            log.warn("System degradation level changed: {} -> {} ({})", 
                    previousLevel, newLevel, newLevel.getDescription());
        }
    }

    /**
     * Persist degradation state to Redis for other services
     */
    private void persistDegradationState() {
        try {
            Map<String, Object> degradationState = new HashMap<>();
            degradationState.put("level", currentDegradationLevel.get().name());
            degradationState.put("severity", currentDegradationLevel.get().getSeverity());
            degradationState.put("description", currentDegradationLevel.get().getDescription());
            degradationState.put("active", degradationActive.get());
            degradationState.put("lastUpdated", Instant.now().toString());
            
            // Add service-specific states
            Map<String, Object> serviceStates = new HashMap<>();
            for (Map.Entry<String, ServiceDegradationState> entry : serviceDegradationStates.entrySet()) {
                ServiceDegradationState state = entry.getValue();
                Map<String, Object> stateInfo = new HashMap<>();
                stateInfo.put("healthy", state.isHealthy());
                stateInfo.put("degraded", state.isDegraded());
                stateInfo.put("level", state.getDegradationLevel().name());
                stateInfo.put("reason", state.getDegradationReason());
                stateInfo.put("lastHealthy", state.getLastHealthyTime().toString());
                serviceStates.put(entry.getKey(), stateInfo);
            }
            degradationState.put("services", serviceStates);
            
            redisTemplate.opsForValue().set(DEGRADATION_STATE_KEY, degradationState, Duration.ofMinutes(5));
            
        } catch (Exception e) {
            log.error("Failed to persist degradation state: {}", e.getMessage());
        }
    }

    /**
     * Public API methods
     */

    public DegradationLevel getCurrentDegradationLevel() {
        return currentDegradationLevel.get();
    }

    public boolean isSystemDegraded() {
        return degradationActive.get();
    }

    public ServiceDegradationState getServiceDegradationState(String serviceName) {
        return serviceDegradationStates.get(serviceName);
    }

    public Map<String, ServiceDegradationState> getAllServiceStates() {
        return Collections.unmodifiableMap(serviceDegradationStates);
    }

    /**
     * Get user-friendly error message for current degradation state
     */
    public String getDegradationMessage() {
        DegradationLevel level = currentDegradationLevel.get();
        if (level == DegradationLevel.NORMAL) {
            return null;
        }
        
        return level.getDescription();
    }

    /**
     * Get service-specific degradation message
     */
    public String getServiceDegradationMessage(String serviceName) {
        ServiceDegradationState state = serviceDegradationStates.get(serviceName);
        if (state == null || !state.isDegraded()) {
            return null;
        }
        
        Map<String, Object> config = state.getFallbackConfig();
        return (String) config.get("fallbackMessage");
    }

    /**
     * Check if a service supports fallback behavior
     */
    public boolean hasFallbackSupport(String serviceName) {
        ServiceDegradationState state = serviceDegradationStates.get(serviceName);
        return state != null && !state.getFallbackConfig().isEmpty();
    }

    /**
     * Get fallback configuration for a service
     */
    public Map<String, Object> getFallbackConfig(String serviceName) {
        ServiceDegradationState state = serviceDegradationStates.get(serviceName);
        return state != null ? state.getFallbackConfig() : Collections.emptyMap();
    }

    /**
     * Manual override for degradation level (for testing or maintenance)
     */
    public void setManualDegradationLevel(DegradationLevel level, String reason) {
        log.warn("Manual degradation override: {} - {}", level, reason);
        updateDegradationLevel(level);
        
        // Mark all services with the manual reason
        for (ServiceDegradationState state : serviceDegradationStates.values()) {
            state.setDegradationLevel(level);
            state.setDegradationReason("Manual override: " + reason);
        }
    }

    /**
     * Clear manual degradation override
     */
    public void clearManualDegradationOverride() {
        log.info("Clearing manual degradation override - returning to automatic assessment");
        // The next scheduled assessment will restore normal operation
    }
}
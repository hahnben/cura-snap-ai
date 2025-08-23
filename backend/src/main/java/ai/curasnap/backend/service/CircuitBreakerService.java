package ai.curasnap.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Circuit Breaker implementation for external service calls
 * Implements the Circuit Breaker pattern to handle external service failures gracefully
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.circuit-breaker.enabled", havingValue = "true", matchIfMissing = true)
public class CircuitBreakerService {

    @Value("${app.circuit-breaker.failure-threshold:5}")
    private int failureThreshold;

    @Value("${app.circuit-breaker.timeout-duration:60000}")
    private long timeoutDurationMs;

    @Value("${app.circuit-breaker.success-threshold:3}")
    private int successThreshold;

    @Value("${app.circuit-breaker.request-volume-threshold:10}")
    private int requestVolumeThreshold;

    @Value("${app.circuit-breaker.error-percentage-threshold:50}")
    private int errorPercentageThreshold;

    // Circuit breakers per service
    private final Map<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

    @PostConstruct
    public void initialize() {
        log.info("Circuit Breaker Service initialized - Failure threshold: {}, Timeout: {}ms, Success threshold: {}", 
                failureThreshold, timeoutDurationMs, successThreshold);
    }

    /**
     * Execute a service call with circuit breaker protection
     *
     * @param serviceName the name of the service being called
     * @param serviceCall the service call to execute
     * @param fallback optional fallback function to execute if circuit is open
     * @param <T> return type
     * @return result of service call or fallback
     * @throws CircuitBreakerException if circuit is open and no fallback provided
     */
    public <T> T executeWithCircuitBreaker(String serviceName, 
                                         Supplier<T> serviceCall, 
                                         Supplier<T> fallback) throws CircuitBreakerException {
        CircuitBreaker circuitBreaker = getOrCreateCircuitBreaker(serviceName);
        return circuitBreaker.execute(serviceCall, fallback);
    }

    /**
     * Execute a service call with circuit breaker protection (no fallback)
     *
     * @param serviceName the name of the service being called
     * @param serviceCall the service call to execute
     * @param <T> return type
     * @return result of service call
     * @throws CircuitBreakerException if circuit is open or service call fails
     */
    public <T> T executeWithCircuitBreaker(String serviceName, Supplier<T> serviceCall) throws CircuitBreakerException {
        return executeWithCircuitBreaker(serviceName, serviceCall, null);
    }

    /**
     * Get circuit breaker state for a service
     *
     * @param serviceName the service name
     * @return circuit breaker state
     */
    public CircuitBreakerState getCircuitBreakerState(String serviceName) {
        CircuitBreaker circuitBreaker = circuitBreakers.get(serviceName);
        if (circuitBreaker == null) {
            return CircuitBreakerState.CLOSED; // Default state for non-existent circuit breakers
        }
        return circuitBreaker.getState();
    }

    /**
     * Get circuit breaker statistics for a service
     *
     * @param serviceName the service name
     * @return circuit breaker statistics or null if not found
     */
    public CircuitBreakerStats getCircuitBreakerStats(String serviceName) {
        CircuitBreaker circuitBreaker = circuitBreakers.get(serviceName);
        return circuitBreaker != null ? circuitBreaker.getStats() : null;
    }

    /**
     * Get all circuit breaker statistics
     *
     * @return map of service name to circuit breaker statistics
     */
    public Map<String, CircuitBreakerStats> getAllCircuitBreakerStats() {
        Map<String, CircuitBreakerStats> stats = new ConcurrentHashMap<>();
        circuitBreakers.forEach((serviceName, circuitBreaker) -> 
                stats.put(serviceName, circuitBreaker.getStats()));
        return stats;
    }

    /**
     * Manually reset a circuit breaker to CLOSED state
     *
     * @param serviceName the service name
     * @return true if reset successful, false if circuit breaker not found
     */
    public boolean resetCircuitBreaker(String serviceName) {
        CircuitBreaker circuitBreaker = circuitBreakers.get(serviceName);
        if (circuitBreaker != null) {
            circuitBreaker.reset();
            log.info("Circuit breaker for service {} manually reset to CLOSED", serviceName);
            return true;
        }
        return false;
    }

    /**
     * Manually open a circuit breaker
     *
     * @param serviceName the service name
     * @return true if opened successfully, false if circuit breaker not found
     */
    public boolean openCircuitBreaker(String serviceName) {
        CircuitBreaker circuitBreaker = circuitBreakers.get(serviceName);
        if (circuitBreaker != null) {
            circuitBreaker.forceOpen();
            log.warn("Circuit breaker for service {} manually opened", serviceName);
            return true;
        }
        return false;
    }

    // Private methods

    private CircuitBreaker getOrCreateCircuitBreaker(String serviceName) {
        return circuitBreakers.computeIfAbsent(serviceName, name -> {
            log.info("Creating circuit breaker for service: {}", name);
            return new CircuitBreaker(name, failureThreshold, timeoutDurationMs, successThreshold, 
                    requestVolumeThreshold, errorPercentageThreshold);
        });
    }

    // Inner classes

    /**
     * Circuit breaker states
     */
    public enum CircuitBreakerState {
        CLOSED,    // Normal operation, allowing requests
        OPEN,      // Circuit is open, blocking requests
        HALF_OPEN  // Testing if service has recovered
    }

    /**
     * Circuit breaker statistics
     */
    public static class CircuitBreakerStats {
        private final String serviceName;
        private final CircuitBreakerState state;
        private final long totalRequests;
        private final long successfulRequests;
        private final long failedRequests;
        private final long rejectedRequests;
        private final double failureRate;
        private final Instant lastFailureTime;
        private final Instant lastSuccessTime;
        private final Instant stateChangedAt;
        private final Duration timeInCurrentState;

        public CircuitBreakerStats(String serviceName, CircuitBreakerState state, long totalRequests,
                                 long successfulRequests, long failedRequests, long rejectedRequests,
                                 Instant lastFailureTime, Instant lastSuccessTime, Instant stateChangedAt) {
            this.serviceName = serviceName;
            this.state = state;
            this.totalRequests = totalRequests;
            this.successfulRequests = successfulRequests;
            this.failedRequests = failedRequests;
            this.rejectedRequests = rejectedRequests;
            this.failureRate = totalRequests > 0 ? (double) failedRequests / totalRequests * 100.0 : 0.0;
            this.lastFailureTime = lastFailureTime;
            this.lastSuccessTime = lastSuccessTime;
            this.stateChangedAt = stateChangedAt;
            this.timeInCurrentState = stateChangedAt != null ? Duration.between(stateChangedAt, Instant.now()) : Duration.ZERO;
        }

        // Getters
        public String getServiceName() { return serviceName; }
        public CircuitBreakerState getState() { return state; }
        public long getTotalRequests() { return totalRequests; }
        public long getSuccessfulRequests() { return successfulRequests; }
        public long getFailedRequests() { return failedRequests; }
        public long getRejectedRequests() { return rejectedRequests; }
        public double getFailureRate() { return failureRate; }
        public Instant getLastFailureTime() { return lastFailureTime; }
        public Instant getLastSuccessTime() { return lastSuccessTime; }
        public Instant getStateChangedAt() { return stateChangedAt; }
        public Duration getTimeInCurrentState() { return timeInCurrentState; }
    }

    /**
     * Individual circuit breaker implementation
     */
    private static class CircuitBreaker {
        private final String serviceName;
        private final int failureThreshold;
        private final long timeoutDurationMs;
        private final int successThreshold;
        private final int requestVolumeThreshold;
        private final int errorPercentageThreshold;

        private final AtomicReference<CircuitBreakerState> state = new AtomicReference<>(CircuitBreakerState.CLOSED);
        private final AtomicLong totalRequests = new AtomicLong(0);
        private final AtomicLong successfulRequests = new AtomicLong(0);
        private final AtomicLong failedRequests = new AtomicLong(0);
        private final AtomicLong rejectedRequests = new AtomicLong(0);
        private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        private final AtomicInteger consecutiveSuccesses = new AtomicInteger(0);
        private final AtomicReference<Instant> lastFailureTime = new AtomicReference<>();
        private final AtomicReference<Instant> lastSuccessTime = new AtomicReference<>();
        private final AtomicReference<Instant> stateChangedAt = new AtomicReference<>(Instant.now());

        public CircuitBreaker(String serviceName, int failureThreshold, long timeoutDurationMs, 
                            int successThreshold, int requestVolumeThreshold, int errorPercentageThreshold) {
            this.serviceName = serviceName;
            this.failureThreshold = failureThreshold;
            this.timeoutDurationMs = timeoutDurationMs;
            this.successThreshold = successThreshold;
            this.requestVolumeThreshold = requestVolumeThreshold;
            this.errorPercentageThreshold = errorPercentageThreshold;
        }

        public <T> T execute(Supplier<T> serviceCall, Supplier<T> fallback) throws CircuitBreakerException {
            totalRequests.incrementAndGet();

            CircuitBreakerState currentState = state.get();

            switch (currentState) {
                case CLOSED:
                    return executeInClosedState(serviceCall, fallback);
                case OPEN:
                    return executeInOpenState(serviceCall, fallback);
                case HALF_OPEN:
                    return executeInHalfOpenState(serviceCall, fallback);
                default:
                    throw new IllegalStateException("Unknown circuit breaker state: " + currentState);
            }
        }

        private <T> T executeInClosedState(Supplier<T> serviceCall, Supplier<T> fallback) throws CircuitBreakerException {
            try {
                T result = serviceCall.get();
                onSuccess();
                return result;
            } catch (Exception e) {
                onFailure();
                
                // Check if we should open the circuit
                if (shouldOpenCircuit()) {
                    transitionToOpen();
                }
                
                if (fallback != null) {
                    return fallback.get();
                }
                throw new CircuitBreakerException("Service call failed in CLOSED state", e);
            }
        }

        private <T> T executeInOpenState(Supplier<T> serviceCall, Supplier<T> fallback) throws CircuitBreakerException {
            rejectedRequests.incrementAndGet();
            
            // Check if timeout has elapsed to try half-open
            Instant lastFailure = lastFailureTime.get();
            if (lastFailure != null && Duration.between(lastFailure, Instant.now()).toMillis() > timeoutDurationMs) {
                transitionToHalfOpen();
                return executeInHalfOpenState(serviceCall, fallback);
            }
            
            if (fallback != null) {
                return fallback.get();
            }
            throw new CircuitBreakerException("Circuit breaker is OPEN for service: " + serviceName);
        }

        private <T> T executeInHalfOpenState(Supplier<T> serviceCall, Supplier<T> fallback) throws CircuitBreakerException {
            try {
                T result = serviceCall.get();
                onSuccess();
                
                // Check if we have enough successful requests to close the circuit
                if (consecutiveSuccesses.get() >= successThreshold) {
                    transitionToClosed();
                }
                
                return result;
            } catch (Exception e) {
                onFailure();
                transitionToOpen();
                
                if (fallback != null) {
                    return fallback.get();
                }
                throw new CircuitBreakerException("Service call failed in HALF_OPEN state", e);
            }
        }

        private void onSuccess() {
            successfulRequests.incrementAndGet();
            consecutiveSuccesses.incrementAndGet();
            consecutiveFailures.set(0);
            lastSuccessTime.set(Instant.now());
        }

        private void onFailure() {
            failedRequests.incrementAndGet();
            consecutiveFailures.incrementAndGet();
            consecutiveSuccesses.set(0);
            lastFailureTime.set(Instant.now());
        }

        private boolean shouldOpenCircuit() {
            long total = totalRequests.get();
            long failed = failedRequests.get();
            
            // Need minimum request volume before considering circuit opening
            if (total < requestVolumeThreshold) {
                return false;
            }
            
            // Check failure threshold
            if (consecutiveFailures.get() >= failureThreshold) {
                return true;
            }
            
            // Check error percentage threshold
            double errorPercentage = (double) failed / total * 100.0;
            return errorPercentage >= errorPercentageThreshold;
        }

        private void transitionToOpen() {
            if (state.compareAndSet(CircuitBreakerState.CLOSED, CircuitBreakerState.OPEN) ||
                state.compareAndSet(CircuitBreakerState.HALF_OPEN, CircuitBreakerState.OPEN)) {
                stateChangedAt.set(Instant.now());
                log.warn("Circuit breaker for service {} transitioned to OPEN state", serviceName);
            }
        }

        private void transitionToHalfOpen() {
            if (state.compareAndSet(CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN)) {
                stateChangedAt.set(Instant.now());
                consecutiveSuccesses.set(0);
                log.info("Circuit breaker for service {} transitioned to HALF_OPEN state", serviceName);
            }
        }

        private void transitionToClosed() {
            if (state.compareAndSet(CircuitBreakerState.HALF_OPEN, CircuitBreakerState.CLOSED)) {
                stateChangedAt.set(Instant.now());
                consecutiveFailures.set(0);
                log.info("Circuit breaker for service {} transitioned to CLOSED state", serviceName);
            }
        }

        public void reset() {
            state.set(CircuitBreakerState.CLOSED);
            stateChangedAt.set(Instant.now());
            consecutiveFailures.set(0);
            consecutiveSuccesses.set(0);
        }

        public void forceOpen() {
            state.set(CircuitBreakerState.OPEN);
            stateChangedAt.set(Instant.now());
        }

        public CircuitBreakerState getState() {
            return state.get();
        }

        public CircuitBreakerStats getStats() {
            return new CircuitBreakerStats(
                serviceName,
                state.get(),
                totalRequests.get(),
                successfulRequests.get(),
                failedRequests.get(),
                rejectedRequests.get(),
                lastFailureTime.get(),
                lastSuccessTime.get(),
                stateChangedAt.get()
            );
        }
    }

    /**
     * Exception thrown when circuit breaker is open or service call fails
     */
    public static class CircuitBreakerException extends Exception {
        public CircuitBreakerException(String message) {
            super(message);
        }

        public CircuitBreakerException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
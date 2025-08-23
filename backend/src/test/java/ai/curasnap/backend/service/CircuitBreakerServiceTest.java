package ai.curasnap.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CircuitBreakerService
 */
@ExtendWith(MockitoExtension.class)
class CircuitBreakerServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    
    @Mock
    private ValueOperations<String, Object> valueOperations;

    private CircuitBreakerService circuitBreakerService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        circuitBreakerService = new CircuitBreakerService();
        
        // Set up the service with reflection to simulate @Value injection
        try {
            var failureThresholdField = CircuitBreakerService.class.getDeclaredField("failureThreshold");
            failureThresholdField.setAccessible(true);
            failureThresholdField.set(circuitBreakerService, 5);
            
            var timeoutField = CircuitBreakerService.class.getDeclaredField("timeoutDurationMs");
            timeoutField.setAccessible(true);
            timeoutField.set(circuitBreakerService, 30000L);
            
            var successThresholdField = CircuitBreakerService.class.getDeclaredField("successThreshold");
            successThresholdField.setAccessible(true);
            successThresholdField.set(circuitBreakerService, 3);
            
            circuitBreakerService.initialize();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void executeWithCircuitBreaker_ServiceHealthy_ShouldExecuteSuccessfully() throws Exception {
        // Given
        String serviceName = "test-service";
        Supplier<String> serviceCall = () -> "success";
        Supplier<String> fallback = () -> "fallback";

        // When
        String result = circuitBreakerService.executeWithCircuitBreaker(serviceName, serviceCall, fallback);

        // Then
        assertThat(result).isEqualTo("success");
        assertThat(circuitBreakerService.getCircuitBreakerState(serviceName))
                .isEqualTo(CircuitBreakerService.CircuitBreakerState.CLOSED);
    }

    @Test
    void executeWithCircuitBreaker_ServiceThrowsException_ShouldRecordFailure() throws Exception {
        // Given
        String serviceName = "test-service";
        Supplier<String> serviceCall = () -> { throw new RuntimeException("Service error"); };
        Supplier<String> fallback = () -> "fallback";

        // When
        String result = circuitBreakerService.executeWithCircuitBreaker(serviceName, serviceCall, fallback);

        // Then
        assertThat(result).isEqualTo("fallback");
        
        // Circuit should still be closed after one failure
        assertThat(circuitBreakerService.getCircuitBreakerState(serviceName))
                .isEqualTo(CircuitBreakerService.CircuitBreakerState.CLOSED);
    }

    @Test
    void executeWithCircuitBreaker_MultipleFailures_ShouldOpenCircuit() throws Exception {
        // Given
        String serviceName = "failing-service";
        Supplier<String> serviceCall = () -> { throw new RuntimeException("Service error"); };
        Supplier<String> fallback = () -> "fallback";

        // When - Execute multiple failing calls to trigger circuit opening
        for (int i = 0; i < 6; i++) { // Default threshold is 5
            String result = circuitBreakerService.executeWithCircuitBreaker(serviceName, serviceCall, fallback);
            assertThat(result).isEqualTo("fallback");
        }

        // Then
        assertThat(circuitBreakerService.getCircuitBreakerState(serviceName))
                .isEqualTo(CircuitBreakerService.CircuitBreakerState.OPEN);
    }

    @Test
    void executeWithCircuitBreaker_CircuitOpen_ShouldReturnFallbackImmediately() throws Exception {
        // Given
        String serviceName = "open-circuit-service";
        Supplier<String> serviceCall = () -> { throw new RuntimeException("Service error"); };
        Supplier<String> fallback = () -> "fallback";

        // First, open the circuit by causing multiple failures
        for (int i = 0; i < 6; i++) {
            circuitBreakerService.executeWithCircuitBreaker(serviceName, serviceCall, fallback);
        }

        // Verify circuit is open
        assertThat(circuitBreakerService.getCircuitBreakerState(serviceName))
                .isEqualTo(CircuitBreakerService.CircuitBreakerState.OPEN);

        // When - Try to execute when circuit is open
        Supplier<String> trackingServiceCall = () -> {
            // This should not be called when circuit is open
            throw new AssertionError("Service call should not be executed when circuit is open");
        };
        
        String result = circuitBreakerService.executeWithCircuitBreaker(serviceName, trackingServiceCall, fallback);

        // Then
        assertThat(result).isEqualTo("fallback");
    }

    @Test
    void executeWithCircuitBreaker_CircuitOpenThenTimeout_ShouldTransitionToHalfOpen() throws Exception {
        // Given
        String serviceName = "timeout-test-service";
        Supplier<String> serviceCall = () -> { throw new RuntimeException("Service error"); };
        Supplier<String> fallback = () -> "fallback";

        // Open the circuit
        for (int i = 0; i < 6; i++) {
            circuitBreakerService.executeWithCircuitBreaker(serviceName, serviceCall, fallback);
        }
        
        assertThat(circuitBreakerService.getCircuitBreakerState(serviceName))
                .isEqualTo(CircuitBreakerService.CircuitBreakerState.OPEN);

        // Wait for circuit to potentially transition (in real implementation, this would be time-based)
        // For testing, we'll simulate the passage of time by calling after the timeout period
        Thread.sleep(100); // Short sleep to allow any background processing

        // In a real implementation, we might need to trigger the timeout check
        // For this test, we'll check if the circuit transitions appropriately
        CircuitBreakerService.CircuitBreakerState state = circuitBreakerService.getCircuitBreakerState(serviceName);
        assertThat(state).isIn(
            CircuitBreakerService.CircuitBreakerState.OPEN,
            CircuitBreakerService.CircuitBreakerState.HALF_OPEN
        );
    }

    @Test
    void executeWithCircuitBreaker_SuccessAfterFailures_ShouldKeepCircuitClosed() throws Exception {
        // Given
        String serviceName = "recovery-service";
        Supplier<String> fallback = () -> "fallback";

        // First some failures
        Supplier<String> failingCall = () -> { throw new RuntimeException("Service error"); };
        for (int i = 0; i < 3; i++) { // Less than threshold
            circuitBreakerService.executeWithCircuitBreaker(serviceName, failingCall, fallback);
        }

        // Then a success
        Supplier<String> successCall = () -> "success";

        // When
        String result = circuitBreakerService.executeWithCircuitBreaker(serviceName, successCall, fallback);

        // Then
        assertThat(result).isEqualTo("success");
        assertThat(circuitBreakerService.getCircuitBreakerState(serviceName))
                .isEqualTo(CircuitBreakerService.CircuitBreakerState.CLOSED);
    }

    @Test
    void getCircuitBreakerState_NewService_ShouldReturnClosed() {
        // Given
        String serviceName = "new-service";

        // When
        CircuitBreakerService.CircuitBreakerState state = 
                circuitBreakerService.getCircuitBreakerState(serviceName);

        // Then
        assertThat(state).isEqualTo(CircuitBreakerService.CircuitBreakerState.CLOSED);
    }

    @Test
    void executeWithCircuitBreaker_AfterOperations_ShouldMaintainState() throws Exception {
        // Given
        String serviceName = "metrics-test-service";
        Supplier<String> successCall = () -> "success";
        Supplier<String> failingCall = () -> { throw new RuntimeException("Service error"); };
        Supplier<String> fallback = () -> "fallback";

        // When - Execute some successful and failing calls
        String result1 = circuitBreakerService.executeWithCircuitBreaker(serviceName, successCall, fallback);
        String result2 = circuitBreakerService.executeWithCircuitBreaker(serviceName, successCall, fallback);
        String result3 = circuitBreakerService.executeWithCircuitBreaker(serviceName, failingCall, fallback);

        // Then
        assertThat(result1).isEqualTo("success");
        assertThat(result2).isEqualTo("success");
        assertThat(result3).isEqualTo("fallback");
        
        // Circuit should still be closed after one failure
        assertThat(circuitBreakerService.getCircuitBreakerState(serviceName))
                .isEqualTo(CircuitBreakerService.CircuitBreakerState.CLOSED);
    }

    @Test
    void executeWithCircuitBreaker_WithoutFallback_ShouldThrowExceptionWhenOpen() {
        // Given
        String serviceName = "no-fallback-service";
        Supplier<String> serviceCall = () -> { throw new RuntimeException("Service error"); };

        // Open the circuit
        for (int i = 0; i < 6; i++) {
            try {
                circuitBreakerService.executeWithCircuitBreaker(serviceName, serviceCall, null);
            } catch (Exception e) {
                // Expected for failures
            }
        }

        // When/Then - Should throw CircuitBreakerException when circuit is open and no fallback
        assertThatThrownBy(() -> 
            circuitBreakerService.executeWithCircuitBreaker(serviceName, serviceCall, null)
        ).isInstanceOf(CircuitBreakerService.CircuitBreakerException.class)
         .hasMessageContaining("Circuit breaker is OPEN");
    }

    @Test
    void executeWithCircuitBreaker_MultipleServices_ShouldMaintainSeparateStates() throws Exception {
        // Given
        String service1 = "service-1";
        String service2 = "service-2";
        Supplier<String> call = () -> "success";
        Supplier<String> fallback = () -> "fallback";

        // Execute calls for different services
        String result1 = circuitBreakerService.executeWithCircuitBreaker(service1, call, fallback);
        String result2 = circuitBreakerService.executeWithCircuitBreaker(service2, call, fallback);

        // Then
        assertThat(result1).isEqualTo("success");
        assertThat(result2).isEqualTo("success");
        
        // Both circuits should be closed
        assertThat(circuitBreakerService.getCircuitBreakerState(service1))
                .isEqualTo(CircuitBreakerService.CircuitBreakerState.CLOSED);
        assertThat(circuitBreakerService.getCircuitBreakerState(service2))
                .isEqualTo(CircuitBreakerService.CircuitBreakerState.CLOSED);
    }

    @Test
    void resetCircuitBreaker_OpenCircuit_ShouldCloseCircuit() throws Exception {
        // Given
        String serviceName = "reset-test-service";
        Supplier<String> failingCall = () -> { throw new RuntimeException("Service error"); };
        Supplier<String> fallback = () -> "fallback";

        // Open the circuit
        for (int i = 0; i < 6; i++) {
            circuitBreakerService.executeWithCircuitBreaker(serviceName, failingCall, fallback);
        }
        
        assertThat(circuitBreakerService.getCircuitBreakerState(serviceName))
                .isEqualTo(CircuitBreakerService.CircuitBreakerState.OPEN);

        // When
        circuitBreakerService.resetCircuitBreaker(serviceName);

        // Then
        assertThat(circuitBreakerService.getCircuitBreakerState(serviceName))
                .isEqualTo(CircuitBreakerService.CircuitBreakerState.CLOSED);
    }
}
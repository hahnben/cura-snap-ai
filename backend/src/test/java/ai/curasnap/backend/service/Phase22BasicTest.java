package ai.curasnap.backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.net.ConnectException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Basic functionality test for Phase 2.2 services
 * Verifies that all services can be instantiated and basic methods work
 */
@ExtendWith(MockitoExtension.class)
class Phase22BasicTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    
    @Mock
    private WorkerHealthService workerHealthService;
    
    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Test
    void errorClassificationService_ShouldInstantiateAndClassifyErrors() {
        // Given
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        ErrorClassificationService service = new ErrorClassificationService(redisTemplate, workerHealthService);
        
        // When
        ErrorClassificationService.ErrorCategory category = 
            service.classifyError("test-service", new ConnectException("Connection refused"));
        
        // Then
        assertThat(category).isEqualTo(ErrorClassificationService.ErrorCategory.TRANSIENT_NETWORK);
    }

    @Test
    void circuitBreakerService_ShouldInstantiateAndProvideState() {
        // Given
        CircuitBreakerService service = new CircuitBreakerService();
        service.initialize();
        
        // When
        CircuitBreakerService.CircuitBreakerState state = service.getCircuitBreakerState("test-service");
        
        // Then
        assertThat(state).isEqualTo(CircuitBreakerService.CircuitBreakerState.CLOSED);
    }

    @Test
    void adaptiveRetryService_ShouldInstantiateWithDependencies() {
        // Given
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        ErrorClassificationService errorClassificationService = 
            new ErrorClassificationService(redisTemplate, workerHealthService);
        CircuitBreakerService circuitBreakerService = new CircuitBreakerService();
        circuitBreakerService.initialize();
        
        // When
        AdaptiveRetryService service = new AdaptiveRetryService(
            errorClassificationService, circuitBreakerService, workerHealthService, redisTemplate);
        
        // Then
        assertThat(service).isNotNull();
    }

    @Test
    void serviceDegradationService_ShouldInstantiateWithDependencies() {
        // Given
        CircuitBreakerService circuitBreakerService = new CircuitBreakerService();
        circuitBreakerService.initialize();
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        
        // When
        ServiceDegradationService service = new ServiceDegradationService(
            circuitBreakerService, workerHealthService, redisTemplate);
        
        // Then
        assertThat(service).isNotNull();
        assertThat(service.getCurrentDegradationLevel())
            .isEqualTo(ServiceDegradationService.DegradationLevel.NORMAL);
    }

    @Test
    void enhancedMonitoringService_ShouldInstantiateWithDependencies() {
        // Given
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        CircuitBreakerService circuitBreakerService = new CircuitBreakerService();
        circuitBreakerService.initialize();
        ServiceDegradationService serviceDegradationService = new ServiceDegradationService(
            circuitBreakerService, workerHealthService, redisTemplate);
        
        // When
        EnhancedMonitoringService service = new EnhancedMonitoringService(
            redisTemplate, workerHealthService, circuitBreakerService, serviceDegradationService);
        
        // Then
        assertThat(service).isNotNull();
    }

    @Test
    void deadLetterQueueService_ShouldInstantiateWithDependencies() {
        // Given
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(redisTemplate.opsForList()).thenReturn(mock(org.springframework.data.redis.core.ListOperations.class));
        
        ErrorClassificationService errorClassificationService = 
            new ErrorClassificationService(redisTemplate, workerHealthService);
        CircuitBreakerService circuitBreakerService = new CircuitBreakerService();
        circuitBreakerService.initialize();
        
        // When
        DeadLetterQueueService service = new DeadLetterQueueService(
            redisTemplate, errorClassificationService, circuitBreakerService, workerHealthService);
        
        // Then
        assertThat(service).isNotNull();
    }
}
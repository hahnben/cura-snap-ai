package ai.curasnap.backend.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for HealthController
 */
@ExtendWith(MockitoExtension.class)
class HealthControllerTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    private HealthController healthController;

    @BeforeEach
    void setUp() {
        healthController = new HealthController();
        ReflectionTestUtils.setField(healthController, "redisTemplate", redisTemplate);
    }

    @Test
    void health_WhenRedisIsUp_ReturnsUpStatus() {
        // Given
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doNothing().when(valueOperations).set(anyString(), eq("ping"), any(Duration.class));
        when(valueOperations.get(anyString())).thenReturn("ping");
        when(redisTemplate.delete(anyString())).thenReturn(true);

        // When
        ResponseEntity<Map<String, Object>> response = healthController.health();

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo("UP");
        
        Map<String, Object> components = (Map<String, Object>) body.get("components");
        assertThat(components).isNotNull();
        
        Map<String, Object> redis = (Map<String, Object>) components.get("redis");
        assertThat(redis.get("status")).isEqualTo("UP");
    }

    @Test
    void health_WhenRedisIsDown_ReturnsDownStatus() {
        // Given
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("Redis connection failed"));

        // When
        ResponseEntity<Map<String, Object>> response = healthController.health();

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo("DOWN");
        
        Map<String, Object> components = (Map<String, Object>) body.get("components");
        Map<String, Object> redis = (Map<String, Object>) components.get("redis");
        assertThat(redis.get("status")).isEqualTo("DOWN");
    }

    @Test
    void health_WhenRedisTemplateIsNull_ReturnsDownStatus() {
        // Given
        HealthController controllerWithoutRedis = new HealthController();
        // redisTemplate ist null

        // When
        ResponseEntity<Map<String, Object>> response = controllerWithoutRedis.health();

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo("DOWN");
        
        Map<String, Object> components = (Map<String, Object>) body.get("components");
        Map<String, Object> redis = (Map<String, Object>) components.get("redis");
        assertThat(redis.get("status")).isEqualTo("DOWN");
        
        Map<String, Object> details = (Map<String, Object>) redis.get("details");
        assertThat(details.get("error")).isEqualTo("Redis not configured");
    }

    @Test
    void redisHealth_WhenRedisIsUp_ReturnsUpStatus() {
        // Given
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doNothing().when(valueOperations).set(anyString(), eq("ping"), any(Duration.class));
        when(valueOperations.get(anyString())).thenReturn("ping");
        when(redisTemplate.delete(anyString())).thenReturn(true);

        // When
        ResponseEntity<Map<String, Object>> response = healthController.redisHealth();

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo("UP");
        
        Map<String, Object> details = (Map<String, Object>) body.get("details");
        assertThat(details.get("version")).isEqualTo("7.x");
        assertThat(details.get("response_time_ms")).isEqualTo("<100");
    }

    @Test
    void redisHealth_WhenPingFails_ReturnsDownStatus() {
        // Given
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doNothing().when(valueOperations).set(anyString(), eq("ping"), any(Duration.class));
        when(valueOperations.get(anyString())).thenReturn("wrong_response");

        // When
        ResponseEntity<Map<String, Object>> response = healthController.redisHealth();

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo("DOWN");
        
        Map<String, Object> details = (Map<String, Object>) body.get("details");
        assertThat(details.get("error")).isEqualTo("Redis connection test failed");
        assertThat(details.get("expected")).isEqualTo("ping");
        assertThat(details.get("actual")).isEqualTo("wrong_response");
    }

    @Test
    void redisHealth_WhenConnectionThrowsException_ReturnsDownStatus() {
        // Given
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("Connection timeout"));

        // When
        ResponseEntity<Map<String, Object>> response = healthController.redisHealth();

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo("DOWN");
        
        Map<String, Object> details = (Map<String, Object>) body.get("details");
        assertThat(details.get("error")).isEqualTo("Redis connection failed");
        // SECURITY: Exception details no longer exposed
        assertThat(details.containsKey("exception")).isFalse();
    }

    @Test
    void checkRedisHealth_VerifiesKeyOperations() {
        // Given
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doNothing().when(valueOperations).set(anyString(), eq("ping"), any(Duration.class));
        when(valueOperations.get(anyString())).thenReturn("ping");
        when(redisTemplate.delete(anyString())).thenReturn(true);

        // When
        healthController.redisHealth();

        // Then
        verify(valueOperations).set(argThat(key -> ((String) key).startsWith("health:check:")), 
                                   eq("ping"), 
                                   eq(Duration.ofSeconds(1)));
        verify(valueOperations).get(argThat(key -> ((String) key).startsWith("health:check:")));
        verify(redisTemplate).delete(argThat((String key) -> key.startsWith("health:check:")));
    }
}
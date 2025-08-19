package ai.curasnap.backend.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RedisConfig configuration class
 */
@ExtendWith(MockitoExtension.class)
@SpringJUnitConfig
@TestPropertySource(properties = {
    "spring.data.redis.host=localhost",
    "spring.data.redis.port=6379",
    "spring.data.redis.database=0",
    "spring.data.redis.password=",
    "spring.data.redis.enabled=true"
})
class RedisConfigTest {

    @TestConfiguration
    static class TestRedisConfig {
        
        @Bean
        @Primary
        public RedisConfig redisConfig() {
            return new RedisConfig();
        }
    }

    @Test
    void testRedisConfigCreation() {
        // Given
        RedisConfig config = new RedisConfig();
        
        // When
        ReflectionTestUtils.setField(config, "redisHost", "localhost");
        ReflectionTestUtils.setField(config, "redisPort", 6379);
        ReflectionTestUtils.setField(config, "redisDatabase", 0);
        ReflectionTestUtils.setField(config, "redisPassword", "");
        
        // Then
        assertThat(config).isNotNull();
        assertThat(ReflectionTestUtils.getField(config, "redisHost")).isEqualTo("localhost");
        assertThat(ReflectionTestUtils.getField(config, "redisPort")).isEqualTo(6379);
        assertThat(ReflectionTestUtils.getField(config, "redisDatabase")).isEqualTo(0);
    }

    @Test
    void testRedisConnectionFactoryCreation() {
        // Given
        RedisConfig config = new RedisConfig();
        ReflectionTestUtils.setField(config, "redisHost", "testhost");
        ReflectionTestUtils.setField(config, "redisPort", 6380);
        ReflectionTestUtils.setField(config, "redisDatabase", 1);
        ReflectionTestUtils.setField(config, "redisPassword", "testpass");
        
        // When
        LettuceConnectionFactory factory = config.redisConnectionFactory();
        
        // Then
        assertThat(factory).isNotNull();
        assertThat(factory.getHostName()).isEqualTo("testhost");
        assertThat(factory.getPort()).isEqualTo(6380);
        assertThat(factory.getDatabase()).isEqualTo(1);
        assertThat(factory.getPassword()).isEqualTo("testpass");
    }

    @Test
    void testRedisConnectionFactoryWithEmptyPassword() {
        // Given
        RedisConfig config = new RedisConfig();
        ReflectionTestUtils.setField(config, "redisHost", "localhost");
        ReflectionTestUtils.setField(config, "redisPort", 6379);
        ReflectionTestUtils.setField(config, "redisDatabase", 0);
        ReflectionTestUtils.setField(config, "redisPassword", "");
        
        // When
        LettuceConnectionFactory factory = config.redisConnectionFactory();
        
        // Then
        assertThat(factory).isNotNull();
        // Empty password should result in no password being set
        assertThat(factory.getPassword()).satisfiesAnyOf(
            password -> assertThat(password).isNull(),
            password -> assertThat(password.toString()).isEmpty()
        );
    }

    @Test
    void testRedisTemplateCreation() {
        // Given
        RedisConfig config = new RedisConfig();
        RedisConnectionFactory connectionFactory = createMockConnectionFactory();
        
        // When
        RedisTemplate<String, Object> template = config.redisTemplate(connectionFactory);
        
        // Then
        assertThat(template).isNotNull();
        assertThat(template.getConnectionFactory()).isEqualTo(connectionFactory);
        assertThat(template.getKeySerializer()).isNotNull();
        assertThat(template.getValueSerializer()).isNotNull();
        assertThat(template.getHashKeySerializer()).isNotNull();
        assertThat(template.getHashValueSerializer()).isNotNull();
    }

    @Test
    void testStringRedisTemplateCreation() {
        // Given
        RedisConfig config = new RedisConfig();
        RedisConnectionFactory connectionFactory = createMockConnectionFactory();
        
        // When
        RedisTemplate<String, String> template = config.stringRedisTemplate(connectionFactory);
        
        // Then
        assertThat(template).isNotNull();
        assertThat(template.getConnectionFactory()).isEqualTo(connectionFactory);
        assertThat(template.getKeySerializer()).isNotNull();
        assertThat(template.getValueSerializer()).isNotNull();
    }

    @Test
    void testPasswordHandlingWithNull() {
        // Given
        RedisConfig config = new RedisConfig();
        ReflectionTestUtils.setField(config, "redisHost", "localhost");
        ReflectionTestUtils.setField(config, "redisPort", 6379);
        ReflectionTestUtils.setField(config, "redisDatabase", 0);
        ReflectionTestUtils.setField(config, "redisPassword", null);
        
        // When
        LettuceConnectionFactory factory = config.redisConnectionFactory();
        
        // Then
        assertThat(factory).isNotNull();
        // Null password should result in no password being set
        assertThat(factory.getPassword()).satisfiesAnyOf(
            password -> assertThat(password).isNull(),
            password -> assertThat(password.toString()).isEmpty()
        );
    }

    @Test
    void testPasswordHandlingWithWhitespace() {
        // Given
        RedisConfig config = new RedisConfig();
        ReflectionTestUtils.setField(config, "redisHost", "localhost");
        ReflectionTestUtils.setField(config, "redisPort", 6379);
        ReflectionTestUtils.setField(config, "redisDatabase", 0);
        ReflectionTestUtils.setField(config, "redisPassword", "   ");
        
        // When
        LettuceConnectionFactory factory = config.redisConnectionFactory();
        
        // Then
        assertThat(factory).isNotNull();
        // Whitespace password should result in no password being set
        assertThat(factory.getPassword()).satisfiesAnyOf(
            password -> assertThat(password).isNull(),
            password -> assertThat(password.toString()).isEmpty()
        );
    }

    private RedisConnectionFactory createMockConnectionFactory() {
        RedisConfig config = new RedisConfig();
        ReflectionTestUtils.setField(config, "redisHost", "localhost");
        ReflectionTestUtils.setField(config, "redisPort", 6379);
        ReflectionTestUtils.setField(config, "redisDatabase", 0);
        ReflectionTestUtils.setField(config, "redisPassword", "");
        return config.redisConnectionFactory();
    }
}
package ai.curasnap.backend.integration;

import ai.curasnap.backend.config.RedisConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Redis functionality
 * These tests require a running Redis instance
 * Run with: -Dredis.integration.test=true
 */
@SpringBootTest(classes = {RedisConfig.class, RedisAutoConfiguration.class})
@ContextConfiguration(classes = RedisConfig.class)
@TestPropertySource(properties = {
    "spring.data.redis.host=localhost",
    "spring.data.redis.port=6379",
    "spring.data.redis.database=15", // Use test database
    "spring.data.redis.enabled=true"
})
@EnabledIfSystemProperty(named = "redis.integration.test", matches = "true")
class RedisIntegrationTest {

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired(required = false)
    private RedisTemplate<String, String> stringRedisTemplate;

    @Test
    void testRedisTemplateConnection() {
        // Given
        assumeRedisAvailable();
        String testKey = "integration:test:" + System.currentTimeMillis();
        String testValue = "test-value";

        // When
        redisTemplate.opsForValue().set(testKey, testValue, Duration.ofSeconds(10));
        Object retrievedValue = redisTemplate.opsForValue().get(testKey);

        // Then
        assertThat(retrievedValue).isEqualTo(testValue);
        
        // Cleanup
        redisTemplate.delete(testKey);
    }

    @Test
    void testStringRedisTemplateConnection() {
        // Given
        assumeRedisAvailable();
        String testKey = "integration:string:test:" + System.currentTimeMillis();
        String testValue = "string-test-value";

        // When
        stringRedisTemplate.opsForValue().set(testKey, testValue, Duration.ofSeconds(10));
        String retrievedValue = stringRedisTemplate.opsForValue().get(testKey);

        // Then
        assertThat(retrievedValue).isEqualTo(testValue);
        
        // Cleanup
        stringRedisTemplate.delete(testKey);
    }

    @Test
    void testRedisHashOperations() {
        // Given
        assumeRedisAvailable();
        String hashKey = "integration:hash:" + System.currentTimeMillis();
        String field1 = "field1";
        String value1 = "value1";
        String field2 = "field2";
        String value2 = "value2";

        // When
        redisTemplate.opsForHash().put(hashKey, field1, value1);
        redisTemplate.opsForHash().put(hashKey, field2, value2);
        redisTemplate.expire(hashKey, Duration.ofSeconds(10));

        Object retrieved1 = redisTemplate.opsForHash().get(hashKey, field1);
        Object retrieved2 = redisTemplate.opsForHash().get(hashKey, field2);

        // Then
        assertThat(retrieved1).isEqualTo(value1);
        assertThat(retrieved2).isEqualTo(value2);
        
        // Cleanup
        redisTemplate.delete(hashKey);
    }

    @Test
    void testRedisListOperations() {
        // Given
        assumeRedisAvailable();
        String listKey = "integration:list:" + System.currentTimeMillis();
        String item1 = "item1";
        String item2 = "item2";

        // When
        redisTemplate.opsForList().rightPush(listKey, item1);
        redisTemplate.opsForList().rightPush(listKey, item2);
        redisTemplate.expire(listKey, Duration.ofSeconds(10));

        Object firstItem = redisTemplate.opsForList().leftPop(listKey);
        Object secondItem = redisTemplate.opsForList().leftPop(listKey);

        // Then
        assertThat(firstItem).isEqualTo(item1);
        assertThat(secondItem).isEqualTo(item2);
        
        // Cleanup (list should be empty now)
        redisTemplate.delete(listKey);
    }

    @Test
    void testRedisExpirationHandling() throws InterruptedException {
        // Given
        assumeRedisAvailable();
        String testKey = "integration:expiry:" + System.currentTimeMillis();
        String testValue = "expiry-test";

        // When
        redisTemplate.opsForValue().set(testKey, testValue, Duration.ofSeconds(1));
        
        // Verify key exists
        assertThat(redisTemplate.hasKey(testKey)).isTrue();
        
        // Wait for expiration
        TimeUnit.SECONDS.sleep(2);
        
        // Then
        assertThat(redisTemplate.hasKey(testKey)).isFalse();
    }

    @Test
    void testRedisSerialization() {
        // Given
        assumeRedisAvailable();
        String testKey = "integration:object:" + System.currentTimeMillis();
        TestObject testObject = new TestObject("test-name", 42);

        // When
        redisTemplate.opsForValue().set(testKey, testObject, Duration.ofSeconds(10));
        Object retrieved = redisTemplate.opsForValue().get(testKey);

        // Then
        assertThat(retrieved).isInstanceOf(TestObject.class);
        TestObject retrievedObject = (TestObject) retrieved;
        assertThat(retrievedObject.getName()).isEqualTo("test-name");
        assertThat(retrievedObject.getValue()).isEqualTo(42);
        
        // Cleanup
        redisTemplate.delete(testKey);
    }

    private void assumeRedisAvailable() {
        assertThat(redisTemplate).isNotNull();
        try {
            // Simple ping test
            String testKey = "ping:test:" + System.currentTimeMillis();
            redisTemplate.opsForValue().set(testKey, "ping", Duration.ofSeconds(1));
            redisTemplate.delete(testKey);
        } catch (Exception e) {
            fail("Redis is not available for integration testing: " + e.getMessage());
        }
    }

    // Test object for serialization testing
    public static class TestObject {
        private String name;
        private int value;

        public TestObject() {} // Default constructor for deserialization

        public TestObject(String name, int value) {
            this.name = name;
            this.value = value;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getValue() { return value; }
        public void setValue(int value) { this.value = value; }
    }
}
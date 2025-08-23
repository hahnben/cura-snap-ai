package ai.curasnap.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CacheKeyGenerator.
 * 
 * Tests cover hash consistency, collision resistance, input validation,
 * and security aspects of cache key generation.
 */
class CacheKeyGeneratorTest {

    private CacheKeyGenerator cacheKeyGenerator;
    private static final String TEST_SALT = "test-salt-123";

    @BeforeEach
    void setUp() {
        cacheKeyGenerator = new CacheKeyGenerator(TEST_SALT);
    }

    @Test
    @DisplayName("Should generate consistent cache keys for identical content")
    void shouldGenerateConsistentKeys() {
        String transcript = "Patient reports headache and fatigue.";
        
        String key1 = cacheKeyGenerator.generateCacheKey(transcript);
        String key2 = cacheKeyGenerator.generateCacheKey(transcript);
        
        assertEquals(key1, key2, "Cache keys should be identical for same content");
    }

    @Test
    @DisplayName("Should generate different cache keys for different content")
    void shouldGenerateDifferentKeysForDifferentContent() {
        String transcript1 = "Patient reports headache.";
        String transcript2 = "Patient reports nausea.";
        
        String key1 = cacheKeyGenerator.generateCacheKey(transcript1);
        String key2 = cacheKeyGenerator.generateCacheKey(transcript2);
        
        assertNotEquals(key1, key2, "Cache keys should be different for different content");
    }

    @Test
    @DisplayName("Should generate keys with correct format and length")
    void shouldGenerateCorrectKeyFormat() {
        String transcript = "Test transcript content.";
        String cacheKey = cacheKeyGenerator.generateCacheKey(transcript);
        
        // Should start with correct prefix
        assertTrue(cacheKey.startsWith("agent:soap:"), "Cache key should have correct prefix");
        
        // Should have correct total length (prefix + 64 hex chars for SHA256)
        int expectedLength = "agent:soap:".length() + 64;
        assertEquals(expectedLength, cacheKey.length(), "Cache key should have correct length");
        
        // Hash part should be valid hexadecimal
        String hashPart = cacheKey.substring("agent:soap:".length());
        assertTrue(hashPart.matches("[0-9a-f]{64}"), "Hash part should be valid hexadecimal");
    }

    @Test
    @DisplayName("Should normalize whitespace in transcript content")
    void shouldNormalizeWhitespace() {
        String transcript1 = "Patient reports    headache.";
        String transcript2 = "Patient reports headache.";
        String transcript3 = "Patient\treports\nheadache.";
        
        String key1 = cacheKeyGenerator.generateCacheKey(transcript1);
        String key2 = cacheKeyGenerator.generateCacheKey(transcript2);
        String key3 = cacheKeyGenerator.generateCacheKey(transcript3);
        
        assertEquals(key1, key2, "Multiple spaces should be normalized to single space");
        assertEquals(key1, key3, "Tabs and newlines should be normalized to single space");
    }

    @Test
    @DisplayName("Should be case insensitive")
    void shouldBeCaseInsensitive() {
        String transcript1 = "Patient reports Headache.";
        String transcript2 = "patient reports headache.";
        String transcript3 = "PATIENT REPORTS HEADACHE.";
        
        String key1 = cacheKeyGenerator.generateCacheKey(transcript1);
        String key2 = cacheKeyGenerator.generateCacheKey(transcript2);
        String key3 = cacheKeyGenerator.generateCacheKey(transcript3);
        
        assertEquals(key1, key2, "Cache keys should be case insensitive");
        assertEquals(key1, key3, "Cache keys should be case insensitive");
    }

    @Test
    @DisplayName("Should handle leading and trailing whitespace")
    void shouldHandleWhitespace() {
        String transcript1 = "Patient reports headache.";
        String transcript2 = "  Patient reports headache.  ";
        String transcript3 = "\t\nPatient reports headache.\n\t";
        
        String key1 = cacheKeyGenerator.generateCacheKey(transcript1);
        String key2 = cacheKeyGenerator.generateCacheKey(transcript2);
        String key3 = cacheKeyGenerator.generateCacheKey(transcript3);
        
        assertEquals(key1, key2, "Leading/trailing spaces should not affect cache key");
        assertEquals(key1, key3, "Leading/trailing tabs/newlines should not affect cache key");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "  ", "\t", "\n", "\r\n"})
    @DisplayName("Should reject null, empty, or whitespace-only content")
    void shouldRejectInvalidContent(String invalidContent) {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> cacheKeyGenerator.generateCacheKey(invalidContent),
            "Should throw IllegalArgumentException for invalid content"
        );
        
        assertTrue(exception.getMessage().contains("cannot be null or empty"),
                  "Exception message should indicate the problem");
    }

    @Test
    @DisplayName("Should validate cache keys correctly")
    void shouldValidateCacheKeys() {
        String validKey = "agent:soap:a1b2c3d4e5f6789012345678901234567890123456789012345678901234abcd";
        String invalidPrefix = "invalid:soap:a1b2c3d4e5f6789012345678901234567890123456789012345678901234abcd";
        String invalidLength = "agent:soap:shortkey";
        String invalidHex = "agent:soap:g1b2c3d4e5f6789012345678901234567890123456789012345678901234abcd";
        String nullKey = null;
        
        assertTrue(cacheKeyGenerator.isValidCacheKey(validKey), "Valid key should be accepted");
        assertFalse(cacheKeyGenerator.isValidCacheKey(invalidPrefix), "Invalid prefix should be rejected");
        assertFalse(cacheKeyGenerator.isValidCacheKey(invalidLength), "Invalid length should be rejected");
        assertFalse(cacheKeyGenerator.isValidCacheKey(invalidHex), "Invalid hex should be rejected");
        assertFalse(cacheKeyGenerator.isValidCacheKey(nullKey), "Null key should be rejected");
    }

    @Test
    @DisplayName("Should extract namespace correctly")
    void shouldExtractNamespace() {
        String validKey = "agent:soap:a1b2c3d4e5f6789012345678901234567890123456789012345678901234abcd";
        String invalidKey = "invalid-key";
        
        String namespace = cacheKeyGenerator.extractNamespace(validKey);
        String invalidNamespace = cacheKeyGenerator.extractNamespace(invalidKey);
        
        assertEquals("agent:soap", namespace, "Should extract correct namespace");
        assertNull(invalidNamespace, "Should return null for invalid key");
    }

    @Test
    @DisplayName("Should return correct cache key prefix")
    void shouldReturnCorrectPrefix() {
        String prefix = cacheKeyGenerator.getCacheKeyPrefix();
        assertEquals("agent:soap:", prefix, "Should return correct cache key prefix");
    }

    @Test
    @DisplayName("Should use salt to prevent key prediction")
    void shouldUseSaltForSecurity() {
        // Create two generators with different salts
        CacheKeyGenerator generator1 = new CacheKeyGenerator("salt1");
        CacheKeyGenerator generator2 = new CacheKeyGenerator("salt2");
        
        String transcript = "Patient reports symptoms.";
        String key1 = generator1.generateCacheKey(transcript);
        String key2 = generator2.generateCacheKey(transcript);
        
        assertNotEquals(key1, key2, "Different salts should produce different keys");
    }

    @Test
    @DisplayName("Should handle long transcript content")
    void shouldHandleLongContent() {
        // Create a long transcript (over 1000 characters)
        StringBuilder longTranscript = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longTranscript.append("Patient reports various symptoms including headache, nausea, and fatigue. ");
        }
        
        String cacheKey = cacheKeyGenerator.generateCacheKey(longTranscript.toString());
        
        // Should still generate valid key format regardless of input length
        assertTrue(cacheKeyGenerator.isValidCacheKey(cacheKey), "Should handle long content");
        assertTrue(cacheKey.startsWith("agent:soap:"), "Should maintain correct format for long content");
    }

    @Test
    @DisplayName("Should handle special characters")
    void shouldHandleSpecialCharacters() {
        String transcript = "Patient reports: \"I have ü-symptoms & 50% pain (level 8/10)!\"";
        
        String cacheKey = cacheKeyGenerator.generateCacheKey(transcript);
        
        assertTrue(cacheKeyGenerator.isValidCacheKey(cacheKey), "Should handle special characters");
        
        // Should be consistent
        String cacheKey2 = cacheKeyGenerator.generateCacheKey(transcript);
        assertEquals(cacheKey, cacheKey2, "Should be consistent with special characters");
    }

    @Test
    @DisplayName("Should generate collision-resistant keys")
    void shouldGenerateCollisionResistantKeys() {
        // Test various similar transcripts that might cause collisions
        String[] similarTranscripts = {
            "Patient has headache",
            "Patient has head ache",
            "Patient has a headache",
            "Patient had headache",
            "Patients has headache",
            "Patient has headaches"
        };
        
        java.util.Set<String> generatedKeys = new java.util.HashSet<>();
        
        for (String transcript : similarTranscripts) {
            String key = cacheKeyGenerator.generateCacheKey(transcript);
            assertTrue(generatedKeys.add(key), 
                      "Generated key should be unique: " + transcript + " -> " + key);
        }
        
        assertEquals(similarTranscripts.length, generatedKeys.size(), 
                    "All keys should be unique");
    }
}
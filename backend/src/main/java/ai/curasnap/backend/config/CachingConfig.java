package ai.curasnap.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration class for caching functionality.
 * 
 * This configuration sets up the caching layer for Agent Service responses.
 * The actual caching implementation is handled through Spring's conditional
 * bean creation based on the app.agent.cache.enabled property.
 * 
 * When caching is enabled:
 * - CachedAgentServiceClient becomes the primary implementation
 * - All AgentServiceClientInterface injections use the cached version
 * 
 * When caching is disabled:
 * - AgentServiceClient remains the primary implementation
 * - No caching overhead occurs
 */
@Slf4j
@Configuration
public class CachingConfig {

    // This configuration class serves as documentation and can be extended
    // with additional caching-related beans in the future if needed.
    // 
    // The actual bean selection happens through @ConditionalOnProperty
    // annotations on the service classes themselves:
    // - CachedAgentServiceClient: enabled when app.agent.cache.enabled=true
    // - AgentServiceClient: always available as fallback
}
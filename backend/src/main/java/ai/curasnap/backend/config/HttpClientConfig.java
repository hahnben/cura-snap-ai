package ai.curasnap.backend.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Configuration class for HTTP client beans.
 * 
 * This configuration provides RestTemplate beans with proper timeout settings
 * for making HTTP requests to external services like the Agent Service.
 */
@Configuration
public class HttpClientConfig {

    /**
     * Creates a RestTemplate bean with configured timeouts.
     * 
     * @param builder the RestTemplateBuilder provided by Spring Boot
     * @return a configured RestTemplate instance
     */
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
            .setConnectTimeout(Duration.ofSeconds(10))
            .setReadTimeout(Duration.ofSeconds(30))
            .build();
    }
}
package ai.curasnap.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Configuration for RestTemplate with timeout settings for external service calls.
 * This configuration ensures that HTTP requests to external services (like Transcription Service)
 * have appropriate timeouts to prevent hanging connections.
 */
@Configuration
public class RestTemplateConfig {

    /**
     * Creates a RestTemplate bean with configured timeouts.
     * 
     * @return configured RestTemplate instance
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate(clientHttpRequestFactory());
    }

    /**
     * Creates a ClientHttpRequestFactory with timeout configuration.
     * 
     * @return configured ClientHttpRequestFactory
     */
    private ClientHttpRequestFactory clientHttpRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        
        // Default timeouts - can be overridden by specific services
        factory.setConnectTimeout(5000); // 5 seconds connection timeout
        factory.setReadTimeout(30000);   // 30 seconds read timeout
        
        return factory;
    }
}
package ai.curasnap.backend.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.security.cert.X509Certificate;
import java.time.Duration;

/**
 * Configuration class for HTTP client beans.
 *
 * This configuration provides separate RestTemplate beans:
 * - Standard RestTemplate with full SSL validation for external services
 * - Internal RestTemplate with relaxed SSL for development service-to-service communication
 */
@Configuration
public class HttpClientConfig {

    /**
     * Creates a primary RestTemplate bean with standard SSL validation.
     * Used for external API calls where full SSL validation is required.
     *
     * @param builder the RestTemplateBuilder provided by Spring Boot
     * @return a configured RestTemplate instance with full SSL validation
     */
    @Bean
    @Primary
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
            .setConnectTimeout(Duration.ofSeconds(10))
            .setReadTimeout(Duration.ofSeconds(30))
            .build();
    }

    /**
     * Creates an internal RestTemplate bean with relaxed SSL configuration.
     * Used for service-to-service communication in development environments
     * where internal services may use HTTP or self-signed certificates.
     *
     * @return a configured RestTemplate instance with relaxed SSL validation
     */
    @Bean
    @Qualifier("internal")
    public RestTemplate internalRestTemplate() {
        try {
            // Create a trust-all SSL context for development
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    // Trust all certificates for development
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    // Trust all certificates for development
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, null);

            // Create a custom request factory that disables SSL verification for HTTPS
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory() {
                @Override
                protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                    super.prepareConnection(connection, httpMethod);

                    // Disable SSL verification for HTTPS connections
                    if (connection instanceof HttpsURLConnection) {
                        HttpsURLConnection httpsConnection = (HttpsURLConnection) connection;
                        httpsConnection.setSSLSocketFactory(sslContext.getSocketFactory());
                        httpsConnection.setHostnameVerifier((hostname, session) -> true);
                    }
                }
            };

            requestFactory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
            requestFactory.setReadTimeout((int) Duration.ofSeconds(30).toMillis());

            RestTemplate restTemplate = new RestTemplate(requestFactory);

            return restTemplate;

        } catch (Exception e) {
            throw new RuntimeException("Failed to create internal RestTemplate with relaxed SSL", e);
        }
    }
}
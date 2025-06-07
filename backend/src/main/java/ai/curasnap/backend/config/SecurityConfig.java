package ai.curasnap.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.Customizer;


import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Configures JWT-based security for the API using Supabase-issued HS256 tokens.
 */
@Configuration
public class SecurityConfig {

    /**
     * JWT secret provided by Supabase (Settings > API > JWT Secret).
     * Loaded from application.properties (supabase.jwt.secret).
     */
    @Value("${supabase.jwt.secret}")
    private String jwtSecret;

    /**
     * Defines which HTTP endpoints require authentication.
     * - /api/** requires valid JWT
     * - all other endpoints are publicly accessible
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable()) // Disable CSRF protection for stateless REST API
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/**").authenticated() // Protect all /api routes
                .anyRequest().permitAll()                   // Allow everything else (e.g., health checks)
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults())); // Enable JWT decoding

        return http.build();
    }

    /**
     * Configures the JWT decoder using HMAC + SHA256 (HS256).
     * Supabase signs JWTs with a shared secret (not with public/private keys).
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        // Convert string-based secret into a cryptographic key
        SecretKey hmacKey = new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");

        // Build a decoder that knows how to verify HS256 tokens
        return NimbusJwtDecoder.withSecretKey(hmacKey).build();
    }
}

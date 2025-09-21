package ai.curasnap.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.Customizer;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

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
            .cors(cors -> cors.configurationSource(corsConfigurationSource())) // Enable CORS
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

    /**
     * Configure CORS to allow frontend access from development environment
     * Allows localhost:5173 (Vite dev server) to access /api endpoints
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        // Allow specific origins - add production origins here when needed
        configuration.addAllowedOrigin("https://localhost:5173"); // Vite dev server (HTTPS)
        configuration.addAllowedOrigin("https://localhost:5174"); // Vite dev server (HTTPS - alternative port)
        configuration.addAllowedOrigin("https://localhost:3000"); // Alternative React dev server (HTTPS)
        
        // Allow common HTTP methods
        configuration.addAllowedMethod("GET");
        configuration.addAllowedMethod("POST");
        configuration.addAllowedMethod("PUT");
        configuration.addAllowedMethod("DELETE");
        configuration.addAllowedMethod("OPTIONS");
        configuration.addAllowedMethod("PATCH");
        
        // Allow necessary headers
        configuration.addAllowedHeader("Authorization");
        configuration.addAllowedHeader("Content-Type");
        configuration.addAllowedHeader("X-Requested-With");
        configuration.addAllowedHeader("Accept");
        configuration.addAllowedHeader("Origin");
        
        // Allow credentials (needed for JWT tokens)
        configuration.setAllowCredentials(true);
        
        // Cache preflight responses for 1 hour
        configuration.setMaxAge(3600L);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        
        return source;
    }
}

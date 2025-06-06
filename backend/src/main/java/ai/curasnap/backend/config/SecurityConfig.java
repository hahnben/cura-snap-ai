package ai.curasnap.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Production security configuration using JWT from Supabase.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF (not needed for stateless REST APIs)
            .csrf(AbstractHttpConfigurer::disable)

            // Authorize requests
            .authorizeHttpRequests(auth -> auth
                // Allow health checks, docs, etc.
                .requestMatchers("/health", "/docs/**").permitAll()

                // Require authentication for everything else
                .anyRequest().authenticated()
            )

            // Enable JWT authentication for OAuth2 resource server
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt() // Spring uses JwtDecoder behind the scenes
            );

        return http.build();
    }
}


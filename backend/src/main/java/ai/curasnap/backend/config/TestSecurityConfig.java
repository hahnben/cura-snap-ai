package ai.curasnap.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Global security configuration that allows all requests.
 * Temporary solution for development and testing purposes.
 */
@Configuration
public class TestSecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .anyRequest().permitAll()  // allow all endpoints
            )
            .csrf(csrf -> csrf.disable()); // disable CSRF protection to allow POST without token
        return http.build();
    }
}
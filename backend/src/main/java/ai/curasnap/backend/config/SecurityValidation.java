package ai.curasnap.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;

/**
 * Security validation component that checks critical security configurations
 * at application startup to prevent deployment with insecure settings.
 */
@Component
public class SecurityValidation {

    private static final Logger logger = LoggerFactory.getLogger(SecurityValidation.class);
    private static final String INSECURE_FALLBACK_SECRET = "DEVELOPMENT_FALLBACK_DO_NOT_USE_IN_PRODUCTION_12345678901234567890123456789012";
    
    @Value("${supabase.jwt.secret}")
    private String jwtSecret;
    
    private final Environment environment;
    
    public SecurityValidation(Environment environment) {
        this.environment = environment;
    }
    
    /**
     * Validates security configuration when application is ready.
     * This runs after all beans are initialized and configured.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void validateSecurityConfiguration() {
        logger.info("Starting security configuration validation...");
        
        validateJwtSecret();
        validateEnvironmentConfiguration();
        
        logger.info("Security configuration validation completed successfully");
    }
    
    /**
     * Validates JWT secret strength and warns about insecure configurations.
     */
    private void validateJwtSecret() {
        if (jwtSecret == null || jwtSecret.trim().isEmpty()) {
            logger.error("CRITICAL SECURITY ISSUE: JWT secret is not configured!");
            throw new IllegalStateException("JWT secret must be configured via SUPABASE_JWT_SECRET environment variable");
        }
        
        // Check if using insecure fallback
        if (INSECURE_FALLBACK_SECRET.equals(jwtSecret)) {
            if (isProductionEnvironment()) {
                logger.error("CRITICAL SECURITY ISSUE: Using development fallback JWT secret in production!");
                throw new IllegalStateException("Production deployment requires proper SUPABASE_JWT_SECRET environment variable");
            } else {
                logger.warn("WARNING: Using development fallback JWT secret. This is only safe for local development.");
            }
        }
        
        // Validate secret strength
        if (jwtSecret.length() < 32) {
            logger.error("CRITICAL SECURITY ISSUE: JWT secret is too short (minimum 32 characters required)");
            throw new IllegalStateException("JWT secret must be at least 32 characters long");
        }
        
        // Check for common weak secrets
        if (isWeakSecret(jwtSecret)) {
            logger.error("CRITICAL SECURITY ISSUE: JWT secret appears to be weak or predictable");
            throw new IllegalStateException("JWT secret must be cryptographically strong");
        }
        
        logger.info("JWT secret validation passed (length: {} characters)", jwtSecret.length());
    }
    
    /**
     * Validates environment-specific configuration.
     */
    private void validateEnvironmentConfiguration() {
        if (isProductionEnvironment()) {
            logger.info("Production environment detected - performing additional security checks");
            
            // Check if JPA is properly configured for production
            String[] activeProfiles = environment.getActiveProfiles();
            if (activeProfiles.length == 0 || !containsProductionProfile(activeProfiles)) {
                logger.warn("WARNING: No production profile active. Ensure proper database configuration.");
            }
            
            // Check for development-only configurations
            String excludeProperty = environment.getProperty("spring.autoconfigure.exclude");
            if (excludeProperty != null && excludeProperty.contains("DataSourceAutoConfiguration")) {
                logger.error("CRITICAL SECURITY ISSUE: Database auto-configuration is disabled in production!");
                throw new IllegalStateException("Database must be properly configured in production");
            }
        }
    }
    
    /**
     * Checks if the current environment appears to be production.
     */
    private boolean isProductionEnvironment() {
        String[] activeProfiles = environment.getActiveProfiles();
        
        // Check for production profile
        for (String profile : activeProfiles) {
            if ("prod".equals(profile) || "production".equals(profile)) {
                return true;
            }
        }
        
        // Check for production-like environment variables
        String javaHome = System.getProperty("java.home");
        String userHome = System.getProperty("user.home");
        
        // Simple heuristics for production detection
        if (javaHome != null && (javaHome.contains("/opt/") || javaHome.contains("/usr/"))) {
            return true;
        }
        
        if (userHome != null && !userHome.contains("ben") && !userHome.contains("dev")) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Checks if the provided JWT secret appears to be weak.
     */
    private boolean isWeakSecret(String secret) {
        // Common weak patterns
        String[] weakPatterns = {
            "password", "secret", "123456", "admin", "test", "development", 
            "changeme", "default", "example", "demo", "temp", "fallback"
        };
        
        String lowerSecret = secret.toLowerCase();
        for (String pattern : weakPatterns) {
            if (lowerSecret.contains(pattern)) {
                return true;
            }
        }
        
        // Check for repeated characters
        if (hasRepeatedCharacters(secret)) {
            return true;
        }
        
        // Check for sequential characters
        if (hasSequentialCharacters(secret)) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Checks if the secret has too many repeated characters.
     */
    private boolean hasRepeatedCharacters(String secret) {
        int maxRepeats = 3;
        int currentRepeats = 1;
        
        for (int i = 1; i < secret.length(); i++) {
            if (secret.charAt(i) == secret.charAt(i - 1)) {
                currentRepeats++;
                if (currentRepeats > maxRepeats) {
                    return true;
                }
            } else {
                currentRepeats = 1;
            }
        }
        
        return false;
    }
    
    /**
     * Checks if the secret has sequential characters.
     */
    private boolean hasSequentialCharacters(String secret) {
        int maxSequential = 4;
        int currentSequential = 1;
        
        for (int i = 1; i < secret.length(); i++) {
            if (secret.charAt(i) == secret.charAt(i - 1) + 1) {
                currentSequential++;
                if (currentSequential > maxSequential) {
                    return true;
                }
            } else {
                currentSequential = 1;
            }
        }
        
        return false;
    }
    
    /**
     * Checks if any of the active profiles is a production profile.
     */
    private boolean containsProductionProfile(String[] profiles) {
        for (String profile : profiles) {
            if ("prod".equals(profile) || "production".equals(profile)) {
                return true;
            }
        }
        return false;
    }
}
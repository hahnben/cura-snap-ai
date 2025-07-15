package ai.curasnap.backend.util;

import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * Security utilities for input sanitization and validation.
 * This class provides methods to sanitize user inputs to prevent security vulnerabilities
 * like log injection, path traversal, and other input-based attacks.
 */
public class SecurityUtils {

    // Pattern to match potentially dangerous characters for logging
    private static final Pattern DANGEROUS_LOG_CHARS = Pattern.compile("[\\r\\n\\t\\x00-\\x1F\\x7F-\\x9F]");
    
    // Pattern to match control characters and non-printable characters
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}]");
    
    // Maximum length for sanitized filenames in logs
    private static final int MAX_FILENAME_LENGTH = 255;

    /**
     * Sanitizes a filename for safe logging by removing potentially dangerous characters.
     * This prevents log injection attacks where malicious filenames could inject
     * control characters or newlines into log files.
     * 
     * @param filename the filename to sanitize
     * @return sanitized filename safe for logging
     */
    public static String sanitizeFilenameForLogging(String filename) {
        if (filename == null) {
            return "null";
        }
        
        if (filename.isEmpty()) {
            return "empty";
        }
        
        // Extract just the filename part (no path components)
        String baseName = Paths.get(filename).getFileName().toString();
        
        // Remove dangerous characters that could be used for log injection
        String sanitized = DANGEROUS_LOG_CHARS.matcher(baseName).replaceAll("_");
        
        // Remove any remaining control characters
        sanitized = CONTROL_CHARS.matcher(sanitized).replaceAll("_");
        
        // Truncate if too long
        if (sanitized.length() > MAX_FILENAME_LENGTH) {
            sanitized = sanitized.substring(0, MAX_FILENAME_LENGTH - 3) + "...";
        }
        
        // Ensure we don't return empty string
        if (sanitized.isEmpty()) {
            return "sanitized_filename";
        }
        
        return sanitized;
    }
    
    /**
     * Sanitizes a general string for safe logging.
     * Removes control characters and newlines that could be used for log injection.
     * 
     * @param input the string to sanitize
     * @return sanitized string safe for logging
     */
    public static String sanitizeForLogging(String input) {
        if (input == null) {
            return "null";
        }
        
        if (input.isEmpty()) {
            return "empty";
        }
        
        // Remove dangerous characters
        String sanitized = DANGEROUS_LOG_CHARS.matcher(input).replaceAll("_");
        
        // Remove control characters
        sanitized = CONTROL_CHARS.matcher(sanitized).replaceAll("_");
        
        // Truncate if too long (for logging purposes)
        if (sanitized.length() > 500) {
            sanitized = sanitized.substring(0, 497) + "...";
        }
        
        return sanitized;
    }
    
    /**
     * Validates that a filename doesn't contain path traversal attempts.
     * 
     * @param filename the filename to validate
     * @return true if filename is safe, false otherwise
     */
    public static boolean isSafeFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return false;
        }
        
        // Check for path traversal attempts
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return false;
        }
        
        // Check for null bytes
        if (filename.contains("\0")) {
            return false;
        }
        
        // Check for control characters
        if (CONTROL_CHARS.matcher(filename).find()) {
            return false;
        }
        
        return true;
    }
}
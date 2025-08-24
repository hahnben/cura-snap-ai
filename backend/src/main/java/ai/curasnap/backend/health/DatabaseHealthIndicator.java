package ai.curasnap.backend.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Duration;

/**
 * Custom health indicator for database connectivity and performance monitoring.
 * 
 * This health indicator:
 * - Tests database connectivity with configurable timeout
 * - Executes a simple query to verify database availability
 * - Measures response times for performance monitoring
 * - Provides detailed health information including connection pool status
 * - Supports graceful degradation when database is unavailable
 */
@Component
public class DatabaseHealthIndicator implements HealthIndicator {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseHealthIndicator.class);

    private final DataSource dataSource;
    private final int timeoutMs;
    private final String healthQuery;

    // Performance thresholds (milliseconds)
    private static final long GOOD_RESPONSE_TIME_MS = 100;
    private static final long WARNING_RESPONSE_TIME_MS = 500;
    private static final long CRITICAL_RESPONSE_TIME_MS = 2000;

    @Autowired
    public DatabaseHealthIndicator(
            DataSource dataSource,
            @Value("${app.health.database.timeout:5000}") int timeoutMs,
            @Value("${app.health.database.query:SELECT 1}") String healthQuery) {
        this.dataSource = dataSource;
        this.timeoutMs = timeoutMs;
        this.healthQuery = healthQuery;
    }

    @Override
    public Health health() {
        Health.Builder healthBuilder = new Health.Builder();
        
        try {
            // Record start time for response time measurement
            Instant startTime = Instant.now();
            
            // Test database connectivity with timeout
            DatabaseHealthResult result = checkDatabaseHealth();
            
            // Calculate response time
            long responseTimeMs = Duration.between(startTime, Instant.now()).toMillis();
            
            // Build health status based on results
            if (result.isHealthy()) {
                // Determine status based on response time
                if (responseTimeMs <= GOOD_RESPONSE_TIME_MS) {
                    healthBuilder.up();
                } else if (responseTimeMs <= WARNING_RESPONSE_TIME_MS) {
                    healthBuilder.status("WARNING");
                } else if (responseTimeMs <= CRITICAL_RESPONSE_TIME_MS) {
                    healthBuilder.status("SLOW");
                } else {
                    healthBuilder.status("CRITICAL");
                }
                
                // Add performance details
                healthBuilder
                    .withDetail("database", "PostgreSQL")
                    .withDetail("status", "Connected")
                    .withDetail("response_time_ms", responseTimeMs)
                    .withDetail("performance_rating", getPerformanceRating(responseTimeMs))
                    .withDetail("query_result", result.getQueryResult())
                    .withDetail("connection_valid", true)
                    .withDetail("last_check", Instant.now().toString());
                    
                // Add connection pool information if available
                addConnectionPoolDetails(healthBuilder);
                
            } else {
                healthBuilder.down()
                    .withDetail("database", "PostgreSQL")
                    .withDetail("status", "Disconnected")
                    .withDetail("error", result.getErrorMessage())
                    .withDetail("response_time_ms", responseTimeMs)
                    .withDetail("connection_valid", false)
                    .withDetail("last_check", Instant.now().toString());
            }
            
        } catch (Exception e) {
            logger.error("Database health check failed with unexpected error", e);
            healthBuilder.down()
                .withDetail("database", "PostgreSQL")
                .withDetail("status", "Error")
                .withDetail("error", "Unexpected error during health check")
                .withDetail("connection_valid", false)
                .withDetail("last_check", Instant.now().toString());
        }
        
        return healthBuilder.build();
    }

    /**
     * Performs the actual database health check with timeout.
     * 
     * @return DatabaseHealthResult containing health status and details
     */
    private DatabaseHealthResult checkDatabaseHealth() {
        try (Connection connection = dataSource.getConnection()) {
            
            // Set query timeout
            if (!connection.isValid(timeoutMs / 1000)) {
                return DatabaseHealthResult.unhealthy("Connection validation failed");
            }
            
            // Execute health query
            try (PreparedStatement statement = connection.prepareStatement(healthQuery)) {
                statement.setQueryTimeout(timeoutMs / 1000);
                
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        String queryResult = resultSet.getString(1);
                        return DatabaseHealthResult.healthy(queryResult);
                    } else {
                        return DatabaseHealthResult.unhealthy("Health query returned no results");
                    }
                }
            }
            
        } catch (SQLException e) {
            logger.warn("Database health check failed: {}", e.getMessage());
            return DatabaseHealthResult.unhealthy("Database connection failed: " + e.getMessage());
        }
    }

    /**
     * Adds connection pool details to the health check if available.
     * 
     * @param healthBuilder the health builder to add details to
     */
    private void addConnectionPoolDetails(Health.Builder healthBuilder) {
        try {
            // Try to get connection pool information
            // This is implementation-specific and may vary based on the connection pool used
            if (dataSource instanceof javax.sql.ConnectionPoolDataSource) {
                healthBuilder.withDetail("connection_pool", "Available");
            } else {
                healthBuilder.withDetail("connection_pool", "Standard DataSource");
            }
        } catch (Exception e) {
            // Connection pool information not available
            logger.debug("Could not retrieve connection pool information: {}", e.getMessage());
        }
    }

    /**
     * Gets a human-readable performance rating based on response time.
     * 
     * @param responseTimeMs response time in milliseconds
     * @return performance rating string
     */
    private String getPerformanceRating(long responseTimeMs) {
        if (responseTimeMs <= GOOD_RESPONSE_TIME_MS) {
            return "EXCELLENT";
        } else if (responseTimeMs <= WARNING_RESPONSE_TIME_MS) {
            return "GOOD";
        } else if (responseTimeMs <= CRITICAL_RESPONSE_TIME_MS) {
            return "SLOW";
        } else {
            return "CRITICAL";
        }
    }

    /**
     * Result object for database health checks.
     */
    private static class DatabaseHealthResult {
        private final boolean healthy;
        private final String errorMessage;
        private final String queryResult;

        private DatabaseHealthResult(boolean healthy, String errorMessage, String queryResult) {
            this.healthy = healthy;
            this.errorMessage = errorMessage;
            this.queryResult = queryResult;
        }

        public static DatabaseHealthResult healthy(String queryResult) {
            return new DatabaseHealthResult(true, null, queryResult);
        }

        public static DatabaseHealthResult unhealthy(String errorMessage) {
            return new DatabaseHealthResult(false, errorMessage, null);
        }

        public boolean isHealthy() {
            return healthy;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public String getQueryResult() {
            return queryResult;
        }
    }
}
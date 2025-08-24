package ai.curasnap.backend.health;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DatabaseHealthIndicatorTest {

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    @Mock
    private PreparedStatement preparedStatement;

    @Mock
    private ResultSet resultSet;

    private DatabaseHealthIndicator healthIndicator;

    @BeforeEach
    void setUp() {
        healthIndicator = new DatabaseHealthIndicator(dataSource, 5000, "SELECT 1");
    }

    @Test
    void health_WhenDatabaseIsHealthy_ShouldReturnUpStatus() throws SQLException {
        // Arrange
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(5)).thenReturn(true);
        when(connection.prepareStatement("SELECT 1")).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(1)).thenReturn("1");

        // Act
        Health result = healthIndicator.health();

        // Assert
        assertEquals(Status.UP, result.getStatus());
        assertEquals("PostgreSQL", result.getDetails().get("database"));
        assertEquals("Connected", result.getDetails().get("status"));
        assertTrue(result.getDetails().containsKey("response_time_ms"));
        assertTrue(result.getDetails().containsKey("performance_rating"));
        assertEquals("1", result.getDetails().get("query_result"));
        assertEquals(true, result.getDetails().get("connection_valid"));
    }

    @Test
    void health_WhenConnectionIsInvalid_ShouldReturnDownStatus() throws SQLException {
        // Arrange
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(5)).thenReturn(false);

        // Act
        Health result = healthIndicator.health();

        // Assert
        assertEquals(Status.DOWN, result.getStatus());
        assertEquals("PostgreSQL", result.getDetails().get("database"));
        assertEquals("Disconnected", result.getDetails().get("status"));
        assertEquals("Connection validation failed", result.getDetails().get("error"));
        assertEquals(false, result.getDetails().get("connection_valid"));
    }

    @Test
    void health_WhenSQLExceptionOccurs_ShouldReturnDownStatus() throws SQLException {
        // Arrange
        when(dataSource.getConnection()).thenThrow(new SQLException("Connection failed"));

        // Act
        Health result = healthIndicator.health();

        // Assert
        assertEquals(Status.DOWN, result.getStatus());
        assertEquals("PostgreSQL", result.getDetails().get("database"));
        assertEquals("Disconnected", result.getDetails().get("status"));
        assertTrue(((String) result.getDetails().get("error")).contains("Connection failed"));
        assertEquals(false, result.getDetails().get("connection_valid"));
    }

    @Test
    void health_WhenQueryReturnsNoResults_ShouldReturnDownStatus() throws SQLException {
        // Arrange
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(5)).thenReturn(true);
        when(connection.prepareStatement("SELECT 1")).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false); // No results

        // Act
        Health result = healthIndicator.health();

        // Assert
        assertEquals(Status.DOWN, result.getStatus());
        assertEquals("PostgreSQL", result.getDetails().get("database"));
        assertEquals("Disconnected", result.getDetails().get("status"));
        assertEquals("Health query returned no results", result.getDetails().get("error"));
        assertEquals(false, result.getDetails().get("connection_valid"));
    }

    @Test
    void health_WhenResponseTimeIsSlow_ShouldReturnSlowStatus() throws SQLException {
        // Arrange
        when(dataSource.getConnection()).thenAnswer(invocation -> {
            // Simulate slow connection
            Thread.sleep(600); // Above WARNING_RESPONSE_TIME_MS (500ms)
            return connection;
        });
        when(connection.isValid(5)).thenReturn(true);
        when(connection.prepareStatement("SELECT 1")).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(1)).thenReturn("1");

        // Act
        Health result = healthIndicator.health();

        // Assert
        assertEquals("SLOW", result.getStatus().getCode());
        assertEquals("PostgreSQL", result.getDetails().get("database"));
        assertEquals("Connected", result.getDetails().get("status"));
        assertTrue((Long) result.getDetails().get("response_time_ms") > 500);
        assertEquals("SLOW", result.getDetails().get("performance_rating"));
    }

    @Test
    void health_WhenUnexpectedExceptionOccurs_ShouldReturnDownStatus() throws SQLException {
        // Arrange
        when(dataSource.getConnection()).thenThrow(new RuntimeException("Unexpected error"));

        // Act
        Health result = healthIndicator.health();

        // Assert
        assertEquals(Status.DOWN, result.getStatus());
        assertEquals("PostgreSQL", result.getDetails().get("database"));
        assertEquals("Error", result.getDetails().get("status"));
        assertEquals("Unexpected error during health check", result.getDetails().get("error"));
        assertEquals(false, result.getDetails().get("connection_valid"));
    }

    @Test
    void health_WhenConnectionIsValid_ShouldCloseResourcesProperly() throws SQLException {
        // Arrange
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(5)).thenReturn(true);
        when(connection.prepareStatement("SELECT 1")).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(1)).thenReturn("1");

        // Act
        healthIndicator.health();

        // Assert - Verify resources are closed
        verify(resultSet, times(1)).close();
        verify(preparedStatement, times(1)).close();
        verify(connection, times(1)).close();
    }
}
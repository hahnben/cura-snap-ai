package ai.curasnap.backend.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Service for tracking request latency and performance metrics using Micrometer.
 * Provides centralized timing capabilities for all controllers and business operations.
 * 
 * Key Features:
 * - Request latency tracking with percentiles (P50, P95, P99)
 * - Custom business operation timing
 * - Automatic counter metrics for success/failure rates
 * - Thread-safe metric collection
 * - Tag-based metric categorization for better observability
 */
@Slf4j
@Service
public class RequestLatencyMetricsService {

    private final MeterRegistry meterRegistry;
    
    // Cache for Timer instances to avoid repeated creation
    private final ConcurrentMap<String, Timer> timerCache = new ConcurrentHashMap<>();
    
    // Cache for Counter instances
    private final ConcurrentMap<String, Counter> counterCache = new ConcurrentHashMap<>();

    @Autowired
    public RequestLatencyMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        log.info("RequestLatencyMetricsService initialized with MeterRegistry");
    }

    /**
     * Times a supplier execution and records the duration with detailed tags.
     * 
     * @param operation the operation name (e.g., "soap_generation", "audio_transcription")
     * @param endpoint the endpoint name (e.g., "/api/v1/notes/format")
     * @param method the HTTP method (e.g., "POST", "GET")
     * @param supplier the operation to time
     * @param <T> the return type of the operation
     * @return the result of the supplier execution
     */
    public <T> T timeOperation(String operation, String endpoint, String method, Supplier<T> supplier) {
        String timerKey = buildTimerKey(operation, endpoint, method);
        Timer timer = getOrCreateTimer(timerKey, operation, endpoint, method);
        
        Timer.Sample sample = Timer.start(meterRegistry);
        boolean success = false;
        String status = "unknown";
        
        try {
            T result = supplier.get();
            success = true;
            status = "success";
            return result;
        } catch (Exception e) {
            status = "error";
            // Record error counter
            incrementErrorCounter(operation, endpoint, method, e.getClass().getSimpleName());
            throw e;
        } finally {
            // Always record timing, regardless of success/failure
            sample.stop(timer);
            
            // Record success/error counters
            if (success) {
                incrementSuccessCounter(operation, endpoint, method);
            }
            
            log.debug("Recorded timing for operation: {}, endpoint: {}, method: {}, status: {}",
                     operation, endpoint, method, status);
        }
    }

    /**
     * Times a runnable execution (void operations).
     * 
     * @param operation the operation name
     * @param endpoint the endpoint name
     * @param method the HTTP method
     * @param runnable the operation to time
     */
    public void timeOperation(String operation, String endpoint, String method, Runnable runnable) {
        timeOperation(operation, endpoint, method, () -> {
            runnable.run();
            return null;
        });
    }

    /**
     * Records a custom duration measurement.
     * 
     * @param operation the operation name
     * @param endpoint the endpoint name
     * @param method the HTTP method
     * @param duration the duration to record
     * @param status the operation status (success, error, etc.)
     */
    public void recordDuration(String operation, String endpoint, String method, Duration duration, String status) {
        String timerKey = buildTimerKey(operation, endpoint, method);
        Timer timer = getOrCreateTimer(timerKey, operation, endpoint, method);
        timer.record(duration);
        
        log.debug("Recorded custom duration for operation: {}, endpoint: {}, method: {}, duration: {}ms, status: {}",
                 operation, endpoint, method, duration.toMillis(), status);
    }

    /**
     * Increments a custom counter for business metrics.
     * 
     * @param metricName the metric name
     * @param tags additional tags for categorization
     */
    public void incrementCounter(String metricName, String... tags) {
        if (tags.length % 2 != 0) {
            throw new IllegalArgumentException("Tags must be provided as key-value pairs");
        }
        
        Counter.Builder builder = Counter.builder(metricName);
        
        // Add tags in pairs
        for (int i = 0; i < tags.length; i += 2) {
            builder.tag(tags[i], tags[i + 1]);
        }
        
        Counter counter = builder.register(meterRegistry);
        counter.increment();
        
        log.debug("Incremented counter: {} with tags: {}", metricName, String.join(", ", tags));
    }

    /**
     * Records a gauge value for monitoring current state metrics.
     * 
     * @param metricName the metric name
     * @param value the current value
     * @param tags additional tags for categorization
     */
    public void recordGauge(String metricName, Number value, String... tags) {
        if (tags.length % 2 != 0) {
            throw new IllegalArgumentException("Tags must be provided as key-value pairs");
        }
        
        // Build tags for Gauge
        io.micrometer.core.instrument.Tags gaugeTags = io.micrometer.core.instrument.Tags.empty();
        for (int i = 0; i < tags.length; i += 2) {
            gaugeTags = gaugeTags.and(tags[i], tags[i + 1]);
        }
        
        meterRegistry.gauge(metricName, gaugeTags, value.doubleValue());
        
        log.debug("Recorded gauge: {} = {} with tags: {}", metricName, value, String.join(", ", tags));
    }

    // Private helper methods

    private String buildTimerKey(String operation, String endpoint, String method) {
        return String.format("request.%s.%s.%s", operation, sanitizeForMetricName(endpoint), method.toLowerCase());
    }

    private Timer getOrCreateTimer(String timerKey, String operation, String endpoint, String method) {
        return timerCache.computeIfAbsent(timerKey, key -> 
            Timer.builder("http_request_duration_seconds")
                .description("HTTP request duration in seconds")
                .tag("operation", operation)
                .tag("endpoint", endpoint)
                .tag("method", method)
                .publishPercentiles(0.5, 0.95, 0.99) // P50, P95, P99
                .register(meterRegistry)
        );
    }

    private void incrementSuccessCounter(String operation, String endpoint, String method) {
        String counterKey = buildCounterKey("success", operation, endpoint, method);
        Counter counter = counterCache.computeIfAbsent(counterKey, key ->
            Counter.builder("http_requests_total")
                .description("Total HTTP requests")
                .tag("operation", operation)
                .tag("endpoint", endpoint)
                .tag("method", method)
                .tag("status", "success")
                .register(meterRegistry)
        );
        counter.increment();
    }

    private void incrementErrorCounter(String operation, String endpoint, String method, String errorType) {
        String counterKey = buildCounterKey("error." + errorType, operation, endpoint, method);
        Counter counter = counterCache.computeIfAbsent(counterKey, key ->
            Counter.builder("http_requests_total")
                .description("Total HTTP requests")
                .tag("operation", operation)
                .tag("endpoint", endpoint)
                .tag("method", method)
                .tag("status", "error")
                .tag("error_type", errorType)
                .register(meterRegistry)
        );
        counter.increment();
    }

    private String buildCounterKey(String type, String operation, String endpoint, String method) {
        return String.format("counter.%s.%s.%s.%s", type, operation, sanitizeForMetricName(endpoint), method.toLowerCase());
    }

    private String sanitizeForMetricName(String input) {
        // Replace non-alphanumeric characters with underscores for valid metric names
        return input.replaceAll("[^a-zA-Z0-9]", "_").toLowerCase();
    }

    // Business-specific convenience methods

    /**
     * Times SOAP note generation operations.
     */
    public <T> T timeSoapGeneration(Supplier<T> supplier) {
        return timeOperation("soap_generation", "/api/v1/notes/format", "POST", supplier);
    }

    /**
     * Times audio transcription operations.
     */
    public <T> T timeAudioTranscription(Supplier<T> supplier) {
        return timeOperation("audio_transcription", "/api/v1/notes/format-audio", "POST", supplier);
    }

    /**
     * Times async job operations.
     */
    public <T> T timeAsyncJob(String jobType, Supplier<T> supplier) {
        return timeOperation("async_job_" + jobType.toLowerCase(), "/api/v1/async", "POST", supplier);
    }

    /**
     * Times database operations.
     */
    public <T> T timeDatabaseOperation(String operation, Supplier<T> supplier) {
        return timeOperation("database_" + operation.toLowerCase(), "/database", "INTERNAL", supplier);
    }

    /**
     * Times external service calls.
     */
    public <T> T timeExternalService(String serviceName, Supplier<T> supplier) {
        return timeOperation("external_service_" + serviceName.toLowerCase(), "/external", "HTTP", supplier);
    }
}
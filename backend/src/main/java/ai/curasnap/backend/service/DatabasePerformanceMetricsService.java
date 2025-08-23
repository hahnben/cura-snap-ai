package ai.curasnap.backend.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Service for tracking database query performance metrics using Micrometer.
 * Provides comprehensive monitoring for Phase 3.2 database query optimizations.
 */
@Service
public class DatabasePerformanceMetricsService {

    private final MeterRegistry meterRegistry;

    // Query timers for different query types
    private final Timer userQueryTimer;
    private final Timer sessionQueryTimer;
    private final Timer dateRangeQueryTimer;
    private final Timer compositeQueryTimer;

    // Query counters
    private final Counter totalQueriesCounter;
    private final Counter optimizedQueriesCounter;
    private final Counter slowQueriesCounter;

    @Autowired
    public DatabasePerformanceMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        // Initialize timers
        this.userQueryTimer = Timer.builder("database.query.user")
                .description("Timer for user-based queries")
                .tag("query_type", "user")
                .register(meterRegistry);

        this.sessionQueryTimer = Timer.builder("database.query.session")
                .description("Timer for session-based queries")
                .tag("query_type", "session")
                .register(meterRegistry);

        this.dateRangeQueryTimer = Timer.builder("database.query.daterange")
                .description("Timer for date-range queries")
                .tag("query_type", "daterange")
                .register(meterRegistry);

        this.compositeQueryTimer = Timer.builder("database.query.composite")
                .description("Timer for composite queries (replaces stream filtering)")
                .tag("query_type", "composite")
                .register(meterRegistry);

        // Initialize counters
        this.totalQueriesCounter = Counter.builder("database.queries.total")
                .description("Total number of database queries")
                .register(meterRegistry);

        this.optimizedQueriesCounter = Counter.builder("database.queries.optimized")
                .description("Number of queries using optimized indexes")
                .register(meterRegistry);

        this.slowQueriesCounter = Counter.builder("database.queries.slow")
                .description("Number of slow queries (>100ms)")
                .register(meterRegistry);
    }

    /**
     * Records metrics for a user-based query execution.
     *
     * @param executionTime the query execution time in milliseconds
     * @param resultCount the number of results returned
     * @param queryMethod the repository method used (for tagging)
     */
    public void recordUserQuery(long executionTime, int resultCount, String queryMethod) {
        userQueryTimer.record(executionTime, TimeUnit.MILLISECONDS);
        totalQueriesCounter.increment();
        optimizedQueriesCounter.increment(); // All new queries use indexes

        if (executionTime > 100) {
            slowQueriesCounter.increment();
        }

        // Additional metrics
        meterRegistry.gauge("database.query.user.result_count", resultCount);
        meterRegistry.counter("database.query.user.method", "method", queryMethod).increment();
    }

    /**
     * Records metrics for a session-based query execution.
     *
     * @param executionTime the query execution time in milliseconds
     * @param resultCount the number of results returned
     * @param isComposite whether this is a composite query (session+user)
     */
    public void recordSessionQuery(long executionTime, int resultCount, boolean isComposite) {
        if (isComposite) {
            compositeQueryTimer.record(executionTime, TimeUnit.MILLISECONDS);
            meterRegistry.counter("database.query.composite.usage").increment();
        } else {
            sessionQueryTimer.record(executionTime, TimeUnit.MILLISECONDS);
        }

        totalQueriesCounter.increment();
        optimizedQueriesCounter.increment();

        if (executionTime > 100) {
            slowQueriesCounter.increment();
        }

        meterRegistry.gauge("database.query.session.result_count", resultCount);
    }

    /**
     * Records metrics for a date-range query execution.
     *
     * @param executionTime the query execution time in milliseconds
     * @param resultCount the number of results returned
     * @param dateRangeSize the size of the date range in days
     */
    public void recordDateRangeQuery(long executionTime, int resultCount, long dateRangeSize) {
        dateRangeQueryTimer.record(executionTime, TimeUnit.MILLISECONDS);
        totalQueriesCounter.increment();
        optimizedQueriesCounter.increment();

        if (executionTime > 100) {
            slowQueriesCounter.increment();
        }

        meterRegistry.gauge("database.query.daterange.result_count", resultCount);
        meterRegistry.gauge("database.query.daterange.range_size_days", dateRangeSize);
    }

    /**
     * Records comparison metrics between old (stream) and new (database) approaches.
     *
     * @param streamTime time taken by stream filtering approach
     * @param databaseTime time taken by database query approach
     * @param resultCount number of results
     */
    public void recordPerformanceComparison(long streamTime, long databaseTime, int resultCount) {
        double performanceImprovement = streamTime > 0 ? (double) streamTime / databaseTime : 1.0;

        meterRegistry.gauge("database.performance.improvement_factor", performanceImprovement);
        meterRegistry.gauge("database.performance.time_saved_ms", streamTime - databaseTime);

        meterRegistry.counter("database.performance.comparisons.total").increment();

        if (performanceImprovement > 2.0) {
            meterRegistry.counter("database.performance.significant_improvements").increment();
        }
    }

    /**
     * Utility method to time query execution and automatically record metrics.
     *
     * @param queryType the type of query being executed
     * @param queryExecution the query execution lambda
     * @param <T> the return type of the query
     * @return the query result
     */
    public <T> T timeQuery(QueryType queryType, QueryExecution<T> queryExecution) {
        Instant start = Instant.now();
        T result = queryExecution.execute();
        long executionTime = Duration.between(start, Instant.now()).toMillis();

        // Record metrics based on query type
        switch (queryType) {
            case USER -> recordUserQuery(executionTime, getResultCount(result), "timed_execution");
            case SESSION -> recordSessionQuery(executionTime, getResultCount(result), false);
            case COMPOSITE -> recordSessionQuery(executionTime, getResultCount(result), true);
            case DATE_RANGE -> recordDateRangeQuery(executionTime, getResultCount(result), 0);
        }

        return result;
    }

    /**
     * Gets custom metrics for the database performance dashboard.
     *
     * @return DatabaseMetrics containing current performance statistics
     */
    public DatabaseMetrics getCurrentMetrics() {
        return DatabaseMetrics.builder()
                .totalQueries(totalQueriesCounter.count())
                .optimizedQueries(optimizedQueriesCounter.count())
                .slowQueries(slowQueriesCounter.count())
                .averageUserQueryTime(userQueryTimer.mean(TimeUnit.MILLISECONDS))
                .averageSessionQueryTime(sessionQueryTimer.mean(TimeUnit.MILLISECONDS))
                .averageDateRangeQueryTime(dateRangeQueryTimer.mean(TimeUnit.MILLISECONDS))
                .averageCompositeQueryTime(compositeQueryTimer.mean(TimeUnit.MILLISECONDS))
                .optimizationRate(calculateOptimizationRate())
                .build();
    }

    private double calculateOptimizationRate() {
        double total = totalQueriesCounter.count();
        return total > 0 ? (optimizedQueriesCounter.count() / total) * 100 : 0;
    }

    private int getResultCount(Object result) {
        if (result instanceof java.util.Collection<?> collection) {
            return collection.size();
        }
        return result != null ? 1 : 0;
    }

    public enum QueryType {
        USER, SESSION, COMPOSITE, DATE_RANGE
    }

    @FunctionalInterface
    public interface QueryExecution<T> {
        T execute();
    }

    /**
     * Data class for database performance metrics.
     */
    public static class DatabaseMetrics {
        private final double totalQueries;
        private final double optimizedQueries;
        private final double slowQueries;
        private final double averageUserQueryTime;
        private final double averageSessionQueryTime;
        private final double averageDateRangeQueryTime;
        private final double averageCompositeQueryTime;
        private final double optimizationRate;

        private DatabaseMetrics(Builder builder) {
            this.totalQueries = builder.totalQueries;
            this.optimizedQueries = builder.optimizedQueries;
            this.slowQueries = builder.slowQueries;
            this.averageUserQueryTime = builder.averageUserQueryTime;
            this.averageSessionQueryTime = builder.averageSessionQueryTime;
            this.averageDateRangeQueryTime = builder.averageDateRangeQueryTime;
            this.averageCompositeQueryTime = builder.averageCompositeQueryTime;
            this.optimizationRate = builder.optimizationRate;
        }

        public static Builder builder() {
            return new Builder();
        }

        // Getters
        public double getTotalQueries() { return totalQueries; }
        public double getOptimizedQueries() { return optimizedQueries; }
        public double getSlowQueries() { return slowQueries; }
        public double getAverageUserQueryTime() { return averageUserQueryTime; }
        public double getAverageSessionQueryTime() { return averageSessionQueryTime; }
        public double getAverageDateRangeQueryTime() { return averageDateRangeQueryTime; }
        public double getAverageCompositeQueryTime() { return averageCompositeQueryTime; }
        public double getOptimizationRate() { return optimizationRate; }

        public static class Builder {
            private double totalQueries;
            private double optimizedQueries;
            private double slowQueries;
            private double averageUserQueryTime;
            private double averageSessionQueryTime;
            private double averageDateRangeQueryTime;
            private double averageCompositeQueryTime;
            private double optimizationRate;

            public Builder totalQueries(double totalQueries) {
                this.totalQueries = totalQueries;
                return this;
            }

            public Builder optimizedQueries(double optimizedQueries) {
                this.optimizedQueries = optimizedQueries;
                return this;
            }

            public Builder slowQueries(double slowQueries) {
                this.slowQueries = slowQueries;
                return this;
            }

            public Builder averageUserQueryTime(double averageUserQueryTime) {
                this.averageUserQueryTime = averageUserQueryTime;
                return this;
            }

            public Builder averageSessionQueryTime(double averageSessionQueryTime) {
                this.averageSessionQueryTime = averageSessionQueryTime;
                return this;
            }

            public Builder averageDateRangeQueryTime(double averageDateRangeQueryTime) {
                this.averageDateRangeQueryTime = averageDateRangeQueryTime;
                return this;
            }

            public Builder averageCompositeQueryTime(double averageCompositeQueryTime) {
                this.averageCompositeQueryTime = averageCompositeQueryTime;
                return this;
            }

            public Builder optimizationRate(double optimizationRate) {
                this.optimizationRate = optimizationRate;
                return this;
            }

            public DatabaseMetrics build() {
                return new DatabaseMetrics(this);
            }
        }
    }
}
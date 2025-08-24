package ai.curasnap.backend.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for monitoring Redis queue depths, processing rates, and job lifecycle metrics.
 * Provides comprehensive observability into the async job processing system.
 * 
 * Key Features:
 * - Real-time queue depth monitoring
 * - Job processing rate tracking
 * - Dead Letter Queue monitoring
 * - Retry statistics
 * - Worker pool utilization metrics
 * - Historical trend analysis
 */
@Slf4j
@Service
public class QueueMonitoringService {

    private final MeterRegistry meterRegistry;
    private final RedisTemplate<String, Object> redisTemplate;
    
    // Redis key prefixes (matching JobServiceImpl)
    private static final String QUEUE_KEY_PREFIX = "queue:";
    private static final String DEAD_LETTER_QUEUE_PREFIX = "dlq:";
    private static final String USER_JOBS_KEY_PREFIX = "user_jobs:";
    private static final String JOB_EXPIRY_SET_KEY = "job_expiry_set";
    private static final String RETRY_SCHEDULE_KEY = "retry_schedule";
    private static final String RETRY_STATS_KEY = "retry_stats";
    
    // Known queue names
    private static final String[] QUEUE_NAMES = {
        "audio_processing", 
        "text_processing", 
        "cache_warming", 
        "default"
    };
    
    // Metrics caching for performance
    private final Map<String, Long> lastQueueSizes = new ConcurrentHashMap<>();
    private final Map<String, Long> lastProcessingCounts = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastUpdateTimes = new ConcurrentHashMap<>();
    
    // Gauges for real-time metrics
    private final Map<String, Gauge> queueDepthGauges = new ConcurrentHashMap<>();
    private final Map<String, Gauge> dlqSizeGauges = new ConcurrentHashMap<>();
    
    // Counters for cumulative metrics
    private final Map<String, Counter> jobsProcessedCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> jobsFailedCounters = new ConcurrentHashMap<>();
    
    // Timers for latency tracking
    private Timer queueMonitoringTimer;

    @Autowired
    public QueueMonitoringService(MeterRegistry meterRegistry, RedisTemplate<String, Object> redisTemplate) {
        this.meterRegistry = meterRegistry;
        this.redisTemplate = redisTemplate;
        initializeMetrics();
        log.info("QueueMonitoringService initialized with {} queue types", QUEUE_NAMES.length);
    }

    /**
     * Initializes all Micrometer metrics for queue monitoring
     */
    private void initializeMetrics() {
        // Initialize queue monitoring timer
        queueMonitoringTimer = Timer.builder("queue_monitoring_duration_seconds")
                .description("Time spent monitoring Redis queues")
                .register(meterRegistry);

        // Initialize gauges and counters for each queue
        for (String queueName : QUEUE_NAMES) {
            initializeQueueMetrics(queueName);
        }

        // Initialize global retry metrics
        initializeRetryMetrics();
    }

    /**
     * Initializes metrics for a specific queue
     */
    private void initializeQueueMetrics(String queueName) {
        // Queue depth gauge
        Gauge queueDepthGauge = Gauge.builder("redis_queue_depth", this, service -> service.getCurrentQueueSize(queueName))
                .description("Current number of jobs in Redis queue")
                .tag("queue", queueName)
                .register(meterRegistry);
        queueDepthGauges.put(queueName, queueDepthGauge);

        // Dead Letter Queue size gauge
        Gauge dlqGauge = Gauge.builder("redis_dlq_depth", this, service -> service.getCurrentDLQSize(queueName))
                .description("Current number of jobs in Dead Letter Queue")
                .tag("queue", queueName)
                .register(meterRegistry);
        dlqSizeGauges.put(queueName, dlqGauge);

        // Jobs processed counter
        Counter processedCounter = Counter.builder("jobs_processed_total")
                .description("Total number of jobs processed")
                .tag("queue", queueName)
                .tag("status", "processed")
                .register(meterRegistry);
        jobsProcessedCounters.put(queueName, processedCounter);

        // Jobs failed counter
        Counter failedCounter = Counter.builder("jobs_failed_total")
                .description("Total number of jobs that failed processing")
                .tag("queue", queueName)
                .tag("status", "failed")
                .register(meterRegistry);
        jobsFailedCounters.put(queueName, failedCounter);

        log.debug("Initialized metrics for queue: {}", queueName);
    }

    /**
     * Initializes retry-related metrics
     */
    private void initializeRetryMetrics() {
        Gauge.builder("redis_retry_schedule_size", this, service -> service.getRetryScheduleSize())
                .description("Number of jobs scheduled for retry")
                .register(meterRegistry);

        Counter.builder("job_retries_total")
                .description("Total number of job retry attempts")
                .register(meterRegistry);
    }

    /**
     * Gets current queue size for a specific queue
     */
    public Long getCurrentQueueSize(String queueName) {
        try {
            String queueKey = QUEUE_KEY_PREFIX + queueName;
            Long size = redisTemplate.opsForList().size(queueKey);
            return size != null ? size : 0L;
        } catch (Exception e) {
            log.warn("Failed to get queue size for {}: {}", queueName, e.getMessage());
            return 0L;
        }
    }

    /**
     * Gets current Dead Letter Queue size for a specific queue
     */
    public Long getCurrentDLQSize(String queueName) {
        try {
            String dlqKey = DEAD_LETTER_QUEUE_PREFIX + queueName;
            Long size = redisTemplate.opsForList().size(dlqKey);
            return size != null ? size : 0L;
        } catch (Exception e) {
            log.warn("Failed to get DLQ size for {}: {}", queueName, e.getMessage());
            return 0L;
        }
    }

    /**
     * Gets retry schedule size
     */
    public Long getRetryScheduleSize() {
        try {
            Long size = redisTemplate.opsForZSet().size(RETRY_SCHEDULE_KEY);
            return size != null ? size : 0L;
        } catch (Exception e) {
            log.warn("Failed to get retry schedule size: {}", e.getMessage());
            return 0L;
        }
    }

    /**
     * Gets total active jobs across all users
     */
    public Long getTotalActiveJobs() {
        try {
            Set<String> userKeys = redisTemplate.keys(USER_JOBS_KEY_PREFIX + "*");
            if (userKeys == null || userKeys.isEmpty()) {
                return 0L;
            }

            long totalJobs = 0;
            for (String userKey : userKeys) {
                Long userJobCount = redisTemplate.opsForSet().size(userKey);
                totalJobs += userJobCount != null ? userJobCount : 0;
            }
            return totalJobs;
        } catch (Exception e) {
            log.warn("Failed to get total active jobs: {}", e.getMessage());
            return 0L;
        }
    }

    /**
     * Gets queue statistics for a specific queue
     */
    public Map<String, Object> getQueueStatistics(String queueName) {
        Map<String, Object> stats = new HashMap<>();
        
        try {
            // Basic queue metrics
            stats.put("queueName", queueName);
            stats.put("queueDepth", getCurrentQueueSize(queueName));
            stats.put("dlqDepth", getCurrentDLQSize(queueName));
            
            // Processing rate calculation (jobs per minute)
            Long currentSize = getCurrentQueueSize(queueName);
            Long previousSize = lastQueueSizes.get(queueName);
            Instant lastUpdate = lastUpdateTimes.get(queueName);
            
            if (previousSize != null && lastUpdate != null) {
                Duration timeDiff = Duration.between(lastUpdate, Instant.now());
                if (timeDiff.toSeconds() > 0) {
                    long sizeDiff = Math.abs(previousSize - currentSize);
                    double processingRate = (sizeDiff * 60.0) / timeDiff.toSeconds();
                    stats.put("processingRatePerMinute", processingRate);
                } else {
                    stats.put("processingRatePerMinute", 0.0);
                }
            } else {
                stats.put("processingRatePerMinute", 0.0);
            }
            
            // Update tracking data
            lastQueueSizes.put(queueName, currentSize);
            lastUpdateTimes.put(queueName, Instant.now());
            
            // Queue health status
            stats.put("status", determineQueueHealth(queueName, currentSize));
            stats.put("timestamp", Instant.now().toString());
            
            log.debug("Generated statistics for queue {}: depth={}, dlq={}", 
                     queueName, currentSize, stats.get("dlqDepth"));
            
        } catch (Exception e) {
            log.error("Failed to generate queue statistics for {}: {}", queueName, e.getMessage());
            stats.put("error", "Failed to retrieve statistics");
            stats.put("status", "ERROR");
        }
        
        return stats;
    }

    /**
     * Gets comprehensive queue statistics for all queues
     */
    public Map<String, Object> getAllQueueStatistics() {
        Map<String, Object> allStats = new HashMap<>();
        
        Timer.Sample sample = Timer.start(meterRegistry);
        
        try {
            // Individual queue statistics
            Map<String, Map<String, Object>> queueStats = new HashMap<>();
            for (String queueName : QUEUE_NAMES) {
                queueStats.put(queueName, getQueueStatistics(queueName));
            }
            allStats.put("queues", queueStats);
            
            // Global statistics
            allStats.put("totalActiveJobs", getTotalActiveJobs());
            allStats.put("retryScheduleSize", getRetryScheduleSize());
            allStats.put("monitoringTimestamp", Instant.now().toString());
            
            // Overall system health
            allStats.put("systemHealth", determineOverallQueueHealth(queueStats));
            
        } finally {
            sample.stop(queueMonitoringTimer);
        }
        
        return allStats;
    }

    /**
     * Determines queue health based on current metrics
     */
    private String determineQueueHealth(String queueName, Long queueDepth) {
        if (queueDepth == null) return "UNKNOWN";
        
        // Health thresholds (configurable in future)
        if (queueDepth == 0) return "IDLE";
        if (queueDepth <= 10) return "HEALTHY";
        if (queueDepth <= 50) return "BUSY";
        if (queueDepth <= 100) return "STRESSED";
        return "OVERLOADED";
    }

    /**
     * Determines overall queue system health
     */
    private String determineOverallQueueHealth(Map<String, Map<String, Object>> queueStats) {
        int overloadedCount = 0;
        int stressedCount = 0;
        int busyCount = 0;
        int totalQueues = queueStats.size();
        
        for (Map<String, Object> stats : queueStats.values()) {
            String status = (String) stats.get("status");
            if ("OVERLOADED".equals(status)) overloadedCount++;
            else if ("STRESSED".equals(status)) stressedCount++;
            else if ("BUSY".equals(status)) busyCount++;
        }
        
        if (overloadedCount > 0) return "CRITICAL";
        if (stressedCount >= totalQueues / 2) return "DEGRADED";
        if (busyCount >= totalQueues / 2) return "BUSY";
        return "HEALTHY";
    }

    /**
     * Records job processing metrics
     */
    public void recordJobProcessed(String queueName, boolean success) {
        if (success) {
            Counter counter = jobsProcessedCounters.get(queueName);
            if (counter != null) {
                counter.increment();
            }
        } else {
            Counter counter = jobsFailedCounters.get(queueName);
            if (counter != null) {
                counter.increment();
            }
        }
        
        log.debug("Recorded job processing: queue={}, success={}", queueName, success);
    }

    /**
     * Records job retry metrics
     */
    public void recordJobRetry(String queueName, int retryCount) {
        Counter retryCounter = meterRegistry.counter("job_retries_total", 
                                                    "queue", queueName, 
                                                    "retry_count", String.valueOf(retryCount));
        retryCounter.increment();
        
        log.debug("Recorded job retry: queue={}, retryCount={}", queueName, retryCount);
    }

    /**
     * Gets queue names that are currently active (have jobs)
     */
    public List<String> getActiveQueues() {
        List<String> activeQueues = new ArrayList<>();
        
        for (String queueName : QUEUE_NAMES) {
            Long queueSize = getCurrentQueueSize(queueName);
            if (queueSize != null && queueSize > 0) {
                activeQueues.add(queueName);
            }
        }
        
        return activeQueues;
    }

    /**
     * Scheduled method to update queue monitoring metrics
     * Runs every 30 seconds to provide near real-time monitoring
     */
    @Scheduled(fixedRate = 30000) // 30 seconds
    public void updateQueueMetrics() {
        try {
            log.debug("Updating queue monitoring metrics...");
            
            Timer.Sample sample = Timer.start(meterRegistry);
            
            // Update metrics for all queues
            for (String queueName : QUEUE_NAMES) {
                Long queueSize = getCurrentQueueSize(queueName);
                Long dlqSize = getCurrentDLQSize(queueName);
                
                // Metrics are automatically updated through the gauges
                log.trace("Queue {}: depth={}, dlq={}", queueName, queueSize, dlqSize);
            }
            
            // Update global metrics
            Long totalJobs = getTotalActiveJobs();
            Long retryScheduleSize = getRetryScheduleSize();
            
            // Record as gauge for Prometheus
            meterRegistry.gauge("total_active_jobs", totalJobs);
            meterRegistry.gauge("retry_schedule_size", retryScheduleSize);
            
            sample.stop(Timer.builder("queue_metrics_update_duration_seconds")
                           .description("Time spent updating queue metrics")
                           .register(meterRegistry));
            
            log.trace("Queue metrics updated successfully. Total active jobs: {}, Retry schedule: {}", 
                     totalJobs, retryScheduleSize);
            
        } catch (Exception e) {
            log.error("Failed to update queue metrics: {}", e.getMessage(), e);
            
            // Record error metric
            meterRegistry.counter("queue_monitoring_errors_total", 
                                "error_type", e.getClass().getSimpleName()).increment();
        }
    }

    /**
     * Gets detailed queue monitoring report for admin/debugging purposes
     */
    public Map<String, Object> getDetailedMonitoringReport() {
        Map<String, Object> report = new HashMap<>();
        
        try {
            // All queue statistics
            report.put("queueStatistics", getAllQueueStatistics());
            
            // Active queues
            report.put("activeQueues", getActiveQueues());
            
            // Redis connection health
            report.put("redisConnectionHealthy", checkRedisHealth());
            
            // Monitoring service metrics
            Map<String, Object> serviceMetrics = new HashMap<>();
            serviceMetrics.put("lastUpdateTimes", lastUpdateTimes);
            serviceMetrics.put("trackedQueues", Arrays.asList(QUEUE_NAMES));
            serviceMetrics.put("metricsCount", queueDepthGauges.size() + dlqSizeGauges.size());
            report.put("monitoringServiceMetrics", serviceMetrics);
            
            report.put("reportGeneratedAt", Instant.now().toString());
            
        } catch (Exception e) {
            log.error("Failed to generate detailed monitoring report: {}", e.getMessage());
            report.put("error", "Failed to generate report: " + e.getMessage());
        }
        
        return report;
    }

    /**
     * Checks Redis connection health
     */
    private boolean checkRedisHealth() {
        try {
            redisTemplate.hasKey("health_check_key");
            return true;
        } catch (Exception e) {
            log.warn("Redis health check failed: {}", e.getMessage());
            return false;
        }
    }
}
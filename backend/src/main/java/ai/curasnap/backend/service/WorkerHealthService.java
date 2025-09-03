package ai.curasnap.backend.service;

import ai.curasnap.backend.service.interfaces.QueueStatsProvider;
import ai.curasnap.backend.service.interfaces.WorkerMetricsProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service for monitoring worker health and performance
 * Tracks worker statistics, health metrics, and provides system insights
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.worker.health.enabled", havingValue = "true", matchIfMissing = true)
public class WorkerHealthService implements WorkerMetricsProvider {

    private final RedisTemplate<String, Object> redisTemplate;
    private final QueueStatsProvider queueStatsProvider;
    
    // Redis key prefixes for worker health data
    private static final String WORKER_HEARTBEAT_PREFIX = "worker_heartbeat:";
    private static final String WORKER_STATS_PREFIX = "worker_stats:";
    private static final String WORKER_METRICS_PREFIX = "worker_metrics:";
    
    // Local health tracking
    private final Map<String, WorkerHealth> workerHealthMap = new ConcurrentHashMap<>();
    private volatile Instant lastHealthCheck = Instant.now();
    
    // System health metrics
    private volatile long totalProcessedJobs = 0;
    private volatile long totalFailedJobs = 0;
    private volatile double averageProcessingTime = 0.0;
    private volatile int activeWorkers = 0;

    @Autowired
    public WorkerHealthService(RedisTemplate<String, Object> redisTemplate, @Lazy QueueStatsProvider queueStatsProvider) {
        this.redisTemplate = redisTemplate;
        this.queueStatsProvider = queueStatsProvider;
    }

    /**
     * Register a worker instance with the health service
     * Called when a worker starts processing
     *
     * @param workerId unique worker identifier
     * @param workerType type of worker (e.g., "audio_processing")
     */
    public void registerWorker(String workerId, String workerType) {
        try {
            WorkerHealth workerHealth = WorkerHealth.builder()
                    .workerId(workerId)
                    .workerType(workerType)
                    .status(WorkerStatus.ACTIVE)
                    .startTime(Instant.now())
                    .lastHeartbeat(Instant.now())
                    .processedJobs(0)
                    .failedJobs(0)
                    .build();

            workerHealthMap.put(workerId, workerHealth);
            
            // Store in Redis for distributed tracking
            String heartbeatKey = WORKER_HEARTBEAT_PREFIX + workerId;
            redisTemplate.opsForValue().set(heartbeatKey, workerHealth, Duration.ofMinutes(10));
            
            log.info("Registered worker {} of type {}", workerId, workerType);
            
        } catch (Exception e) {
            log.error("Failed to register worker {}: {}", workerId, e.getMessage(), e);
        }
    }

    /**
     * Update worker heartbeat - called periodically by active workers
     *
     * @param workerId the worker ID
     */
    public void updateWorkerHeartbeat(String workerId) {
        try {
            WorkerHealth workerHealth = workerHealthMap.get(workerId);
            if (workerHealth != null) {
                workerHealth.setLastHeartbeat(Instant.now());
                workerHealth.setStatus(WorkerStatus.ACTIVE);
                
                // Update in Redis
                String heartbeatKey = WORKER_HEARTBEAT_PREFIX + workerId;
                redisTemplate.opsForValue().set(heartbeatKey, workerHealth, Duration.ofMinutes(10));
            } else {
                log.warn("Heartbeat update for unregistered worker: {}", workerId);
            }
            
        } catch (Exception e) {
            log.error("Failed to update heartbeat for worker {}: {}", workerId, e.getMessage(), e);
        }
    }

    /**
     * Record job processing statistics for a worker
     *
     * @param workerId the worker ID
     * @param success whether the job was successful
     * @param processingTimeMs processing time in milliseconds
     */
    public void recordJobProcessing(String workerId, boolean success, long processingTimeMs) {
        try {
            WorkerHealth workerHealth = workerHealthMap.get(workerId);
            if (workerHealth != null) {
                if (success) {
                    workerHealth.setProcessedJobs(workerHealth.getProcessedJobs() + 1);
                    totalProcessedJobs++;
                } else {
                    workerHealth.setFailedJobs(workerHealth.getFailedJobs() + 1);
                    totalFailedJobs++;
                }
                
                // Update average processing time
                updateAverageProcessingTime(processingTimeMs);
                
                // Store metrics in Redis
                recordWorkerMetrics(workerId, success, processingTimeMs);
                
            } else {
                log.warn("Job processing record for unregistered worker: {}", workerId);
            }
            
        } catch (Exception e) {
            log.error("Failed to record job processing for worker {}: {}", workerId, e.getMessage(), e);
        }
    }

    /**
     * Mark worker as inactive (e.g., during shutdown)
     *
     * @param workerId the worker ID
     */
    public void deactivateWorker(String workerId) {
        try {
            WorkerHealth workerHealth = workerHealthMap.get(workerId);
            if (workerHealth != null) {
                workerHealth.setStatus(WorkerStatus.INACTIVE);
                workerHealth.setEndTime(Instant.now());
                
                // Update in Redis with shorter TTL
                String heartbeatKey = WORKER_HEARTBEAT_PREFIX + workerId;
                redisTemplate.opsForValue().set(heartbeatKey, workerHealth, Duration.ofMinutes(1));
                
                log.info("Deactivated worker {}", workerId);
            }
            
        } catch (Exception e) {
            log.error("Failed to deactivate worker {}: {}", workerId, e.getMessage(), e);
        }
    }

    /**
     * Get comprehensive system health report
     *
     * @return system health data
     */
    public SystemHealthReport getSystemHealthReport() {
        try {
            // Update active worker count
            long activeWorkerCount = workerHealthMap.values().stream()
                    .filter(w -> w.getStatus() == WorkerStatus.ACTIVE)
                    .filter(w -> Duration.between(w.getLastHeartbeat(), Instant.now()).getSeconds() < 60)
                    .count();
            
            this.activeWorkers = (int) activeWorkerCount;
            
            // Get queue statistics
            Map<String, Object> audioQueueStats = queueStatsProvider.getQueueStats("audio_processing");
            Map<String, Object> textQueueStats = queueStatsProvider.getQueueStats("text_processing");
            
            // Calculate system health score (0-100)
            int healthScore = calculateSystemHealthScore();
            
            return SystemHealthReport.builder()
                    .timestamp(Instant.now())
                    .systemStatus(healthScore >= 80 ? "HEALTHY" : healthScore >= 60 ? "DEGRADED" : "UNHEALTHY")
                    .healthScore(healthScore)
                    .activeWorkers(activeWorkers)
                    .totalWorkersRegistered(workerHealthMap.size())
                    .totalProcessedJobs(totalProcessedJobs)
                    .totalFailedJobs(totalFailedJobs)
                    .averageProcessingTimeMs(averageProcessingTime)
                    .audioQueueSize(getLongFromMap(audioQueueStats, "size"))
                    .textQueueSize(getLongFromMap(textQueueStats, "size"))
                    .workerDetails(new ArrayList<>(workerHealthMap.values()))
                    .build();
                    
        } catch (Exception e) {
            log.error("Failed to generate system health report: {}", e.getMessage(), e);
            return SystemHealthReport.builder()
                    .timestamp(Instant.now())
                    .systemStatus("ERROR")
                    .healthScore(0)
                    .build();
        }
    }

    /**
     * Get health information for a specific worker
     *
     * @param workerId the worker ID
     * @return worker health data or empty if not found
     */
    public Optional<WorkerHealth> getWorkerHealth(String workerId) {
        return Optional.ofNullable(workerHealthMap.get(workerId));
    }

    /**
     * Get list of all active workers
     *
     * @return list of active worker health data
     */
    public List<WorkerHealth> getActiveWorkers() {
        return workerHealthMap.values().stream()
                .filter(w -> w.getStatus() == WorkerStatus.ACTIVE)
                .filter(w -> Duration.between(w.getLastHeartbeat(), Instant.now()).getSeconds() < 120)
                .collect(Collectors.toList());
    }

    /**
     * Periodic health check - runs every 30 seconds
     * Identifies stale workers and updates system health
     */
    @Scheduled(fixedDelay = 30000)
    public void performHealthCheck() {
        try {
            Instant now = Instant.now();
            lastHealthCheck = now;
            
            // Check for stale workers (no heartbeat for 2 minutes)
            workerHealthMap.entrySet().removeIf(entry -> {
                WorkerHealth worker = entry.getValue();
                if (worker.getStatus() == WorkerStatus.ACTIVE && 
                    Duration.between(worker.getLastHeartbeat(), now).getSeconds() > 120) {
                    
                    log.warn("Worker {} appears stale, removing from active list", entry.getKey());
                    return true;
                }
                return false;
            });
            
            // Log system health summary
            if (log.isDebugEnabled()) {
                SystemHealthReport report = getSystemHealthReport();
                log.debug("System Health: {} workers active, {} jobs processed, {} failed", 
                        report.getActiveWorkers(), report.getTotalProcessedJobs(), report.getTotalFailedJobs());
            }
            
        } catch (Exception e) {
            log.error("Error during health check: {}", e.getMessage(), e);
        }
    }

    // WorkerMetricsProvider interface implementation

    /**
     * Check if a specific worker is healthy
     *
     * @param workerId unique worker identifier  
     * @return true if worker is healthy and responsive
     */
    @Override
    public boolean isWorkerHealthy(String workerId) {
        WorkerHealth workerHealth = workerHealthMap.get(workerId);
        if (workerHealth == null || workerHealth.getStatus() != WorkerStatus.ACTIVE) {
            return false;
        }
        
        // Check if heartbeat is recent (within last 2 minutes)
        return Duration.between(workerHealth.getLastHeartbeat(), Instant.now()).getSeconds() < 120;
    }

    /**
     * Get the count of currently active workers
     *
     * @return number of active workers
     */
    @Override
    public int getActiveWorkerCount() {
        return activeWorkers;
    }

    // Private helper methods

    private void recordWorkerMetrics(String workerId, boolean success, long processingTimeMs) {
        try {
            String metricsKey = WORKER_METRICS_PREFIX + workerId;
            Map<String, Object> metrics = new HashMap<>();
            metrics.put("timestamp", Instant.now().toString());
            metrics.put("success", success);
            metrics.put("processingTimeMs", processingTimeMs);
            
            // Store as a list in Redis (keep last 100 entries)
            redisTemplate.opsForList().rightPush(metricsKey, metrics);
            redisTemplate.opsForList().trim(metricsKey, -100, -1);
            redisTemplate.expire(metricsKey, Duration.ofHours(24));
            
        } catch (Exception e) {
            log.debug("Failed to record worker metrics for {}: {}", workerId, e.getMessage());
        }
    }

    private void updateAverageProcessingTime(long processingTimeMs) {
        // Simple moving average calculation
        long totalJobs = totalProcessedJobs + totalFailedJobs;
        if (totalJobs > 0) {
            averageProcessingTime = ((averageProcessingTime * (totalJobs - 1)) + processingTimeMs) / totalJobs;
        }
    }

    private int calculateSystemHealthScore() {
        try {
            int score = 100;
            
            // Deduct points for inactive workers
            if (activeWorkers == 0) {
                score -= 50; // No active workers is critical
            }
            
            // Deduct points for high failure rate
            long totalJobs = totalProcessedJobs + totalFailedJobs;
            if (totalJobs > 0) {
                double failureRate = (double) totalFailedJobs / totalJobs;
                if (failureRate > 0.1) { // More than 10% failure rate
                    score -= (int) (failureRate * 100);
                }
            }
            
            // Deduct points for slow processing
            if (averageProcessingTime > 30000) { // More than 30 seconds average
                score -= 20;
            }
            
            return Math.max(0, score);
            
        } catch (Exception e) {
            log.error("Error calculating health score: {}", e.getMessage());
            return 50; // Default moderate health
        }
    }

    private Long getLongFromMap(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return 0L;
    }

    // Inner classes for health data structures

    public static class WorkerHealth {
        private String workerId;
        private String workerType;
        private WorkerStatus status;
        private Instant startTime;
        private Instant endTime;
        private Instant lastHeartbeat;
        private long processedJobs;
        private long failedJobs;

        // Constructors
        public WorkerHealth() {}

        private WorkerHealth(Builder builder) {
            this.workerId = builder.workerId;
            this.workerType = builder.workerType;
            this.status = builder.status;
            this.startTime = builder.startTime;
            this.endTime = builder.endTime;
            this.lastHeartbeat = builder.lastHeartbeat;
            this.processedJobs = builder.processedJobs;
            this.failedJobs = builder.failedJobs;
        }

        public static Builder builder() {
            return new Builder();
        }

        // Getters and Setters
        public String getWorkerId() { return workerId; }
        public void setWorkerId(String workerId) { this.workerId = workerId; }

        public String getWorkerType() { return workerType; }
        public void setWorkerType(String workerType) { this.workerType = workerType; }

        public WorkerStatus getStatus() { return status; }
        public void setStatus(WorkerStatus status) { this.status = status; }

        public Instant getStartTime() { return startTime; }
        public void setStartTime(Instant startTime) { this.startTime = startTime; }

        public Instant getEndTime() { return endTime; }
        public void setEndTime(Instant endTime) { this.endTime = endTime; }

        public Instant getLastHeartbeat() { return lastHeartbeat; }
        public void setLastHeartbeat(Instant lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }

        public long getProcessedJobs() { return processedJobs; }
        public void setProcessedJobs(long processedJobs) { this.processedJobs = processedJobs; }

        public long getFailedJobs() { return failedJobs; }
        public void setFailedJobs(long failedJobs) { this.failedJobs = failedJobs; }

        public static class Builder {
            private String workerId;
            private String workerType;
            private WorkerStatus status;
            private Instant startTime;
            private Instant endTime;
            private Instant lastHeartbeat;
            private long processedJobs;
            private long failedJobs;

            public Builder workerId(String workerId) { this.workerId = workerId; return this; }
            public Builder workerType(String workerType) { this.workerType = workerType; return this; }
            public Builder status(WorkerStatus status) { this.status = status; return this; }
            public Builder startTime(Instant startTime) { this.startTime = startTime; return this; }
            public Builder endTime(Instant endTime) { this.endTime = endTime; return this; }
            public Builder lastHeartbeat(Instant lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; return this; }
            public Builder processedJobs(long processedJobs) { this.processedJobs = processedJobs; return this; }
            public Builder failedJobs(long failedJobs) { this.failedJobs = failedJobs; return this; }

            public WorkerHealth build() {
                return new WorkerHealth(this);
            }
        }
    }

    public static class SystemHealthReport {
        private Instant timestamp;
        private String systemStatus;
        private int healthScore;
        private int activeWorkers;
        private int totalWorkersRegistered;
        private long totalProcessedJobs;
        private long totalFailedJobs;
        private double averageProcessingTimeMs;
        private Long audioQueueSize;
        private Long textQueueSize;
        private List<WorkerHealth> workerDetails;

        // Constructors
        public SystemHealthReport() {}

        private SystemHealthReport(Builder builder) {
            this.timestamp = builder.timestamp;
            this.systemStatus = builder.systemStatus;
            this.healthScore = builder.healthScore;
            this.activeWorkers = builder.activeWorkers;
            this.totalWorkersRegistered = builder.totalWorkersRegistered;
            this.totalProcessedJobs = builder.totalProcessedJobs;
            this.totalFailedJobs = builder.totalFailedJobs;
            this.averageProcessingTimeMs = builder.averageProcessingTimeMs;
            this.audioQueueSize = builder.audioQueueSize;
            this.textQueueSize = builder.textQueueSize;
            this.workerDetails = builder.workerDetails;
        }

        public static Builder builder() {
            return new Builder();
        }

        // Getters
        public Instant getTimestamp() { return timestamp; }
        public String getSystemStatus() { return systemStatus; }
        public int getHealthScore() { return healthScore; }
        public int getActiveWorkers() { return activeWorkers; }
        public int getTotalWorkersRegistered() { return totalWorkersRegistered; }
        public long getTotalProcessedJobs() { return totalProcessedJobs; }
        public long getTotalFailedJobs() { return totalFailedJobs; }
        public double getAverageProcessingTimeMs() { return averageProcessingTimeMs; }
        public Long getAudioQueueSize() { return audioQueueSize; }
        public Long getTextQueueSize() { return textQueueSize; }
        public List<WorkerHealth> getWorkerDetails() { return workerDetails; }

        public static class Builder {
            private Instant timestamp;
            private String systemStatus;
            private int healthScore;
            private int activeWorkers;
            private int totalWorkersRegistered;
            private long totalProcessedJobs;
            private long totalFailedJobs;
            private double averageProcessingTimeMs;
            private Long audioQueueSize;
            private Long textQueueSize;
            private List<WorkerHealth> workerDetails;

            public Builder timestamp(Instant timestamp) { this.timestamp = timestamp; return this; }
            public Builder systemStatus(String systemStatus) { this.systemStatus = systemStatus; return this; }
            public Builder healthScore(int healthScore) { this.healthScore = healthScore; return this; }
            public Builder activeWorkers(int activeWorkers) { this.activeWorkers = activeWorkers; return this; }
            public Builder totalWorkersRegistered(int totalWorkersRegistered) { this.totalWorkersRegistered = totalWorkersRegistered; return this; }
            public Builder totalProcessedJobs(long totalProcessedJobs) { this.totalProcessedJobs = totalProcessedJobs; return this; }
            public Builder totalFailedJobs(long totalFailedJobs) { this.totalFailedJobs = totalFailedJobs; return this; }
            public Builder averageProcessingTimeMs(double averageProcessingTimeMs) { this.averageProcessingTimeMs = averageProcessingTimeMs; return this; }
            public Builder audioQueueSize(Long audioQueueSize) { this.audioQueueSize = audioQueueSize; return this; }
            public Builder textQueueSize(Long textQueueSize) { this.textQueueSize = textQueueSize; return this; }
            public Builder workerDetails(List<WorkerHealth> workerDetails) { this.workerDetails = workerDetails; return this; }

            public SystemHealthReport build() {
                return new SystemHealthReport(this);
            }
        }
    }

    public enum WorkerStatus {
        ACTIVE, INACTIVE, ERROR, SHUTDOWN
    }
}
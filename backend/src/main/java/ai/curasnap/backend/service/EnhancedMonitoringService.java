package ai.curasnap.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Enhanced Monitoring and Alerting Service for comprehensive system observability
 * 
 * Features:
 * - Real-time metrics collection and aggregation
 * - Threshold-based alerting with multiple severity levels
 * - Performance trend analysis
 * - System health scoring
 * - Alert suppression and escalation
 * - Metrics export for external monitoring systems
 * - Custom business metrics tracking
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.monitoring.enhanced.enabled", havingValue = "true", matchIfMissing = true)
public class EnhancedMonitoringService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final WorkerHealthService workerHealthService;
    private final CircuitBreakerService circuitBreakerService;
    private final ServiceDegradationService serviceDegradationService;

    // Metrics storage
    private final Map<String, MetricTimeSeries> metrics = new ConcurrentHashMap<>();
    private final Map<String, AlertRule> alertRules = new ConcurrentHashMap<>();
    private final Map<String, Alert> activeAlerts = new ConcurrentHashMap<>();
    
    // Performance counters
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    
    // Redis keys
    private static final String METRICS_KEY_PREFIX = "metrics:";
    private static final String ALERTS_KEY = "active_alerts";
    private static final String HEALTH_SCORE_KEY = "system_health_score";

    @Autowired
    public EnhancedMonitoringService(RedisTemplate<String, Object> redisTemplate,
                                   WorkerHealthService workerHealthService,
                                   CircuitBreakerService circuitBreakerService,
                                   ServiceDegradationService serviceDegradationService) {
        this.redisTemplate = redisTemplate;
        this.workerHealthService = workerHealthService;
        this.circuitBreakerService = circuitBreakerService;
        this.serviceDegradationService = serviceDegradationService;
        
        initializeMetrics();
        initializeAlertRules();
        
        log.info("EnhancedMonitoringService initialized with comprehensive observability");
    }

    /**
     * Alert severity levels
     */
    public enum AlertSeverity {
        INFO(1, "Informational"),
        WARNING(2, "Warning - attention needed"), 
        ERROR(3, "Error - action required"),
        CRITICAL(4, "Critical - immediate action required");

        private final int level;
        private final String description;

        AlertSeverity(int level, String description) {
            this.level = level;
            this.description = description;
        }

        public int getLevel() { return level; }
        public String getDescription() { return description; }
    }

    /**
     * Metric data point
     */
    public static class MetricDataPoint {
        private final Instant timestamp;
        private final double value;
        private final Map<String, String> tags;

        public MetricDataPoint(double value, Map<String, String> tags) {
            this.timestamp = Instant.now();
            this.value = value;
            this.tags = tags != null ? new HashMap<>(tags) : new HashMap<>();
        }

        public Instant getTimestamp() { return timestamp; }
        public double getValue() { return value; }
        public Map<String, String> getTags() { return tags; }
    }

    /**
     * Time series data for a metric
     */
    public static class MetricTimeSeries {
        private final String name;
        private final String description;
        private final String unit;
        private final LinkedList<MetricDataPoint> dataPoints;
        private final int maxDataPoints;
        
        public MetricTimeSeries(String name, String description, String unit, int maxDataPoints) {
            this.name = name;
            this.description = description;
            this.unit = unit;
            this.maxDataPoints = maxDataPoints;
            this.dataPoints = new LinkedList<>();
        }

        public synchronized void addDataPoint(MetricDataPoint dataPoint) {
            dataPoints.addFirst(dataPoint);
            while (dataPoints.size() > maxDataPoints) {
                dataPoints.removeLast();
            }
        }

        public synchronized List<MetricDataPoint> getRecentDataPoints(int count) {
            return dataPoints.stream().limit(count).collect(Collectors.toList());
        }

        public synchronized double getLatestValue() {
            return dataPoints.isEmpty() ? 0.0 : dataPoints.getFirst().getValue();
        }

        public synchronized double getAverage(Duration period) {
            Instant cutoff = Instant.now().minus(period);
            return dataPoints.stream()
                    .filter(dp -> dp.getTimestamp().isAfter(cutoff))
                    .mapToDouble(MetricDataPoint::getValue)
                    .average()
                    .orElse(0.0);
        }

        // Getters
        public String getName() { return name; }
        public String getDescription() { return description; }
        public String getUnit() { return unit; }
        public List<MetricDataPoint> getDataPoints() { return new ArrayList<>(dataPoints); }
    }

    /**
     * Alert rule definition
     */
    public static class AlertRule {
        private final String name;
        private final String metricName;
        private final String condition; // e.g., ">", "<", ">=", "<=", "=="
        private final double threshold;
        private final Duration evaluationPeriod;
        private final AlertSeverity severity;
        private final String description;
        private final boolean enabled;
        private final Duration suppressionPeriod;

        public AlertRule(String name, String metricName, String condition, double threshold,
                        Duration evaluationPeriod, AlertSeverity severity, String description,
                        boolean enabled, Duration suppressionPeriod) {
            this.name = name;
            this.metricName = metricName;
            this.condition = condition;
            this.threshold = threshold;
            this.evaluationPeriod = evaluationPeriod;
            this.severity = severity;
            this.description = description;
            this.enabled = enabled;
            this.suppressionPeriod = suppressionPeriod;
        }

        // Getters
        public String getName() { return name; }
        public String getMetricName() { return metricName; }
        public String getCondition() { return condition; }
        public double getThreshold() { return threshold; }
        public Duration getEvaluationPeriod() { return evaluationPeriod; }
        public AlertSeverity getSeverity() { return severity; }
        public String getDescription() { return description; }
        public boolean isEnabled() { return enabled; }
        public Duration getSuppressionPeriod() { return suppressionPeriod; }
    }

    /**
     * Active alert
     */
    public static class Alert {
        private final String id;
        private final String ruleName;
        private final String metricName;
        private final double actualValue;
        private final double threshold;
        private final AlertSeverity severity;
        private final String description;
        private final Instant triggeredAt;
        private Instant lastTriggeredAt;
        private int triggerCount;
        private boolean acknowledged;
        private String acknowledgedBy;
        private Instant acknowledgedAt;

        public Alert(String ruleName, String metricName, double actualValue, double threshold,
                    AlertSeverity severity, String description) {
            this.id = UUID.randomUUID().toString();
            this.ruleName = ruleName;
            this.metricName = metricName;
            this.actualValue = actualValue;
            this.threshold = threshold;
            this.severity = severity;
            this.description = description;
            this.triggeredAt = Instant.now();
            this.lastTriggeredAt = this.triggeredAt;
            this.triggerCount = 1;
            this.acknowledged = false;
        }

        public void trigger() {
            this.lastTriggeredAt = Instant.now();
            this.triggerCount++;
        }

        public void acknowledge(String acknowledgedBy) {
            this.acknowledged = true;
            this.acknowledgedBy = acknowledgedBy;
            this.acknowledgedAt = Instant.now();
        }

        // Getters
        public String getId() { return id; }
        public String getRuleName() { return ruleName; }
        public String getMetricName() { return metricName; }
        public double getActualValue() { return actualValue; }
        public double getThreshold() { return threshold; }
        public AlertSeverity getSeverity() { return severity; }
        public String getDescription() { return description; }
        public Instant getTriggeredAt() { return triggeredAt; }
        public Instant getLastTriggeredAt() { return lastTriggeredAt; }
        public int getTriggerCount() { return triggerCount; }
        public boolean isAcknowledged() { return acknowledged; }
        public String getAcknowledgedBy() { return acknowledgedBy; }
        public Instant getAcknowledgedAt() { return acknowledgedAt; }
    }

    /**
     * Initialize core metrics
     */
    private void initializeMetrics() {
        // System metrics
        metrics.put("system.cpu.usage", new MetricTimeSeries(
            "system.cpu.usage", "CPU usage percentage", "percent", 1000));
        metrics.put("system.memory.usage", new MetricTimeSeries(
            "system.memory.usage", "Memory usage percentage", "percent", 1000));
        metrics.put("system.health.score", new MetricTimeSeries(
            "system.health.score", "Overall system health score", "score", 1000));

        // Job processing metrics
        metrics.put("jobs.processed.total", new MetricTimeSeries(
            "jobs.processed.total", "Total jobs processed", "count", 1000));
        metrics.put("jobs.failed.total", new MetricTimeSeries(
            "jobs.failed.total", "Total jobs failed", "count", 1000));
        metrics.put("jobs.processing.time", new MetricTimeSeries(
            "jobs.processing.time", "Job processing time", "milliseconds", 1000));
        metrics.put("jobs.queue.size", new MetricTimeSeries(
            "jobs.queue.size", "Current queue size", "count", 1000));

        // Service metrics
        metrics.put("service.transcription.availability", new MetricTimeSeries(
            "service.transcription.availability", "Transcription service availability", "percent", 1000));
        metrics.put("service.agent.availability", new MetricTimeSeries(
            "service.agent.availability", "Agent service availability", "percent", 1000));
        metrics.put("service.redis.availability", new MetricTimeSeries(
            "service.redis.availability", "Redis service availability", "percent", 1000));

        // Circuit breaker metrics
        metrics.put("circuit_breaker.failures", new MetricTimeSeries(
            "circuit_breaker.failures", "Circuit breaker failures", "count", 1000));
        metrics.put("circuit_breaker.success_rate", new MetricTimeSeries(
            "circuit_breaker.success_rate", "Circuit breaker success rate", "percent", 1000));

        // Initialize counters
        counters.put("requests.total", new AtomicLong(0));
        counters.put("requests.successful", new AtomicLong(0));
        counters.put("requests.failed", new AtomicLong(0));
        counters.put("alerts.triggered", new AtomicLong(0));
    }

    /**
     * Initialize alert rules
     */
    private void initializeAlertRules() {
        // System health alerts
        alertRules.put("system_health_low", new AlertRule(
            "system_health_low", "system.health.score", "<", 70.0,
            Duration.ofMinutes(5), AlertSeverity.WARNING,
            "System health score is below acceptable threshold",
            true, Duration.ofMinutes(10)
        ));

        alertRules.put("system_health_critical", new AlertRule(
            "system_health_critical", "system.health.score", "<", 50.0,
            Duration.ofMinutes(2), AlertSeverity.CRITICAL,
            "System health score is critically low",
            true, Duration.ofMinutes(5)
        ));

        // Job processing alerts
        alertRules.put("job_failure_rate_high", new AlertRule(
            "job_failure_rate_high", "jobs.failure.rate", ">", 20.0,
            Duration.ofMinutes(10), AlertSeverity.ERROR,
            "Job failure rate is above 20%",
            true, Duration.ofMinutes(15)
        ));

        alertRules.put("queue_size_large", new AlertRule(
            "queue_size_large", "jobs.queue.size", ">", 100.0,
            Duration.ofMinutes(5), AlertSeverity.WARNING,
            "Job queue size is large - potential backlog",
            true, Duration.ofMinutes(20)
        ));

        // Service availability alerts
        alertRules.put("transcription_service_down", new AlertRule(
            "transcription_service_down", "service.transcription.availability", "<", 50.0,
            Duration.ofMinutes(3), AlertSeverity.CRITICAL,
            "Transcription service availability is critically low",
            true, Duration.ofMinutes(10)
        ));

        alertRules.put("agent_service_down", new AlertRule(
            "agent_service_down", "service.agent.availability", "<", 50.0,
            Duration.ofMinutes(3), AlertSeverity.CRITICAL,
            "Agent service availability is critically low",
            true, Duration.ofMinutes(10)
        ));
    }

    /**
     * Record a metric value
     */
    public void recordMetric(String metricName, double value, Map<String, String> tags) {
        try {
            MetricTimeSeries timeSeries = metrics.get(metricName);
            if (timeSeries != null) {
                MetricDataPoint dataPoint = new MetricDataPoint(value, tags);
                timeSeries.addDataPoint(dataPoint);
                
                // Store in Redis for persistence and external access
                storeMetricInRedis(metricName, dataPoint);
                
                log.debug("Recorded metric {}: {} {}", metricName, value, 
                         timeSeries.getUnit());
            }
        } catch (Exception e) {
            log.error("Failed to record metric {}: {}", metricName, e.getMessage());
        }
    }

    /**
     * Record a metric value without tags
     */
    public void recordMetric(String metricName, double value) {
        recordMetric(metricName, value, null);
    }

    /**
     * Increment a counter
     */
    public void incrementCounter(String counterName) {
        counters.computeIfAbsent(counterName, k -> new AtomicLong(0)).incrementAndGet();
    }

    /**
     * Get counter value
     */
    public long getCounterValue(String counterName) {
        AtomicLong counter = counters.get(counterName);
        return counter != null ? counter.get() : 0;
    }

    /**
     * Scheduled metrics collection
     */
    @Scheduled(fixedDelay = 30000) // Every 30 seconds
    public void collectMetrics() {
        try {
            collectSystemMetrics();
            collectJobMetrics();
            collectServiceMetrics();
            calculateDerivedMetrics();
        } catch (Exception e) {
            log.error("Failed to collect metrics: {}", e.getMessage(), e);
        }
    }

    /**
     * Collect system-level metrics
     */
    private void collectSystemMetrics() {
        try {
            // Get system health score
            WorkerHealthService.SystemHealthReport healthReport = 
                workerHealthService.getSystemHealthReport();
            recordMetric("system.health.score", healthReport.getHealthScore());
            
            // Memory usage (simplified)
            Runtime runtime = Runtime.getRuntime();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            double memoryUsage = ((totalMemory - freeMemory) / (double) totalMemory) * 100;
            recordMetric("system.memory.usage", memoryUsage);
            
        } catch (Exception e) {
            log.error("Failed to collect system metrics: {}", e.getMessage());
        }
    }

    /**
     * Collect job processing metrics
     */
    private void collectJobMetrics() {
        try {
            WorkerHealthService.SystemHealthReport healthReport = 
                workerHealthService.getSystemHealthReport();
            
            recordMetric("jobs.processed.total", healthReport.getTotalProcessedJobs());
            recordMetric("jobs.failed.total", healthReport.getTotalFailedJobs());
            recordMetric("jobs.processing.time", healthReport.getAverageProcessingTimeMs());
            
            // Queue sizes
            Long audioQueueSize = healthReport.getAudioQueueSize();
            Long textQueueSize = healthReport.getTextQueueSize();
            if (audioQueueSize != null && textQueueSize != null) {
                recordMetric("jobs.queue.size", audioQueueSize + textQueueSize);
            }
            
            // Calculate job failure rate
            long totalJobs = healthReport.getTotalProcessedJobs() + healthReport.getTotalFailedJobs();
            if (totalJobs > 0) {
                double failureRate = (healthReport.getTotalFailedJobs() / (double) totalJobs) * 100;
                recordMetric("jobs.failure.rate", failureRate);
            }
            
        } catch (Exception e) {
            log.error("Failed to collect job metrics: {}", e.getMessage());
        }
    }

    /**
     * Collect service availability metrics
     */
    private void collectServiceMetrics() {
        try {
            // Check service degradation states
            Map<String, ServiceDegradationService.ServiceDegradationState> serviceStates = 
                serviceDegradationService.getAllServiceStates();
            
            for (Map.Entry<String, ServiceDegradationService.ServiceDegradationState> entry : serviceStates.entrySet()) {
                String serviceName = entry.getKey();
                ServiceDegradationService.ServiceDegradationState state = entry.getValue();
                
                double availability = state.isHealthy() ? 100.0 : 0.0;
                
                // Map service names to metric names
                String metricName = "service." + serviceName.replace("-", "_") + ".availability";
                recordMetric(metricName, availability);
            }
            
        } catch (Exception e) {
            log.error("Failed to collect service metrics: {}", e.getMessage());
        }
    }

    /**
     * Calculate derived metrics
     */
    private void calculateDerivedMetrics() {
        // Calculate request success rate
        long totalRequests = getCounterValue("requests.total");
        long successfulRequests = getCounterValue("requests.successful");
        
        if (totalRequests > 0) {
            double successRate = (successfulRequests / (double) totalRequests) * 100;
            recordMetric("requests.success.rate", successRate);
        }
    }

    /**
     * Scheduled alert evaluation
     */
    @Scheduled(fixedDelay = 60000) // Every minute
    public void evaluateAlerts() {
        try {
            for (AlertRule rule : alertRules.values()) {
                if (rule.isEnabled()) {
                    evaluateAlertRule(rule);
                }
            }
            
            // Clean up resolved alerts
            cleanupResolvedAlerts();
            
        } catch (Exception e) {
            log.error("Failed to evaluate alerts: {}", e.getMessage(), e);
        }
    }

    /**
     * Evaluate a single alert rule
     */
    private void evaluateAlertRule(AlertRule rule) {
        try {
            MetricTimeSeries timeSeries = metrics.get(rule.getMetricName());
            if (timeSeries == null) {
                return;
            }
            
            // Get average value over evaluation period
            double currentValue = timeSeries.getAverage(rule.getEvaluationPeriod());
            
            // Check if condition is met
            boolean conditionMet = evaluateCondition(currentValue, rule.getCondition(), rule.getThreshold());
            
            String alertKey = rule.getName();
            Alert existingAlert = activeAlerts.get(alertKey);
            
            if (conditionMet) {
                if (existingAlert == null) {
                    // Create new alert
                    Alert newAlert = new Alert(
                        rule.getName(), rule.getMetricName(), currentValue, rule.getThreshold(),
                        rule.getSeverity(), rule.getDescription()
                    );
                    
                    activeAlerts.put(alertKey, newAlert);
                    triggerAlert(newAlert);
                    incrementCounter("alerts.triggered");
                    
                } else if (!existingAlert.isAcknowledged()) {
                    // Update existing alert
                    existingAlert.trigger();
                    
                    // Check if we should suppress repeated notifications
                    Duration timeSinceLastTrigger = Duration.between(
                        existingAlert.getLastTriggeredAt(), Instant.now());
                    
                    if (timeSinceLastTrigger.compareTo(rule.getSuppressionPeriod()) > 0) {
                        triggerAlert(existingAlert);
                    }
                }
            } else if (existingAlert != null) {
                // Condition no longer met - resolve alert
                resolveAlert(existingAlert);
                activeAlerts.remove(alertKey);
            }
            
        } catch (Exception e) {
            log.error("Failed to evaluate alert rule {}: {}", rule.getName(), e.getMessage());
        }
    }

    /**
     * Evaluate condition
     */
    private boolean evaluateCondition(double value, String condition, double threshold) {
        switch (condition) {
            case ">":
                return value > threshold;
            case ">=":
                return value >= threshold;
            case "<":
                return value < threshold;
            case "<=":
                return value <= threshold;
            case "==":
                return Math.abs(value - threshold) < 0.001; // Account for floating point precision
            default:
                log.warn("Unknown condition: {}", condition);
                return false;
        }
    }

    /**
     * Trigger an alert
     */
    private void triggerAlert(Alert alert) {
        try {
            log.warn("ALERT TRIGGERED: {} - {} ({}={}, threshold={})", 
                    alert.getSeverity(), alert.getDescription(), 
                    alert.getMetricName(), alert.getActualValue(), alert.getThreshold());
            
            // Store alert in Redis
            storeAlertInRedis(alert);
            
            // Here you could integrate with external alerting systems
            // sendToSlack(alert);
            // sendToEmail(alert);
            // sendToWebhook(alert);
            
        } catch (Exception e) {
            log.error("Failed to trigger alert {}: {}", alert.getId(), e.getMessage());
        }
    }

    /**
     * Resolve an alert
     */
    private void resolveAlert(Alert alert) {
        log.info("ALERT RESOLVED: {} - {}", alert.getSeverity(), alert.getDescription());
        removeAlertFromRedis(alert);
    }

    /**
     * Clean up resolved alerts
     */
    private void cleanupResolvedAlerts() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        activeAlerts.entrySet().removeIf(entry -> 
            entry.getValue().getTriggeredAt().isBefore(cutoff)
        );
    }

    /**
     * Store metric in Redis
     */
    private void storeMetricInRedis(String metricName, MetricDataPoint dataPoint) {
        try {
            String key = METRICS_KEY_PREFIX + metricName;
            Map<String, Object> data = new HashMap<>();
            data.put("value", dataPoint.getValue());
            data.put("timestamp", dataPoint.getTimestamp().toString());
            data.put("tags", dataPoint.getTags());
            
            redisTemplate.opsForList().leftPush(key, data);
            redisTemplate.opsForList().trim(key, 0, 999); // Keep last 1000 points
            redisTemplate.expire(key, Duration.ofDays(7));
            
        } catch (Exception e) {
            log.debug("Failed to store metric in Redis: {}", e.getMessage());
        }
    }

    /**
     * Store alert in Redis
     */
    private void storeAlertInRedis(Alert alert) {
        try {
            Map<String, Object> alertData = new HashMap<>();
            alertData.put("id", alert.getId());
            alertData.put("ruleName", alert.getRuleName());
            alertData.put("severity", alert.getSeverity().name());
            alertData.put("description", alert.getDescription());
            alertData.put("metricName", alert.getMetricName());
            alertData.put("actualValue", alert.getActualValue());
            alertData.put("threshold", alert.getThreshold());
            alertData.put("triggeredAt", alert.getTriggeredAt().toString());
            alertData.put("triggerCount", alert.getTriggerCount());
            
            redisTemplate.opsForHash().put(ALERTS_KEY, alert.getId(), alertData);
            redisTemplate.expire(ALERTS_KEY, Duration.ofDays(7));
            
        } catch (Exception e) {
            log.error("Failed to store alert in Redis: {}", e.getMessage());
        }
    }

    /**
     * Remove alert from Redis
     */
    private void removeAlertFromRedis(Alert alert) {
        try {
            redisTemplate.opsForHash().delete(ALERTS_KEY, alert.getId());
        } catch (Exception e) {
            log.debug("Failed to remove alert from Redis: {}", e.getMessage());
        }
    }

    // Public API methods

    public MetricTimeSeries getMetric(String metricName) {
        return metrics.get(metricName);
    }

    public Map<String, MetricTimeSeries> getAllMetrics() {
        return Collections.unmodifiableMap(metrics);
    }

    public Collection<Alert> getActiveAlerts() {
        return Collections.unmodifiableCollection(activeAlerts.values());
    }

    public List<Alert> getActiveAlertsBySeverity(AlertSeverity severity) {
        return activeAlerts.values().stream()
                .filter(alert -> alert.getSeverity() == severity)
                .collect(Collectors.toList());
    }

    public void acknowledgeAlert(String alertId, String acknowledgedBy) {
        Alert alert = activeAlerts.values().stream()
                .filter(a -> a.getId().equals(alertId))
                .findFirst()
                .orElse(null);
        
        if (alert != null) {
            alert.acknowledge(acknowledgedBy);
            log.info("Alert {} acknowledged by {}", alertId, acknowledgedBy);
        }
    }

    /**
     * Get system health summary
     */
    public Map<String, Object> getHealthSummary() {
        Map<String, Object> summary = new HashMap<>();
        
        // Overall health score
        MetricTimeSeries healthScore = metrics.get("system.health.score");
        if (healthScore != null) {
            summary.put("healthScore", healthScore.getLatestValue());
        }
        
        // Active alerts by severity
        Map<AlertSeverity, Long> alertCounts = activeAlerts.values().stream()
                .collect(Collectors.groupingBy(Alert::getSeverity, Collectors.counting()));
        summary.put("alertCounts", alertCounts);
        
        // Service availability
        Map<String, Object> serviceAvailability = new HashMap<>();
        metrics.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("service.") && entry.getKey().endsWith(".availability"))
                .forEach(entry -> {
                    String serviceName = entry.getKey().replace("service.", "").replace(".availability", "");
                    serviceAvailability.put(serviceName, entry.getValue().getLatestValue());
                });
        summary.put("serviceAvailability", serviceAvailability);
        
        // Recent metrics
        summary.put("timestamp", Instant.now().toString());
        
        return summary;
    }
}
package ai.curasnap.backend.controller;

import ai.curasnap.backend.service.EnhancedMonitoringService;
import ai.curasnap.backend.service.ServiceDegradationService;
import ai.curasnap.backend.service.WorkerHealthService;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST controller for monitoring and observability endpoints
 * Provides access to system metrics, alerts, and health status
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/monitoring")
public class MonitoringController {

    private final EnhancedMonitoringService monitoringService;
    private final ServiceDegradationService degradationService;
    private final WorkerHealthService workerHealthService;

    @Autowired
    public MonitoringController(EnhancedMonitoringService monitoringService,
                               ServiceDegradationService degradationService,
                               WorkerHealthService workerHealthService) {
        this.monitoringService = monitoringService;
        this.degradationService = degradationService;
        this.workerHealthService = workerHealthService;
    }

    /**
     * Get system health dashboard data
     */
    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> getDashboard(@AuthenticationPrincipal Jwt jwt) {
        try {
            String userId = (jwt != null) ? jwt.getSubject() : "anonymous";
            log.debug("Dashboard request from user: {}", userId);

            Map<String, Object> dashboard = new HashMap<>();

            // System health summary
            dashboard.put("healthSummary", monitoringService.getHealthSummary());

            // Degradation status
            dashboard.put("degradationLevel", degradationService.getCurrentDegradationLevel());
            dashboard.put("isDegraded", degradationService.isSystemDegraded());
            if (degradationService.isSystemDegraded()) {
                dashboard.put("degradationMessage", degradationService.getDegradationMessage());
            }

            // Recent alerts (last 24 hours)
            List<EnhancedMonitoringService.Alert> recentAlerts = monitoringService.getActiveAlerts()
                    .stream()
                    .sorted((a, b) -> b.getTriggeredAt().compareTo(a.getTriggeredAt()))
                    .limit(10)
                    .collect(Collectors.toList());
            dashboard.put("recentAlerts", formatAlertsForResponse(recentAlerts));

            // Key metrics
            Map<String, Object> keyMetrics = new HashMap<>();
            addMetricToMap(keyMetrics, "healthScore", "system.health.score");
            addMetricToMap(keyMetrics, "jobsProcessed", "jobs.processed.total");
            addMetricToMap(keyMetrics, "jobsFailed", "jobs.failed.total");
            addMetricToMap(keyMetrics, "queueSize", "jobs.queue.size");
            dashboard.put("keyMetrics", keyMetrics);

            // Service status
            Map<String, Object> serviceStatus = new HashMap<>();
            for (Map.Entry<String, ServiceDegradationService.ServiceDegradationState> entry : 
                 degradationService.getAllServiceStates().entrySet()) {
                Map<String, Object> status = new HashMap<>();
                status.put("healthy", entry.getValue().isHealthy());
                status.put("degraded", entry.getValue().isDegraded());
                status.put("level", entry.getValue().getDegradationLevel().name());
                serviceStatus.put(entry.getKey(), status);
            }
            dashboard.put("serviceStatus", serviceStatus);

            return ResponseEntity.ok(dashboard);

        } catch (Exception e) {
            log.error("Failed to get dashboard data: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get specific metric data
     */
    @GetMapping("/metrics/{metricName}")
    public ResponseEntity<Map<String, Object>> getMetric(
            @PathVariable String metricName,
            @RequestParam(value = "period", defaultValue = "1h") String period,
            @RequestParam(value = "points", defaultValue = "100") Integer maxPoints,
            @AuthenticationPrincipal Jwt jwt) {
        try {
            EnhancedMonitoringService.MetricTimeSeries metric = monitoringService.getMetric(metricName);
            if (metric == null) {
                return ResponseEntity.notFound().build();
            }

            Map<String, Object> response = new HashMap<>();
            response.put("name", metric.getName());
            response.put("description", metric.getDescription());
            response.put("unit", metric.getUnit());
            response.put("latestValue", metric.getLatestValue());

            // Get data points for specified period
            Duration periodDuration = parsePeriod(period);
            List<EnhancedMonitoringService.MetricDataPoint> dataPoints = 
                metric.getRecentDataPoints(maxPoints);
            
            // Format data points for response
            List<Map<String, Object>> formattedPoints = dataPoints.stream()
                    .map(this::formatDataPointForResponse)
                    .collect(Collectors.toList());
            response.put("dataPoints", formattedPoints);

            // Calculate statistics for the period
            double average = metric.getAverage(periodDuration);
            response.put("average", average);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to get metric {}: {}", metricName, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get all available metrics
     */
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getAllMetrics(@AuthenticationPrincipal Jwt jwt) {
        try {
            Map<String, Object> response = new HashMap<>();
            Map<String, Object> metrics = new HashMap<>();

            for (Map.Entry<String, EnhancedMonitoringService.MetricTimeSeries> entry : 
                 monitoringService.getAllMetrics().entrySet()) {
                Map<String, Object> metricInfo = new HashMap<>();
                metricInfo.put("name", entry.getValue().getName());
                metricInfo.put("description", entry.getValue().getDescription());
                metricInfo.put("unit", entry.getValue().getUnit());
                metricInfo.put("latestValue", entry.getValue().getLatestValue());
                metrics.put(entry.getKey(), metricInfo);
            }

            response.put("metrics", metrics);
            response.put("timestamp", java.time.Instant.now().toString());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to get all metrics: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get active alerts
     */
    @GetMapping("/alerts")
    public ResponseEntity<Map<String, Object>> getActiveAlerts(
            @RequestParam(value = "severity", required = false) String severityFilter,
            @AuthenticationPrincipal Jwt jwt) {
        try {
            Collection<EnhancedMonitoringService.Alert> alerts;

            if (severityFilter != null) {
                EnhancedMonitoringService.AlertSeverity severity = 
                    EnhancedMonitoringService.AlertSeverity.valueOf(severityFilter.toUpperCase());
                alerts = monitoringService.getActiveAlertsBySeverity(severity);
            } else {
                alerts = monitoringService.getActiveAlerts();
            }

            Map<String, Object> response = new HashMap<>();
            response.put("alerts", formatAlertsForResponse(alerts));
            response.put("totalCount", alerts.size());
            
            // Group by severity
            Map<String, Long> severityCounts = alerts.stream()
                    .collect(Collectors.groupingBy(
                        alert -> alert.getSeverity().name(),
                        Collectors.counting()
                    ));
            response.put("severityCounts", severityCounts);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to get alerts: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Acknowledge an alert
     */
    @PostMapping("/alerts/{alertId}/acknowledge")
    public ResponseEntity<Map<String, String>> acknowledgeAlert(
            @PathVariable String alertId,
            @AuthenticationPrincipal Jwt jwt) {
        try {
            String userId = (jwt != null) ? jwt.getSubject() : "anonymous";
            monitoringService.acknowledgeAlert(alertId, userId);

            Map<String, String> response = new HashMap<>();
            response.put("status", "acknowledged");
            response.put("alertId", alertId);
            response.put("acknowledgedBy", userId);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to acknowledge alert {}: {}", alertId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get system health report
     */
    @GetMapping("/health/system")
    public ResponseEntity<WorkerHealthService.SystemHealthReport> getSystemHealth(
            @AuthenticationPrincipal Jwt jwt) {
        try {
            WorkerHealthService.SystemHealthReport healthReport = 
                workerHealthService.getSystemHealthReport();
            return ResponseEntity.ok(healthReport);

        } catch (Exception e) {
            log.error("Failed to get system health report: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get service degradation status
     */
    @GetMapping("/degradation")
    public ResponseEntity<Map<String, Object>> getDegradationStatus(@AuthenticationPrincipal Jwt jwt) {
        try {
            Map<String, Object> response = new HashMap<>();
            response.put("level", degradationService.getCurrentDegradationLevel());
            response.put("isDegraded", degradationService.isSystemDegraded());
            response.put("message", degradationService.getDegradationMessage());

            Map<String, Object> serviceStates = new HashMap<>();
            for (Map.Entry<String, ServiceDegradationService.ServiceDegradationState> entry : 
                 degradationService.getAllServiceStates().entrySet()) {
                Map<String, Object> state = new HashMap<>();
                state.put("healthy", entry.getValue().isHealthy());
                state.put("degraded", entry.getValue().isDegraded());
                state.put("level", entry.getValue().getDegradationLevel().name());
                state.put("reason", entry.getValue().getDegradationReason());
                state.put("lastHealthyTime", entry.getValue().getLastHealthyTime().toString());
                serviceStates.put(entry.getKey(), state);
            }
            response.put("services", serviceStates);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to get degradation status: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Manual degradation override (admin only)
     */
    @PostMapping("/admin/degradation/override")
    public ResponseEntity<Map<String, String>> setDegradationOverride(
            @RequestParam("level") String level,
            @RequestParam("reason") String reason,
            @AuthenticationPrincipal Jwt jwt) {
        try {
            // TODO: Add admin role check
            String userId = (jwt != null) ? jwt.getSubject() : "anonymous";
            
            ServiceDegradationService.DegradationLevel degradationLevel = 
                ServiceDegradationService.DegradationLevel.valueOf(level.toUpperCase());
            
            degradationService.setManualDegradationLevel(degradationLevel, 
                "Manual override by " + userId + ": " + reason);

            Map<String, String> response = new HashMap<>();
            response.put("status", "override_set");
            response.put("level", level);
            response.put("reason", reason);
            response.put("setBy", userId);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to set degradation override: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Clear degradation override (admin only)
     */
    @DeleteMapping("/admin/degradation/override")
    public ResponseEntity<Map<String, String>> clearDegradationOverride(@AuthenticationPrincipal Jwt jwt) {
        try {
            // TODO: Add admin role check
            String userId = (jwt != null) ? jwt.getSubject() : "anonymous";
            
            degradationService.clearManualDegradationOverride();

            Map<String, String> response = new HashMap<>();
            response.put("status", "override_cleared");
            response.put("clearedBy", userId);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to clear degradation override: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    // Helper methods

    private void addMetricToMap(Map<String, Object> map, String key, String metricName) {
        EnhancedMonitoringService.MetricTimeSeries metric = monitoringService.getMetric(metricName);
        if (metric != null) {
            map.put(key, metric.getLatestValue());
        }
    }

    private Duration parsePeriod(String period) {
        try {
            if (period.endsWith("m")) {
                return Duration.ofMinutes(Integer.parseInt(period.substring(0, period.length() - 1)));
            } else if (period.endsWith("h")) {
                return Duration.ofHours(Integer.parseInt(period.substring(0, period.length() - 1)));
            } else if (period.endsWith("d")) {
                return Duration.ofDays(Integer.parseInt(period.substring(0, period.length() - 1)));
            } else {
                return Duration.ofHours(1); // Default to 1 hour
            }
        } catch (Exception e) {
            return Duration.ofHours(1);
        }
    }

    private Map<String, Object> formatDataPointForResponse(EnhancedMonitoringService.MetricDataPoint dataPoint) {
        Map<String, Object> formatted = new HashMap<>();
        formatted.put("timestamp", dataPoint.getTimestamp().toString());
        formatted.put("value", dataPoint.getValue());
        formatted.put("tags", dataPoint.getTags());
        return formatted;
    }

    private List<Map<String, Object>> formatAlertsForResponse(Collection<EnhancedMonitoringService.Alert> alerts) {
        return alerts.stream()
                .map(this::formatAlertForResponse)
                .collect(Collectors.toList());
    }

    private Map<String, Object> formatAlertForResponse(EnhancedMonitoringService.Alert alert) {
        Map<String, Object> formatted = new HashMap<>();
        formatted.put("id", alert.getId());
        formatted.put("ruleName", alert.getRuleName());
        formatted.put("severity", alert.getSeverity().name());
        formatted.put("description", alert.getDescription());
        formatted.put("metricName", alert.getMetricName());
        formatted.put("actualValue", alert.getActualValue());
        formatted.put("threshold", alert.getThreshold());
        formatted.put("triggeredAt", alert.getTriggeredAt().toString());
        formatted.put("triggerCount", alert.getTriggerCount());
        formatted.put("acknowledged", alert.isAcknowledged());
        if (alert.isAcknowledged()) {
            formatted.put("acknowledgedBy", alert.getAcknowledgedBy());
            formatted.put("acknowledgedAt", alert.getAcknowledgedAt().toString());
        }
        return formatted;
    }
}
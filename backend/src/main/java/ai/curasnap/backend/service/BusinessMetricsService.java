package ai.curasnap.backend.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service for tracking business-specific KPIs and domain metrics for CuraSnap AI.
 * Focuses on medical workflow efficiency, user engagement, and business performance.
 * 
 * Key Business Metrics:
 * - SOAP note generation success rates and quality metrics
 * - Audio transcription accuracy and processing efficiency
 * - User adoption and engagement patterns
 * - Medical workflow optimization metrics
 * - Cost efficiency and resource utilization
 * - Patient data processing compliance metrics
 */
@Slf4j
@Service
public class BusinessMetricsService {

    private final MeterRegistry meterRegistry;
    private final RedisTemplate<String, Object> redisTemplate;
    
    // Business metric counters
    private final Map<String, Counter> businessCounters = new ConcurrentHashMap<>();
    private final Map<String, DistributionSummary> distributionSummaries = new ConcurrentHashMap<>();
    private final Map<String, Timer> businessTimers = new ConcurrentHashMap<>();
    
    // KPI tracking storage
    private final Map<String, AtomicLong> kpiCounters = new ConcurrentHashMap<>();
    private final Map<String, UserEngagementMetrics> userEngagementMap = new ConcurrentHashMap<>();
    private final Map<String, MedicalWorkflowMetrics> workflowMetricsMap = new ConcurrentHashMap<>();
    
    // Redis keys for persistent metrics
    private static final String BUSINESS_METRICS_PREFIX = "business_metrics:";
    private static final String USER_ENGAGEMENT_PREFIX = "user_engagement:";
    private static final String WORKFLOW_METRICS_PREFIX = "workflow_metrics:";
    private static final String DAILY_STATS_PREFIX = "daily_stats:";
    
    @Autowired
    public BusinessMetricsService(MeterRegistry meterRegistry, RedisTemplate<String, Object> redisTemplate) {
        this.meterRegistry = meterRegistry;
        this.redisTemplate = redisTemplate;
        initializeBusinessMetrics();
        log.info("BusinessMetricsService initialized with medical domain KPI tracking");
    }

    /**
     * User engagement metrics container
     */
    @Data
    public static class UserEngagementMetrics {
        private String userId;
        private Instant firstSession;
        private Instant lastSession;
        private int totalSessions;
        private int soapNotesGenerated;
        private int audioFilesProcessed;
        private Duration totalActiveTime;
        private Map<String, Integer> featureUsage;
        private String medicalSpecialty; // Optional user segment
        
        public UserEngagementMetrics(String userId) {
            this.userId = userId;
            this.firstSession = Instant.now();
            this.lastSession = Instant.now();
            this.totalSessions = 0;
            this.soapNotesGenerated = 0;
            this.audioFilesProcessed = 0;
            this.totalActiveTime = Duration.ZERO;
            this.featureUsage = new HashMap<>();
        }
    }

    /**
     * Medical workflow efficiency metrics
     */
    @Data
    public static class MedicalWorkflowMetrics {
        private String workflowId;
        private Instant createdAt;
        private int stepsCompleted;
        private int totalSteps;
        private Duration processingTime;
        private boolean completed;
        private String workflowType; // e.g., "routine_visit", "emergency", "consultation"
        private Map<String, Object> qualityMetrics;
        
        public MedicalWorkflowMetrics(String workflowId, String workflowType) {
            this.workflowId = workflowId;
            this.workflowType = workflowType;
            this.createdAt = Instant.now();
            this.stepsCompleted = 0;
            this.totalSteps = 0;
            this.processingTime = Duration.ZERO;
            this.completed = false;
            this.qualityMetrics = new HashMap<>();
        }
    }

    /**
     * Initializes all business-specific metrics
     */
    private void initializeBusinessMetrics() {
        // SOAP Note Generation Metrics
        initializeSoapNoteMetrics();
        
        // Audio Processing Metrics
        initializeAudioMetrics();
        
        // User Engagement Metrics
        initializeUserEngagementMetrics();
        
        // Medical Workflow Metrics
        initializeWorkflowMetrics();
        
        // Cost and Efficiency Metrics
        initializeCostMetrics();
        
        log.info("Initialized {} business metric categories", 5);
    }

    /**
     * Initialize SOAP note generation metrics
     */
    private void initializeSoapNoteMetrics() {
        // Success rate counter
        businessCounters.put("soap_notes_generated", 
            Counter.builder("soap_notes_generated_total")
                   .description("Total SOAP notes successfully generated")
                   .register(meterRegistry));

        businessCounters.put("soap_generation_failures", 
            Counter.builder("soap_generation_failures_total")
                   .description("Total SOAP note generation failures")
                   .register(meterRegistry));

        // Quality metrics
        distributionSummaries.put("soap_note_length", 
            DistributionSummary.builder("soap_note_length_characters")
                              .description("Distribution of SOAP note lengths in characters")
                              .register(meterRegistry));

        distributionSummaries.put("soap_note_completeness", 
            DistributionSummary.builder("soap_note_completeness_score")
                              .description("SOAP note completeness score (0-100)")
                              .baseUnit("percent")
                              .register(meterRegistry));

        // Processing time
        businessTimers.put("soap_generation_time", 
            Timer.builder("soap_generation_duration_seconds")
                 .description("Time taken to generate SOAP notes")
                 .register(meterRegistry));
    }

    /**
     * Initialize audio processing metrics
     */
    private void initializeAudioMetrics() {
        // Processing success/failure
        businessCounters.put("audio_transcriptions_success", 
            Counter.builder("audio_transcriptions_success_total")
                   .description("Successful audio transcriptions")
                   .register(meterRegistry));

        businessCounters.put("audio_transcriptions_failed", 
            Counter.builder("audio_transcriptions_failed_total")
                   .description("Failed audio transcriptions")
                   .register(meterRegistry));

        // Audio file characteristics
        distributionSummaries.put("audio_file_duration", 
            DistributionSummary.builder("audio_file_duration_seconds")
                              .description("Distribution of audio file durations")
                              .baseUnit("seconds")
                              .register(meterRegistry));

        distributionSummaries.put("audio_file_size", 
            DistributionSummary.builder("audio_file_size_bytes")
                              .description("Distribution of audio file sizes")
                              .baseUnit("bytes")
                              .register(meterRegistry));

        // Transcription accuracy (if available)
        distributionSummaries.put("transcription_confidence", 
            DistributionSummary.builder("transcription_confidence_score")
                              .description("Transcription confidence scores")
                              .baseUnit("percent")
                              .register(meterRegistry));
    }

    /**
     * Initialize user engagement metrics
     */
    private void initializeUserEngagementMetrics() {
        // User activity
        businessCounters.put("user_sessions", 
            Counter.builder("user_sessions_total")
                   .description("Total user sessions")
                   .register(meterRegistry));

        businessCounters.put("new_users", 
            Counter.builder("new_users_total")
                   .description("Total new users registered")
                   .register(meterRegistry));

        // Feature adoption
        businessCounters.put("feature_usage", 
            Counter.builder("feature_usage_total")
                   .description("Feature usage by type")
                   .register(meterRegistry));

        // Session duration
        businessTimers.put("user_session_duration", 
            Timer.builder("user_session_duration_seconds")
                 .description("User session durations")
                 .register(meterRegistry));
    }

    /**
     * Initialize medical workflow metrics
     */
    private void initializeWorkflowMetrics() {
        // Workflow completion
        businessCounters.put("workflows_started", 
            Counter.builder("medical_workflows_started_total")
                   .description("Medical workflows started")
                   .register(meterRegistry));

        businessCounters.put("workflows_completed", 
            Counter.builder("medical_workflows_completed_total")
                   .description("Medical workflows completed")
                   .register(meterRegistry));

        // Workflow efficiency
        businessTimers.put("workflow_duration", 
            Timer.builder("medical_workflow_duration_seconds")
                 .description("Medical workflow completion times")
                 .register(meterRegistry));

        distributionSummaries.put("workflow_steps", 
            DistributionSummary.builder("medical_workflow_steps_count")
                              .description("Number of steps in medical workflows")
                              .register(meterRegistry));
    }

    /**
     * Initialize cost and efficiency metrics
     */
    private void initializeCostMetrics() {
        // API usage tracking (for cost calculation)
        businessCounters.put("openai_api_calls", 
            Counter.builder("openai_api_calls_total")
                   .description("OpenAI API calls made")
                   .register(meterRegistry));

        distributionSummaries.put("openai_tokens_used", 
            DistributionSummary.builder("openai_tokens_used_total")
                              .description("OpenAI tokens consumed")
                              .register(meterRegistry));

        // Resource efficiency
        distributionSummaries.put("processing_efficiency", 
            DistributionSummary.builder("processing_efficiency_ratio")
                              .description("Processing efficiency ratio (output/input)")
                              .register(meterRegistry));
    }

    // Business KPI Recording Methods

    /**
     * Records SOAP note generation metrics
     */
    public void recordSoapNoteGenerated(String userId, boolean success, int noteLength, Duration processingTime, double completenessScore) {
        if (success) {
            businessCounters.get("soap_notes_generated").increment();
            distributionSummaries.get("soap_note_length").record(noteLength);
            distributionSummaries.get("soap_note_completeness").record(completenessScore);
        } else {
            businessCounters.get("soap_generation_failures").increment();
        }
        
        businessTimers.get("soap_generation_time").record(processingTime);
        
        // Update user engagement
        updateUserEngagement(userId, "soap_note_generated");
        
        log.debug("Recorded SOAP note generation: userId={}, success={}, length={}, completeness={}", 
                 userId, success, noteLength, completenessScore);
    }

    /**
     * Records audio transcription metrics
     */
    public void recordAudioTranscription(String userId, boolean success, Duration audioLength, long fileSizeBytes, Double confidenceScore) {
        if (success) {
            businessCounters.get("audio_transcriptions_success").increment();
            if (confidenceScore != null) {
                distributionSummaries.get("transcription_confidence").record(confidenceScore);
            }
        } else {
            businessCounters.get("audio_transcriptions_failed").increment();
        }
        
        distributionSummaries.get("audio_file_duration").record(audioLength.toSeconds());
        distributionSummaries.get("audio_file_size").record(fileSizeBytes);
        
        // Update user engagement
        updateUserEngagement(userId, "audio_processed");
        
        log.debug("Recorded audio transcription: userId={}, success={}, duration={}s, size={} bytes", 
                 userId, success, audioLength.toSeconds(), fileSizeBytes);
    }

    /**
     * Records user session metrics
     */
    public void recordUserSession(String userId, Duration sessionDuration, String medicalSpecialty) {
        businessCounters.get("user_sessions").increment();
        businessTimers.get("user_session_duration").record(sessionDuration);
        
        // Update or create user engagement metrics
        UserEngagementMetrics engagement = userEngagementMap.computeIfAbsent(userId, UserEngagementMetrics::new);
        engagement.setLastSession(Instant.now());
        engagement.setTotalSessions(engagement.getTotalSessions() + 1);
        engagement.setTotalActiveTime(engagement.getTotalActiveTime().plus(sessionDuration));
        if (medicalSpecialty != null) {
            engagement.setMedicalSpecialty(medicalSpecialty);
        }
        
        log.debug("Recorded user session: userId={}, duration={}s, specialty={}", 
                 userId, sessionDuration.toSeconds(), medicalSpecialty);
    }

    /**
     * Records feature usage
     */
    public void recordFeatureUsage(String userId, String featureName, String context) {
        meterRegistry.counter("feature_usage_total", "feature", featureName, "context", context).increment();
        
        // Update user engagement
        updateUserEngagement(userId, "feature_" + featureName);
        
        log.debug("Recorded feature usage: userId={}, feature={}, context={}", userId, featureName, context);
    }

    /**
     * Records medical workflow metrics
     */
    public void recordMedicalWorkflow(String workflowId, String workflowType, boolean started, boolean completed, Duration duration) {
        if (started) {
            meterRegistry.counter("medical_workflows_started_total", "type", workflowType).increment();
            
            MedicalWorkflowMetrics workflow = new MedicalWorkflowMetrics(workflowId, workflowType);
            workflowMetricsMap.put(workflowId, workflow);
        }
        
        if (completed) {
            meterRegistry.counter("medical_workflows_completed_total", "type", workflowType).increment();
            businessTimers.get("workflow_duration").record(duration);
            
            MedicalWorkflowMetrics workflow = workflowMetricsMap.get(workflowId);
            if (workflow != null) {
                workflow.setCompleted(true);
                workflow.setProcessingTime(duration);
            }
        }
        
        log.debug("Recorded medical workflow: id={}, type={}, started={}, completed={}", 
                 workflowId, workflowType, started, completed);
    }

    /**
     * Records API cost metrics
     */
    public void recordApiUsage(String provider, int tokensUsed, double estimatedCost) {
        if ("openai".equals(provider)) {
            businessCounters.get("openai_api_calls").increment();
            distributionSummaries.get("openai_tokens_used").record(tokensUsed);
        }
        
        // Record cost in Redis for daily aggregation
        String dailyKey = DAILY_STATS_PREFIX + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        redisTemplate.opsForHash().increment(dailyKey, "api_cost_" + provider, estimatedCost);
        redisTemplate.opsForHash().increment(dailyKey, "api_tokens_" + provider, tokensUsed);
        
        log.debug("Recorded API usage: provider={}, tokens={}, cost=${}", provider, tokensUsed, estimatedCost);
    }

    /**
     * Updates user engagement metrics
     */
    private void updateUserEngagement(String userId, String action) {
        UserEngagementMetrics engagement = userEngagementMap.computeIfAbsent(userId, UserEngagementMetrics::new);
        engagement.setLastSession(Instant.now());
        
        // Update feature usage counter
        engagement.getFeatureUsage().merge(action, 1, Integer::sum);
        
        // Update specific counters based on action
        switch (action) {
            case "soap_note_generated":
                engagement.setSoapNotesGenerated(engagement.getSoapNotesGenerated() + 1);
                break;
            case "audio_processed":
                engagement.setAudioFilesProcessed(engagement.getAudioFilesProcessed() + 1);
                break;
        }
    }

    /**
     * Gets business KPI summary
     */
    public Map<String, Object> getBusinessKPISummary() {
        Map<String, Object> kpis = new HashMap<>();
        
        try {
            // SOAP generation KPIs
            Map<String, Object> soapKpis = new HashMap<>();
            soapKpis.put("totalGenerated", getCounterValue("soap_notes_generated"));
            soapKpis.put("totalFailures", getCounterValue("soap_generation_failures"));
            soapKpis.put("successRate", calculateSuccessRate("soap_notes_generated", "soap_generation_failures"));
            soapKpis.put("averageLength", getDistributionSummaryMean("soap_note_length"));
            soapKpis.put("averageCompleteness", getDistributionSummaryMean("soap_note_completeness"));
            kpis.put("soapGeneration", soapKpis);
            
            // Audio processing KPIs
            Map<String, Object> audioKpis = new HashMap<>();
            audioKpis.put("totalProcessed", getCounterValue("audio_transcriptions_success"));
            audioKpis.put("totalFailed", getCounterValue("audio_transcriptions_failed"));
            audioKpis.put("successRate", calculateSuccessRate("audio_transcriptions_success", "audio_transcriptions_failed"));
            audioKpis.put("averageDuration", getDistributionSummaryMean("audio_file_duration"));
            audioKpis.put("averageConfidence", getDistributionSummaryMean("transcription_confidence"));
            kpis.put("audioProcessing", audioKpis);
            
            // User engagement KPIs
            Map<String, Object> userKpis = new HashMap<>();
            userKpis.put("totalSessions", getCounterValue("user_sessions"));
            userKpis.put("activeUsers", userEngagementMap.size());
            userKpis.put("averageSessionDuration", getTimerMean("user_session_duration"));
            kpis.put("userEngagement", userKpis);
            
            // Workflow KPIs
            Map<String, Object> workflowKpis = new HashMap<>();
            workflowKpis.put("totalStarted", getCounterValue("workflows_started"));
            workflowKpis.put("totalCompleted", getCounterValue("workflows_completed"));
            workflowKpis.put("completionRate", calculateSuccessRate("workflows_completed", "workflows_started"));
            workflowKpis.put("averageDuration", getTimerMean("workflow_duration"));
            kpis.put("medicalWorkflows", workflowKpis);
            
            kpis.put("timestamp", Instant.now().toString());
            
        } catch (Exception e) {
            log.error("Failed to generate business KPI summary: {}", e.getMessage());
            kpis.put("error", "Failed to calculate KPIs");
        }
        
        return kpis;
    }

    /**
     * Gets detailed user engagement report
     */
    public Map<String, Object> getUserEngagementReport() {
        Map<String, Object> report = new HashMap<>();
        
        try {
            // Overall engagement stats
            report.put("totalUsers", userEngagementMap.size());
            report.put("totalSessions", getCounterValue("user_sessions"));
            
            // User segments by activity
            Map<String, Integer> activitySegments = new HashMap<>();
            Map<String, Integer> specialtySegments = new HashMap<>();
            
            for (UserEngagementMetrics metrics : userEngagementMap.values()) {
                // Activity-based segmentation
                String activityLevel = determineActivityLevel(metrics);
                activitySegments.merge(activityLevel, 1, Integer::sum);
                
                // Specialty-based segmentation
                String specialty = metrics.getMedicalSpecialty() != null ? 
                                 metrics.getMedicalSpecialty() : "unknown";
                specialtySegments.merge(specialty, 1, Integer::sum);
            }
            
            report.put("activitySegments", activitySegments);
            report.put("specialtySegments", specialtySegments);
            report.put("generatedAt", Instant.now().toString());
            
        } catch (Exception e) {
            log.error("Failed to generate user engagement report: {}", e.getMessage());
            report.put("error", "Failed to generate report");
        }
        
        return report;
    }

    // Helper methods

    private double getCounterValue(String counterName) {
        Counter counter = businessCounters.get(counterName);
        return counter != null ? counter.count() : 0.0;
    }

    private double getDistributionSummaryMean(String summaryName) {
        DistributionSummary summary = distributionSummaries.get(summaryName);
        return summary != null ? summary.mean() : 0.0;
    }

    private double getTimerMean(String timerName) {
        Timer timer = businessTimers.get(timerName);
        return timer != null ? timer.mean(java.util.concurrent.TimeUnit.SECONDS) : 0.0;
    }

    private double calculateSuccessRate(String successCounterName, String failureCounterName) {
        double success = getCounterValue(successCounterName);
        double failure = getCounterValue(failureCounterName);
        double total = success + failure;
        return total > 0 ? (success / total) * 100.0 : 0.0;
    }

    private String determineActivityLevel(UserEngagementMetrics metrics) {
        if (metrics.getTotalSessions() >= 50 || metrics.getSoapNotesGenerated() >= 100) {
            return "high_activity";
        } else if (metrics.getTotalSessions() >= 10 || metrics.getSoapNotesGenerated() >= 20) {
            return "medium_activity";
        } else {
            return "low_activity";
        }
    }

    /**
     * Scheduled method to persist engagement metrics to Redis
     */
    @Scheduled(fixedRate = 300000) // 5 minutes
    public void persistEngagementMetrics() {
        try {
            for (Map.Entry<String, UserEngagementMetrics> entry : userEngagementMap.entrySet()) {
                String userId = entry.getKey();
                UserEngagementMetrics metrics = entry.getValue();
                
                String redisKey = USER_ENGAGEMENT_PREFIX + userId;
                redisTemplate.opsForValue().set(redisKey, metrics, Duration.ofDays(30));
            }
            
            log.debug("Persisted {} user engagement metrics to Redis", userEngagementMap.size());
            
        } catch (Exception e) {
            log.error("Failed to persist engagement metrics: {}", e.getMessage());
        }
    }
}
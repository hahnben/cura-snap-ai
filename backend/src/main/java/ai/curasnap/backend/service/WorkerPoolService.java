package ai.curasnap.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Service for managing multiple worker instances
 * Provides horizontal scaling and load balancing across worker pool
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.worker.pool.enabled", havingValue = "true", matchIfMissing = true)
public class WorkerPoolService {

    private final JobService jobService;
    private final TranscriptionService transcriptionService;
    private final NoteService noteService;
    private final WorkerHealthService workerHealthService;

    @Value("${app.worker.pool.audio.size:2}")
    private int audioWorkerPoolSize;

    @Value("${app.worker.pool.text.size:1}")
    private int textWorkerPoolSize;

    @Value("${app.worker.pool.transcription.size:1}")
    private int transcriptionWorkerPoolSize;

    @Value("${app.worker.pool.processing.interval:5000}")
    private long processingIntervalMs;

    @Value("${app.worker.pool.shutdown.timeout:30}")
    private int shutdownTimeoutSeconds;

    // Worker pool management
    private ScheduledExecutorService schedulerService;
    private final List<ManagedAudioWorker> audioWorkers = new ArrayList<>();
    private final List<ManagedTextWorker> textWorkers = new ArrayList<>();
    private final List<ManagedTranscriptWorker> transcriptionWorkers = new ArrayList<>();
    private final AtomicInteger workerIdCounter = new AtomicInteger(1);

    // Pool statistics
    private volatile boolean poolRunning = false;
    private volatile int totalWorkersCreated = 0;
    private volatile int activeWorkerCount = 0;

    @Autowired
    public WorkerPoolService(JobService jobService,
                           TranscriptionService transcriptionService,
                           NoteService noteService,
                           WorkerHealthService workerHealthService) {
        this.jobService = jobService;
        this.transcriptionService = transcriptionService;
        this.noteService = noteService;
        this.workerHealthService = workerHealthService;
    }

    @PostConstruct
    public void startWorkerPool() {
        try {
            log.info("Starting worker pool - Audio workers: {}, Text workers: {}, Transcription workers: {}",
                    audioWorkerPoolSize, textWorkerPoolSize, transcriptionWorkerPoolSize);

            // Create scheduler for worker pool management
            int totalWorkers = audioWorkerPoolSize + textWorkerPoolSize + transcriptionWorkerPoolSize;
            schedulerService = Executors.newScheduledThreadPool(totalWorkers + 2); // +2 for management tasks

            // Start audio workers
            for (int i = 0; i < audioWorkerPoolSize; i++) {
                startAudioWorker();
            }

            // Start text workers
            for (int i = 0; i < textWorkerPoolSize; i++) {
                startTextWorker();
            }

            // Start transcription workers
            for (int i = 0; i < transcriptionWorkerPoolSize; i++) {
                startTranscriptionWorker();
            }

            // Start pool monitoring task
            schedulerService.scheduleWithFixedDelay(
                    this::monitorWorkerPool, 
                    30, 30, TimeUnit.SECONDS
            );

            poolRunning = true;
            log.info("Worker pool started successfully with {} total workers", totalWorkers);

        } catch (Exception e) {
            log.error("Failed to start worker pool: {}", e.getMessage(), e);
            throw new RuntimeException("Worker pool startup failed", e);
        }
    }

    @PreDestroy
    public void shutdownWorkerPool() {
        log.info("Shutting down worker pool...");
        poolRunning = false;

        try {
            // Deactivate all workers
            audioWorkers.forEach(worker -> workerHealthService.deactivateWorker(worker.getWorkerId()));
            textWorkers.forEach(worker -> workerHealthService.deactivateWorker(worker.getWorkerId()));
            transcriptionWorkers.forEach(worker -> workerHealthService.deactivateWorker(worker.getWorkerId()));

            // Shutdown scheduler
            if (schedulerService != null) {
                schedulerService.shutdown();
                
                if (!schedulerService.awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                    log.warn("Worker pool shutdown timeout, forcing shutdown");
                    schedulerService.shutdownNow();
                }
            }

            log.info("Worker pool shutdown complete");

        } catch (Exception e) {
            log.error("Error during worker pool shutdown: {}", e.getMessage(), e);
        }
    }

    /**
     * Start a new audio worker instance
     */
    private void startAudioWorker() {
        try {
            ManagedAudioWorker worker = new ManagedAudioWorker(
                    generateWorkerId("audio"),
                    jobService,
                    transcriptionService,
                    noteService,
                    workerHealthService
            );

            // Schedule worker processing task
            ScheduledFuture<?> workerTask = schedulerService.scheduleWithFixedDelay(
                    worker::processJobs,
                    0,
                    processingIntervalMs,
                    TimeUnit.MILLISECONDS
            );

            worker.setWorkerTask(workerTask);
            audioWorkers.add(worker);
            totalWorkersCreated++;
            activeWorkerCount++;

            log.info("Started audio worker: {}", worker.getWorkerId());

        } catch (Exception e) {
            log.error("Failed to start audio worker: {}", e.getMessage(), e);
        }
    }

    /**
     * Start a new text worker instance
     */
    private void startTextWorker() {
        try {
            ManagedTextWorker worker = new ManagedTextWorker(
                    generateWorkerId("text"),
                    jobService,
                    noteService,
                    workerHealthService
            );

            // Schedule worker processing task
            ScheduledFuture<?> workerTask = schedulerService.scheduleWithFixedDelay(
                    worker::processJobs,
                    0,
                    processingIntervalMs,
                    TimeUnit.MILLISECONDS
            );

            worker.setWorkerTask(workerTask);
            textWorkers.add(worker);
            totalWorkersCreated++;
            activeWorkerCount++;

            log.info("Started text worker: {}", worker.getWorkerId());

        } catch (Exception e) {
            log.error("Failed to start text worker: {}", e.getMessage(), e);
        }
    }

    /**
     * Start a new transcription worker instance
     */
    private void startTranscriptionWorker() {
        try {
            ManagedTranscriptWorker worker = new ManagedTranscriptWorker(
                    generateWorkerId("transcription"),
                    jobService,
                    transcriptionService,
                    workerHealthService
            );

            // Schedule worker processing task
            ScheduledFuture<?> workerTask = schedulerService.scheduleWithFixedDelay(
                    worker::processJobs,
                    0,
                    processingIntervalMs,
                    TimeUnit.MILLISECONDS
            );

            worker.setWorkerTask(workerTask);
            transcriptionWorkers.add(worker);
            totalWorkersCreated++;
            activeWorkerCount++;

            log.info("Started transcription worker: {}", worker.getWorkerId());

        } catch (Exception e) {
            log.error("Failed to start transcription worker: {}", e.getMessage(), e);
        }
    }

    /**
     * Monitor worker pool health and performance
     */
    private void monitorWorkerPool() {
        try {
            log.debug("Monitoring worker pool - Audio workers: {}, Text workers: {}, Transcription workers: {}, Active: {}",
                    audioWorkers.size(), textWorkers.size(), transcriptionWorkers.size(), activeWorkerCount);

            // Check worker health and restart failed workers if needed
            checkAndRestartFailedWorkers();

            // Log pool statistics
            logPoolStatistics();

        } catch (Exception e) {
            log.error("Error during worker pool monitoring: {}", e.getMessage(), e);
        }
    }

    /**
     * Check for failed workers and restart them if necessary
     */
    private void checkAndRestartFailedWorkers() {
        // Check audio workers
        for (ManagedAudioWorker worker : new ArrayList<>(audioWorkers)) {
            if (worker.isFailed()) {
                log.warn("Audio worker {} has failed, restarting", worker.getWorkerId());
                restartAudioWorker(worker);
            }
        }

        // Check text workers
        for (ManagedTextWorker worker : new ArrayList<>(textWorkers)) {
            if (worker.isFailed()) {
                log.warn("Text worker {} has failed, restarting", worker.getWorkerId());
                restartTextWorker(worker);
            }
        }

        // Check transcription workers
        for (ManagedTranscriptWorker worker : new ArrayList<>(transcriptionWorkers)) {
            if (worker.isFailed()) {
                log.warn("Transcription worker {} has failed, restarting", worker.getWorkerId());
                restartTranscriptionWorker(worker);
            }
        }
    }

    /**
     * Restart a failed audio worker
     */
    private void restartAudioWorker(ManagedAudioWorker failedWorker) {
        try {
            // Remove failed worker
            audioWorkers.remove(failedWorker);
            failedWorker.shutdown();
            activeWorkerCount--;

            // Start replacement worker
            startAudioWorker();
            
            log.info("Successfully restarted audio worker {}", failedWorker.getWorkerId());

        } catch (Exception e) {
            log.error("Failed to restart audio worker {}: {}", failedWorker.getWorkerId(), e.getMessage());
        }
    }

    /**
     * Restart a failed text worker
     */
    private void restartTextWorker(ManagedTextWorker failedWorker) {
        try {
            // Remove failed worker
            textWorkers.remove(failedWorker);
            failedWorker.shutdown();
            activeWorkerCount--;

            // Start replacement worker
            startTextWorker();
            
            log.info("Successfully restarted text worker {}", failedWorker.getWorkerId());

        } catch (Exception e) {
            log.error("Failed to restart text worker {}: {}", failedWorker.getWorkerId(), e.getMessage());
        }
    }

    /**
     * Restart a failed transcription worker
     */
    private void restartTranscriptionWorker(ManagedTranscriptWorker failedWorker) {
        try {
            // Remove failed worker
            transcriptionWorkers.remove(failedWorker);
            failedWorker.shutdown();
            activeWorkerCount--;

            // Start replacement worker
            startTranscriptionWorker();

            log.info("Successfully restarted transcription worker {}", failedWorker.getWorkerId());

        } catch (Exception e) {
            log.error("Failed to restart transcription worker {}: {}", failedWorker.getWorkerId(), e.getMessage());
        }
    }

    /**
     * Scale worker pool up or down
     *
     * @param audioWorkers target number of audio workers
     * @param textWorkers target number of text workers
     */
    public void scaleWorkerPool(int audioWorkers, int textWorkers) {
        try {
            log.info("Scaling worker pool - Audio: {} -> {}, Text: {} -> {}", 
                    this.audioWorkers.size(), audioWorkers,
                    this.textWorkers.size(), textWorkers);

            // Scale audio workers
            scaleAudioWorkers(audioWorkers);

            // Scale text workers
            scaleTextWorkers(textWorkers);

            log.info("Worker pool scaling complete");

        } catch (Exception e) {
            log.error("Error scaling worker pool: {}", e.getMessage(), e);
        }
    }

    /**
     * Get worker pool statistics
     *
     * @return worker pool stats
     */
    public WorkerPoolStats getPoolStats() {
        try {
            long totalJobsProcessed = audioWorkers.stream()
                    .mapToLong(ManagedAudioWorker::getTotalJobsProcessed)
                    .sum() + textWorkers.stream()
                    .mapToLong(ManagedTextWorker::getTotalJobsProcessed)
                    .sum();

            long totalJobsFailed = audioWorkers.stream()
                    .mapToLong(ManagedAudioWorker::getTotalJobsFailed)
                    .sum() + textWorkers.stream()
                    .mapToLong(ManagedTextWorker::getTotalJobsFailed)
                    .sum();

            return WorkerPoolStats.builder()
                    .poolRunning(poolRunning)
                    .audioWorkersConfigured(audioWorkerPoolSize)
                    .textWorkersConfigured(textWorkerPoolSize)
                    .audioWorkersActive(audioWorkers.size())
                    .textWorkersActive(textWorkers.size())
                    .totalWorkersCreated(totalWorkersCreated)
                    .activeWorkerCount(activeWorkerCount)
                    .totalJobsProcessed(totalJobsProcessed)
                    .totalJobsFailed(totalJobsFailed)
                    .processingIntervalMs(processingIntervalMs)
                    .build();

        } catch (Exception e) {
            log.error("Error getting pool stats: {}", e.getMessage());
            return WorkerPoolStats.builder()
                    .poolRunning(false)
                    .build();
        }
    }

    // Private helper methods

    private void scaleAudioWorkers(int targetCount) {
        int currentCount = audioWorkers.size();
        
        if (targetCount > currentCount) {
            // Scale up
            IntStream.range(0, targetCount - currentCount)
                    .forEach(i -> startAudioWorker());
        } else if (targetCount < currentCount) {
            // Scale down
            for (int i = currentCount - 1; i >= targetCount; i--) {
                ManagedAudioWorker worker = audioWorkers.get(i);
                audioWorkers.remove(worker);
                worker.shutdown();
                activeWorkerCount--;
            }
        }
    }

    private void scaleTextWorkers(int targetCount) {
        int currentCount = textWorkers.size();
        
        if (targetCount > currentCount) {
            // Scale up
            IntStream.range(0, targetCount - currentCount)
                    .forEach(i -> startTextWorker());
        } else if (targetCount < currentCount) {
            // Scale down
            for (int i = currentCount - 1; i >= targetCount; i--) {
                ManagedTextWorker worker = textWorkers.get(i);
                textWorkers.remove(worker);
                worker.shutdown();
                activeWorkerCount--;
            }
        }
    }

    private String generateWorkerId(String workerType) {
        return String.format("%s-pool-worker-%d-%d", 
                workerType, 
                workerIdCounter.getAndIncrement(),
                System.currentTimeMillis());
    }

    private void logPoolStatistics() {
        if (log.isDebugEnabled()) {
            WorkerPoolStats stats = getPoolStats();
            log.debug("Worker Pool Stats: {} audio workers, {} text workers, {} total jobs processed", 
                    stats.getAudioWorkersActive(), 
                    stats.getTextWorkersActive(), 
                    stats.getTotalJobsProcessed());
        }
    }

    // Data classes

    public static class WorkerPoolStats {
        private boolean poolRunning;
        private int audioWorkersConfigured;
        private int textWorkersConfigured;
        private int audioWorkersActive;
        private int textWorkersActive;
        private int totalWorkersCreated;
        private int activeWorkerCount;
        private long totalJobsProcessed;
        private long totalJobsFailed;
        private long processingIntervalMs;

        // Constructor
        private WorkerPoolStats(Builder builder) {
            this.poolRunning = builder.poolRunning;
            this.audioWorkersConfigured = builder.audioWorkersConfigured;
            this.textWorkersConfigured = builder.textWorkersConfigured;
            this.audioWorkersActive = builder.audioWorkersActive;
            this.textWorkersActive = builder.textWorkersActive;
            this.totalWorkersCreated = builder.totalWorkersCreated;
            this.activeWorkerCount = builder.activeWorkerCount;
            this.totalJobsProcessed = builder.totalJobsProcessed;
            this.totalJobsFailed = builder.totalJobsFailed;
            this.processingIntervalMs = builder.processingIntervalMs;
        }

        public static Builder builder() {
            return new Builder();
        }

        // Getters
        public boolean isPoolRunning() { return poolRunning; }
        public int getAudioWorkersConfigured() { return audioWorkersConfigured; }
        public int getTextWorkersConfigured() { return textWorkersConfigured; }
        public int getAudioWorkersActive() { return audioWorkersActive; }
        public int getTextWorkersActive() { return textWorkersActive; }
        public int getTotalWorkersCreated() { return totalWorkersCreated; }
        public int getActiveWorkerCount() { return activeWorkerCount; }
        public long getTotalJobsProcessed() { return totalJobsProcessed; }
        public long getTotalJobsFailed() { return totalJobsFailed; }
        public long getProcessingIntervalMs() { return processingIntervalMs; }

        public static class Builder {
            private boolean poolRunning;
            private int audioWorkersConfigured;
            private int textWorkersConfigured;
            private int audioWorkersActive;
            private int textWorkersActive;
            private int totalWorkersCreated;
            private int activeWorkerCount;
            private long totalJobsProcessed;
            private long totalJobsFailed;
            private long processingIntervalMs;

            public Builder poolRunning(boolean poolRunning) { this.poolRunning = poolRunning; return this; }
            public Builder audioWorkersConfigured(int count) { this.audioWorkersConfigured = count; return this; }
            public Builder textWorkersConfigured(int count) { this.textWorkersConfigured = count; return this; }
            public Builder audioWorkersActive(int count) { this.audioWorkersActive = count; return this; }
            public Builder textWorkersActive(int count) { this.textWorkersActive = count; return this; }
            public Builder totalWorkersCreated(int count) { this.totalWorkersCreated = count; return this; }
            public Builder activeWorkerCount(int count) { this.activeWorkerCount = count; return this; }
            public Builder totalJobsProcessed(long count) { this.totalJobsProcessed = count; return this; }
            public Builder totalJobsFailed(long count) { this.totalJobsFailed = count; return this; }
            public Builder processingIntervalMs(long interval) { this.processingIntervalMs = interval; return this; }

            public WorkerPoolStats build() {
                return new WorkerPoolStats(this);
            }
        }
    }
}
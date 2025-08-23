package ai.curasnap.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class WorkerHealthServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    
    @Mock
    private JobService jobService;

    private WorkerHealthService workerHealthService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        workerHealthService = new WorkerHealthService(redisTemplate, jobService);
    }

    @Test
    void testRegisterWorker() {
        // Given
        String workerId = "test-worker-1";
        String workerType = "audio_processing";

        // When
        workerHealthService.registerWorker(workerId, workerType);

        // Then
        Optional<WorkerHealthService.WorkerHealth> workerHealth = 
                workerHealthService.getWorkerHealth(workerId);
        
        assertTrue(workerHealth.isPresent());
        assertEquals(workerId, workerHealth.get().getWorkerId());
        assertEquals(workerType, workerHealth.get().getWorkerType());
        assertEquals(WorkerHealthService.WorkerStatus.ACTIVE, workerHealth.get().getStatus());
    }

    @Test
    void testUpdateWorkerHeartbeat() {
        // Given
        String workerId = "test-worker-1";
        String workerType = "audio_processing";
        
        workerHealthService.registerWorker(workerId, workerType);
        Instant beforeHeartbeat = Instant.now();
        
        // Wait a small amount to ensure timestamp difference
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // When
        workerHealthService.updateWorkerHeartbeat(workerId);

        // Then
        Optional<WorkerHealthService.WorkerHealth> workerHealth = 
                workerHealthService.getWorkerHealth(workerId);
        
        assertTrue(workerHealth.isPresent());
        assertTrue(workerHealth.get().getLastHeartbeat().isAfter(beforeHeartbeat));
    }

    @Test
    void testRecordJobProcessing_Success() {
        // Given
        String workerId = "test-worker-1";
        String workerType = "audio_processing";
        long processingTime = 1500L;
        
        workerHealthService.registerWorker(workerId, workerType);

        // When
        workerHealthService.recordJobProcessing(workerId, true, processingTime);

        // Then
        Optional<WorkerHealthService.WorkerHealth> workerHealth = 
                workerHealthService.getWorkerHealth(workerId);
        
        assertTrue(workerHealth.isPresent());
        assertEquals(1, workerHealth.get().getProcessedJobs());
        assertEquals(0, workerHealth.get().getFailedJobs());
    }

    @Test
    void testRecordJobProcessing_Failure() {
        // Given
        String workerId = "test-worker-1";
        String workerType = "audio_processing";
        long processingTime = 2000L;
        
        workerHealthService.registerWorker(workerId, workerType);

        // When
        workerHealthService.recordJobProcessing(workerId, false, processingTime);

        // Then
        Optional<WorkerHealthService.WorkerHealth> workerHealth = 
                workerHealthService.getWorkerHealth(workerId);
        
        assertTrue(workerHealth.isPresent());
        assertEquals(0, workerHealth.get().getProcessedJobs());
        assertEquals(1, workerHealth.get().getFailedJobs());
    }

    @Test
    void testDeactivateWorker() {
        // Given
        String workerId = "test-worker-1";
        String workerType = "audio_processing";
        
        workerHealthService.registerWorker(workerId, workerType);

        // When
        workerHealthService.deactivateWorker(workerId);

        // Then
        Optional<WorkerHealthService.WorkerHealth> workerHealth = 
                workerHealthService.getWorkerHealth(workerId);
        
        assertTrue(workerHealth.isPresent());
        assertEquals(WorkerHealthService.WorkerStatus.INACTIVE, workerHealth.get().getStatus());
        assertNotNull(workerHealth.get().getEndTime());
    }

    @Test
    void testGetActiveWorkers() {
        // Given
        String activeWorkerId = "active-worker-1";
        String inactiveWorkerId = "inactive-worker-1";
        String workerType = "audio_processing";
        
        workerHealthService.registerWorker(activeWorkerId, workerType);
        workerHealthService.registerWorker(inactiveWorkerId, workerType);
        workerHealthService.deactivateWorker(inactiveWorkerId);

        // When
        List<WorkerHealthService.WorkerHealth> activeWorkers = 
                workerHealthService.getActiveWorkers();

        // Then
        assertEquals(1, activeWorkers.size());
        assertEquals(activeWorkerId, activeWorkers.get(0).getWorkerId());
        assertEquals(WorkerHealthService.WorkerStatus.ACTIVE, activeWorkers.get(0).getStatus());
    }

    @Test
    void testGetSystemHealthReport() {
        // Given
        String workerId1 = "worker-1";
        String workerId2 = "worker-2";
        String workerType = "audio_processing";
        
        // Mock JobService queue stats
        when(jobService.getQueueStats("audio_processing"))
                .thenReturn(java.util.Map.of("size", 5L));
        when(jobService.getQueueStats("text_processing"))
                .thenReturn(java.util.Map.of("size", 2L));
        
        workerHealthService.registerWorker(workerId1, workerType);
        workerHealthService.registerWorker(workerId2, workerType);
        
        // Record some job processing
        workerHealthService.recordJobProcessing(workerId1, true, 1000L);
        workerHealthService.recordJobProcessing(workerId1, true, 1500L);
        workerHealthService.recordJobProcessing(workerId2, false, 2000L);

        // When
        WorkerHealthService.SystemHealthReport report = 
                workerHealthService.getSystemHealthReport();

        // Then
        assertNotNull(report);
        assertEquals(2, report.getActiveWorkers());
        assertEquals(2, report.getTotalWorkersRegistered());
        assertEquals(2, report.getTotalProcessedJobs());
        assertEquals(1, report.getTotalFailedJobs());
        assertTrue(report.getHealthScore() >= 0 && report.getHealthScore() <= 100);
        assertEquals(5L, report.getAudioQueueSize());
        assertEquals(2L, report.getTextQueueSize());
    }

    @Test
    void testGetWorkerHealth_NonExistent() {
        // Given
        String nonExistentWorkerId = "non-existent-worker";

        // When
        Optional<WorkerHealthService.WorkerHealth> workerHealth = 
                workerHealthService.getWorkerHealth(nonExistentWorkerId);

        // Then
        assertFalse(workerHealth.isPresent());
    }

    @Test
    void testMultipleWorkersOfDifferentTypes() {
        // Given
        String audioWorkerId = "audio-worker-1";
        String textWorkerId = "text-worker-1";
        
        // When
        workerHealthService.registerWorker(audioWorkerId, "audio_processing");
        workerHealthService.registerWorker(textWorkerId, "text_processing");

        // Then
        Optional<WorkerHealthService.WorkerHealth> audioWorker = 
                workerHealthService.getWorkerHealth(audioWorkerId);
        Optional<WorkerHealthService.WorkerHealth> textWorker = 
                workerHealthService.getWorkerHealth(textWorkerId);
        
        assertTrue(audioWorker.isPresent());
        assertTrue(textWorker.isPresent());
        assertEquals("audio_processing", audioWorker.get().getWorkerType());
        assertEquals("text_processing", textWorker.get().getWorkerType());
        
        List<WorkerHealthService.WorkerHealth> activeWorkers = 
                workerHealthService.getActiveWorkers();
        assertEquals(2, activeWorkers.size());
    }
}
package ai.curasnap.backend.service.interfaces;

import java.util.Map;

/**
 * Interface for providing queue statistics without circular dependency.
 * Implemented by JobService to provide queue information to other services.
 */
public interface QueueStatsProvider {
    
    /**
     * Get statistics for a specific queue
     *
     * @param queueName the name of the queue
     * @return map containing queue statistics (size, oldest job, etc.)
     */
    Map<String, Object> getQueueStats(String queueName);
}
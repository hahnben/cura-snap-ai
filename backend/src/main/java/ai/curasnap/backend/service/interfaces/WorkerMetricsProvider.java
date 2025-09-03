package ai.curasnap.backend.service.interfaces;

/**
 * Interface for providing worker metrics without circular dependency.
 * Implemented by WorkerHealthService to provide worker information to other services.
 */
public interface WorkerMetricsProvider {
    
    /**
     * Record job processing statistics for a worker
     *
     * @param workerId unique worker identifier
     * @param success whether the job was successful
     * @param processingTime processing time in milliseconds
     */
    void recordJobProcessing(String workerId, boolean success, long processingTime);
    
    /**
     * Check if a specific worker is healthy
     *
     * @param workerId unique worker identifier
     * @return true if worker is healthy and responsive
     */
    boolean isWorkerHealthy(String workerId);
    
    /**
     * Get the count of currently active workers
     *
     * @return number of active workers
     */
    int getActiveWorkerCount();
}
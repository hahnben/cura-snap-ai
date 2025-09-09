import { apiClient } from './api.client';

// Backend DTO interfaces matching Java classes
export interface NoteRequest {
  textRaw: string;
  sessionId?: string;
  transcriptId?: string;
}

export interface JobResponse {
  jobId: string;
  jobType: string;
  status: string;
  createdAt: string;
  estimatedCompletionAt?: string;
  queuePosition?: number;
  statusUrl: string;
  message?: string;
}

export interface JobStatusResponse {
  jobId: string;
  jobType: string;
  status: 'QUEUED' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
  createdAt: string;
  startedAt?: string;
  completedAt?: string;
  result?: {
    noteResponse?: any;
    transcriptId?: string;
  };
  errorMessage?: string;
  progressPercentage?: number;
  progressMessage?: string;
  retryCount: number;
  maxRetries: number;
  processingTimeMs?: number;
  queuePosition?: number;
  estimatedCompletionAt?: string;
}

export interface SOAPResult {
  subjective: string;
  objective: string;
  assessment: string;
  plan: string;
  [key: string]: any; // For additional fields
}

class TextProcessingService {
  private readonly basePath = '/api/v1/async';

  /**
   * Submit text for SOAP note generation
   * Returns job ID for polling status
   */
  async submitText(textRaw: string, sessionId?: string): Promise<JobResponse> {
    try {
      const request: NoteRequest = {
        textRaw: textRaw.trim(),
        sessionId,
      };

      return await apiClient.post<JobResponse>(
        `${this.basePath}/notes/format`,
        request
      );
    } catch (error) {
      console.error('Failed to submit text for processing:', error);
      throw error;
    }
  }

  /**
   * Check job status by job ID
   * Used for polling until completion
   */
  async checkJobStatus(jobId: string): Promise<JobStatusResponse> {
    try {
      return await apiClient.get<JobStatusResponse>(
        `${this.basePath}/jobs/${jobId}`
      );
    } catch (error) {
      console.error('Failed to check job status:', error);
      throw error;
    }
  }

  /**
   * Cancel a queued job
   * Only works for jobs in QUEUED status
   */
  async cancelJob(jobId: string): Promise<void> {
    try {
      await apiClient.delete(`${this.basePath}/jobs/${jobId}`);
    } catch (error) {
      console.error('Failed to cancel job:', error);
      throw error;
    }
  }

  /**
   * Poll job status until completion
   * Returns final result or throws on failure
   */
  async pollJobUntilComplete(
    jobId: string,
    onProgress?: (status: JobStatusResponse) => void,
    timeoutMs: number = 300000 // 5 minutes default
  ): Promise<SOAPResult> {
    const startTime = Date.now();
    const pollInterval = 2000; // 2 seconds

    return new Promise((resolve, reject) => {
      const poll = async () => {
        try {
          // Check timeout
          if (Date.now() - startTime > timeoutMs) {
            reject(new Error('Job polling timeout'));
            return;
          }

          const status = await this.checkJobStatus(jobId);
          
          // Notify progress callback
          if (onProgress) {
            onProgress(status);
          }

          switch (status.status) {
            case 'COMPLETED':
              if (status.result?.noteResponse) {
                resolve(status.result.noteResponse as SOAPResult);
              } else {
                reject(new Error('Job completed but no result available'));
              }
              return;

            case 'FAILED':
              reject(new Error(status.errorMessage || 'Job processing failed'));
              return;

            case 'CANCELLED':
              reject(new Error('Job was cancelled'));
              return;

            case 'QUEUED':
            case 'PROCESSING':
              // Continue polling
              setTimeout(poll, pollInterval);
              break;

            default:
              reject(new Error(`Unknown job status: ${status.status}`));
              return;
          }
        } catch (error) {
          reject(error);
        }
      };

      // Start polling
      poll();
    });
  }

  /**
   * Process text and wait for result (convenience method)
   * Combines submitText and pollJobUntilComplete
   */
  async processTextToSOAP(
    textRaw: string,
    sessionId?: string,
    onProgress?: (status: JobStatusResponse) => void,
    timeoutMs?: number
  ): Promise<SOAPResult> {
    try {
      // Submit text for processing
      const jobResponse = await this.submitText(textRaw, sessionId);
      
      // Poll until completion
      return await this.pollJobUntilComplete(
        jobResponse.jobId,
        onProgress,
        timeoutMs
      );
    } catch (error) {
      console.error('Failed to process text to SOAP:', error);
      throw error;
    }
  }

  /**
   * Get user's job history with pagination
   */
  async getUserJobs(limit: number = 20, offset: number = 0): Promise<JobStatusResponse[]> {
    try {
      return await apiClient.get<JobStatusResponse[]>(
        `${this.basePath}/jobs`,
        { limit, offset }
      );
    } catch (error) {
      console.error('Failed to get user jobs:', error);
      throw error;
    }
  }
}

export const textProcessingService = new TextProcessingService();
export default textProcessingService;
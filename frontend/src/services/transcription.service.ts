import { apiClient } from './api.client';

// Backend DTO interfaces matching Java classes for transcription
export interface TranscriptionJobResponse {
  jobId: string;
  jobType: string;
  status: string;
  createdAt: string;
  estimatedCompletionAt?: string;
  queuePosition?: number;
  statusUrl: string;
  message?: string;
}

export interface TranscriptionJobStatusResponse {
  jobId: string;
  jobType: string;
  status: 'QUEUED' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
  createdAt: string;
  startedAt?: string;
  completedAt?: string;
  result?: {
    transcript?: string;
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

export interface TranscriptionResult {
  transcript: string;
  transcriptId?: string;
  processingTimeMs?: number;
}

class TranscriptionService {
  private readonly basePath = '/api/v1/async';

  /**
   * Submit audio file for transcription
   * Returns job ID for polling status
   */
  async submitAudio(audioFile: File, sessionId?: string): Promise<TranscriptionJobResponse> {
    try {
      // Validate audio file before upload
      this.validateAudioFile(audioFile);

      // Create FormData manually to match backend expectations
      const formData = new FormData();
      formData.append('audio', audioFile); // Backend expects 'audio' parameter

      if (sessionId) {
        formData.append('sessionId', sessionId);
      }

      // Use post method with multipart/form-data
      return await apiClient.post<TranscriptionJobResponse>(
        `${this.basePath}/transcribe`,
        formData,
        {
          headers: {
            'Content-Type': 'multipart/form-data',
          },
          timeout: 120000, // 2 minutes for audio uploads
        }
      );
    } catch (error) {
      console.error('Failed to submit audio for transcription:', error);
      throw error;
    }
  }

  /**
   * Check transcription job status by job ID
   * Used for polling until completion
   */
  async checkJobStatus(jobId: string): Promise<TranscriptionJobStatusResponse> {
    try {
      return await apiClient.get<TranscriptionJobStatusResponse>(
        `${this.basePath}/jobs/${jobId}`
      );
    } catch (error) {
      console.error('Failed to check transcription job status:', error);
      throw error;
    }
  }

  /**
   * Cancel a queued transcription job
   * Only works for jobs in QUEUED status
   */
  async cancelJob(jobId: string): Promise<void> {
    try {
      await apiClient.delete(`${this.basePath}/jobs/${jobId}`);
    } catch (error) {
      console.error('Failed to cancel transcription job:', error);
      throw error;
    }
  }

  /**
   * Poll transcription job status until completion
   * Returns final transcript or throws on failure
   */
  async pollJobUntilComplete(
    jobId: string,
    onProgress?: (status: TranscriptionJobStatusResponse) => void,
    timeoutMs: number = 180000 // 3 minutes default for transcription
  ): Promise<TranscriptionResult> {
    const startTime = Date.now();
    const pollInterval = 1500; // 1.5 seconds for transcription (faster polling)

    return new Promise((resolve, reject) => {
      const poll = async () => {
        try {
          // Check timeout
          if (Date.now() - startTime > timeoutMs) {
            reject(new Error('Transcription job polling timeout'));
            return;
          }

          const status = await this.checkJobStatus(jobId);

          // Notify progress callback
          if (onProgress) {
            onProgress(status);
          }

          switch (status.status) {
            case 'COMPLETED':
              if (status.result?.transcript) {
                resolve({
                  transcript: status.result.transcript,
                  transcriptId: status.result.transcriptId,
                  processingTimeMs: status.processingTimeMs
                });
              } else {
                reject(new Error('Transcription completed but no transcript available'));
              }
              return;

            case 'FAILED':
              reject(new Error(status.errorMessage || 'Transcription processing failed'));
              return;

            case 'CANCELLED':
              reject(new Error('Transcription job was cancelled'));
              return;

            case 'QUEUED':
            case 'PROCESSING':
              // Continue polling
              setTimeout(poll, pollInterval);
              break;

            default:
              reject(new Error(`Unknown transcription job status: ${status.status}`));
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
   * Process audio file and wait for transcript (convenience method)
   * Combines submitAudio and pollJobUntilComplete
   */
  async transcribeAudio(
    audioFile: File,
    sessionId?: string,
    onProgress?: (status: TranscriptionJobStatusResponse) => void,
    timeoutMs?: number
  ): Promise<TranscriptionResult> {
    try {
      // Submit audio for transcription
      const jobResponse = await this.submitAudio(audioFile, sessionId);

      // Poll until completion
      return await this.pollJobUntilComplete(
        jobResponse.jobId,
        onProgress,
        timeoutMs
      );
    } catch (error) {
      console.error('Failed to transcribe audio:', error);
      throw error;
    }
  }

  /**
   * Validate audio file before upload
   * Throws error if validation fails
   */
  private validateAudioFile(audioFile: File): void {
    // Check if file exists
    if (!audioFile) {
      throw new Error('No audio file provided');
    }

    // Check file size (25MB limit matching backend)
    const maxFileSize = 25 * 1024 * 1024; // 25MB
    if (audioFile.size > maxFileSize) {
      throw new Error(`Audio file too large: ${Math.round(audioFile.size / 1024 / 1024)}MB (max: 25MB)`);
    }

    // Check minimum file size
    if (audioFile.size < 1024) { // 1KB minimum
      throw new Error('Audio file too small (minimum: 1KB)');
    }

    // Check allowed file extensions
    const allowedExtensions = ['.mp3', '.wav', '.webm', '.m4a', '.ogg', '.flac'];
    const fileExtension = this.getFileExtension(audioFile.name).toLowerCase();

    if (!allowedExtensions.includes(fileExtension)) {
      throw new Error(`Unsupported audio format: ${fileExtension}. Allowed: ${allowedExtensions.join(', ')}`);
    }

    // Check MIME type
    const allowedMimeTypes = [
      'audio/mpeg', 'audio/mp3', 'audio/wav', 'audio/wave', 'audio/x-wav',
      'audio/webm', 'audio/mp4', 'audio/m4a', 'audio/ogg', 'audio/flac'
    ];

    if (audioFile.type && !allowedMimeTypes.includes(audioFile.type.toLowerCase())) {
      console.warn(`Unexpected MIME type: ${audioFile.type}. Proceeding with upload.`);
    }
  }

  /**
   * Extract file extension from filename
   */
  private getFileExtension(filename: string): string {
    const lastDotIndex = filename.lastIndexOf('.');
    return lastDotIndex > 0 ? filename.substring(lastDotIndex) : '';
  }

  /**
   * Get estimated transcription time based on audio duration
   * This is a rough estimate for user feedback
   */
  getEstimatedTranscriptionTime(audioFile: File): Promise<number> {
    return new Promise((resolve) => {
      // Create audio element to get duration
      const audio = new Audio();
      const url = URL.createObjectURL(audioFile);

      audio.addEventListener('loadedmetadata', () => {
        const durationSeconds = audio.duration;
        // Rough estimate: transcription takes about 10-30% of audio duration
        const estimatedSeconds = Math.max(5, Math.ceil(durationSeconds * 0.2));

        URL.revokeObjectURL(url);
        resolve(estimatedSeconds);
      });

      audio.addEventListener('error', () => {
        URL.revokeObjectURL(url);
        // Default estimate if we can't determine duration
        resolve(30);
      });

      audio.src = url;
    });
  }

  /**
   * Get user's transcription job history with pagination
   */
  async getUserTranscriptionJobs(limit: number = 20, offset: number = 0): Promise<TranscriptionJobStatusResponse[]> {
    try {
      const allJobs = await apiClient.get<TranscriptionJobStatusResponse[]>(
        `${this.basePath}/jobs`,
        { limit: limit * 2, offset } // Get more jobs to filter
      );

      // Filter for transcription-only jobs
      return allJobs
        .filter(job => job.jobType === 'TRANSCRIPTION_ONLY')
        .slice(0, limit);
    } catch (error) {
      console.error('Failed to get user transcription jobs:', error);
      throw error;
    }
  }
}

export const transcriptionService = new TranscriptionService();
export default transcriptionService;
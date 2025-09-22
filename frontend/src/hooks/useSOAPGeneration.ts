import { useCallback } from 'react';
import { textProcessingService } from '../services/text-processing.service';
import type { JobStatusResponse, SOAPResult } from '../services/text-processing.service';

export interface SOAPGenerationCallbacks {
  onStart?: () => void;
  onProgress?: (status: JobStatusResponse) => void;
  onSuccess?: (result: SOAPResult) => void;
  onError?: (error: string) => void;
  onComplete?: () => void;
}

export interface SOAPGenerationOptions {
  sessionId?: string;
}

/**
 * Custom hook for SOAP note generation from unstructured text.
 * Provides a clean interface for the SOAP generation business logic
 * without any UI-specific state management.
 */
export function useSOAPGeneration() {
  const generateSOAP = useCallback(async (
    inputText: string,
    callbacks: SOAPGenerationCallbacks = {},
    options: SOAPGenerationOptions = {}
  ): Promise<SOAPResult> => {
    const {
      onStart,
      onProgress,
      onSuccess,
      onError,
      onComplete,
    } = callbacks;

    const { sessionId } = options;

    // Validate input
    if (!inputText.trim()) {
      const error = 'Eingabetext darf nicht leer sein';
      onError?.(error);
      throw new Error(error);
    }

    try {
      // Notify start
      onStart?.();

      // Process text to SOAP
      const result = await textProcessingService.processTextToSOAP(
        inputText.trim(),
        sessionId,
        (status: JobStatusResponse) => {
          // Forward progress updates
          onProgress?.(status);
        }
      );

      // Notify success
      onSuccess?.(result);
      return result;

    } catch (error) {
      // Handle and forward errors
      const errorMessage = error instanceof Error ? error.message : 'Unbekannter Fehler bei der SOAP-Generierung';
      onError?.(errorMessage);
      throw error;

    } finally {
      // Always notify completion
      onComplete?.();
    }
  }, []);

  return {
    generateSOAP,
  };
}

// Type exports for consumers
export type { SOAPResult, JobStatusResponse } from '../services/text-processing.service';
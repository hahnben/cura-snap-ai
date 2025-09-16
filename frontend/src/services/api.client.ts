import axios, { type AxiosInstance, type AxiosResponse, AxiosError } from 'axios';
import { createClient } from '@supabase/supabase-js';
import { validateAudioFile, validateGeneralFile, validateAudioFileHeader } from '../utils/fileValidation';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';
const REQUEST_TIMEOUT = 30000; // 30 seconds

// Create Supabase client for token access
const supabaseUrl = import.meta.env.VITE_SUPABASE_URL;
const supabaseAnonKey = import.meta.env.VITE_SUPABASE_ANON_KEY;

if (!supabaseUrl || !supabaseAnonKey) {
  throw new Error('Missing Supabase environment variables. Please check your .env.local file.');
}
const supabase = createClient(supabaseUrl, supabaseAnonKey);

class ApiClient {
  private client: AxiosInstance;

  constructor() {
    this.client = axios.create({
      baseURL: API_BASE_URL,
      timeout: REQUEST_TIMEOUT,
      headers: {
        'Content-Type': 'application/json',
      },
    });

    this.setupInterceptors();
  }

  private setupInterceptors() {
    // Request interceptor - Add auth token from Supabase session
    this.client.interceptors.request.use(
      async (config) => {
        try {
          const { data: { session } } = await supabase.auth.getSession();
          
          if (session?.access_token) {
            config.headers.Authorization = `Bearer ${session.access_token}`;

            // 🔍 JWT SESSION DEBUGGING (Development only)
            if (import.meta.env.DEV) {
              console.group('🔍 JWT Session Debug');
              console.log('Session exists:', !!session);
              console.log('User ID:', session?.user?.id);
              console.log('User email:', session?.user?.email);
              console.log('✅ Authorization header set');
              console.groupEnd();
            }
          } else if (import.meta.env.DEV) {
            console.warn('❌ No access token available');
          }
          
        } catch (error) {
          console.error('❌ Failed to get Supabase session:', error);
        }
        return config;
      },
      (error) => {
        console.error('Request interceptor error:', error);
        return Promise.reject(error);
      }
    );

    // Response interceptor - Handle errors
    this.client.interceptors.response.use(
      (response: AxiosResponse) => response,
      async (error: AxiosError) => {
        // 🔍 API ERROR DEBUGGING (Development only)
        if (import.meta.env.DEV) {
          console.group('🚨 API Error Debug');
          console.error('Status:', error.response?.status);
          console.error('URL:', error.config?.url);
          console.error('Method:', error.config?.method?.toUpperCase());
          console.error('Headers sent:', error.config?.headers);
          console.error('Response data:', error.response?.data);
          console.error('Full error:', error);
          console.groupEnd();
        }

        // Handle specific error cases
        if (error.response?.status === 401) {
          // Unauthorized - sign out and redirect to login
          try {
            await supabase.auth.signOut();
          } catch (signOutError) {
            console.warn('Failed to sign out:', signOutError);
          }
          window.location.href = '/login';
          return Promise.reject(new Error('Session expired. Please log in again.'));
        }

        if (error.response?.status === 403) {
          return Promise.reject(new Error('Access denied. You do not have permission to perform this action.'));
        }

        if (error.response && error.response.status >= 500) {
          return Promise.reject(new Error('Server error. Please try again later.'));
        }

        if (error.code === 'ECONNABORTED' || error.message.includes('timeout')) {
          return Promise.reject(new Error('Request timeout. Please check your connection and try again.'));
        }

        if (!error.response) {
          return Promise.reject(new Error('Network error. Please check your connection and try again.'));
        }

        // Default error handling
        const message = (error.response?.data as any)?.message || error.message || 'An unexpected error occurred.';
        return Promise.reject(new Error(message));
      }
    );
  }

  // HTTP Methods
  async get<T>(url: string, params?: Record<string, any>): Promise<T> {
    const response = await this.client.get<T>(url, { params });
    return response.data;
  }

  async post<T>(url: string, data?: any, config?: any): Promise<T> {
    const response = await this.client.post<T>(url, data, config);
    return response.data;
  }

  async put<T>(url: string, data?: any): Promise<T> {
    const response = await this.client.put<T>(url, data);
    return response.data;
  }

  async delete<T>(url: string): Promise<T> {
    const response = await this.client.delete<T>(url);
    return response.data;
  }

  async patch<T>(url: string, data?: any): Promise<T> {
    const response = await this.client.patch<T>(url, data);
    return response.data;
  }

  // Secure file upload helper with validation
  async uploadFile<T>(
    url: string,
    file: File | Blob,
    fileName?: string,
    additionalData?: Record<string, any>,
    options?: {
      validateAsAudio?: boolean;
      maxSize?: number;
      skipHeaderValidation?: boolean;
    }
  ): Promise<T> {
    // Convert Blob to File for validation if needed
    let fileToValidate: File;
    if (file instanceof File) {
      fileToValidate = file;
    } else {
      fileToValidate = new File([file], fileName || 'recording.webm', {
        type: 'audio/webm'
      });
    }

    // Validate file based on type
    let validationResult;
    if (options?.validateAsAudio) {
      validationResult = validateAudioFile(fileToValidate);

      // Additional header validation for audio files
      if (validationResult.isValid && !options.skipHeaderValidation) {
        const isValidHeader = await validateAudioFileHeader(fileToValidate);
        if (!isValidHeader) {
          throw new Error('Invalid audio file format detected. File may be corrupted or not a valid audio file.');
        }
      }
    } else {
      validationResult = validateGeneralFile(fileToValidate, options?.maxSize);
    }

    if (!validationResult.isValid) {
      throw new Error(validationResult.error || 'File validation failed');
    }

    const formData = new FormData();

    // Use sanitized filename if available
    if (file instanceof File) {
      const sanitizedName = validationResult.sanitizedFileName || file.name;
      formData.append('file', file, sanitizedName);
    } else {
      const sanitizedName = validationResult.sanitizedFileName || fileName || 'recording.webm';
      formData.append('file', file, sanitizedName);
    }

    // Add additional form data with validation
    if (additionalData) {
      Object.entries(additionalData).forEach(([key, value]) => {
        // Sanitize form data keys and values
        const sanitizedKey = key.replace(/[^a-zA-Z0-9_]/g, '_');
        const sanitizedValue = String(value).substring(0, 1000); // Limit value length
        formData.append(sanitizedKey, sanitizedValue);
      });
    }

    const response = await this.client.post<T>(url, formData, {
      headers: {
        'Content-Type': 'multipart/form-data',
      },
      timeout: 120000, // 2 minutes for file uploads
    });

    return response.data;
  }

  // Convenience method for audio uploads with strict validation
  async uploadAudioFile<T>(
    url: string,
    audioFile: File | Blob,
    fileName?: string,
    additionalData?: Record<string, any>
  ): Promise<T> {
    return this.uploadFile<T>(url, audioFile, fileName, additionalData, {
      validateAsAudio: true,
      skipHeaderValidation: false,
    });
  }
}

// Export singleton instance
export const apiClient = new ApiClient();
export default apiClient;
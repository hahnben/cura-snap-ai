import axios, { type AxiosInstance, type AxiosResponse, AxiosError } from 'axios';
import { createClient } from '@supabase/supabase-js';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';
const REQUEST_TIMEOUT = 30000; // 30 seconds

// Create Supabase client for token access
const supabaseUrl = import.meta.env.VITE_SUPABASE_URL || 'http://localhost:54321';
const supabaseAnonKey = import.meta.env.VITE_SUPABASE_ANON_KEY || 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZS1kZW1vIiwicm9sZSI6ImFub24iLCJleHAiOjE5ODM4MTI5OTZ9.CRXP1A7WOeoJeXxjNni43kdQwgnWNReilDMblYTn_I0';
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
          
          // 🔍 JWT SESSION DEBUGGING
          console.group('🔍 JWT Session Debug');
          console.log('Session exists:', !!session);
          console.log('User ID:', session?.user?.id);
          console.log('User email:', session?.user?.email);
          console.log('Token exists:', !!session?.access_token);
          if (session?.access_token) {
            // Decode JWT to see claims (basic parsing)
            try {
              const payload = JSON.parse(atob(session.access_token.split('.')[1]));
              console.log('JWT Claims:', {
                role: payload.role,
                aud: payload.aud,
                exp: new Date(payload.exp * 1000),
                iat: new Date(payload.iat * 1000),
                email: payload.email,
                sub: payload.sub
              });
            } catch (jwtError) {
              console.warn('Could not decode JWT:', jwtError);
            }
            config.headers.Authorization = `Bearer ${session.access_token}`;
            console.log('✅ Authorization header set');
          } else {
            console.warn('❌ No access token available');
          }
          console.groupEnd();
          
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
        // 🔍 API ERROR DEBUGGING
        console.group('🚨 API Error Debug');
        console.error('Status:', error.response?.status);
        console.error('URL:', error.config?.url);
        console.error('Method:', error.config?.method?.toUpperCase());
        console.error('Headers sent:', error.config?.headers);
        console.error('Response data:', error.response?.data);
        console.error('Full error:', error);
        console.groupEnd();

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

  // File upload helper
  async uploadFile<T>(
    url: string,
    file: File | Blob,
    fileName?: string,
    additionalData?: Record<string, any>
  ): Promise<T> {
    const formData = new FormData();
    
    if (file instanceof File) {
      formData.append('file', file);
    } else {
      formData.append('file', file, fileName || 'recording.webm');
    }

    // Add additional form data
    if (additionalData) {
      Object.entries(additionalData).forEach(([key, value]) => {
        formData.append(key, String(value));
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
}

// Export singleton instance
export const apiClient = new ApiClient();
export default apiClient;
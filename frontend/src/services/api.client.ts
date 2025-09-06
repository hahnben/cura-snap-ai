import axios, { AxiosInstance, AxiosResponse, AxiosError } from 'axios';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';
const REQUEST_TIMEOUT = 30000; // 30 seconds

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
    // Request interceptor - Add auth token
    this.client.interceptors.request.use(
      (config) => {
        const token = sessionStorage.getItem('supabase.auth.token');
        if (token) {
          config.headers.Authorization = `Bearer ${token}`;
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
      (error: AxiosError) => {
        console.error('API Error:', error);

        // Handle specific error cases
        if (error.response?.status === 401) {
          // Unauthorized - redirect to login
          sessionStorage.removeItem('supabase.auth.token');
          window.location.href = '/login';
          return Promise.reject(new Error('Session expired. Please log in again.'));
        }

        if (error.response?.status === 403) {
          return Promise.reject(new Error('Access denied. You do not have permission to perform this action.'));
        }

        if (error.response?.status >= 500) {
          return Promise.reject(new Error('Server error. Please try again later.'));
        }

        if (error.code === 'ECONNABORTED' || error.message.includes('timeout')) {
          return Promise.reject(new Error('Request timeout. Please check your connection and try again.'));
        }

        if (!error.response) {
          return Promise.reject(new Error('Network error. Please check your connection and try again.'));
        }

        // Default error handling
        const message = error.response?.data?.message || error.message || 'An unexpected error occurred.';
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
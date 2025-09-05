// Medical domain types for CuraSnap AI

export interface User {
  id: string;
  email: string;
  created_at: string;
}

export interface Session {
  id: string;
  user_id: string;
  start_time: string;
  end_time?: string;
  status: 'active' | 'completed' | 'archived';
  created_at: string;
  updated_at: string;
}

export interface Transcript {
  id: string;
  session_id: string;
  content: string;
  created_at: string;
  updated_at: string;
}

export interface SoapNote {
  id: string;
  transcript_id: string;
  soap_data: {
    subjective: string;
    objective: string;
    assessment: string;
    plan: string;
  };
  created_at: string;
  updated_at: string;
}

export interface ApiResponse<T> {
  data: T;
  message?: string;
  status: 'success' | 'error';
}

export interface Session {
  id: string;
  patientName?: string;
  createdAt: string;
  updatedAt: string;
  status: 'active' | 'completed';
  userId: string;
}

export interface Message {
  id: string;
  sessionId: string;
  content: string;
  type: 'user' | 'assistant' | 'system';
  source: 'text' | 'audio';
  createdAt: string;
}

export interface SOAPNote {
  id: string;
  sessionId: string;
  subjective: string;
  objective: string;
  assessment: string;
  plan: string;
  createdAt: string;
  updatedAt: string;
}

export interface AudioRecording {
  id: string;
  sessionId: string;
  audioUrl: string;
  transcription?: string;
  duration: number;
  createdAt: string;
}
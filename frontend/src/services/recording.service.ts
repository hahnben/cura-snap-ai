import { apiClient } from './api.client';
import { Message, SOAPNote, AudioRecording } from '../types/session';
import { v4 as uuidv4 } from 'uuid';

interface ProcessAudioResponse {
  message: Message;
  soapNote: SOAPNote;
  audioRecording: AudioRecording;
}

interface GenerateSOAPResponse {
  soapNote: SOAPNote;
}

class RecordingService {
  private readonly basePath = '/api/recordings';

  async processAudio(sessionId: string, audioBlob: Blob): Promise<{ message: Message; soapNote: SOAPNote }> {
    try {
      // Use secure audio upload with validation
      const response = await apiClient.uploadAudioFile<ProcessAudioResponse>(
        `${this.basePath}/process`,
        audioBlob,
        `recording-${Date.now()}.webm`,
        { sessionId }
      );
      
      return {
        message: response.message,
        soapNote: response.soapNote,
      };
    } catch (error) {
      console.error('Failed to process audio:', error);
      
      // In development or fallback mode, create mock responses
      if (import.meta.env.DEV) {
        const mockMessage: Message = {
          id: uuidv4(),
          sessionId,
          content: '[Audio recording processed - Mock transcription for development]',
          type: 'user',
          source: 'audio',
          createdAt: new Date().toISOString(),
        };
        
        const mockSOAP: SOAPNote = {
          id: uuidv4(),
          sessionId,
          subjective: 'Patient reports chief complaint and symptoms (Mock data for development)',
          objective: 'Clinical findings and vital signs (Mock data for development)',
          assessment: 'Medical assessment and diagnosis (Mock data for development)',
          plan: 'Treatment plan and follow-up (Mock data for development)',
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        };
        
        return {
          message: mockMessage,
          soapNote: mockSOAP,
        };
      }
      
      throw error;
    }
  }

  async generateSOAP(sessionId: string): Promise<SOAPNote> {
    try {
      const response = await apiClient.post<GenerateSOAPResponse>(
        `/api/sessions/${sessionId}/soap`,
        {}
      );
      
      return response.soapNote;
    } catch (error) {
      console.error('Failed to generate SOAP note:', error);
      
      // In development or fallback mode, create mock SOAP note
      if (import.meta.env.DEV) {
        const mockSOAP: SOAPNote = {
          id: uuidv4(),
          sessionId,
          subjective: 'Patient reports chief complaint and symptoms (Regenerated mock data)',
          objective: 'Clinical findings and vital signs (Regenerated mock data)',
          assessment: 'Medical assessment and diagnosis (Regenerated mock data)',
          plan: 'Treatment plan and follow-up (Regenerated mock data)',
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        };
        
        return mockSOAP;
      }
      
      throw error;
    }
  }

  async transcribeAudio(audioBlob: Blob): Promise<string> {
    try {
      // Use secure audio upload with validation
      const response = await apiClient.uploadAudioFile<{ transcription: string }>(
        '/api/transcription/transcribe',
        audioBlob,
        `audio-${Date.now()}.webm`
      );
      
      return response.transcription;
    } catch (error) {
      console.error('Failed to transcribe audio:', error);
      
      // Fallback transcription in development
      if (import.meta.env.DEV) {
        return '[Mock transcription for development - audio transcription not available]';
      }
      
      throw error;
    }
  }

  async getRecording(recordingId: string): Promise<AudioRecording> {
    try {
      return await apiClient.get<AudioRecording>(`${this.basePath}/${recordingId}`);
    } catch (error) {
      console.error('Failed to get recording:', error);
      throw error;
    }
  }

  async getSessionRecordings(sessionId: string): Promise<AudioRecording[]> {
    try {
      return await apiClient.get<AudioRecording[]>(`/api/sessions/${sessionId}/recordings`);
    } catch (error) {
      console.error('Failed to get session recordings:', error);
      throw error;
    }
  }

  async deleteRecording(recordingId: string): Promise<void> {
    try {
      await apiClient.delete(`${this.basePath}/${recordingId}`);
    } catch (error) {
      console.error('Failed to delete recording:', error);
      throw error;
    }
  }
}

export const recordingService = new RecordingService();
export default recordingService;
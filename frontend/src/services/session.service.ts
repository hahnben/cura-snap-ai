import { apiClient } from './api.client';
import { Session, Message } from '../types/session';
import { v4 as uuidv4 } from 'uuid';

interface CreateSessionRequest {
  patientName?: string;
}

interface CreateSessionResponse {
  session: Session;
}

interface SendMessageRequest {
  content: string;
  source: 'text' | 'audio';
}

interface SendMessageResponse {
  message: Message;
}

class SessionService {
  private readonly basePath = '/api/sessions';

  async createSession(data: CreateSessionRequest): Promise<Session> {
    try {
      const response = await apiClient.post<CreateSessionResponse>(this.basePath, data);
      return response.session;
    } catch (error) {
      console.error('Failed to create session:', error);
      
      // Fallback to local session creation in case backend is unavailable
      const fallbackSession: Session = {
        id: uuidv4(),
        patientName: data.patientName,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
        status: 'active',
        userId: 'local-user', // This would come from auth in real implementation
      };
      
      return fallbackSession;
    }
  }

  async getSession(sessionId: string): Promise<Session> {
    try {
      return await apiClient.get<Session>(`${this.basePath}/${sessionId}`);
    } catch (error) {
      console.error('Failed to get session:', error);
      throw error;
    }
  }

  async updateSession(sessionId: string, updates: Partial<Session>): Promise<Session> {
    try {
      return await apiClient.patch<Session>(`${this.basePath}/${sessionId}`, updates);
    } catch (error) {
      console.error('Failed to update session:', error);
      throw error;
    }
  }

  async deleteSession(sessionId: string): Promise<void> {
    try {
      await apiClient.delete(`${this.basePath}/${sessionId}`);
    } catch (error) {
      console.error('Failed to delete session:', error);
      throw error;
    }
  }

  async sendMessage(
    sessionId: string,
    content: string,
    source: 'text' | 'audio'
  ): Promise<Message> {
    try {
      const response = await apiClient.post<SendMessageResponse>(
        `${this.basePath}/${sessionId}/messages`,
        { content, source }
      );
      return response.message;
    } catch (error) {
      console.error('Failed to send message:', error);
      
      // Fallback to local message creation
      const fallbackMessage: Message = {
        id: uuidv4(),
        sessionId,
        content,
        type: 'user',
        source,
        createdAt: new Date().toISOString(),
      };
      
      return fallbackMessage;
    }
  }

  async getMessages(sessionId: string): Promise<Message[]> {
    try {
      return await apiClient.get<Message[]>(`${this.basePath}/${sessionId}/messages`);
    } catch (error) {
      console.error('Failed to get messages:', error);
      throw error;
    }
  }

  async getUserSessions(): Promise<Session[]> {
    try {
      return await apiClient.get<Session[]>(this.basePath);
    } catch (error) {
      console.error('Failed to get user sessions:', error);
      throw error;
    }
  }
}

export const sessionService = new SessionService();
export default sessionService;
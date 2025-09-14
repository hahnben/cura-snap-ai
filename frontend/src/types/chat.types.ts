import type { SOAPResult } from '../services/text-processing.service';

/**
 * Unified message interface for chat functionality
 * Combines features from both DashboardPage and ChatInterface
 */
export interface ChatMessage {
  id: string;
  type: 'user' | 'assistant' | 'system';
  content: string;
  timestamp: Date;
  source?: 'text' | 'audio';
  soapResult?: SOAPResult;
  isProcessing?: boolean;
  error?: string;
  /** Recording duration in seconds for audio messages */
  recordingDuration?: number;
}

/**
 * Audio recording state interface
 */
export interface AudioRecordingState {
  isRecording: boolean;
  recordingTime: number;
  audioPermission: 'granted' | 'denied' | 'prompt';
}

/**
 * Chat interface component props
 */
export interface ChatInterfaceProps {
  /** Callback when a new message is sent */
  onMessage?: (message: ChatMessage) => void;
  /** Whether the chat is currently processing */
  isProcessing?: boolean;
  /** Custom placeholder text for input */
  placeholder?: string;
  /** Whether to show the header section */
  showHeader?: boolean;
  /** Whether to enable audio functionality */
  enableAudio?: boolean;
}

/**
 * Dashboard header component props
 */
export interface DashboardHeaderProps {
  /** Current user information */
  user: {
    email?: string;
  } | null;
  /** Sign out callback */
  onSignOut: () => Promise<void>;
  /** Session time remaining in milliseconds */
  timeRemaining: number;
  /** Current patient information */
  currentPatient?: {
    name: string;
  } | null;
}

/**
 * Audio controls component props
 */
export interface AudioControlsProps {
  /** Current recording state (optional - component can manage its own state) */
  recordingState?: AudioRecordingState;
  /** Start recording callback (optional - component handles if not provided) */
  onStartRecording?: () => Promise<void>;
  /** Stop recording callback (optional - component handles if not provided) */
  onStopRecording?: () => void;
  /** Callback when transcript is ready from voice recording */
  onTranscriptReady?: (transcript: string, transcriptId?: string) => void;
  /** Whether audio controls are disabled */
  disabled?: boolean;
}

/**
 * Message processing status
 */
export interface MessageProcessingStatus {
  isProcessing: boolean;
  statusMessage?: string;
  progress?: number;
}

/**
 * Chat session context
 */
export interface ChatSession {
  sessionId?: string;
  patientName?: string;
  messages: ChatMessage[];
  startedAt: Date;
}
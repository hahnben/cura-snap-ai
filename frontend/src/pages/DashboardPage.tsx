import { useState, useRef, useEffect } from 'react';
import {
  Box,
  LinearProgress,
  Alert,
  useMediaQuery,
  useTheme,
} from '@mui/material';
import { useAuth } from '../contexts/AuthContext';
import { useError } from '../contexts/ErrorContext';
import { usePatientSession, useInitializePatientSession } from '../contexts/PatientSessionContext';
import { DashboardHeader } from '../components/dashboard/DashboardHeader';
import { ChatInterface } from '../components/chat/ChatInterface';
import { ChatMessage, AudioRecordingState } from '../types/chat.types';


export function DashboardPage() {
  const theme = useTheme();
  const isMobile = useMediaQuery(theme.breakpoints.down('lg'));
  const { user, signOut, timeRemaining } = useAuth();
  const { showError } = useError();
  const { currentPatient } = usePatientSession();
  const initializePatientSession = useInitializePatientSession();
  
  // Simplified state - components manage their own state now
  const [isLoading, setIsLoading] = useState(false);
  const [audioPermission, setAudioPermission] = useState<'granted' | 'denied' | 'prompt'>('prompt');

  // Initialize patient session on component mount
  useEffect(() => {
    initializePatientSession();
  }, [initializePatientSession]);

  // Initialize audio permission check
  useEffect(() => {
    navigator.mediaDevices.getUserMedia({ audio: true })
      .then(() => setAudioPermission('granted'))
      .catch(() => setAudioPermission('denied'));
  }, []);

  // Callback handlers for components
  const handleNewMessage = (message: ChatMessage) => {
    // Handle new messages from chat interface
    console.log('New message:', message);
  };

  const handleAudioTranscription = (audioBlob: Blob, duration: number) => {
    // Handle audio transcription
    console.log('Audio transcription received:', audioBlob, duration);
    // TODO: Send to transcription service
  };


  return (
    <Box sx={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      {/* Header Component */}
      <DashboardHeader
        user={user}
        onSignOut={signOut}
        timeRemaining={timeRemaining}
        currentPatient={currentPatient}
      />

      {/* Main Content */}
      <Box sx={{ flexGrow: 1, display: 'flex', flexDirection: 'column', p: 2, gap: 2, overflow: 'hidden' }}>
        
        {/* Progress Indicator */}
        {isLoading && (
          <LinearProgress sx={{ borderRadius: 1 }} />
        )}

        {/* Audio Permission Warning */}
        {audioPermission === 'denied' && (
          <Alert severity="warning">
            Mikrofonzugriff verweigert. Für Audio-Aufnahmen aktivieren Sie bitte die Mikrofon-Berechtigung.
          </Alert>
        )}

        {/* Chat Interface Component */}
        <Box sx={{ flexGrow: 1, overflow: 'hidden' }}>
          <ChatInterface
            onMessage={handleNewMessage}
            onAudioTranscription={handleAudioTranscription}
            isProcessing={isLoading}
            placeholder="Beschreiben Sie die Patientenbegegnung..."
            showHeader={false}
            enableAudio={true}
          />
        </Box>
      </Box>
    </Box>
  );
}
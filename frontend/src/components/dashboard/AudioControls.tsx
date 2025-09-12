import { useState, useRef, useEffect, useCallback, memo } from 'react';
import {
  Box,
  IconButton,
  Typography,
  Alert,
} from '@mui/material';
import {
  Mic,
  MicOff,
} from '@mui/icons-material';
import type { AudioControlsProps, AudioRecordingState } from '../../types/chat.types';
import { useError } from '../../contexts/ErrorContext';

const AudioControlsComponent = ({ 
  recordingState, 
  onStartRecording, 
  onStopRecording, 
  disabled = false 
}: AudioControlsProps) => {
  const { showError } = useError();
  const [internalRecordingState, setInternalRecordingState] = useState<AudioRecordingState>({
    isRecording: false,
    recordingTime: 0,
    audioPermission: 'prompt'
  });
  
  // Refs for MediaRecorder API
  const mediaRecorder = useRef<MediaRecorder | null>(null);
  const audioChunks = useRef<Blob[]>([]);
  const recordingInterval = useRef<NodeJS.Timeout | null>(null);

  // Use external state if provided, otherwise use internal state
  const currentState = recordingState || internalRecordingState;

  // Initialize audio permission check
  useEffect(() => {
    navigator.mediaDevices.getUserMedia({ audio: true })
      .then(() => {
        if (!recordingState) {
          setInternalRecordingState(prev => ({ ...prev, audioPermission: 'granted' }));
        }
      })
      .catch(() => {
        if (!recordingState) {
          setInternalRecordingState(prev => ({ ...prev, audioPermission: 'denied' }));
        }
      });
  }, [recordingState]);

  const handleStartRecording = useCallback(async () => {
    if (onStartRecording) {
      await onStartRecording();
      return;
    }

    // Default implementation when no external handler provided
    try {
      if (currentState.audioPermission !== 'granted') {
        await navigator.mediaDevices.getUserMedia({ audio: true });
        setInternalRecordingState(prev => ({ ...prev, audioPermission: 'granted' }));
      }

      const audioStream = await navigator.mediaDevices.getUserMedia({ audio: true });
      mediaRecorder.current = new MediaRecorder(audioStream);
      audioChunks.current = [];

      mediaRecorder.current.ondataavailable = (event) => {
        audioChunks.current.push(event.data);
      };

      mediaRecorder.current.onstop = () => {
        const audioBlob = new Blob(audioChunks.current, { type: 'audio/wav' });
        // TODO: Handle audio blob - this should be passed to parent via callback
        console.log('Audio recording completed:', audioBlob);
        audioStream.getTracks().forEach(track => track.stop());
      };

      mediaRecorder.current.start();
      setInternalRecordingState(prev => ({ 
        ...prev, 
        isRecording: true, 
        recordingTime: 0 
      }));

      recordingInterval.current = setInterval(() => {
        setInternalRecordingState(prev => ({ 
          ...prev, 
          recordingTime: prev.recordingTime + 1 
        }));
      }, 1000);

    } catch (error) {
      showError('Mikrofonzugriff fehlgeschlagen. Bitte überprüfen Sie die Berechtigungen.');
      setInternalRecordingState(prev => ({ ...prev, audioPermission: 'denied' }));
    }
  }, [onStartRecording, currentState.audioPermission, showError]);

  const handleStopRecording = useCallback(() => {
    if (onStopRecording) {
      onStopRecording();
      return;
    }

    // Default implementation when no external handler provided
    if (mediaRecorder.current && currentState.isRecording) {
      mediaRecorder.current.stop();
      setInternalRecordingState(prev => ({ ...prev, isRecording: false }));
      
      if (recordingInterval.current) {
        clearInterval(recordingInterval.current);
        recordingInterval.current = null;
      }
    }
  }, [onStopRecording, currentState.isRecording]);

  // Show audio permission warning
  if (currentState.audioPermission === 'denied') {
    return (
      <Box>
        <Alert severity="warning" sx={{ mb: 2 }}>
          Mikrofonzugriff verweigert. Für Audio-Aufnahmen aktivieren Sie bitte die Mikrofon-Berechtigung.
        </Alert>
        <Box>
          <IconButton
            color="primary"
            disabled={true}
            size="large"
          >
            <Mic />
          </IconButton>
        </Box>
      </Box>
    );
  }

  return (
    <Box>
      {currentState.isRecording ? (
        <IconButton
          color="error"
          onClick={handleStopRecording}
          size="large"
          disabled={disabled}
          aria-label="Aufnahme stoppen"
          sx={{ 
            animation: 'pulse 1.5s infinite',
            '@keyframes pulse': {
              '0%': { opacity: 1 },
              '50%': { opacity: 0.5 },
              '100%': { opacity: 1 },
            }
          }}
        >
          <MicOff />
        </IconButton>
      ) : (
        <IconButton
          color="primary"
          onClick={handleStartRecording}
          disabled={disabled || currentState.audioPermission !== 'granted'}
          size="large"
          aria-label="Aufnahme starten"
        >
          <Mic />
        </IconButton>
      )}
      {currentState.isRecording && (
        <Typography 
          variant="caption" 
          display="block" 
          textAlign="center"
          aria-live="polite"
          aria-label={`Aufnahmezeit: ${currentState.recordingTime} Sekunden`}
        >
          {currentState.recordingTime}s
        </Typography>
      )}
    </Box>
  );
};

// Memoized export to prevent unnecessary re-renders
export const AudioControls = memo(AudioControlsComponent);
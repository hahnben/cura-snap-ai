import { useState, useRef, useEffect, useCallback, memo } from 'react';
import {
  Box,
  IconButton,
  Typography,
  Alert,
  CircularProgress,
  Stack,
  Fade,
  Chip,
} from '@mui/material';
import {
  Mic,
  MicOff,
  Stop,
  CheckCircle,
  Error as ErrorIcon,
} from '@mui/icons-material';
import type { AudioControlsProps, AudioRecordingState } from '../../types/chat.types';
import { useError } from '../../contexts/ErrorContext';
import { transcriptionService, type TranscriptionJobStatusResponse } from '../../services/transcription.service';

// Enhanced recording states for medical UI
type RecordingPhase = 'idle' | 'recording' | 'stopping' | 'processing' | 'completed' | 'error';

interface EnhancedRecordingState extends AudioRecordingState {
  phase: RecordingPhase;
  transcript?: string;
  transcriptId?: string;
  errorMessage?: string;
  processingProgress?: number;
}

const AudioControlsComponent = ({
  recordingState,
  onStartRecording,
  onStopRecording,
  onTranscriptReady,
  disabled = false
}: AudioControlsProps) => {
  const { showError } = useError();

  // Enhanced internal state
  const [internalState, setInternalState] = useState<EnhancedRecordingState>({
    isRecording: false,
    recordingTime: 0,
    audioPermission: 'prompt',
    phase: 'idle'
  });

  // Refs for MediaRecorder API
  const mediaRecorder = useRef<MediaRecorder | null>(null);
  const audioChunks = useRef<Blob[]>([]);
  const recordingInterval = useRef<NodeJS.Timeout | null>(null);
  const audioStream = useRef<MediaStream | null>(null);

  // Use external state if provided, otherwise use internal state
  const currentState = recordingState ? {
    ...recordingState,
    phase: 'idle' as RecordingPhase,
    transcript: undefined,
    transcriptId: undefined,
    errorMessage: undefined,
    processingProgress: undefined
  } : internalState;

  // Initialize audio permission check
  useEffect(() => {
    const checkPermissions = async () => {
      try {
        await navigator.mediaDevices.getUserMedia({ audio: true });
        if (!recordingState) {
          setInternalState(prev => ({ ...prev, audioPermission: 'granted' }));
        }
      } catch {
        if (!recordingState) {
          setInternalState(prev => ({ ...prev, audioPermission: 'denied' }));
        }
      }
    };

    checkPermissions();
  }, [recordingState]);

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      if (recordingInterval.current) {
        clearInterval(recordingInterval.current);
      }
      if (audioStream.current) {
        audioStream.current.getTracks().forEach(track => track.stop());
      }
    };
  }, []);

  const handleRecordingComplete = useCallback(async () => {
    try {
      if (audioChunks.current.length === 0) {
        throw new Error('No audio data recorded');
      }

      // Create audio file from chunks
      const mimeType = mediaRecorder.current?.mimeType || 'audio/webm';
      const audioBlob = new Blob(audioChunks.current, { type: mimeType });

      // Determine file extension based on MIME type
      let extension = '.webm';
      if (mimeType.includes('mp4')) extension = '.m4a';
      else if (mimeType.includes('wav')) extension = '.wav';
      else if (mimeType.includes('mpeg')) extension = '.mp3';

      const audioFile = new File(
        [audioBlob],
        `recording-${Date.now()}${extension}`,
        { type: mimeType }
      );

      console.log(`Audio file created: ${audioFile.size} bytes, ${audioFile.type}`);

      // Start transcription process
      setInternalState(prev => ({
        ...prev,
        phase: 'processing',
        isRecording: false,
        processingProgress: 0
      }));

      // Submit for transcription
      const result = await transcriptionService.transcribeAudio(
        audioFile,
        undefined, // sessionId - can be added later
        (status: TranscriptionJobStatusResponse) => {
          // Update processing progress
          const progressMessages: Record<string, number> = {
            'QUEUED': 10,
            'PROCESSING': 50,
          };

          const progress = progressMessages[status.status] || status.progressPercentage || 0;

          setInternalState(prev => ({
            ...prev,
            processingProgress: progress
          }));
        }
      );

      // Transcription completed successfully
      setInternalState(prev => ({
        ...prev,
        phase: 'completed',
        transcript: result.transcript,
        transcriptId: result.transcriptId,
        processingProgress: 100
      }));

      // Notify parent component
      if (onTranscriptReady) {
        onTranscriptReady(result.transcript, result.transcriptId);
      }

      console.log('Transcription completed:', result.transcript);

    } catch (error) {
      console.error('Transcription failed:', error);

      const errorMessage = error instanceof Error
        ? error.message
        : 'Transkription fehlgeschlagen';

      setInternalState(prev => ({
        ...prev,
        phase: 'error',
        errorMessage
      }));

      showError(`Transkription fehlgeschlagen: ${errorMessage}`);
    }
  }, [onTranscriptReady, showError]);

  const startRecording = useCallback(async () => {
    try {
      setInternalState(prev => ({ ...prev, phase: 'recording', isRecording: true, recordingTime: 0 }));

      // Get audio stream
      audioStream.current = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          sampleRate: 44100,
        }
      });

      // Configure MediaRecorder for better compatibility
      const options: MediaRecorderOptions = {};

      // Try different MIME types for better compatibility
      if (MediaRecorder.isTypeSupported('audio/webm;codecs=opus')) {
        options.mimeType = 'audio/webm;codecs=opus';
      } else if (MediaRecorder.isTypeSupported('audio/webm')) {
        options.mimeType = 'audio/webm';
      } else if (MediaRecorder.isTypeSupported('audio/mp4')) {
        options.mimeType = 'audio/mp4';
      }

      mediaRecorder.current = new MediaRecorder(audioStream.current, options);
      audioChunks.current = [];

      mediaRecorder.current.ondataavailable = (event) => {
        if (event.data.size > 0) {
          audioChunks.current.push(event.data);
        }
      };

      mediaRecorder.current.onstop = async () => {
        await handleRecordingComplete();
      };

      mediaRecorder.current.onerror = (event) => {
        console.error('MediaRecorder error:', event);
        setInternalState(prev => ({
          ...prev,
          phase: 'error',
          errorMessage: 'Aufnahmefehler aufgetreten'
        }));
      };

      // Start recording
      mediaRecorder.current.start();

      // Start timer
      recordingInterval.current = setInterval(() => {
        setInternalState(prev => ({
          ...prev,
          recordingTime: prev.recordingTime + 1
        }));
      }, 1000);

      console.log('Audio recording started');
    } catch (error) {
      console.error('Failed to start recording:', error);
      setInternalState(prev => ({
        ...prev,
        phase: 'error',
        audioPermission: 'denied',
        errorMessage: 'Mikrofonzugriff fehlgeschlagen'
      }));
      showError('Mikrofonzugriff fehlgeschlagen. Bitte überprüfen Sie die Berechtigungen.');
    }
  }, [showError, handleRecordingComplete]);

  const stopRecording = useCallback(() => {
    if (mediaRecorder.current && mediaRecorder.current.state === 'recording') {
      setInternalState(prev => ({ ...prev, phase: 'stopping' }));

      // Stop timer
      if (recordingInterval.current) {
        clearInterval(recordingInterval.current);
        recordingInterval.current = null;
      }

      // Stop recording
      mediaRecorder.current.stop();

      // Stop audio stream
      if (audioStream.current) {
        audioStream.current.getTracks().forEach(track => track.stop());
        audioStream.current = null;
      }

      console.log('Audio recording stopped');
    }
  }, []);

  const handleStartRecording = useCallback(async () => {
    if (onStartRecording) {
      await onStartRecording();
    } else {
      await startRecording();
    }
  }, [onStartRecording, startRecording]);

  const handleStopRecording = useCallback(() => {
    if (onStopRecording) {
      onStopRecording();
    } else {
      stopRecording();
    }
  }, [onStopRecording, stopRecording]);

  const resetToIdle = useCallback(() => {
    setInternalState(prev => ({
      ...prev,
      phase: 'idle',
      transcript: undefined,
      transcriptId: undefined,
      errorMessage: undefined,
      processingProgress: undefined,
      recordingTime: 0
    }));
  }, []);

  // Format recording time for display
  const formatRecordingTime = (seconds: number): string => {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins}:${secs.toString().padStart(2, '0')}`;
  };

  // Medical UI color scheme based on phase
  const getButtonColor = (phase: RecordingPhase) => {
    switch (phase) {
      case 'idle': return 'primary'; // Gray/Blue
      case 'recording': return 'error'; // Red
      case 'stopping': return 'warning'; // Orange
      case 'processing': return 'info'; // Blue
      case 'completed': return 'success'; // Green
      case 'error': return 'error'; // Red
      default: return 'primary';
    }
  };

  const getButtonIcon = (phase: RecordingPhase) => {
    switch (phase) {
      case 'idle':
        return <Mic />;
      case 'recording':
        return <Stop />;
      case 'stopping':
      case 'processing':
        return <CircularProgress size={24} color="inherit" />;
      case 'completed':
        return <CheckCircle />;
      case 'error':
        return <ErrorIcon />;
      default:
        return <Mic />;
    }
  };

  const getAriaLabel = (phase: RecordingPhase) => {
    switch (phase) {
      case 'idle': return 'Sprachaufnahme starten';
      case 'recording': return 'Aufnahme stoppen';
      case 'stopping': return 'Aufnahme wird beendet';
      case 'processing': return 'Transkription läuft';
      case 'completed': return 'Transkription abgeschlossen';
      case 'error': return 'Fehler - erneut versuchen';
      default: return 'Sprachaufnahme';
    }
  };

  // Show audio permission warning
  if (currentState.audioPermission === 'denied') {
    return (
      <Stack spacing={2} alignItems="center">
        <Alert severity="warning" sx={{ mb: 1 }}>
          Mikrofonzugriff verweigert. Für Sprachaufnahmen aktivieren Sie bitte die Mikrofon-Berechtigung in Ihren Browser-Einstellungen.
        </Alert>
        <IconButton
          color="primary"
          disabled={true}
          size="large"
          aria-label="Mikrofon nicht verfügbar"
        >
          <MicOff />
        </IconButton>
      </Stack>
    );
  }

  return (
    <Stack spacing={1} alignItems="center">
      {/* Main recording button */}
      <IconButton
        color={getButtonColor(currentState.phase)}
        onClick={currentState.phase === 'recording' ? handleStopRecording :
                currentState.phase === 'idle' ? handleStartRecording :
                currentState.phase === 'completed' || currentState.phase === 'error' ? resetToIdle : undefined}
        disabled={disabled || currentState.phase === 'stopping' || currentState.phase === 'processing'}
        size="large"
        aria-label={getAriaLabel(currentState.phase)}
        sx={{
          ...(currentState.phase === 'recording' && {
            animation: 'pulse 1.5s infinite',
            '@keyframes pulse': {
              '0%': { opacity: 1, transform: 'scale(1)' },
              '50%': { opacity: 0.7, transform: 'scale(1.05)' },
              '100%': { opacity: 1, transform: 'scale(1)' },
            }
          }),
          transition: 'all 0.2s ease-in-out'
        }}
      >
        {getButtonIcon(currentState.phase)}
      </IconButton>

      {/* Status information */}
      <Fade in={currentState.phase !== 'idle'}>
        <Box textAlign="center">
          {/* Recording timer */}
          {currentState.phase === 'recording' && (
            <Typography
              variant="body2"
              fontWeight="bold"
              color="error"
              aria-live="polite"
              aria-label={`Aufnahmezeit: ${formatRecordingTime(currentState.recordingTime)}`}
            >
              🔴 {formatRecordingTime(currentState.recordingTime)}
            </Typography>
          )}

          {/* Processing status */}
          {currentState.phase === 'processing' && (
            <Stack spacing={1} alignItems="center">
              <Typography variant="body2" color="info.main">
                Transkription läuft...
              </Typography>
              {currentState.processingProgress !== undefined && (
                <Typography variant="caption" color="text.secondary">
                  {Math.round(currentState.processingProgress)}%
                </Typography>
              )}
            </Stack>
          )}

          {/* Completed status */}
          {currentState.phase === 'completed' && (
            <Chip
              label="Transkription abgeschlossen"
              color="success"
              size="small"
              icon={<CheckCircle />}
            />
          )}

          {/* Error status */}
          {currentState.phase === 'error' && (
            <Typography variant="body2" color="error">
              {currentState.errorMessage || 'Fehler aufgetreten'}
            </Typography>
          )}
        </Box>
      </Fade>

      {/* Accessibility helper */}
      <Typography
        variant="caption"
        sx={{
          position: 'absolute',
          left: '-10000px',
          width: '1px',
          height: '1px',
          overflow: 'hidden'
        }}
        aria-live="polite"
      >
        {currentState.phase === 'recording'
          ? `Aufnahme läuft seit ${formatRecordingTime(currentState.recordingTime)}`
          : currentState.phase === 'processing'
          ? 'Transkription wird verarbeitet'
          : currentState.phase === 'completed'
          ? 'Transkription erfolgreich abgeschlossen'
          : currentState.phase === 'error'
          ? `Fehler: ${currentState.errorMessage}`
          : 'Bereit für Sprachaufnahme'
        }
      </Typography>
    </Stack>
  );
};

// Memoized export to prevent unnecessary re-renders
export const AudioControls = memo(AudioControlsComponent);
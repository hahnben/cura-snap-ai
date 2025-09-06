import { useState, useRef, useEffect } from 'react';
import {
  Box,
  AppBar,
  Toolbar,
  Typography,
  IconButton,
  Button,
  Card,
  CardContent,
  TextField,
  Stack,
  Chip,
  Avatar,
  Menu,
  MenuItem,
  LinearProgress,
  Alert,
} from '@mui/material';
import {
  Logout,
  AccountCircle,
  Mic,
  MicOff,
  Send,
  Stop,
  PlayArrow,
  Pause,
} from '@mui/icons-material';
import { useAuth } from '../contexts/AuthContext';
import { useError } from '../contexts/ErrorContext';

interface Message {
  id: string;
  content: string;
  type: 'user' | 'assistant';
  timestamp: string;
  source?: 'text' | 'audio';
}

export function DashboardPage() {
  const { user, signOut, timeRemaining } = useAuth();
  const { showError } = useError();
  
  // UI State
  const [anchorEl, setAnchorEl] = useState<null | HTMLElement>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [inputText, setInputText] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  
  // Audio State
  const [isRecording, setIsRecording] = useState(false);
  const [isPlaying, setIsPlaying] = useState(false);
  const [recordingTime, setRecordingTime] = useState(0);
  const [audioPermission, setAudioPermission] = useState<'granted' | 'denied' | 'prompt'>('prompt');
  
  // Refs
  const mediaRecorder = useRef<MediaRecorder | null>(null);
  const audioChunks = useRef<Blob[]>([]);
  const recordingInterval = useRef<NodeJS.Timeout | null>(null);

  // Format remaining time
  const formatTime = (ms: number) => {
    const minutes = Math.floor(ms / 60000);
    const seconds = Math.floor((ms % 60000) / 1000);
    return `${minutes}:${seconds.toString().padStart(2, '0')}`;
  };

  // Initialize audio permission check
  useEffect(() => {
    navigator.mediaDevices.getUserMedia({ audio: true })
      .then(() => setAudioPermission('granted'))
      .catch(() => setAudioPermission('denied'));
  }, []);

  const handleMenuClick = (event: React.MouseEvent<HTMLElement>) => {
    setAnchorEl(event.currentTarget);
  };

  const handleMenuClose = () => {
    setAnchorEl(null);
  };

  const handleSignOut = async () => {
    handleMenuClose();
    await signOut();
  };

  const startRecording = async () => {
    try {
      if (audioPermission !== 'granted') {
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        setAudioPermission('granted');
      }

      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      mediaRecorder.current = new MediaRecorder(stream);
      audioChunks.current = [];

      mediaRecorder.current.ondataavailable = (event) => {
        audioChunks.current.push(event.data);
      };

      mediaRecorder.current.onstop = () => {
        const audioBlob = new Blob(audioChunks.current, { type: 'audio/wav' });
        handleAudioMessage(audioBlob);
        stream.getTracks().forEach(track => track.stop());
      };

      mediaRecorder.current.start();
      setIsRecording(true);
      setRecordingTime(0);

      recordingInterval.current = setInterval(() => {
        setRecordingTime(prev => prev + 1);
      }, 1000);

    } catch (error) {
      showError('Mikrofonzugriff fehlgeschlagen. Bitte überprüfen Sie die Berechtigungen.');
      setAudioPermission('denied');
    }
  };

  const stopRecording = () => {
    if (mediaRecorder.current && isRecording) {
      mediaRecorder.current.stop();
      setIsRecording(false);
      
      if (recordingInterval.current) {
        clearInterval(recordingInterval.current);
        recordingInterval.current = null;
      }
    }
  };

  const handleAudioMessage = async (audioBlob: Blob) => {
    setIsLoading(true);
    
    try {
      // Create audio message
      const audioMessage: Message = {
        id: Date.now().toString(),
        content: `🎤 Audio-Aufnahme (${recordingTime}s)`,
        type: 'user',
        timestamp: new Date().toISOString(),
        source: 'audio',
      };

      setMessages(prev => [...prev, audioMessage]);
      
      // Simulate API call for transcription and SOAP generation
      await new Promise(resolve => setTimeout(resolve, 2000));
      
      const assistantMessage: Message = {
        id: (Date.now() + 1).toString(),
        content: `**SOAP Note Entwurf generiert**

**Subjektiv:**
Patient berichtet über... [Hier würde die Transkription der Audioaufnahme verarbeitet]

**Objektiv:**
Vital signs... [Objektive Befunde basierend auf der Eingabe]

**Assessment:**
Verdachtsdiagnose... [KI-generierte Einschätzung]

**Plan:**
Behandlungsplan... [Empfohlene nächste Schritte]

_📝 Bitte überprüfen und bei Bedarf anpassen._`,
        type: 'assistant',
        timestamp: new Date().toISOString(),
      };

      setMessages(prev => [...prev, assistantMessage]);
      showError('SOAP Note erfolgreich generiert!', 'success');
      
    } catch (error) {
      showError('Fehler bei der Audio-Verarbeitung. Bitte versuchen Sie es erneut.');
    } finally {
      setIsLoading(false);
      setRecordingTime(0);
    }
  };

  const handleTextMessage = async () => {
    if (!inputText.trim() || isLoading) return;

    const userMessage: Message = {
      id: Date.now().toString(),
      content: inputText,
      type: 'user',
      timestamp: new Date().toISOString(),
      source: 'text',
    };

    setMessages(prev => [...prev, userMessage]);
    setInputText('');
    setIsLoading(true);

    try {
      // Simulate API call
      await new Promise(resolve => setTimeout(resolve, 1500));
      
      const assistantMessage: Message = {
        id: (Date.now() + 1).toString(),
        content: `**SOAP Note basierend auf Ihrem Text:**

**Subjektiv:**
${inputText.substring(0, 100)}...

**Objektiv:**
[Zu vervollständigen basierend auf klinischen Befunden]

**Assessment:**
[KI-Analyse der beschriebenen Symptome]

**Plan:**
[Empfohlene Behandlungsschritte]

_📝 Bitte ergänzen Sie fehlende Informationen._`,
        type: 'assistant',
        timestamp: new Date().toISOString(),
      };

      setMessages(prev => [...prev, assistantMessage]);
      showError('SOAP Note Entwurf erstellt!', 'success');
      
    } catch (error) {
      showError('Fehler bei der Verarbeitung. Bitte versuchen Sie es erneut.');
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <Box sx={{ flexGrow: 1, height: '100vh', display: 'flex', flexDirection: 'column' }}>
      {/* Header */}
      <AppBar position="static" elevation={1}>
        <Toolbar>
          <Typography variant="h6" component="div" sx={{ flexGrow: 1 }}>
            CuraSnap AI - Dashboard
          </Typography>
          
          {/* Session Timer */}
          <Chip
            label={`Session: ${formatTime(timeRemaining)}`}
            variant="outlined"
            sx={{ mr: 2, color: 'white', borderColor: 'white' }}
          />
          
          {/* User Menu */}
          <IconButton
            size="large"
            aria-label="account menu"
            aria-controls="menu-appbar"
            aria-haspopup="true"
            onClick={handleMenuClick}
            color="inherit"
          >
            <AccountCircle />
          </IconButton>
          
          <Menu
            id="menu-appbar"
            anchorEl={anchorEl}
            anchorOrigin={{
              vertical: 'top',
              horizontal: 'right',
            }}
            keepMounted
            transformOrigin={{
              vertical: 'top',
              horizontal: 'right',
            }}
            open={Boolean(anchorEl)}
            onClose={handleMenuClose}
          >
            <MenuItem disabled>
              <Typography variant="body2">{user?.email}</Typography>
            </MenuItem>
            <MenuItem onClick={handleSignOut}>
              <Logout sx={{ mr: 1 }} />
              Abmelden
            </MenuItem>
          </Menu>
        </Toolbar>
      </AppBar>

      {/* Main Content */}
      <Box sx={{ flexGrow: 1, display: 'flex', flexDirection: 'column', p: 2, gap: 2 }}>
        
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

        {/* Messages */}
        <Box sx={{ flexGrow: 1, overflow: 'auto' }}>
          <Stack spacing={2}>
            {messages.length === 0 && (
              <Card>
                <CardContent>
                  <Typography variant="h6" gutterBottom>
                    Willkommen bei CuraSnap AI
                  </Typography>
                  <Typography variant="body2" color="text.secondary">
                    Beginnen Sie mit einer Audio-Aufnahme oder geben Sie Patienteninformationen per Text ein.
                    Die KI erstellt automatisch einen SOAP Note Entwurf.
                  </Typography>
                </CardContent>
              </Card>
            )}

            {messages.map((message) => (
              <Box
                key={message.id}
                sx={{
                  display: 'flex',
                  justifyContent: message.type === 'user' ? 'flex-end' : 'flex-start',
                }}
              >
                <Card
                  sx={{
                    maxWidth: '70%',
                    bgcolor: message.type === 'user' ? 'primary.main' : 'background.paper',
                    color: message.type === 'user' ? 'primary.contrastText' : 'text.primary',
                  }}
                >
                  <CardContent sx={{ '&:last-child': { pb: 2 } }}>
                    {message.type === 'assistant' ? (
                      <Typography 
                        variant="body1" 
                        component="div"
                        sx={{ whiteSpace: 'pre-wrap' }}
                      >
                        {message.content}
                      </Typography>
                    ) : (
                      <Typography variant="body1">
                        {message.content}
                      </Typography>
                    )}
                    <Typography 
                      variant="caption" 
                      sx={{ 
                        display: 'block', 
                        mt: 1, 
                        opacity: 0.8,
                      }}
                    >
                      {new Date(message.timestamp).toLocaleTimeString('de-DE')}
                      {message.source === 'audio' && ' 🎤'}
                    </Typography>
                  </CardContent>
                </Card>
              </Box>
            ))}
          </Stack>
        </Box>

        {/* Input Area */}
        <Card>
          <CardContent>
            <Stack direction="row" spacing={1} alignItems="flex-end">
              {/* Audio Controls */}
              <Box>
                {isRecording ? (
                  <IconButton
                    color="error"
                    onClick={stopRecording}
                    size="large"
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
                    onClick={startRecording}
                    disabled={isLoading || audioPermission === 'denied'}
                    size="large"
                  >
                    <Mic />
                  </IconButton>
                )}
                {isRecording && (
                  <Typography variant="caption" display="block" textAlign="center">
                    {recordingTime}s
                  </Typography>
                )}
              </Box>

              {/* Text Input */}
              <TextField
                fullWidth
                multiline
                maxRows={4}
                placeholder="Beschreiben Sie die Patientenbegegnung..."
                value={inputText}
                onChange={(e) => setInputText(e.target.value)}
                disabled={isLoading || isRecording}
                onKeyPress={(e) => {
                  if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault();
                    handleTextMessage();
                  }
                }}
              />

              {/* Send Button */}
              <IconButton
                color="primary"
                onClick={handleTextMessage}
                disabled={!inputText.trim() || isLoading || isRecording}
              >
                <Send />
              </IconButton>
            </Stack>
          </CardContent>
        </Card>
      </Box>
    </Box>
  );
}
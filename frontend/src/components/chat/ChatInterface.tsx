import React, { useState, useRef, useEffect, useCallback, memo } from 'react';
import {
  Box,
  Paper,
  TextField,
  IconButton,
  Typography,
  CircularProgress,
  Alert,
  Chip,
  Card,
  CardContent,
  Stack,
  LinearProgress,
  Button
} from '@mui/material';
import {
  Send as SendIcon,
  Clear as ClearIcon,
  Description as DocumentIcon,
  Add as AddIcon,
  Mic as MicIcon,
  Upload as UploadIcon
} from '@mui/icons-material';
import { useSOAPGeneration } from '../../hooks/useSOAPGeneration';
import type { SOAPResult } from '../../services/text-processing.service';
import type { ChatMessage, ChatInterfaceProps } from '../../types/chat.types';
import { AudioControls } from '../dashboard/AudioControls';

const ChatInterfaceComponent = ({
  onMessage,
  isProcessing: externalIsProcessing,
  placeholder = "Beschreiben Sie die Patientenbegegnung...",
  showHeader = true,
  enableAudio = true,
  layoutMode = 'chat'
}: ChatInterfaceProps = {}) => {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [inputText, setInputText] = useState('');
  const [isProcessing, setIsProcessing] = useState(false);
  const [processingStatus, setProcessingStatus] = useState<string>('');

  // Use the SOAP generation hook
  const { generateSOAP } = useSOAPGeneration();
  const messagesEndRef = useRef<HTMLDivElement>(null);

  // Use external processing state if provided
  const currentIsProcessing = externalIsProcessing ?? isProcessing;

  // Auto-scroll to bottom when new messages arrive
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const addMessage = useCallback((message: Omit<ChatMessage, 'id' | 'timestamp'>) => {
    const newMessage: ChatMessage = {
      ...message,
      id: Math.random().toString(36).substr(2, 9),
      timestamp: new Date(),
    };
    setMessages(prev => [...prev, newMessage]);

    // Notify parent component if callback provided
    if (onMessage) {
      onMessage(newMessage);
    }

    return newMessage.id;
  }, [onMessage]);

  const updateMessage = useCallback((messageId: string, updates: Partial<ChatMessage>) => {
    setMessages(prev =>
      prev.map(msg => (msg.id === messageId ? { ...msg, ...updates } : msg))
    );
  }, []);

  const handleSubmit = useCallback(async () => {
    if (!inputText.trim() || isProcessing) return;

    const userText = inputText.trim();
    setInputText('');

    // Add user message
    addMessage({
      type: 'user',
      content: userText,
    });

    // Add processing message
    const processingMessageId = addMessage({
      type: 'assistant',
      content: 'Verarbeitung Ihrer Patientennotiz zu einer SOAP-Note...',
      isProcessing: true,
    });

    try {
      await generateSOAP(
        userText,
        {
          onStart: () => {
            setIsProcessing(true);
            setProcessingStatus('Anfrage wird eingereicht...');
          },
          onProgress: (status) => {
            // Update processing status
            const statusMessages = {
              'QUEUED': 'Anfrage in Warteschlange...',
              'PROCESSING': 'SOAP-Note wird generiert...',
            };

            const statusMessage = statusMessages[status.status as keyof typeof statusMessages]
              || `Status: ${status.status}`;

            setProcessingStatus(statusMessage);

            if (status.progressMessage) {
              updateMessage(processingMessageId, {
                content: status.progressMessage,
              });
            }
          },
          onSuccess: (soapResult) => {
            // Success - update message with SOAP result
            updateMessage(processingMessageId, {
              content: 'SOAP-Note wurde erfolgreich erstellt:',
              isProcessing: false,
              soapResult,
            });
          },
          onError: (error) => {
            console.error('Text processing failed:', error);

            // Error - update message with error info
            updateMessage(processingMessageId, {
              content: 'Fehler bei der Verarbeitung',
              isProcessing: false,
              error,
            });
          },
          onComplete: () => {
            setIsProcessing(false);
            setProcessingStatus('');
          }
        },
        {
          sessionId: undefined // Could be added later
        }
      );
    } catch (error) {
      // Error handling is done in the hook callbacks
    }
  }, [inputText, isProcessing, addMessage, updateMessage, generateSOAP]);

  const handleKeyPress = useCallback((event: React.KeyboardEvent) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      handleSubmit();
    }
  }, [handleSubmit]);

  const clearChat = useCallback(() => {
    setMessages([]);
  }, []);


  const renderSOAPNote = (soap: SOAPResult) => (
    <Card sx={{ mt: 2, bgcolor: 'background.default' }}>
      <CardContent>
        <Box sx={{ display: 'flex', alignItems: 'center', mb: 2 }}>
          <DocumentIcon sx={{ mr: 1, color: 'primary.main' }} />
          <Typography variant="h6" color="primary">
            SOAP-Note
          </Typography>
        </Box>
        
        <Box
          component="pre"
          sx={{ 
            whiteSpace: 'pre-wrap',
            fontFamily: 'inherit',
            fontSize: '0.875rem',
            lineHeight: 1.6,
            margin: 0,
            padding: 2,
            bgcolor: 'grey.50',
            borderRadius: 1,
            border: '1px solid',
            borderColor: 'divider'
          }}
        >
          {typeof soap === 'string' ? soap : soap.textStructured || JSON.stringify(soap, null, 2)}
        </Box>
      </CardContent>
    </Card>
  );

  const renderMessage = (message: ChatMessage) => (
    <Box
      key={message.id}
      sx={{
        mb: 2,
        display: 'flex',
        flexDirection: message.type === 'user' ? 'row-reverse' : 'row',
      }}
    >
      <Paper
        sx={{
          maxWidth: '70%',
          p: 2,
          bgcolor: message.type === 'user' ? 'primary.main' : 'grey.100',
          color: message.type === 'user' ? 'primary.contrastText' : 'text.primary',
        }}
      >
        <Box sx={{ display: 'flex', alignItems: 'center', mb: 1 }}>
          <Chip
            label={message.type === 'user' ? 'Sie' : 'CuraSnap AI'}
            size="small"
            sx={{
              bgcolor: message.type === 'user' ? 'primary.dark' : 'primary.light',
              color: 'white',
              fontSize: '0.75rem',
            }}
          />
          <Typography variant="caption" sx={{ ml: 1, opacity: 0.8 }}>
            {message.timestamp.toLocaleTimeString()}
            {message.source === 'audio' && ' 🎤'}
          </Typography>
        </Box>
        
        <Typography variant="body1" sx={{ whiteSpace: 'pre-wrap' }}>
          {message.content}
        </Typography>
        
        {message.isProcessing && (
          <Box sx={{ display: 'flex', alignItems: 'center', mt: 1 }}>
            <CircularProgress size={16} sx={{ mr: 1 }} />
            <Typography variant="caption">
              {processingStatus || 'Verarbeitung läuft...'}
            </Typography>
          </Box>
        )}
        
        {message.error && (
          <Alert severity="error" sx={{ mt: 1 }}>
            {message.error}
          </Alert>
        )}
        
        {message.soapResult && renderSOAPNote(message.soapResult)}
      </Paper>
    </Box>
  );

  // Workflow layout implementation
  if (layoutMode === 'workflow') {
    return (
      <Box sx={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
        {/* Progress Indicator */}
        {currentIsProcessing && (
          <LinearProgress sx={{ borderRadius: 1 }} />
        )}

        <Box sx={{ flexGrow: 1, display: 'flex', overflow: 'hidden' }}>
          {/* Left Side - Input Areas */}
          <Box sx={{ flex: 1, display: 'flex', flexDirection: 'column', p: 2, gap: 2 }}>

            {/* Transcripts Section */}
            <Box>
              <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 2 }}>
                <Typography variant="h6" sx={{ fontWeight: 600 }}>
                  Transcripts
                </Typography>
                <Stack direction="row" spacing={1}>
                  {enableAudio && (
                    <Button
                      variant="outlined"
                      size="small"
                      startIcon={<MicIcon />}
                      disabled={currentIsProcessing}
                    >
                      Audio
                    </Button>
                  )}
                  <Button
                    variant="outlined"
                    size="small"
                    startIcon={<AddIcon />}
                    disabled={currentIsProcessing}
                  >
                    Add Transcript
                  </Button>
                </Stack>
              </Box>

              {/* Transcript Input Area */}
              <Paper elevation={1} sx={{ p: 2, mb: 2 }}>
                <TextField
                  fullWidth
                  multiline
                  rows={6}
                  value={inputText}
                  onChange={(e) => setInputText(e.target.value)}
                  placeholder={placeholder}
                  disabled={currentIsProcessing}
                  variant="outlined"
                  label="Patient Encounter Notes"
                />

                {/* Audio Controls in Workflow Mode */}
                {enableAudio && (
                  <Box sx={{ mt: 2, pt: 2, borderTop: 1, borderColor: 'divider' }}>
                    <AudioControls
                      onTranscriptReady={(transcript: string) => {
                        setInputText(prev => prev ? `${prev}\n\n${transcript}` : transcript);
                      }}
                      disabled={currentIsProcessing}
                    />
                  </Box>
                )}
              </Paper>
            </Box>

            {/* Context/Files Section */}
            <Box>
              <Typography variant="h6" sx={{ fontWeight: 600, mb: 2 }}>
                Context & Files
              </Typography>
              <Paper
                elevation={0}
                sx={{
                  p: 3,
                  textAlign: 'center',
                  border: 2,
                  borderColor: 'divider',
                  borderStyle: 'dashed',
                  bgcolor: 'grey.50'
                }}
              >
                <UploadIcon sx={{ fontSize: 48, color: 'text.secondary', mb: 1 }} />
                <Typography variant="body2" color="text.secondary" gutterBottom>
                  Drag & drop files here or click to browse
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  Support for medical documents, lab results, etc.
                </Typography>
                <Box sx={{ mt: 2 }}>
                  <Button variant="outlined" startIcon={<UploadIcon />} disabled>
                    Browse Files
                  </Button>
                </Box>
              </Paper>
            </Box>

            {/* Command Input */}
            <Box>
              <Button
                variant="contained"
                fullWidth
                size="large"
                onClick={handleSubmit}
                disabled={!inputText.trim() || currentIsProcessing}
                startIcon={currentIsProcessing ? <CircularProgress size={20} /> : <DocumentIcon />}
                sx={{ minHeight: 56 }}
              >
                {currentIsProcessing ? 'Creating SOAP Note...' : 'Create SOAP Note'}
              </Button>
            </Box>
          </Box>

          {/* Right Side - Output Area */}
          <Box sx={{ flex: 1, display: 'flex', flexDirection: 'column', borderLeft: 1, borderColor: 'divider' }}>
            <Box sx={{ p: 2, flexGrow: 1, overflow: 'auto' }}>
              <Typography variant="h6" sx={{ fontWeight: 600, mb: 2 }}>
                Generated SOAP Note
              </Typography>

              {messages.length === 0 ? (
                <Paper
                  elevation={0}
                  sx={{
                    p: 4,
                    textAlign: 'center',
                    border: 1,
                    borderColor: 'divider',
                    bgcolor: 'background.default'
                  }}
                >
                  <DocumentIcon sx={{ fontSize: 48, color: 'text.secondary', mb: 2 }} />
                  <Typography variant="body1" color="text.secondary" gutterBottom>
                    No SOAP note generated yet
                  </Typography>
                  <Typography variant="body2" color="text.secondary">
                    Enter patient notes and click "Create SOAP Note" to generate a structured medical note.
                  </Typography>
                </Paper>
              ) : (
                <Box>
                  {/* Show only the latest SOAP result */}
                  {(() => {
                    const latestSoapMessage = [...messages].reverse().find(m => m.soapResult);
                    if (latestSoapMessage?.soapResult) {
                      return renderSOAPNote(latestSoapMessage.soapResult);
                    }

                    const latestMessage = messages[messages.length - 1];
                    if (latestMessage?.isProcessing) {
                      return (
                        <Paper sx={{ p: 3 }}>
                          <Box sx={{ display: 'flex', alignItems: 'center', mb: 2 }}>
                            <CircularProgress size={24} sx={{ mr: 2 }} />
                            <Typography variant="h6">
                              Processing...
                            </Typography>
                          </Box>
                          <Typography variant="body2" color="text.secondary">
                            {processingStatus || 'Generating SOAP note from your input...'}
                          </Typography>
                        </Paper>
                      );
                    }

                    if (latestMessage?.error) {
                      return (
                        <Alert severity="error">
                          <Typography variant="subtitle2" gutterBottom>
                            Processing Error
                          </Typography>
                          {latestMessage.error}
                        </Alert>
                      );
                    }

                    return null;
                  })()}
                </Box>
              )}
            </Box>
          </Box>
        </Box>
      </Box>
    );
  }

  // Default chat layout
  return (
    <Box sx={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      {/* Header */}
      {showHeader && (
        <Box sx={{ p: 2, borderBottom: 1, borderColor: 'divider' }}>
          <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
            <Typography variant="h6" color="primary">
              SOAP Note Assistant
            </Typography>
            <IconButton onClick={clearChat} disabled={currentIsProcessing} aria-label="Chat löschen">
              <ClearIcon />
            </IconButton>
          </Box>
          <Typography variant="body2" color="text.secondary">
            Geben Sie eine unstrukturierte Patientennotiz ein, und sie wird in eine SOAP-Note umgewandelt
          </Typography>
        </Box>
      )}
      
      {/* Progress Indicator */}
      {currentIsProcessing && (
        <LinearProgress sx={{ borderRadius: 1 }} />
      )}

      {/* Messages */}
      <Box 
        sx={{ flex: 1, overflowY: 'auto', p: 2 }}
        role="log"
        aria-live="polite"
        aria-label="Chat-Verlauf"
      >
        {messages.length === 0 ? (
          <Box sx={{ 
            height: '100%', 
            display: 'flex', 
            alignItems: 'center', 
            justifyContent: 'center' 
          }}>
            <Typography variant="body1" color="text.secondary" textAlign="center">
              Geben Sie eine Patientennotiz ein, um zu beginnen.<br />
              Zum Beispiel: "Patient klagt über Kopfschmerzen seit 2 Tagen, keine Fieber, nimmt Ibuprofen..."
            </Typography>
          </Box>
        ) : (
          <>
            {messages.map(renderMessage)}
            <div ref={messagesEndRef} />
          </>
        )}
      </Box>

      {/* Input */}
      <Card>
        <CardContent>
          <Stack direction="row" spacing={1} alignItems="flex-end">
            {/* Audio Controls */}
            {enableAudio && (
              <AudioControls
                onTranscriptReady={(transcript: string, transcriptId?: string) => {
                  // Insert transcript into text input field for user editing
                  setInputText(transcript);

                  // Add user message indicating this came from voice
                  addMessage({
                    type: 'system',
                    content: `🎤 Sprachnotiz transkribiert${transcriptId ? ` (ID: ${transcriptId.substring(0, 8)}...)` : ''}: "${transcript.substring(0, 100)}${transcript.length > 100 ? '...' : ''}"`,
                    source: 'audio',
                  });
                }}
                disabled={currentIsProcessing}
              />
            )}

            {/* Text Input */}
            <TextField
              fullWidth
              multiline
              maxRows={4}
              value={inputText}
              onChange={(e) => setInputText(e.target.value)}
              onKeyPress={handleKeyPress}
              placeholder={placeholder}
              disabled={currentIsProcessing}
              variant="outlined"
              size="small"
              aria-label="Patientennotiz eingeben"
              inputProps={{
                'aria-describedby': 'input-help-text'
              }}
            />

            {/* Send Button */}
            <IconButton
              onClick={handleSubmit}
              disabled={!inputText.trim() || currentIsProcessing}
              color="primary"
              aria-label="Nachricht senden"
            >
              {currentIsProcessing ? <CircularProgress size={24} /> : <SendIcon />}
            </IconButton>
          </Stack>
          
          {/* Screen reader helper text */}
          <Typography 
            id="input-help-text" 
            variant="caption" 
            sx={{ 
              position: 'absolute', 
              left: '-10000px',
              width: '1px',
              height: '1px',
              overflow: 'hidden'
            }}
          >
            Geben Sie eine unstrukturierte Patientennotiz ein. Drücken Sie Enter zum Senden oder verwenden Sie den Senden-Button.
          </Typography>
        </CardContent>
      </Card>
    </Box>
  );
};

// Memoized export to prevent unnecessary re-renders
export const ChatInterface = memo(ChatInterfaceComponent);
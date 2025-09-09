import React, { useState, useRef, useEffect } from 'react';
import {
  Box,
  Paper,
  TextField,
  IconButton,
  Typography,
  CircularProgress,
  Alert,
  Chip,
  Divider,
  Card,
  CardContent
} from '@mui/material';
import {
  Send as SendIcon,
  Clear as ClearIcon,
  Description as DocumentIcon
} from '@mui/icons-material';
import { textProcessingService, JobStatusResponse, SOAPResult } from '../../services/text-processing.service';

interface ChatMessage {
  id: string;
  type: 'user' | 'assistant' | 'system';
  content: string;
  timestamp: Date;
  soapResult?: SOAPResult;
  isProcessing?: boolean;
  error?: string;
}

export function ChatInterface() {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [inputText, setInputText] = useState('');
  const [isProcessing, setIsProcessing] = useState(false);
  const [processingStatus, setProcessingStatus] = useState<string>('');
  const messagesEndRef = useRef<HTMLDivElement>(null);

  // Auto-scroll to bottom when new messages arrive
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const addMessage = (message: Omit<ChatMessage, 'id' | 'timestamp'>) => {
    const newMessage: ChatMessage = {
      ...message,
      id: Math.random().toString(36).substr(2, 9),
      timestamp: new Date(),
    };
    setMessages(prev => [...prev, newMessage]);
    return newMessage.id;
  };

  const updateMessage = (messageId: string, updates: Partial<ChatMessage>) => {
    setMessages(prev =>
      prev.map(msg => (msg.id === messageId ? { ...msg, ...updates } : msg))
    );
  };

  const handleSubmit = async () => {
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

    setIsProcessing(true);
    setProcessingStatus('Anfrage wird eingereicht...');

    try {
      await textProcessingService.processTextToSOAP(
        userText,
        undefined, // sessionId - could be added later
        (status: JobStatusResponse) => {
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
        }
      ).then((soapResult: SOAPResult) => {
        // Success - update message with SOAP result
        updateMessage(processingMessageId, {
          content: 'SOAP-Note wurde erfolgreich erstellt:',
          isProcessing: false,
          soapResult,
        });
      });

    } catch (error) {
      console.error('Text processing failed:', error);
      
      // Error - update message with error info
      updateMessage(processingMessageId, {
        content: 'Fehler bei der Verarbeitung',
        isProcessing: false,
        error: error instanceof Error ? error.message : 'Unbekannter Fehler',
      });
    } finally {
      setIsProcessing(false);
      setProcessingStatus('');
    }
  };

  const handleKeyPress = (event: React.KeyboardEvent) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      handleSubmit();
    }
  };

  const clearChat = () => {
    setMessages([]);
  };

  const renderSOAPNote = (soap: SOAPResult) => (
    <Card sx={{ mt: 2, bgcolor: 'background.default' }}>
      <CardContent>
        <Box sx={{ display: 'flex', alignItems: 'center', mb: 2 }}>
          <DocumentIcon sx={{ mr: 1, color: 'primary.main' }} />
          <Typography variant="h6" color="primary">
            SOAP-Note
          </Typography>
        </Box>
        
        <Box sx={{ '& > *:not(:last-child)': { mb: 2 } }}>
          <Box>
            <Typography variant="subtitle2" sx={{ fontWeight: 'bold', color: 'text.secondary' }}>
              Subjektiv (S):
            </Typography>
            <Typography variant="body2" sx={{ mt: 0.5, whiteSpace: 'pre-wrap' }}>
              {soap.subjective}
            </Typography>
          </Box>
          
          <Divider />
          
          <Box>
            <Typography variant="subtitle2" sx={{ fontWeight: 'bold', color: 'text.secondary' }}>
              Objektiv (O):
            </Typography>
            <Typography variant="body2" sx={{ mt: 0.5, whiteSpace: 'pre-wrap' }}>
              {soap.objective}
            </Typography>
          </Box>
          
          <Divider />
          
          <Box>
            <Typography variant="subtitle2" sx={{ fontWeight: 'bold', color: 'text.secondary' }}>
              Assessment (A):
            </Typography>
            <Typography variant="body2" sx={{ mt: 0.5, whiteSpace: 'pre-wrap' }}>
              {soap.assessment}
            </Typography>
          </Box>
          
          <Divider />
          
          <Box>
            <Typography variant="subtitle2" sx={{ fontWeight: 'bold', color: 'text.secondary' }}>
              Plan (P):
            </Typography>
            <Typography variant="body2" sx={{ mt: 0.5, whiteSpace: 'pre-wrap' }}>
              {soap.plan}
            </Typography>
          </Box>
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

  return (
    <Box sx={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      {/* Header */}
      <Box sx={{ p: 2, borderBottom: 1, borderColor: 'divider' }}>
        <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <Typography variant="h6" color="primary">
            SOAP Note Assistant
          </Typography>
          <IconButton onClick={clearChat} disabled={isProcessing}>
            <ClearIcon />
          </IconButton>
        </Box>
        <Typography variant="body2" color="text.secondary">
          Geben Sie eine unstrukturierte Patientennotiz ein, und sie wird in eine SOAP-Note umgewandelt
        </Typography>
      </Box>

      {/* Messages */}
      <Box sx={{ flex: 1, overflowY: 'auto', p: 2 }}>
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
      <Box sx={{ p: 2, borderTop: 1, borderColor: 'divider' }}>
        <Box sx={{ display: 'flex', gap: 1 }}>
          <TextField
            fullWidth
            multiline
            maxRows={4}
            value={inputText}
            onChange={(e) => setInputText(e.target.value)}
            onKeyPress={handleKeyPress}
            placeholder="Patientennotiz eingeben..."
            disabled={isProcessing}
            variant="outlined"
            size="small"
          />
          <IconButton
            onClick={handleSubmit}
            disabled={!inputText.trim() || isProcessing}
            color="primary"
            sx={{ alignSelf: 'flex-end' }}
          >
            {isProcessing ? <CircularProgress size={24} /> : <SendIcon />}
          </IconButton>
        </Box>
      </Box>
    </Box>
  );
}
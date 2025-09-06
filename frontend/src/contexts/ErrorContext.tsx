import React, { createContext, useContext, useState, ReactNode } from 'react';
import { Snackbar, Alert, AlertTitle } from '@mui/material';
// import { ErrorFallback } from '../components/ui/ErrorFallback';

interface ErrorInfo {
  message: string;
  type: 'error' | 'warning' | 'info';
  source?: 'audio' | 'api' | 'auth' | 'system';
  showFallback?: boolean;
  persistent?: boolean;
}

interface ErrorContextType {
  showError: (error: ErrorInfo) => void;
  showSuccess: (message: string) => void;
  clearError: () => void;
  currentError: ErrorInfo | null;
}

const ErrorContext = createContext<ErrorContextType | undefined>(undefined);

interface ErrorProviderProps {
  children: ReactNode;
}

export function ErrorProvider({ children }: ErrorProviderProps) {
  const [currentError, setCurrentError] = useState<ErrorInfo | null>(null);
  const [snackbarOpen, setSnackbarOpen] = useState(false);
  const [snackbarMessage, setSnackbarMessage] = useState('');
  const [snackbarType, setSnackbarType] = useState<'success' | 'error' | 'warning' | 'info'>('info');

  const showError = (error: ErrorInfo) => {
    console.error('ErrorContext:', error);
    
    if (error.persistent || error.showFallback) {
      setCurrentError(error);
    } else {
      setSnackbarMessage(error.message);
      setSnackbarType(error.type);
      setSnackbarOpen(true);
    }
  };

  const showSuccess = (message: string) => {
    setSnackbarMessage(message);
    setSnackbarType('success');
    setSnackbarOpen(true);
  };

  const clearError = () => {
    setCurrentError(null);
  };

  const handleSnackbarClose = () => {
    setSnackbarOpen(false);
  };

  const handleRetry = () => {
    // This could trigger a re-attempt of the failed operation
    clearError();
  };

  const handleUseTextInput = () => {
    // This could focus the text input or show a guidance message
    clearError();
    showSuccess('You can now use the text input below to continue.');
  };

  return (
    <ErrorContext.Provider value={{
      showError,
      showSuccess,
      clearError,
      currentError,
    }}>
      {children}
      
      {/* Persistent error display - temporarily disabled */}
      {false && currentError?.persistent && (
        <div>Error display placeholder</div>
      )}
      
      {/* Snackbar for temporary notifications */}
      <Snackbar
        open={snackbarOpen}
        autoHideDuration={snackbarType === 'success' ? 3000 : 6000}
        onClose={handleSnackbarClose}
        anchorOrigin={{ vertical: 'top', horizontal: 'center' }}
        sx={{ mt: { xs: 7, sm: 8, md: 1 } }}
      >
        <Alert
          onClose={handleSnackbarClose}
          severity={snackbarType}
          variant="filled"
          sx={{ minWidth: 300 }}
        >
          {snackbarType === 'error' && (
            <AlertTitle>Error</AlertTitle>
          )}
          {snackbarType === 'warning' && (
            <AlertTitle>Warning</AlertTitle>
          )}
          {snackbarMessage}
        </Alert>
      </Snackbar>
    </ErrorContext.Provider>
  );
}

export function useError() {
  const context = useContext(ErrorContext);
  if (context === undefined) {
    throw new Error('useError must be used within an ErrorProvider');
  }
  return context;
}

// Convenience hooks for specific error types
export function useAudioError() {
  const { showError, clearError } = useError();
  
  const showAudioError = (message: string, showFallback = true) => {
    showError({
      message,
      type: 'error',
      source: 'audio',
      showFallback,
      persistent: showFallback,
    });
  };
  
  return { showAudioError, clearError };
}

export function useApiError() {
  const { showError, clearError } = useError();
  
  const showApiError = (message: string, persistent = false) => {
    showError({
      message,
      type: 'error',
      source: 'api',
      persistent,
    });
  };
  
  return { showApiError, clearError };
}

export function useAuthError() {
  const { showError, clearError } = useError();
  
  const showAuthError = (message: string) => {
    showError({
      message,
      type: 'error',
      source: 'auth',
      persistent: true,
    });
  };
  
  return { showAuthError, clearError };
}
import { createContext, useContext, useState, ReactNode } from 'react';
import { Snackbar, Alert, AlertTitle } from '@mui/material';

interface ErrorContextType {
  showError: (message: string, severity?: 'error' | 'warning' | 'info' | 'success') => void;
  clearError: () => void;
}

const ErrorContext = createContext<ErrorContextType | undefined>(undefined);

export function ErrorProvider({ children }: { children: ReactNode }) {
  const [error, setError] = useState<{
    message: string;
    severity: 'error' | 'warning' | 'info' | 'success';
  } | null>(null);

  const showError = (message: string, severity: 'error' | 'warning' | 'info' | 'success' = 'error') => {
    setError({ message, severity });
  };

  const clearError = () => {
    setError(null);
  };

  return (
    <ErrorContext.Provider value={{ showError, clearError }}>
      {children}
      <Snackbar
        open={!!error}
        autoHideDuration={6000}
        onClose={clearError}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'left' }}
      >
        {error && (
          <Alert 
            onClose={clearError} 
            severity={error.severity}
            variant="filled"
            sx={{ width: '100%' }}
          >
            {error.severity === 'error' && (
              <AlertTitle>Fehler</AlertTitle>
            )}
            {error.severity === 'warning' && (
              <AlertTitle>Warnung</AlertTitle>
            )}
            {error.severity === 'info' && (
              <AlertTitle>Information</AlertTitle>
            )}
            {error.severity === 'success' && (
              <AlertTitle>Erfolg</AlertTitle>
            )}
            {error.message}
          </Alert>
        )}
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
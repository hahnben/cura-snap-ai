import React from 'react';
import { Box, Typography, Button, Container } from '@mui/material';

interface Props {
  children: React.ReactNode;
}

interface State {
  hasError: boolean;
  error?: Error;
}

export class ErrorBoundary extends React.Component<Props, State> {
  constructor(props: Props) {
    super(props);
    this.state = { hasError: false };
  }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, errorInfo: React.ErrorInfo) {
    console.error('ErrorBoundary caught an error:', error, errorInfo);
  }

  render() {
    if (this.state.hasError) {
      return (
        <Container maxWidth="md">
          <Box sx={{ py: 4, textAlign: 'center' }}>
            <Typography variant="h4" component="h1" gutterBottom color="error">
              Something went wrong
            </Typography>
            <Typography variant="body1" sx={{ mb: 2 }}>
              {this.state.error?.message || 'An unexpected error occurred'}
            </Typography>
            <Button 
              variant="contained" 
              onClick={() => window.location.reload()}
            >
              Reload Page
            </Button>
            <Box sx={{ mt: 4, p: 2, bgcolor: 'grey.100', textAlign: 'left' }}>
              <Typography variant="h6" gutterBottom>Debug Info:</Typography>
              <pre style={{ fontSize: '12px', wordWrap: 'break-word' }}>
                {this.state.error?.stack}
              </pre>
            </Box>
          </Box>
        </Container>
      );
    }

    return this.props.children;
  }
}
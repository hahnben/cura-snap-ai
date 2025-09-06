import React, { Component, ReactNode } from 'react';
import { Box, Typography, Button, Paper, Stack } from '@mui/material';
import { ErrorOutline, Refresh } from '@mui/icons-material';

interface Props {
  children: ReactNode;
  fallback?: ReactNode;
}

interface State {
  hasError: boolean;
  error?: Error;
  errorInfo?: React.ErrorInfo;
}

export class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props);
    this.state = { hasError: false };
  }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true };
  }

  componentDidCatch(error: Error, errorInfo: React.ErrorInfo) {
    this.setState({
      error,
      errorInfo,
    });
    
    // Log error to monitoring service in production
    if (import.meta.env.PROD) {
      console.error('ErrorBoundary caught an error:', error, errorInfo);
    }
  }

  handleRetry = () => {
    this.setState({ hasError: false, error: undefined, errorInfo: undefined });
  };

  render() {
    if (this.state.hasError) {
      if (this.props.fallback) {
        return this.props.fallback;
      }

      return (
        <Box
          display="flex"
          justifyContent="center"
          alignItems="center"
          minHeight="100vh"
          p={3}
          bgcolor="background.default"
        >
          <Paper
            elevation={2}
            sx={{
              p: 4,
              maxWidth: 500,
              width: '100%',
              textAlign: 'center',
            }}
          >
            <Stack spacing={3} alignItems="center">
              <ErrorOutline
                color="error"
                sx={{ fontSize: 64 }}
                aria-label="Error"
              />
              
              <Typography
                variant="h5"
                component="h1"
                gutterBottom
                color="text.primary"
              >
                Something went wrong
              </Typography>
              
              <Typography
                variant="body1"
                color="text.secondary"
                sx={{ mb: 3 }}
              >
                We're sorry, but there was an unexpected error. 
                Please try refreshing the page or contact support if the problem persists.
              </Typography>

              <Button
                variant="contained"
                startIcon={<Refresh />}
                onClick={this.handleRetry}
                size="large"
                aria-label="Try again"
              >
                Try Again
              </Button>

              {import.meta.env.DEV && this.state.error && (
                <Box
                  component="details"
                  sx={{
                    mt: 3,
                    p: 2,
                    bgcolor: 'grey.100',
                    borderRadius: 1,
                    maxWidth: '100%',
                    overflow: 'auto',
                  }}
                >
                  <Typography
                    component="summary"
                    variant="body2"
                    sx={{ cursor: 'pointer', fontWeight: 500, mb: 1 }}
                  >
                    Error Details (Development Only)
                  </Typography>
                  <Typography
                    variant="caption"
                    component="pre"
                    sx={{
                      whiteSpace: 'pre-wrap',
                      wordBreak: 'break-word',
                      fontSize: '0.75rem',
                    }}
                  >
                    {this.state.error.toString()}
                    {this.state.errorInfo?.componentStack}
                  </Typography>
                </Box>
              )}
            </Stack>
          </Paper>
        </Box>
      );
    }

    return this.props.children;
  }
}
import { Box, CircularProgress, Typography } from '@mui/material';

interface LoadingSpinnerProps {
  message?: string;
  size?: number;
}

export function LoadingSpinner({ 
  message = 'Loading...', 
  size = 40 
}: LoadingSpinnerProps) {
  return (
    <Box
      display="flex"
      flexDirection="column"
      justifyContent="center"
      alignItems="center"
      minHeight="100vh"
      gap={2}
      role="progressbar"
      aria-label={message}
    >
      <CircularProgress 
        size={size} 
        thickness={4}
        sx={{ color: 'primary.main' }}
      />
      <Typography 
        variant="body2" 
        color="text.secondary"
        aria-live="polite"
      >
        {message}
      </Typography>
    </Box>
  );
}
import { Box, Typography } from '@mui/material';

export function ChatInterface() {
  return (
    <Box
      sx={{
        height: '100%',
        display: 'flex',
        flexDirection: 'column',
        justifyContent: 'center',
        alignItems: 'center',
        p: 4,
      }}
    >
      <Typography variant="h4" color="primary" gutterBottom>
        CuraSnap AI
      </Typography>
      <Typography variant="body1" color="text.secondary">
        SOAP Note Assistant - Development Mode
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mt: 2 }}>
        Chat interface is being built...
      </Typography>
    </Box>
  );
}
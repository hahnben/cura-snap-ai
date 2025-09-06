import { Box } from '@mui/material';
import { DashboardLayout } from '../components/layout/DashboardLayout';
import { ChatInterface } from '../components/chat/ChatInterface';

export function DashboardPage() {
  return (
    <DashboardLayout>
      <Box
        sx={{
          height: '100%',
          display: 'flex',
          flexDirection: 'column',
        }}
      >
        <ChatInterface />
      </Box>
    </DashboardLayout>
  );
}
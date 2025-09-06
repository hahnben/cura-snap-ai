import { Box, Typography } from '@mui/material';

interface DashboardLayoutProps {
  children: React.ReactNode;
}

export function DashboardLayout({ children }: DashboardLayoutProps) {
  return (
    <Box sx={{ height: '100vh', width: '100vw', bgcolor: 'background.default' }}>
      {children}
    </Box>
  );
}
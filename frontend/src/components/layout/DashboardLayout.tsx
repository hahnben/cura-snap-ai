import { useState } from 'react';
import {
  Box,
  AppBar,
  Toolbar,
  Typography,
  IconButton,
  useMediaQuery,
  useTheme,
} from '@mui/material';
import { Menu as MenuIcon } from '@mui/icons-material';
import { Sidebar } from '../navigation/Sidebar';
import { SkipLink } from '../ui/SkipLink';
import { PatientSessionProvider } from '../../contexts/PatientSessionContext';

interface DashboardLayoutProps {
  children: React.ReactNode;
}

export function DashboardLayout({ children }: DashboardLayoutProps) {
  const theme = useTheme();
  const isMobile = useMediaQuery(theme.breakpoints.down('lg'));
  const [mobileOpen, setMobileOpen] = useState(false);

  const handleDrawerToggle = () => {
    setMobileOpen(!mobileOpen);
  };

  return (
    <PatientSessionProvider>
      {/* Skip Link for Accessibility */}
      <SkipLink href="#main-content">
        Skip to main content
      </SkipLink>
      
      <Box sx={{ display: 'flex', height: '100vh' }}>
        {/* Mobile AppBar */}
        {isMobile && (
          <AppBar
            position="fixed"
            sx={{
              zIndex: (theme) => theme.zIndex.drawer + 1,
              display: { lg: 'none' },
            }}
          >
            <Toolbar>
              <IconButton
                color="inherit"
                aria-label="open drawer"
                edge="start"
                onClick={handleDrawerToggle}
                sx={{ mr: 2 }}
              >
                <MenuIcon />
              </IconButton>
              <Typography variant="h6" noWrap component="div">
                CuraSnap AI
              </Typography>
            </Toolbar>
          </AppBar>
        )}

        {/* Sidebar */}
        <Sidebar
          open={isMobile ? mobileOpen : true}
          onClose={() => setMobileOpen(false)}
          variant={isMobile ? 'temporary' : 'permanent'}
        />

        {/* Main Content Area */}
        <Box
          id="main-content"
          component="main"
          role="main"
          aria-label="Main application content"
          tabIndex={-1}
          sx={{
            flexGrow: 1,
            height: '100vh',
            overflow: 'hidden',
            backgroundColor: 'background.default',
            '&:focus': {
              outline: 'none',
            },
            ...(isMobile && {
              marginTop: '64px', // AppBar height
            }),
          }}
        >
          {children}
        </Box>
      </Box>
    </PatientSessionProvider>
  );
}
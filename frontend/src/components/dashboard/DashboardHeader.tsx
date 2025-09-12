import { useState } from 'react';
import {
  AppBar,
  Toolbar,
  Typography,
  Chip,
  IconButton,
  Menu,
  MenuItem,
  useMediaQuery,
  useTheme,
} from '@mui/material';
import {
  Logout,
  AccountCircle,
} from '@mui/icons-material';
import { DashboardHeaderProps } from '../../types/chat.types';

export function DashboardHeader({ user, onSignOut, timeRemaining, currentPatient }: DashboardHeaderProps) {
  const theme = useTheme();
  const isMobile = useMediaQuery(theme.breakpoints.down('lg'));
  const [anchorEl, setAnchorEl] = useState<null | HTMLElement>(null);

  // Format remaining time
  const formatTime = (ms: number) => {
    const minutes = Math.floor(ms / 60000);
    const seconds = Math.floor((ms % 60000) / 1000);
    return `${minutes}:${seconds.toString().padStart(2, '0')}`;
  };

  const handleMenuClick = (event: React.MouseEvent<HTMLElement>) => {
    setAnchorEl(event.currentTarget);
  };

  const handleMenuClose = () => {
    setAnchorEl(null);
  };

  const handleSignOut = async () => {
    handleMenuClose();
    await onSignOut();
  };

  // Only render on desktop (matches original logic)
  if (isMobile) {
    return null;
  }

  return (
    <AppBar position="static" elevation={0} sx={{ backgroundColor: 'background.paper', borderBottom: 1, borderColor: 'divider' }}>
      <Toolbar sx={{ minHeight: 64 }}>
        <Typography variant="h6" component="div" sx={{ flexGrow: 1, color: 'text.primary' }}>
          SOAP Assistant
          {currentPatient && (
            <Typography variant="body2" color="text.secondary" sx={{ ml: 1, display: 'inline' }}>
              - {currentPatient.name}
            </Typography>
          )}
        </Typography>
        
        {/* Session Timer */}
        <Chip
          label={`Session: ${formatTime(timeRemaining)}`}
          variant="outlined"
          size="small"
          sx={{ mr: 2 }}
        />
        
        {/* User Menu */}
        <IconButton
          size="large"
          aria-label="account menu"
          aria-controls="menu-appbar"
          aria-haspopup="true"
          onClick={handleMenuClick}
          sx={{ color: 'text.primary' }}
        >
          <AccountCircle />
        </IconButton>
        
        <Menu
          id="menu-appbar"
          anchorEl={anchorEl}
          anchorOrigin={{
            vertical: 'bottom',
            horizontal: 'right',
          }}
          keepMounted
          transformOrigin={{
            vertical: 'top',
            horizontal: 'right',
          }}
          open={Boolean(anchorEl)}
          onClose={handleMenuClose}
        >
          <MenuItem disabled>
            <Typography variant="body2">{user?.email}</Typography>
          </MenuItem>
          <MenuItem onClick={handleSignOut}>
            <Logout sx={{ mr: 1 }} />
            Abmelden
          </MenuItem>
        </Menu>
      </Toolbar>
    </AppBar>
  );
}
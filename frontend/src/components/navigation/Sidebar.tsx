import { useState } from 'react';
import {
  Box,
  Drawer,
  List,
  ListItem,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Divider,
  Typography,
  Button,
  TextField,
  IconButton,
  Chip,
  Paper,
  useTheme,
  alpha,
} from '@mui/material';
import {
  Chat,
  Psychology,
  Storage,
  History,
  Assessment,
  Settings,
  PersonAdd,
  Edit,
  Check,
  Close,
} from '@mui/icons-material';
import { usePatientSession } from '../../contexts/PatientSessionContext';

const SIDEBAR_WIDTH = 280;

interface SidebarProps {
  open: boolean;
  onClose?: () => void;
  variant?: 'permanent' | 'temporary';
}

interface NavigationSection {
  id: string;
  label: string;
  icon: React.ReactNode;
  active: boolean;
  comingSoon?: boolean;
}

const navigationSections: NavigationSection[] = [
  {
    id: 'soap-assistant',
    label: 'SOAP Assistant',
    icon: <Chat />,
    active: true,
  },
  {
    id: 'clinical-decision',
    label: 'Clinical Decision Support',
    icon: <Psychology />,
    active: false,
    comingSoon: true,
  },
  {
    id: 'medical-database',
    label: 'Medical Database',
    icon: <Storage />,
    active: false,
    comingSoon: true,
  },
  {
    id: 'patient-history',
    label: 'Patient History',
    icon: <History />,
    active: false,
    comingSoon: true,
  },
  {
    id: 'reports-analytics',
    label: 'Reports & Analytics',
    icon: <Assessment />,
    active: false,
    comingSoon: true,
  },
  {
    id: 'settings',
    label: 'Settings',
    icon: <Settings />,
    active: false,
    comingSoon: true,
  },
];

export function Sidebar({ open, onClose, variant = 'permanent' }: SidebarProps) {
  const theme = useTheme();
  const {
    currentPatient,
    startNewPatientSession,
    updatePatientName,
    isEditingPatientName,
    setIsEditingPatientName,
  } = usePatientSession();
  
  const [editingName, setEditingName] = useState('');

  const handleEditPatientName = () => {
    setEditingName(currentPatient?.name || '');
    setIsEditingPatientName(true);
  };

  const handleSavePatientName = () => {
    updatePatientName(editingName);
  };

  const handleCancelEditPatientName = () => {
    setIsEditingPatientName(false);
    setEditingName('');
  };

  const sidebarContent = (
    <Box 
      sx={{ height: '100%', display: 'flex', flexDirection: 'column' }}
      role="navigation"
      aria-label="Main navigation sidebar"
    >
      {/* Header */}
      <Box sx={{ p: 2, borderBottom: 1, borderColor: 'divider' }} role="banner">
        <Typography variant="h6" sx={{ fontWeight: 600, color: 'primary.main' }}>
          CuraSnap AI
        </Typography>
        <Typography variant="caption" color="text.secondary">
          Medical Assistant Platform
        </Typography>
      </Box>

      {/* Patient Session Management */}
      <Box 
        sx={{ p: 2, borderBottom: 1, borderColor: 'divider' }}
        role="region"
        aria-labelledby="patient-session-heading"
      >
        <Typography 
          id="patient-session-heading"
          variant="subtitle2" 
          sx={{ mb: 1.5, fontWeight: 600 }}
        >
          Current Patient Session
        </Typography>
        
        {currentPatient ? (
          <Paper
            elevation={0}
            sx={{
              p: 1.5,
              backgroundColor: alpha(theme.palette.primary.main, 0.05),
              border: 1,
              borderColor: alpha(theme.palette.primary.main, 0.2),
              mb: 1.5,
            }}
          >
            <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 1 }}>
              <Typography variant="body2" color="text.secondary">
                Patient:
              </Typography>
              {!isEditingPatientName && (
                <IconButton
                  size="small"
                  onClick={handleEditPatientName}
                  aria-label="Edit patient name"
                  sx={{ ml: 1 }}
                >
                  <Edit fontSize="small" />
                </IconButton>
              )}
            </Box>
            
            {isEditingPatientName ? (
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                <TextField
                  size="small"
                  value={editingName}
                  onChange={(e) => setEditingName(e.target.value)}
                  placeholder="Patient name..."
                  autoFocus
                  sx={{ flexGrow: 1 }}
                  onKeyPress={(e) => {
                    if (e.key === 'Enter') {
                      e.preventDefault();
                      handleSavePatientName();
                    } else if (e.key === 'Escape') {
                      handleCancelEditPatientName();
                    }
                  }}
                />
                <IconButton size="small" onClick={handleSavePatientName} color="primary">
                  <Check fontSize="small" />
                </IconButton>
                <IconButton size="small" onClick={handleCancelEditPatientName}>
                  <Close fontSize="small" />
                </IconButton>
              </Box>
            ) : (
              <Typography
                variant="body1"
                sx={{
                  fontWeight: 500,
                  cursor: 'pointer',
                  '&:hover': {
                    textDecoration: 'underline',
                  },
                }}
                onClick={handleEditPatientName}
                role="button"
                tabIndex={0}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    handleEditPatientName();
                  }
                }}
              >
                {currentPatient.name}
              </Typography>
            )}
            
            <Typography variant="caption" color="text.secondary">
              Started: {currentPatient.timestamp}
            </Typography>
          </Paper>
        ) : (
          <Paper
            elevation={0}
            sx={{
              p: 1.5,
              backgroundColor: 'background.default',
              border: 1,
              borderColor: 'divider',
              mb: 1.5,
              textAlign: 'center',
            }}
          >
            <Typography variant="body2" color="text.secondary">
              No active patient session
            </Typography>
          </Paper>
        )}
        
        <Button
          variant="contained"
          fullWidth
          startIcon={<PersonAdd />}
          onClick={() => startNewPatientSession()}
          sx={{
            borderRadius: 2,
            textTransform: 'none',
            fontWeight: 500,
            minHeight: 44,
          }}
        >
          Next Patient
        </Button>
      </Box>

      {/* Navigation Sections */}
      <Box 
        sx={{ flexGrow: 1, overflowY: 'auto' }}
        role="main"
        aria-labelledby="navigation-heading"
      >
        <Typography 
          id="navigation-heading"
          variant="subtitle2" 
          sx={{ p: 2, pb: 1, fontWeight: 600 }}
        >
          Medical Assistants
        </Typography>
        
        <List 
          sx={{ px: 1 }}
          role="menu"
          aria-labelledby="navigation-heading"
        >
          {navigationSections.map((section) => (
            <ListItem key={section.id} disablePadding sx={{ mb: 0.5 }} role="none">
              <ListItemButton
                disabled={!section.active}
                role="menuitem"
                aria-label={`${section.label}${section.comingSoon ? ' (Coming Soon)' : ''}${section.active ? ' (Currently Active)' : ''}`}
                aria-current={section.active ? 'page' : undefined}
                tabIndex={section.active ? 0 : -1}
                sx={{
                  borderRadius: 2,
                  minHeight: 48,
                  backgroundColor: section.active ? alpha(theme.palette.primary.main, 0.1) : 'transparent',
                  border: section.active ? 1 : 0,
                  borderColor: section.active ? alpha(theme.palette.primary.main, 0.2) : 'transparent',
                  '&:hover': {
                    backgroundColor: section.active
                      ? alpha(theme.palette.primary.main, 0.15)
                      : alpha(theme.palette.action.hover, 0.04),
                  },
                  '&.Mui-disabled': {
                    opacity: 0.5,
                  },
                  '&:focus': {
                    outline: `2px solid ${theme.palette.primary.main}`,
                    outlineOffset: '2px',
                  },
                }}
              >
                <ListItemIcon
                  sx={{
                    color: section.active ? 'primary.main' : 'text.secondary',
                    minWidth: 40,
                  }}
                >
                  {section.icon}
                </ListItemIcon>
                <ListItemText
                  primary={section.label}
                  sx={{
                    '& .MuiListItemText-primary': {
                      fontSize: '0.875rem',
                      fontWeight: section.active ? 500 : 400,
                      color: section.active ? 'primary.main' : 'text.primary',
                    },
                  }}
                />
                {section.comingSoon && (
                  <Chip
                    label="Coming Soon"
                    size="small"
                    variant="outlined"
                    sx={{
                      fontSize: '0.675rem',
                      height: 20,
                      color: 'text.secondary',
                      borderColor: 'text.secondary',
                    }}
                  />
                )}
              </ListItemButton>
            </ListItem>
          ))}
        </List>
      </Box>

      {/* Footer */}
      <Divider />
      <Box sx={{ p: 2 }}>
        <Typography variant="caption" color="text.secondary" align="center" display="block">
          CuraSnap AI v1.0 - Medical MVP
        </Typography>
      </Box>
    </Box>
  );

  return (
    <Drawer
      variant={variant}
      open={open}
      onClose={onClose}
      sx={{
        width: SIDEBAR_WIDTH,
        flexShrink: 0,
        '& .MuiDrawer-paper': {
          width: SIDEBAR_WIDTH,
          boxSizing: 'border-box',
          borderRight: 1,
          borderColor: 'divider',
          backgroundColor: 'background.paper',
        },
      }}
    >
      {sidebarContent}
    </Drawer>
  );
}
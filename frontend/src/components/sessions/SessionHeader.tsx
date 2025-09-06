import { useState } from 'react';
import {
  Box,
  Typography,
  IconButton,
  Button,
  Chip,
  TextField,
  Stack,
  useTheme,
} from '@mui/material';
import {
  Edit as EditIcon,
  Check as CheckIcon,
  Close as CloseIcon,
  PersonAdd,
  AccessTime,
} from '@mui/icons-material';
import { format } from 'date-fns';
import { Session } from '../../types/session';

interface SessionHeaderProps {
  session: Session | null;
  onNewSession: () => void;
  onUpdatePatientName?: (sessionId: string, name: string) => void;
}

export function SessionHeader({
  session,
  onNewSession,
  onUpdatePatientName,
}: SessionHeaderProps) {
  const [isEditing, setIsEditing] = useState(false);
  const [editName, setEditName] = useState('');
  const theme = useTheme();

  const handleEditStart = () => {
    setEditName(session?.patientName || '');
    setIsEditing(true);
  };

  const handleEditCancel = () => {
    setIsEditing(false);
    setEditName('');
  };

  const handleEditSave = () => {
    if (session && editName.trim() && onUpdatePatientName) {
      onUpdatePatientName(session.id, editName.trim());
    }
    setIsEditing(false);
    setEditName('');
  };

  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') {
      handleEditSave();
    } else if (e.key === 'Escape') {
      handleEditCancel();
    }
  };

  return (
    <Box
      sx={{
        p: 2,
        bgcolor: 'background.paper',
        borderBottom: '1px solid',
        borderColor: 'divider',
      }}
    >
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        justifyContent="space-between"
        alignItems={{ xs: 'stretch', sm: 'center' }}
        spacing={2}
      >
        <Box sx={{ flex: 1 }}>
          {session ? (
            <Stack spacing={1}>
              <Box display="flex" alignItems="center" gap={1}>
                {isEditing ? (
                  <Box display="flex" alignItems="center" gap={1} flex={1}>
                    <TextField
                      value={editName}
                      onChange={(e) => setEditName(e.target.value)}
                      onKeyDown={handleKeyPress}
                      placeholder="Enter patient name"
                      size="small"
                      autoFocus
                      sx={{
                        flex: 1,
                        maxWidth: 300,
                      }}
                      inputProps={{
                        'aria-label': 'Patient name',
                      }}
                    />
                    <IconButton
                      onClick={handleEditSave}
                      size="small"
                      color="primary"
                      disabled={!editName.trim()}
                      aria-label="Save patient name"
                    >
                      <CheckIcon fontSize="small" />
                    </IconButton>
                    <IconButton
                      onClick={handleEditCancel}
                      size="small"
                      aria-label="Cancel edit"
                    >
                      <CloseIcon fontSize="small" />
                    </IconButton>
                  </Box>
                ) : (
                  <Box display="flex" alignItems="center" gap={1} flex={1}>
                    <Typography
                      variant="h6"
                      component="h2"
                      fontWeight={500}
                      sx={{ color: 'primary.main' }}
                    >
                      Patient: {session.patientName || 'Unnamed Patient'}
                    </Typography>
                    {onUpdatePatientName && (
                      <IconButton
                        onClick={handleEditStart}
                        size="small"
                        aria-label="Edit patient name"
                        sx={{ ml: 1 }}
                      >
                        <EditIcon fontSize="small" />
                      </IconButton>
                    )}
                  </Box>
                )}
              </Box>

              <Stack
                direction={{ xs: 'column', sm: 'row' }}
                spacing={2}
                alignItems={{ xs: 'flex-start', sm: 'center' }}
              >
                <Chip
                  icon={<AccessTime />}
                  label={`Started: ${format(new Date(session.createdAt), 'MMM d, yyyy h:mm a')}`}
                  size="small"
                  variant="outlined"
                  sx={{
                    bgcolor: 'background.default',
                  }}
                />
                
                <Chip
                  label={session.status === 'active' ? 'Active Session' : 'Completed'}
                  size="small"
                  color={session.status === 'active' ? 'success' : 'default'}
                  sx={{
                    fontWeight: 500,
                  }}
                />
              </Stack>
            </Stack>
          ) : (
            <Typography variant="h6" color="text.secondary">
              No active session
            </Typography>
          )}
        </Box>

        <Box
          sx={{
            display: 'flex',
            gap: 1,
            justifyContent: { xs: 'stretch', sm: 'flex-end' },
          }}
        >
          <Button
            variant={session ? 'outlined' : 'contained'}
            startIcon={<PersonAdd />}
            onClick={onNewSession}
            sx={{
              minWidth: { xs: '100%', sm: 'auto' },
              whiteSpace: 'nowrap',
            }}
            aria-label="Start new patient session"
          >
            {session ? 'Next Patient' : 'Start Session'}
          </Button>
        </Box>
      </Stack>
    </Box>
  );
}
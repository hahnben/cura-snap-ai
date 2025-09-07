import { useState, useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  Box,
  Card,
  CardContent,
  TextField,
  Button,
  Typography,
  CircularProgress,
  Stack,
} from '@mui/material';
import { Lock, Save } from '@mui/icons-material';
import { createClient } from '@supabase/supabase-js';
import { useError } from '../contexts/ErrorContext';

const supabaseUrl = import.meta.env.VITE_SUPABASE_URL || 'http://localhost:54321';
const supabaseAnonKey = import.meta.env.VITE_SUPABASE_ANON_KEY || 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZS1kZW1vIiwicm9sZSI6ImFub24iLCJleHAiOjE5ODM4MTI5OTZ9.CRXP1A7WOeoJeXxjNni43kdQwgnWNReilDMblYTn_I0';
const supabase = createClient(supabaseUrl, supabaseAnonKey);

export function ResetPasswordPage() {
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const { showError } = useError();

  useEffect(() => {
    // Set session from URL parameters
    const accessToken = searchParams.get('access_token');
    const refreshToken = searchParams.get('refresh_token');
    
    if (accessToken && refreshToken) {
      supabase.auth.setSession({
        access_token: accessToken,
        refresh_token: refreshToken,
      });
    }
  }, [searchParams]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!password.trim()) {
      showError('Bitte geben Sie ein neues Passwort ein.');
      return;
    }

    if (password.length < 6) {
      showError('Das Passwort muss mindestens 6 Zeichen lang sein.');
      return;
    }

    if (password !== confirmPassword) {
      showError('Die Passwörter stimmen nicht überein.');
      return;
    }

    setLoading(true);
    try {
      const { error } = await supabase.auth.updateUser({
        password: password
      });

      if (error) {
        showError(`Fehler beim Passwort-Update: ${error.message}`);
      } else {
        showError('Passwort erfolgreich aktualisiert!', 'success');
        setTimeout(() => navigate('/'), 2000);
      }
    } catch (err) {
      showError('Ein unerwarteter Fehler ist aufgetreten.');
      console.error('Password update error:', err);
    } finally {
      setLoading(false);
    }
  };

  return (
    <Box
      sx={{
        minHeight: '100vh',
        bgcolor: 'background.default',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        p: 3,
      }}
    >
      <Card 
        elevation={3} 
        sx={{ 
          maxWidth: 400, 
          width: '100%',
          borderRadius: 2,
        }}
      >
        <CardContent sx={{ p: 4 }}>
          <Stack spacing={3} alignItems="center">
            <Box textAlign="center">
              <Typography 
                variant="h5" 
                component="h1" 
                color="primary" 
                gutterBottom
                sx={{ fontWeight: 600 }}
              >
                Neues Passwort setzen
              </Typography>
              <Typography 
                variant="body2" 
                color="text.secondary"
              >
                Geben Sie Ihr neues Passwort ein
              </Typography>
            </Box>

            <Box
              component="form"
              onSubmit={handleSubmit}
              sx={{ width: '100%' }}
            >
              <Stack spacing={3}>
                <TextField
                  fullWidth
                  label="Neues Passwort"
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  disabled={loading}
                  required
                  InputProps={{
                    startAdornment: <Lock sx={{ mr: 1, color: 'text.secondary' }} />,
                  }}
                  sx={{
                    '& .MuiOutlinedInput-root': {
                      borderRadius: 2,
                    },
                  }}
                />

                <TextField
                  fullWidth
                  label="Passwort bestätigen"
                  type="password"
                  value={confirmPassword}
                  onChange={(e) => setConfirmPassword(e.target.value)}
                  disabled={loading}
                  required
                  InputProps={{
                    startAdornment: <Lock sx={{ mr: 1, color: 'text.secondary' }} />,
                  }}
                  sx={{
                    '& .MuiOutlinedInput-root': {
                      borderRadius: 2,
                    },
                  }}
                />

                <Button
                  type="submit"
                  variant="contained"
                  size="large"
                  fullWidth
                  disabled={loading}
                  startIcon={
                    loading ? (
                      <CircularProgress size={16} color="inherit" />
                    ) : (
                      <Save />
                    )
                  }
                  sx={{
                    py: 1.5,
                    borderRadius: 2,
                    textTransform: 'none',
                    fontSize: '1rem',
                  }}
                >
                  {loading ? 'Speichern...' : 'Passwort speichern'}
                </Button>
              </Stack>
            </Box>
          </Stack>
        </CardContent>
      </Card>
    </Box>
  );
}
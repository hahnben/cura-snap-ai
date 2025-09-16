import { useState } from 'react';
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
import { Email, Login, Lock } from '@mui/icons-material';
import { useAuth } from '../contexts/AuthContext';
import { useError } from '../contexts/ErrorContext';
import { validateEmail, validatePassword, checkLoginRateLimit, loginRateLimiter } from '../utils/inputValidation';

export function LoginPage() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const { signIn } = useAuth();
  const { showError } = useError();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    // Enhanced email validation
    const emailValidation = validateEmail(email);
    if (!emailValidation.isValid) {
      showError(emailValidation.error!);
      return;
    }

    // Basic password validation (not full requirements for login)
    if (!password.trim()) {
      showError('Bitte geben Sie ein Passwort ein.');
      return;
    }

    if (password.length > 128) {
      showError('Passwort ist zu lang.');
      return;
    }

    // Check rate limiting
    const rateLimitCheck = checkLoginRateLimit(emailValidation.sanitizedValue!);
    if (!rateLimitCheck.allowed) {
      showError(rateLimitCheck.message!);
      return;
    }

    setLoading(true);

    try {
      // Record login attempt for rate limiting
      loginRateLimiter.recordAttempt(emailValidation.sanitizedValue!);

      const { error } = await signIn(emailValidation.sanitizedValue!, password);

      if (error) {
        // Sanitize error messages to prevent information disclosure
        let errorMessage = 'Anmeldung fehlgeschlagen.';

        if (error.message.includes('Invalid login credentials')) {
          errorMessage = 'E-Mail oder Passwort sind nicht korrekt.';
        } else if (error.message.includes('Email not confirmed')) {
          errorMessage = 'Bitte bestätigen Sie Ihre E-Mail-Adresse.';
        } else if (error.message.includes('Too many requests')) {
          errorMessage = 'Zu viele Anmeldeversuche. Bitte warten Sie einen Moment.';
        }

        showError(errorMessage);
      } else {
        // Clear rate limiting on successful login
        loginRateLimiter.clear(emailValidation.sanitizedValue!);
        showError('Anmeldung erfolgreich!', 'success');
      }
    } catch (err) {
      // Generic error message to prevent information disclosure
      showError('Ein Fehler ist aufgetreten. Bitte versuchen Sie es später erneut.');

      // Log error for debugging (only in development)
      if (import.meta.env.DEV) {
        console.error('Login error:', err);
      }
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
            {/* Header */}
            <Box textAlign="center">
              <Typography 
                variant="h4" 
                component="h1" 
                color="primary" 
                gutterBottom
                sx={{ fontWeight: 600 }}
              >
                CuraSnap AI
              </Typography>
              <Typography 
                variant="h6" 
                color="text.secondary"
                gutterBottom
              >
                SOAP Note Assistant
              </Typography>
              <Typography 
                variant="body2" 
                color="text.secondary"
              >
                Sichere Anmeldung für medizinische Fachkräfte
              </Typography>
            </Box>


            {/* Login Form */}
            <Box
              component="form"
              onSubmit={handleSubmit}
              sx={{ width: '100%' }}
            >
              <Stack spacing={3}>
                <TextField
                  fullWidth
                  label="E-Mail-Adresse"
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  disabled={loading}
                  required
                  InputProps={{
                    startAdornment: <Email sx={{ mr: 1, color: 'text.secondary' }} />,
                  }}
                  sx={{
                    '& .MuiOutlinedInput-root': {
                      borderRadius: 2,
                    },
                  }}
                  placeholder="ihre.email@klinik.de"
                />

                <TextField
                  fullWidth
                  label="Passwort"
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
                  placeholder="Ihr Passwort"
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
                      <Login />
                    )
                  }
                  sx={{
                    py: 1.5,
                    borderRadius: 2,
                    textTransform: 'none',
                    fontSize: '1rem',
                  }}
                >
                  {loading ? 'Anmeldung...' : 'Anmelden'}
                </Button>
              </Stack>
            </Box>

            {/* Info */}
            <Box sx={{ textAlign: 'center', mt: 2 }}>
              <Typography variant="caption" color="text.secondary">
                <strong>Sicherheitshinweis:</strong><br />
                • Sichere Passwort-basierte Authentifizierung<br />
                • HIPAA-konforme Anmeldung<br />
                • Automatische 30-Minuten Session-Timeout
              </Typography>
            </Box>
          </Stack>
        </CardContent>
      </Card>
    </Box>
  );
}
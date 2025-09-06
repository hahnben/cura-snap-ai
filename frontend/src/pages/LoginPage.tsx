import { useState } from 'react';
import {
  Box,
  Card,
  CardContent,
  TextField,
  Button,
  Typography,
  CircularProgress,
  Alert,
  Stack,
} from '@mui/material';
import { Email, Login } from '@mui/icons-material';
import { useAuth } from '../contexts/AuthContext';
import { useError } from '../contexts/ErrorContext';

export function LoginPage() {
  const [email, setEmail] = useState('');
  const [loading, setLoading] = useState(false);
  const [emailSent, setEmailSent] = useState(false);
  const { signIn } = useAuth();
  const { showError } = useError();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    
    if (!email.trim()) {
      showError('Bitte geben Sie eine E-Mail-Adresse ein.');
      return;
    }

    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    if (!emailRegex.test(email)) {
      showError('Bitte geben Sie eine gültige E-Mail-Adresse ein.');
      return;
    }

    setLoading(true);
    try {
      const { error } = await signIn(email);
      
      if (error) {
        showError(`Anmeldung fehlgeschlagen: ${error.message}`);
      } else {
        setEmailSent(true);
        showError(
          'Ein Magic Link wurde an Ihre E-Mail-Adresse gesendet. Bitte überprüfen Sie Ihr Postfach.',
          'success'
        );
      }
    } catch (err) {
      showError('Ein unerwarteter Fehler ist aufgetreten. Bitte versuchen Sie es erneut.');
      console.error('Login error:', err);
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

            {/* Success Message */}
            {emailSent && (
              <Alert severity="success" sx={{ width: '100%' }}>
                <Typography variant="body2">
                  <strong>E-Mail gesendet!</strong><br />
                  Klicken Sie auf den Link in Ihrer E-Mail, um sich anzumelden.
                  Der Link ist 1 Stunde gültig.
                </Typography>
              </Alert>
            )}

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
                  disabled={loading || emailSent}
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

                <Button
                  type="submit"
                  variant="contained"
                  size="large"
                  fullWidth
                  disabled={loading || emailSent}
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
                  {loading 
                    ? 'Magic Link wird gesendet...' 
                    : emailSent 
                      ? 'E-Mail gesendet'
                      : 'Magic Link senden'
                  }
                </Button>
              </Stack>
            </Box>

            {/* Info */}
            <Box sx={{ textAlign: 'center', mt: 2 }}>
              <Typography variant="caption" color="text.secondary">
                <strong>Sicherheitshinweis:</strong><br />
                • Keine Passwörter erforderlich<br />
                • HIPAA-konforme Authentifizierung<br />
                • Automatische 30-Minuten Session-Timeout
              </Typography>
            </Box>

            {/* Resend Link */}
            {emailSent && (
              <Button
                variant="text"
                size="small"
                onClick={() => {
                  setEmailSent(false);
                  setEmail('');
                }}
                sx={{ textTransform: 'none' }}
              >
                Mit anderer E-Mail-Adresse anmelden
              </Button>
            )}
          </Stack>
        </CardContent>
      </Card>
    </Box>
  );
}
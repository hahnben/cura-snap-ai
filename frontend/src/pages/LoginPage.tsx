import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Box,
  Card,
  CardContent,
  TextField,
  Button,
  Typography,
  Alert,
  Stack,
  InputAdornment,
} from '@mui/material';
import { Email, Login as LoginIcon } from '@mui/icons-material';
import { useAuth } from '../contexts/AuthContext';

export function LoginPage() {
  const [email, setEmail] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [message, setMessage] = useState<{ text: string; type: 'success' | 'error' } | null>(null);
  const { signInWithEmail } = useAuth();
  const navigate = useNavigate();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    
    if (!email.trim()) {
      setMessage({ text: 'Please enter your email address', type: 'error' });
      return;
    }

    setIsLoading(true);
    setMessage(null);
    
    try {
      const { error } = await signInWithEmail(email.trim());
      
      if (error) {
        setMessage({ 
          text: error.message || 'Failed to send magic link', 
          type: 'error' 
        });
      } else {
        setMessage({
          text: 'Magic link sent! Check your email and click the link to sign in.',
          type: 'success'
        });
      }
    } catch (error) {
      setMessage({ 
        text: 'An unexpected error occurred. Please try again.', 
        type: 'error' 
      });
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <Box
      display="flex"
      justifyContent="center"
      alignItems="center"
      minHeight="100vh"
      bgcolor="background.default"
      p={3}
    >
      <Card
        elevation={3}
        sx={{
          maxWidth: 400,
          width: '100%',
          p: 2,
        }}
      >
        <CardContent>
          <Stack spacing={3} alignItems="center">
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
                variant="subtitle1"
                color="text.secondary"
              >
                SOAP Note Assistant
              </Typography>
            </Box>

            <Box component="form" onSubmit={handleSubmit} width="100%">
              <Stack spacing={3}>
                <TextField
                  fullWidth
                  label="Email Address"
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  required
                  autoFocus
                  autoComplete="email"
                  disabled={isLoading}
                  InputProps={{
                    startAdornment: (
                      <InputAdornment position="start">
                        <Email color="action" />
                      </InputAdornment>
                    ),
                  }}
                  aria-label="Enter your email address"
                />

                {message && (
                  <Alert 
                    severity={message.type}
                    sx={{ borderRadius: 2 }}
                    role="alert"
                    aria-live="polite"
                  >
                    {message.text}
                  </Alert>
                )}

                <Button
                  type="submit"
                  fullWidth
                  variant="contained"
                  size="large"
                  disabled={isLoading}
                  startIcon={<LoginIcon />}
                  sx={{
                    py: 1.5,
                    fontWeight: 500,
                  }}
                  aria-label="Send magic link to email"
                >
                  {isLoading ? 'Sending...' : 'Send Magic Link'}
                </Button>
              </Stack>
            </Box>

            <Typography
              variant="caption"
              color="text.secondary"
              textAlign="center"
              sx={{ mt: 2 }}
            >
              We'll send you a secure link to sign in without a password.
            </Typography>
          </Stack>
        </CardContent>
      </Card>
    </Box>
  );
}
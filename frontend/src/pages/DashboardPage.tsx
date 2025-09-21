import { useState, useEffect, useCallback } from 'react';
import {
  Box,
  LinearProgress,
  Alert,
  Tabs,
  Tab,
  Typography,
  Paper,
} from '@mui/material';
import { useAuth } from '../contexts/AuthContext';
import { usePatientSession, useInitializePatientSession } from '../contexts/PatientSessionContext';
import { DashboardHeader } from '../components/dashboard/DashboardHeader';
import { ChatInterface } from '../components/chat/ChatInterface';
import type { ChatMessage } from '../types/chat.types';


// Custom TabPanel component for content switching
interface TabPanelProps {
  children?: React.ReactNode;
  index: string;
  value: string;
}

function TabPanel(props: TabPanelProps) {
  const { children, value, index, ...other } = props;

  return (
    <div
      role="tabpanel"
      hidden={value !== index}
      id={`simple-tabpanel-${index}`}
      aria-labelledby={`simple-tab-${index}`}
      {...other}
      style={{ height: '100%', display: value === index ? 'flex' : 'none', flexDirection: 'column' }}
    >
      {value === index && children}
    </div>
  );
}

export function DashboardPage() {
  const { user, signOut, timeRemaining } = useAuth();
  const { currentPatient } = usePatientSession();
  const initializePatientSession = useInitializePatientSession();

  // Tab state management
  const [activeTab, setActiveTab] = useState('soap');

  // Simplified state - components manage their own state now
  const [isLoading] = useState(false);
  const [audioPermission, setAudioPermission] = useState<'granted' | 'denied' | 'prompt'>('prompt');

  // Initialize patient session on component mount
  useEffect(() => {
    initializePatientSession();
  }, [initializePatientSession]);

  // Initialize audio permission check
  useEffect(() => {
    navigator.mediaDevices.getUserMedia({ audio: true })
      .then(() => setAudioPermission('granted'))
      .catch(() => setAudioPermission('denied'));
  }, []);

  // Tab change handler
  const handleTabChange = useCallback((event: React.SyntheticEvent, newValue: string) => {
    setActiveTab(newValue);
  }, []);

  // Callback handlers for components
  const handleNewMessage = useCallback((message: ChatMessage) => {
    // Handle new messages from chat interface
    console.log('New message:', message);
  }, []);



  return (
    <Box sx={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      {/* Header Component */}
      <DashboardHeader
        user={user}
        onSignOut={signOut}
        timeRemaining={timeRemaining}
        currentPatient={currentPatient}
      />

      {/* Tab Navigation */}
      <Box sx={{ borderBottom: 1, borderColor: 'divider' }}>
        <Tabs
          value={activeTab}
          onChange={handleTabChange}
          aria-label="Medical Assistant Tabs"
          sx={{ px: 2 }}
        >
          <Tab label="SOAP Assistant" value="soap" />
          <Tab label="Referrals Assistant" value="referrals" disabled />
          <Tab label="Summaries Assistant" value="summaries" disabled />
        </Tabs>
      </Box>

      {/* Tab Content */}
      <Box sx={{ flexGrow: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>

        {/* SOAP Tab Panel - Preserve exact existing functionality */}
        <TabPanel value={activeTab} index="soap">
          <Box sx={{ flexGrow: 1, display: 'flex', flexDirection: 'column', p: 2, gap: 2, overflow: 'hidden' }}>

            {/* Progress Indicator */}
            {isLoading && (
              <LinearProgress sx={{ borderRadius: 1 }} />
            )}

            {/* Audio Permission Warning */}
            {audioPermission === 'denied' && (
              <Alert severity="warning">
                Mikrofonzugriff verweigert. Für Audio-Aufnahmen aktivieren Sie bitte die Mikrofon-Berechtigung.
              </Alert>
            )}

            {/* Chat Interface Component - Exact same as before */}
            <Box sx={{ flexGrow: 1, overflow: 'hidden' }}>
              <ChatInterface
                onMessage={handleNewMessage}
                isProcessing={isLoading}
                placeholder="Beschreiben Sie die Patientenbegegnung..."
                showHeader={false}
                enableAudio={true}
              />
            </Box>
          </Box>
        </TabPanel>

        {/* Referrals Tab Panel - Placeholder */}
        <TabPanel value={activeTab} index="referrals">
          <Box sx={{ flexGrow: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', p: 4 }}>
            <Paper elevation={0} sx={{ p: 4, textAlign: 'center', maxWidth: 400 }}>
              <Typography variant="h6" gutterBottom color="text.secondary">
                Referrals Assistant
              </Typography>
              <Typography variant="body2" color="text.secondary">
                This feature is coming soon. It will help manage patient referrals and specialist appointments.
              </Typography>
            </Paper>
          </Box>
        </TabPanel>

        {/* Summaries Tab Panel - Placeholder */}
        <TabPanel value={activeTab} index="summaries">
          <Box sx={{ flexGrow: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', p: 4 }}>
            <Paper elevation={0} sx={{ p: 4, textAlign: 'center', maxWidth: 400 }}>
              <Typography variant="h6" gutterBottom color="text.secondary">
                Summaries Assistant
              </Typography>
              <Typography variant="body2" color="text.secondary">
                This feature is coming soon. It will provide comprehensive patient summaries and medical history overviews.
              </Typography>
            </Paper>
          </Box>
        </TabPanel>
      </Box>
    </Box>
  );
}
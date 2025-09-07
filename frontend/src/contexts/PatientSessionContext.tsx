import { createContext, useContext, useState, useCallback } from 'react';
import type { ReactNode } from 'react';

export interface PatientSession {
  id: string;
  name: string;
  timestamp: string;
  isActive: boolean;
}

interface PatientSessionContextType {
  currentPatient: PatientSession | null;
  startNewPatientSession: (patientName?: string) => void;
  updatePatientName: (name: string) => void;
  endCurrentSession: () => void;
  isEditingPatientName: boolean;
  setIsEditingPatientName: (editing: boolean) => void;
}

const PatientSessionContext = createContext<PatientSessionContextType | undefined>(undefined);

interface PatientSessionProviderProps {
  children: ReactNode;
}

export function PatientSessionProvider({ children }: PatientSessionProviderProps) {
  const [currentPatient, setCurrentPatient] = useState<PatientSession | null>(null);
  const [isEditingPatientName, setIsEditingPatientName] = useState(false);

  const generateSessionId = () => {
    return `session_${Date.now()}_${Math.random().toString(36).substring(2, 9)}`;
  };

  const formatTimestamp = (date: Date) => {
    return date.toLocaleString('de-DE', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  };

  const startNewPatientSession = useCallback((patientName?: string) => {
    const now = new Date();
    const defaultName = `Patient ${now.toLocaleDateString('de-DE')} ${now.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' })}`;
    
    const newSession: PatientSession = {
      id: generateSessionId(),
      name: patientName || defaultName,
      timestamp: formatTimestamp(now),
      isActive: true,
    };

    setCurrentPatient(newSession);
    setIsEditingPatientName(false);
  }, []);

  const updatePatientName = useCallback((name: string) => {
    if (currentPatient) {
      setCurrentPatient({
        ...currentPatient,
        name: name.trim() || currentPatient.name,
      });
    }
    setIsEditingPatientName(false);
  }, [currentPatient]);

  const endCurrentSession = useCallback(() => {
    setCurrentPatient(null);
    setIsEditingPatientName(false);
  }, []);


  const value: PatientSessionContextType = {
    currentPatient,
    startNewPatientSession,
    updatePatientName,
    endCurrentSession,
    isEditingPatientName,
    setIsEditingPatientName,
  };

  return (
    <PatientSessionContext.Provider value={value}>
      {children}
    </PatientSessionContext.Provider>
  );
}

export function usePatientSession(): PatientSessionContextType {
  const context = useContext(PatientSessionContext);
  if (context === undefined) {
    throw new Error('usePatientSession must be used within a PatientSessionProvider');
  }
  return context;
}

// Hook to initialize session on component mount
export function useInitializePatientSession() {
  const { currentPatient, startNewPatientSession } = usePatientSession();
  
  return useCallback(() => {
    if (!currentPatient) {
      startNewPatientSession();
    }
  }, [currentPatient, startNewPatientSession]);
}
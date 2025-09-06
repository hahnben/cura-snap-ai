import React, { createContext, useContext, useEffect, useState, ReactNode } from 'react';
import { createClient, AuthError, User, Session } from '@supabase/supabase-js';

const supabaseUrl = import.meta.env.VITE_SUPABASE_URL;
const supabaseAnonKey = import.meta.env.VITE_SUPABASE_ANON_KEY;

if (!supabaseUrl || !supabaseAnonKey) {
  throw new Error('Missing Supabase environment variables');
}

export const supabase = createClient(supabaseUrl, supabaseAnonKey);

interface AuthContextType {
  user: User | null;
  session: Session | null;
  loading: boolean;
  signInWithEmail: (email: string) => Promise<{ error: AuthError | null }>;
  signOut: () => Promise<{ error: AuthError | null }>;
  timeUntilLogout: number | null;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

interface AuthProviderProps {
  children: ReactNode;
}

const SESSION_TIMEOUT = 30 * 60 * 1000; // 30 minutes
const WARNING_TIME = 5 * 60 * 1000; // 5 minutes before logout

export function AuthProvider({ children }: AuthProviderProps) {
  const [user, setUser] = useState<User | null>(null);
  const [session, setSession] = useState<Session | null>(null);
  const [loading, setLoading] = useState(true);
  const [timeUntilLogout, setTimeUntilLogout] = useState<number | null>(null);
  const [sessionTimer, setSessionTimer] = useState<NodeJS.Timeout | null>(null);
  const [warningTimer, setWarningTimer] = useState<NodeJS.Timeout | null>(null);

  const clearTimers = () => {
    if (sessionTimer) {
      clearTimeout(sessionTimer);
      setSessionTimer(null);
    }
    if (warningTimer) {
      clearTimeout(warningTimer);
      setWarningTimer(null);
    }
  };

  const startSessionTimer = () => {
    clearTimers();
    
    // Set warning timer
    const warningTimeout = setTimeout(() => {
      setTimeUntilLogout(WARNING_TIME);
      
      // Start countdown
      const countdownInterval = setInterval(() => {
        setTimeUntilLogout((prev) => {
          if (prev === null || prev <= 1000) {
            clearInterval(countdownInterval);
            return null;
          }
          return prev - 1000;
        });
      }, 1000);
    }, SESSION_TIMEOUT - WARNING_TIME);
    
    setWarningTimer(warningTimeout);
    
    // Set automatic logout timer
    const logoutTimeout = setTimeout(async () => {
      await supabase.auth.signOut();
      setTimeUntilLogout(null);
    }, SESSION_TIMEOUT);
    
    setSessionTimer(logoutTimeout);
  };

  const signInWithEmail = async (email: string) => {
    setLoading(true);
    try {
      const { error } = await supabase.auth.signInWithOtp({
        email,
        options: {
          shouldCreateUser: false, // Only allow existing users
        },
      });
      return { error };
    } catch (error) {
      return { error: error as AuthError };
    } finally {
      setLoading(false);
    }
  };

  const signOut = async () => {
    setLoading(true);
    clearTimers();
    setTimeUntilLogout(null);
    try {
      const { error } = await supabase.auth.signOut();
      return { error };
    } catch (error) {
      return { error: error as AuthError };
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    // Get initial session
    supabase.auth.getSession().then(({ data: { session } }) => {
      setSession(session);
      setUser(session?.user ?? null);
      
      if (session) {
        startSessionTimer();
      }
      
      setLoading(false);
    });

    // Listen for auth changes
    const {
      data: { subscription },
    } = supabase.auth.onAuthStateChange(async (event, session) => {
      setSession(session);
      setUser(session?.user ?? null);
      
      if (event === 'SIGNED_IN' && session) {
        startSessionTimer();
        // Store token in sessionStorage for API calls
        sessionStorage.setItem('supabase.auth.token', session.access_token);
      } else if (event === 'SIGNED_OUT') {
        clearTimers();
        setTimeUntilLogout(null);
        sessionStorage.removeItem('supabase.auth.token');
      }
      
      setLoading(false);
    });

    return () => {
      subscription.unsubscribe();
      clearTimers();
    };
  }, []);

  // Clean up timers on unmount
  useEffect(() => {
    return () => {
      clearTimers();
    };
  }, []);

  const value = {
    user,
    session,
    loading,
    signInWithEmail,
    signOut,
    timeUntilLogout,
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (context === undefined) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}
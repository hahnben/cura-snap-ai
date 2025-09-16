import { createContext, useContext, useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { createClient } from '@supabase/supabase-js';
import type { User, Session } from '@supabase/supabase-js';
import { secureStorage, medicalStorage } from '../utils/secureStorage';

const supabaseUrl = import.meta.env.VITE_SUPABASE_URL;
const supabaseAnonKey = import.meta.env.VITE_SUPABASE_ANON_KEY;

if (!supabaseUrl || !supabaseAnonKey) {
  throw new Error('Missing Supabase environment variables. Please check your .env.local file.');
}

const supabase = createClient(supabaseUrl, supabaseAnonKey);

export interface AuthContextType {
  user: User | null;
  session: Session | null;
  loading: boolean;
  signIn: (email: string, password: string) => Promise<{ error: Error | null }>;
  signOut: () => Promise<void>;
  timeRemaining: number;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

const SESSION_TIMEOUT = 30 * 60 * 1000; // 30 minutes in milliseconds

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [session, setSession] = useState<Session | null>(null);
  const [loading, setLoading] = useState(true);
  const [timeRemaining, setTimeRemaining] = useState(SESSION_TIMEOUT);

  useEffect(() => {
    // Get initial session
    supabase.auth.getSession().then(({ data: { session } }) => {
      setSession(session);
      setUser(session?.user ?? null);
      setLoading(false);
      
      // Initialize timer if session exists
      if (session) {
        const loginTime = Date.now();
        setTimeRemaining(SESSION_TIMEOUT);
        secureStorage.setItem('loginTime', loginTime, { ttl: SESSION_TIMEOUT });
      }
    });

    // Listen for auth changes
    const { data: { subscription } } = supabase.auth.onAuthStateChange(
      async (event, session) => {
        setSession(session);
        setUser(session?.user ?? null);
        setLoading(false);
        
        if (event === 'SIGNED_IN' && session) {
          const loginTime = Date.now();
          setTimeRemaining(SESSION_TIMEOUT);

          // Store login time securely with automatic expiration
          secureStorage.setItem('loginTime', loginTime, { ttl: SESSION_TIMEOUT });
        } else if (event === 'SIGNED_OUT') {
          setTimeRemaining(SESSION_TIMEOUT);

          // Clear all sensitive data on sign out
          secureStorage.clear();
          medicalStorage.emergencyWipe();
        }
      }
    );

    return () => subscription.unsubscribe();
  }, []);

  // Session timeout countdown
  useEffect(() => {
    if (!session) return;

    const interval = setInterval(() => {
      const loginTime = secureStorage.getItem<number>('loginTime');
      if (loginTime) {
        const elapsed = Date.now() - loginTime;
        const remaining = Math.max(0, SESSION_TIMEOUT - elapsed);
        setTimeRemaining(remaining);

        // Auto logout when session expires
        if (remaining === 0) {
          signOut();
        }
      } else if (session) {
        // Login time not found but session exists - security measure
        signOut();
      }
    }, 1000);

    return () => clearInterval(interval);
  }, [session]);

  const signIn = async (email: string, password: string) => {
    try {
      const { error } = await supabase.auth.signInWithPassword({
        email,
        password,
      });
      return { error };
    } catch (error) {
      return { error: error as Error };
    }
  };

  const signOut = async () => {
    try {
      // Clear all sensitive data before signing out
      secureStorage.clear();
      medicalStorage.emergencyWipe();

      const { error } = await supabase.auth.signOut();
      if (error) {
        console.warn('Sign out error:', error);
      }
    } catch (error) {
      console.warn('Error during sign out:', error);
      // Force cleanup even if sign out fails
      secureStorage.clear();
      medicalStorage.emergencyWipe();
    }
  };

  const value: AuthContextType = {
    user,
    session,
    loading,
    signIn,
    signOut,
    timeRemaining,
  };

  return (
    <AuthContext.Provider value={value}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (context === undefined) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}
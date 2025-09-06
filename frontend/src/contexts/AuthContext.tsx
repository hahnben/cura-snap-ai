import { createContext, useContext, useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { createClient } from '@supabase/supabase-js';
import type { User, Session } from '@supabase/supabase-js';

const supabaseUrl = import.meta.env.VITE_SUPABASE_URL || 'http://localhost:54321';
const supabaseAnonKey = import.meta.env.VITE_SUPABASE_ANON_KEY || 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZS1kZW1vIiwicm9sZSI6ImFub24iLCJleHAiOjE5ODM4MTI5OTZ9.CRXP1A7WOeoJeXxjNni43kdQwgnWNReilDMblYTn_I0';

const supabase = createClient(supabaseUrl, supabaseAnonKey);

export interface AuthContextType {
  user: User | null;
  session: Session | null;
  loading: boolean;
  signIn: (email: string) => Promise<{ error: Error | null }>;
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
        sessionStorage.setItem('loginTime', loginTime.toString());
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
          
          // Store login time in sessionStorage for persistence
          sessionStorage.setItem('loginTime', loginTime.toString());
        } else if (event === 'SIGNED_OUT') {
          setTimeRemaining(SESSION_TIMEOUT);
          sessionStorage.removeItem('loginTime');
        }
      }
    );

    return () => subscription.unsubscribe();
  }, []);

  // Session timeout countdown
  useEffect(() => {
    if (!session) return;

    const interval = setInterval(() => {
      const loginTime = parseInt(sessionStorage.getItem('loginTime') || '0');
      if (loginTime) {
        const elapsed = Date.now() - loginTime;
        const remaining = Math.max(0, SESSION_TIMEOUT - elapsed);
        setTimeRemaining(remaining);
        
        // Auto logout when session expires
        if (remaining === 0) {
          signOut();
        }
      }
    }, 1000);

    return () => clearInterval(interval);
  }, [session]);

  const signIn = async (email: string) => {
    try {
      const { error } = await supabase.auth.signInWithOtp({
        email,
        options: {
          emailRedirectTo: `${window.location.origin}/dashboard`,
        }
      });
      return { error };
    } catch (error) {
      return { error: error as Error };
    }
  };

  const signOut = async () => {
    const { error } = await supabase.auth.signOut();
    if (!error) {
      sessionStorage.removeItem('loginTime');
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
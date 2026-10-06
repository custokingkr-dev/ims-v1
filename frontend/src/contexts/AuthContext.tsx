import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { identityAuthClient, invalidateAuthSession, refreshToken, setAccessToken, withAuthSessionLock } from '../services/api';
import { PasskeyVerification } from '../features/security/PasskeyVerification';
import { AuthUser } from '../types/auth';

const LS_KEY = 'custoking_isLoggedIn';

interface AuthContextType {
  user: AuthUser | null;
  /** True while the initial silent refresh is in-flight on page load. */
  loading: boolean;
  login: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [restoreError, setRestoreError] = useState(false);
  const [stepUpRequired, setStepUpRequired] = useState(false);
  const generation = useRef(0);
  // Start loading only if we believe a session exists; avoids a flash of the
  // login screen for returning users while the silent refresh is in-flight.
  const [loading, setLoading] = useState(() => localStorage.getItem(LS_KEY) === 'true');

  const restore = useCallback(async () => {
    const admitted = generation.current;
    setLoading(true);
    setRestoreError(false);
    try {
      const restored = await refreshToken();
      if (admitted !== generation.current) return;
      setUser(restored);
      if (!restored) localStorage.removeItem(LS_KEY);
    } catch { if (admitted === generation.current) setRestoreError(true); }
    finally { if (admitted === generation.current) setLoading(false); }
  }, []);

  useEffect(() => {
    if (localStorage.getItem(LS_KEY) !== 'true') {
      return;
    }
    // Attempt to silently restore the session using the HttpOnly refresh cookie.
    void restore();
  }, [restore]);

  useEffect(() => {
    const show = () => setStepUpRequired(true);
    window.addEventListener('custoking-step-up-required', show);
    return () => window.removeEventListener('custoking-step-up-required', show);
  }, []);

  useEffect(() => {
    const clear = (event: StorageEvent) => {
      if (event.key !== 'custoking_session_epoch') return;
      generation.current += 1;
      setAccessToken(null); setUser(null); setRestoreError(false); setLoading(false); setStepUpRequired(false);
    };
    window.addEventListener('storage', clear);
    return () => window.removeEventListener('storage', clear);
  }, []);

  const value = useMemo(() => ({
    user,
    loading,
    async login(email: string, password: string) {
      const admitted = ++generation.current;
      const authenticated = await withAuthSessionLock(() => identityAuthClient.login({ email, password }));
      if (admitted !== generation.current) return;
      setAccessToken(authenticated.accessToken);
      setUser(authenticated);
      localStorage.setItem(LS_KEY, 'true');
    },
    async logout() {
      generation.current += 1;
      invalidateAuthSession(); setUser(null); setRestoreError(false); setLoading(false); setStepUpRequired(false);
      try {
        // Ask the server to clear the HttpOnly refresh-token cookie.
        await withAuthSessionLock(() => identityAuthClient.logout());
      } catch {
        // Best-effort — clear client state regardless.
      }
    },
  }), [user, loading]);

  return <AuthContext.Provider value={value}>
    {restoreError ? <main className="ck-security-recovery" role="alert">
      <h1>Your session is temporarily unavailable</h1>
      <p>Check your connection, then retry to return to your work.</p>
      <button className="ck-btn ck-btn-primary" onClick={() => void restore()}>Retry</button>
      <button className="ck-btn ck-btn-ghost" onClick={() => {
        generation.current += 1; invalidateAuthSession(); setUser(null); setRestoreError(false); setLoading(false);
      }}>Return to sign in</button>
    </main> : children}
    {stepUpRequired && user && <PasskeyVerification
      onClose={() => setStepUpRequired(false)}
      onVerified={() => { setStepUpRequired(false); void restore(); }}
    />}
  </AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}

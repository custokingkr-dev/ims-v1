import { createContext, useContext, useEffect, useMemo, useState } from 'react';
import api, { refreshToken, setAccessToken } from '../services/api';
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
  // Start loading only if we believe a session exists; avoids a flash of the
  // login screen for returning users while the silent refresh is in-flight.
  const [loading, setLoading] = useState(() => localStorage.getItem(LS_KEY) === 'true');

  useEffect(() => {
    if (localStorage.getItem(LS_KEY) !== 'true') {
      return;
    }
    // Attempt to silently restore the session using the HttpOnly refresh cookie.
    refreshToken()
      .then((restored) => {
        setUser(restored);
        if (!restored) localStorage.removeItem(LS_KEY);
      })
      .finally(() => setLoading(false));
  }, []);

  const value = useMemo(() => ({
    user,
    loading,
    async login(email: string, password: string) {
      const res = await api.post<AuthUser>('/auth/login', { email, password });
      setAccessToken(res.data.accessToken);
      setUser(res.data);
      localStorage.setItem(LS_KEY, 'true');
    },
    async logout() {
      try {
        // Ask the server to clear the HttpOnly refresh-token cookie.
        await api.post('/auth/logout');
      } catch {
        // Best-effort — clear client state regardless.
      }
      setAccessToken(null);
      setUser(null);
      localStorage.removeItem(LS_KEY);
    },
  }), [user, loading]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}

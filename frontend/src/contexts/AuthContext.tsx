import { createContext, useContext, useEffect, useMemo, useState } from 'react';
import api from '../services/api';
import { AuthUser } from '../types/auth';

interface AuthContextType {
  user: AuthUser | null;
  login: (email: string, password: string) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(() => {
    const stored = localStorage.getItem('custoking_auth');
    return stored ? JSON.parse(stored) as AuthUser : null;
  });

  useEffect(() => {
    const handler = () => {
      const stored = localStorage.getItem('custoking_auth');
      setUser(stored ? JSON.parse(stored) as AuthUser : null);
    };
    window.addEventListener('storage', handler);
    return () => window.removeEventListener('storage', handler);
  }, []);

  const value = useMemo(() => ({
    user,
    async login(email: string, password: string) {
      const response = await api.post<AuthUser>('/auth/login', { email, password });
      setUser(response.data);
      localStorage.setItem('custoking_auth', JSON.stringify(response.data));
    },
    logout() {
      setUser(null);
      localStorage.removeItem('custoking_auth');
    }
  }), [user]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}

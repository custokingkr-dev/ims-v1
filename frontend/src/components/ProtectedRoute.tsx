import { ReactElement } from 'react';
import { Navigate } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import { LoadingState } from '../shared/components/LoadingState';

export default function ProtectedRoute({ children }: { children: ReactElement }) {
  const { user, loading } = useAuth();
  // Suspend rendering while the silent token refresh is in-flight so we don't
  // flash the login page for users whose session is being restored from the cookie.
  if (loading) return <LoadingState label="Loading session…" />;
  if (!user) return <Navigate to="/login" replace />;
  return children;
}

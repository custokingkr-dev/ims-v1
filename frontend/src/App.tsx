import { lazy, Suspense } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import ProtectedRoute from './components/ProtectedRoute';
import LoginPage from './pages/LoginPage';

const SchoolManagementPage = lazy(() => import('./pages/SchoolManagementPage'));
const UnifiedWorkspacePage = lazy(() => import('./pages/UnifiedWorkspacePage'));
const ZoneManagementPage = lazy(() => import('./pages/ZoneManagementPage'));

function PageFallback() {
  return <div className="ck-loading">Loading...</div>;
}

export default function App() {
  return (
    <Suspense fallback={<PageFallback />}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/" element={<Navigate to="/dashboard" replace />} />
        <Route path="/dashboard" element={<ProtectedRoute><UnifiedWorkspacePage /></ProtectedRoute>} />
        <Route path="/schools" element={<ProtectedRoute><SchoolManagementPage /></ProtectedRoute>} />
        <Route path="/zones" element={<ProtectedRoute><ZoneManagementPage /></ProtectedRoute>} />
        <Route path="*" element={<Navigate to="/dashboard" replace />} />
      </Routes>
    </Suspense>
  );
}

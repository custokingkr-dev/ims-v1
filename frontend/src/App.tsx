import { Navigate, Route, Routes } from 'react-router-dom';
import ProtectedRoute from './components/ProtectedRoute';
import LoginPage from './pages/LoginPage';
import SchoolManagementPage from './pages/SchoolManagementPage';
import UnifiedWorkspacePage from './pages/UnifiedWorkspacePage';

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/" element={<Navigate to="/dashboard" replace />} />
      <Route path="/dashboard" element={<ProtectedRoute><UnifiedWorkspacePage /></ProtectedRoute>} />
      <Route path="/schools" element={<ProtectedRoute><SchoolManagementPage /></ProtectedRoute>} />
      <Route path="*" element={<Navigate to="/dashboard" replace />} />
    </Routes>
  );
}

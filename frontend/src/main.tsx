import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import { AuthProvider } from './contexts/AuthContext';
import { ErrorBoundary } from './components/ErrorBoundary';
import { installStaleChunkReload } from './utils/staleChunkReload';
import './styles.css';
import './styles/erp-modules.css';
import './styles/tokens.css';
import './styles/skeleton.css';
import './styles/sidebar.css';
import './styles/drawers.css';
import './styles/attendance.css';
import './styles/photo-import.css';

// A tab left open across a deploy reloads onto the new release instead of failing on a retired chunk.
installStaleChunkReload();

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      <AuthProvider>
        <ErrorBoundary>
          <App />
        </ErrorBoundary>
      </AuthProvider>
    </BrowserRouter>
  </React.StrictMode>
);

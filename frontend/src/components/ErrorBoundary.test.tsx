import { render, screen, cleanup } from '@testing-library/react';
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { ErrorBoundary } from './ErrorBoundary';

function Boom(): JSX.Element {
  throw new Error('boom');
}

describe('ErrorBoundary', () => {
  beforeEach(() => vi.spyOn(console, 'error').mockImplementation(() => {}));
  afterEach(() => { vi.restoreAllMocks(); cleanup(); });

  it('presents the failure in the application shell rather than as bare browser defaults', () => {
    // The fallback rendered an unstyled h1 and a browser-default button, so the one screen a
    // user only ever sees when something is already wrong looked broken rather than handled.
    render(<ErrorBoundary><Boom /></ErrorBoundary>);
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /reload/i }).className).toMatch(/\bck-btn\b/);
  });

  it('renders its children when nothing has failed', () => {
    render(<ErrorBoundary><p>fine</p></ErrorBoundary>);
    expect(screen.getByText('fine')).toBeInTheDocument();
  });
});

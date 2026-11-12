import { act, render, screen, cleanup } from '@testing-library/react';
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

// After a deploy the old content-hashed chunks are gone, so a tab opened before it fails the first time
// it opens a panel it has not loaded yet. That is not a fault to report: reload onto the new release.
describe('ErrorBoundary and a chunk retired by a deploy', () => {
  beforeEach(() => vi.spyOn(console, 'error').mockImplementation(() => {}));
  afterEach(() => { vi.restoreAllMocks(); vi.doUnmock('../utils/staleChunkReload'); vi.resetModules(); cleanup(); });

  function StaleChunk(): JSX.Element {
    throw new TypeError('Failed to fetch dynamically imported module: https://x/assets/HomePanel-B1k_VtHD.js');
  }

  it('reloads the page instead of showing the error screen', async () => {
    const reloadOnceForStaleChunk = vi.fn(() => true);
    vi.resetModules();
    vi.doMock('../utils/staleChunkReload', async (original) => ({ ...await original<object>(), reloadOnceForStaleChunk }));
    const { ErrorBoundary: Boundary } = await import('./ErrorBoundary');
    render(<Boundary><StaleChunk /></Boundary>);
    expect(reloadOnceForStaleChunk).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Loading the latest version');
  });

  it('shows the error screen when the reload was already tried', async () => {
    const reloadOnceForStaleChunk = vi.fn(() => false);
    vi.resetModules();
    vi.doMock('../utils/staleChunkReload', async (original) => ({ ...await original<object>(), reloadOnceForStaleChunk }));
    const { ErrorBoundary: Boundary } = await import('./ErrorBoundary');
    render(<Boundary><StaleChunk /></Boundary>);
    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong');
  });
});

// The reload can be refused: with an unsaved draft the browser asks "Leave site?", and Stay keeps this page alive.
describe('ErrorBoundary when the reload onto the new release does not happen', () => {
  beforeEach(() => vi.spyOn(console, 'error').mockImplementation(() => {}));
  afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); vi.resetModules(); cleanup(); });

  function memoryStorage(): Storage {
    const values = new Map<string, string>();
    return {
      get length() { return values.size; },
      clear: () => values.clear(),
      getItem: (key) => values.get(key) ?? null,
      key: (index) => [...values.keys()][index] ?? null,
      removeItem: (key) => { values.delete(key); },
      setItem: (key, value) => { values.set(key, String(value)); },
    };
  }

  // Once Vite's event is cancelled the import resolves to nothing, and the panel's lazy wrapper reads from it.
  function MissingModule(): JSX.Element {
    throw new TypeError("Cannot read properties of undefined (reading 'HomePanel')");
  }

  it('offers the reload button again when the page is still here', async () => {
    vi.resetModules();
    const { installStaleChunkReload, STALE_CHUNK_RELOAD_STALL_MS } = await import('../utils/staleChunkReload');
    const { ErrorBoundary: Boundary } = await import('./ErrorBoundary');
    // Answering Stay amounts to a reload that returns without leaving the page.
    const reload = vi.fn();
    const uninstall = installStaleChunkReload(window, { storage: memoryStorage(), now: () => 1_000_000, reload });
    try {
      const retired = new Event('vite:preloadError', { cancelable: true });
      (retired as Event & { payload?: unknown }).payload =
        new TypeError('Failed to fetch dynamically imported module: https://x/assets/HomePanel-B1k_VtHD.js');
      window.dispatchEvent(retired);
      expect(retired.defaultPrevented).toBe(true);
      expect(reload).toHaveBeenCalledTimes(1);

      vi.useFakeTimers();
      render(<Boundary><MissingModule /></Boundary>);
      expect(screen.getByRole('status')).toHaveTextContent('Loading the latest version');

      act(() => { vi.advanceTimersByTime(STALE_CHUNK_RELOAD_STALL_MS); });
      expect(screen.getByRole('alert')).toHaveTextContent('Something went wrong');
      expect(screen.getByRole('button', { name: 'Reload the page' })).toBeInTheDocument();
    } finally {
      uninstall();
    }
  });
});

import { Component, ReactNode } from 'react';
import {
  isStaleChunkError, isStaleChunkReloadPending, reloadOnceForStaleChunk, STALE_CHUNK_RELOAD_STALL_MS,
} from '../utils/staleChunkReload';

interface ErrorBoundaryProps {
  children: ReactNode;
}

interface ErrorBoundaryState {
  hasError: boolean;
  /** The failure is a chunk retired by a deploy and the page is reloading onto the new release. */
  reloading: boolean;
}

export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state = { hasError: false, reloading: false };

  private reloadStall: ReturnType<typeof setTimeout> | undefined;

  static getDerivedStateFromError(error: unknown): ErrorBoundaryState {
    // Once a reload is under way, whatever the failed import breaks next is part of the same stale page.
    return { hasError: true, reloading: isStaleChunkError(error) || isStaleChunkReloadPending() };
  }

  componentDidCatch(error: Error, errorInfo: unknown) {
    // A tab opened before a deploy asks for chunks the new release no longer serves: reload onto it, once.
    if (isStaleChunkReloadPending() || (isStaleChunkError(error) && reloadOnceForStaleChunk())) {
      this.awaitReload();
      return;
    }
    if (this.state.reloading) this.setState({ reloading: false });
    console.error('Unhandled error in application:', error, errorInfo);
  }

  componentWillUnmount() {
    clearTimeout(this.reloadStall);
  }

  /** A reload the user refused leaves this page here: after a while, show the error screen and its reload button. */
  private awaitReload() {
    clearTimeout(this.reloadStall);
    this.reloadStall = setTimeout(() => this.setState({ reloading: false }), STALE_CHUNK_RELOAD_STALL_MS);
  }

  render() {
    if (this.state.hasError && this.state.reloading) {
      return <div className="ck-loading" role="status">Loading the latest version...</div>;
    }
    if (this.state.hasError) {
      // This is the one screen a user only ever reaches when something has already gone wrong,
      // and it used to arrive as an unstyled heading over a browser-default button — reading as
      // a broken page rather than a handled failure. It now speaks in the same card, type and
      // button as the rest of the application.
      return (
        <div
          className="error-boundary"
          style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', padding: 24,
                   background: 'var(--bg, #f4f6f4)' }}
        >
          <div className="ck-card" role="alert" style={{ maxWidth: 440, padding: 28, textAlign: 'center' }}>
            <div style={{ fontSize: 28, marginBottom: 12 }} aria-hidden="true">⚠</div>
            <h1 style={{ fontSize: 20, fontWeight: 600, margin: '0 0 8px', color: 'var(--ink1, #1b1f1c)' }}>
              Something went wrong
            </h1>
            <p style={{ fontSize: 14, lineHeight: 1.5, margin: '0 0 20px', color: 'var(--ink2, #4a534c)' }}>
              The page could not finish loading. Reloading usually clears it — if it keeps
              happening, contact support.
            </p>
            <button type="button" className="ck-btn ck-btn-g" onClick={() => window.location.reload()}>
              Reload the page
            </button>
          </div>
        </div>
      );
    }

    return this.props.children;
  }
}

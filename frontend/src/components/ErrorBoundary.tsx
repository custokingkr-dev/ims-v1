import { Component, ReactNode } from 'react';

interface ErrorBoundaryProps {
  children: ReactNode;
}

interface ErrorBoundaryState {
  hasError: boolean;
}

export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state = { hasError: false };

  static getDerivedStateFromError(): ErrorBoundaryState {
    return { hasError: true };
  }

  componentDidCatch(error: Error, errorInfo: unknown) {
    console.error('Unhandled error in application:', error, errorInfo);
  }

  render() {
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

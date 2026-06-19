interface LoadingStateProps {
  /** Accessible label for screen-readers — defaults to "Loading…" */
  label?: string;
  /** Optional size variant */
  size?: 'sm' | 'md' | 'lg';
  /** Optional extra CSS class on the outer wrapper */
  className?: string;
}

const SIZE_CSS = {
  sm: 'ck-spinner ck-spinner-sm',
  md: 'ck-spinner',
  lg: 'ck-spinner ck-spinner-lg',
} as const;

/**
 * Centred loading spinner.
 *
 * Uses `ck-spinner` design tokens so it inherits the app brand colour.
 *
 * @example
 * {isLoading && <LoadingState label="Fetching students…" />}
 */
export function LoadingState({ label = 'Loading…', size = 'md', className = '' }: LoadingStateProps) {
  return (
    <div
      className={`ck-loading-state ${className}`.trim()}
      role="status"
      aria-label={label}
      aria-live="polite"
    >
      <span className={SIZE_CSS[size]} aria-hidden="true" />
      <span className="ck-sr-only">{label}</span>
    </div>
  );
}

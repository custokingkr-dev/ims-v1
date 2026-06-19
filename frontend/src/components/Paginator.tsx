interface PaginatorProps {
  /** Current 0-based page index */
  page: number;
  /** Total number of pages (from API PageResponse.totalPages) */
  totalPages: number;
  /** Called with the new 0-based page index when the user navigates */
  onPageChange: (page: number) => void;
  /** Optional CSS class added to the outer wrapper */
  className?: string;
}

/**
 * Minimal prev / next paginator.
 *
 * Renders nothing when there is only one page, so callers don't need
 * to guard the JSX.  Uses existing `ck-` design tokens — no new CSS needed.
 */
export default function Paginator({ page, totalPages, onPageChange, className = '' }: PaginatorProps) {
  if (totalPages <= 1) return null;

  return (
    <div className={`ck-paginator ${className}`.trim()}>
      <button
        className="ck-btn ck-btn-ghost ck-btn-sm"
        disabled={page <= 0}
        onClick={() => onPageChange(page - 1)}
        aria-label="Previous page"
      >
        ← Prev
      </button>

      <span className="ck-paginator-label">
        Page {page + 1} of {totalPages}
      </span>

      <button
        className="ck-btn ck-btn-ghost ck-btn-sm"
        disabled={page >= totalPages - 1}
        onClick={() => onPageChange(page + 1)}
        aria-label="Next page"
      >
        Next →
      </button>
    </div>
  );
}

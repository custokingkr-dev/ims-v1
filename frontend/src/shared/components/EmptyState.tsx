import type { ReactNode } from 'react';

interface EmptyStateProps {
  /** Primary message — e.g. "No students found" */
  message: string;
  /** Secondary descriptive text */
  description?: string;
  /** Optional action button / link rendered below the description */
  action?: ReactNode;
  /** Optional icon or illustration — pass an emoji or a small SVG element */
  icon?: ReactNode;
  /** Optional extra CSS class on the outer wrapper */
  className?: string;
}

/**
 * Centred empty-state placeholder.
 *
 * Rendered when a list or table has zero items.  Uses `ck-empty-state`
 * design tokens — no new CSS needed.
 *
 * @example
 * <EmptyState
 *   icon="🎒"
 *   message="No students yet"
 *   description="Import your first batch or add a student manually."
 *   action={<button className="ck-btn ck-btn-primary">Add Student</button>}
 * />
 */
export function EmptyState({ message, description, action, icon, className = '' }: EmptyStateProps) {
  return (
    <div className={`ck-empty-state ${className}`.trim()} role="status" aria-live="polite">
      {icon && <div className="ck-empty-state-icon">{icon}</div>}
      <p className="ck-empty-state-message">{message}</p>
      {description && <p className="ck-empty-state-description">{description}</p>}
      {action && <div className="ck-empty-state-action">{action}</div>}
    </div>
  );
}

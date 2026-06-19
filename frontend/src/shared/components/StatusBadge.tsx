/**
 * Semantic status badge that maps well-known status strings to visual variants.
 *
 * Uses existing `ck-badge` design tokens — no new CSS needed.
 *
 * @example
 * <StatusBadge status="Paid" />
 * <StatusBadge status="PENDING" />
 * <StatusBadge status="APPROVED" label="Approved ✓" />
 */

type Variant = 'success' | 'warning' | 'danger' | 'info' | 'neutral';

interface StatusBadgeProps {
  /** The raw status string from the backend */
  status: string;
  /** Optional display label — defaults to the capitalized status string */
  label?: string;
  /** Optional additional CSS classes */
  className?: string;
}

const VARIANT_MAP: Record<string, Variant> = {
  // Fee / payment
  paid:     'success',
  overdue:  'danger',
  partial:  'warning',
  pending:  'warning',
  // Order workflow
  approved: 'success',
  fulfilled: 'success',
  rejected:  'danger',
  cancelled: 'neutral',
  draft:     'neutral',
  submitted: 'info',
  review:    'info',
  // Firefighting
  open:      'danger',
  closed:    'success',
  resolved:  'success',
  // Generic booleans
  active:   'success',
  inactive: 'neutral',
  true:     'success',
  false:    'neutral',
};

const VARIANT_CSS: Record<Variant, string> = {
  success: 'ck-badge ck-badge-success',
  warning: 'ck-badge ck-badge-warning',
  danger:  'ck-badge ck-badge-danger',
  info:    'ck-badge ck-badge-info',
  neutral: 'ck-badge ck-badge-neutral',
};

function resolveVariant(status: string): Variant {
  return VARIANT_MAP[status.toLowerCase()] ?? 'neutral';
}

export function StatusBadge({ status, label, className = '' }: StatusBadgeProps) {
  const variant = resolveVariant(status);
  const display = label ?? (status.charAt(0).toUpperCase() + status.slice(1).toLowerCase());
  const css = [VARIANT_CSS[variant], className].filter(Boolean).join(' ');
  return <span className={css}>{display}</span>;
}

export type StatusVariant = 'success' | 'warning' | 'info' | 'neutral' | 'danger';

const DISPLAY_MAP: Record<string, string> = {
  AWAITING_BURSAR:    'Finance Review Pending',
  AWAITING_PRINCIPAL: 'Admin Approval Pending',
  CUSTOKING_APPROVED: 'Approved for Fulfilment',
  FULFILLED:          'Delivered',
  PROCESSING:         'In Fulfilment',
  PARTIAL:            'Partially Paid',
  APPROVED:           'Approved',
  REJECTED:           'Rejected',
  DRAFT:              'Draft',
  SUBMITTED:          'Submitted',
  PENDING:            'Pending',
  PAID:               'Paid',
  OVERDUE:            'Overdue',
};

const VARIANT_MAP: Record<string, StatusVariant> = {
  AWAITING_BURSAR:    'warning',
  AWAITING_PRINCIPAL: 'warning',
  CUSTOKING_APPROVED: 'success',
  FULFILLED:          'success',
  PROCESSING:         'info',
  PARTIAL:            'warning',
  APPROVED:           'success',
  REJECTED:           'danger',
  DRAFT:              'neutral',
  SUBMITTED:          'info',
  PENDING:            'warning',
  PAID:               'success',
  OVERDUE:            'danger',
};

export function getDisplayStatus(status: string): string {
  const key = String(status ?? '').trim();
  if (!key) return '—';
  if (DISPLAY_MAP[key]) return DISPLAY_MAP[key];
  // An unmapped status used to render as its raw constant, so PENDING_APPROVAL and QUOTED
  // sat in the same column as "In Fulfilment". Every status reads as words, mapped or not.
  const words = key.replace(/_/g, ' ').toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

export function getStatusBadgeVariant(status: string): StatusVariant {
  return VARIANT_MAP[status] ?? 'neutral';
}

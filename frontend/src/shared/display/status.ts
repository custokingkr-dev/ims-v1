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
  return DISPLAY_MAP[status] ?? status;
}

export function getStatusBadgeVariant(status: string): StatusVariant {
  return VARIANT_MAP[status] ?? 'neutral';
}

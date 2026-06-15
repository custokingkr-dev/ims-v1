import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { StatusBadge } from './StatusBadge';

describe('<StatusBadge>', () => {

  // ── rendering ─────────────────────────────────────────────────────────────

  it('renders the status text (capitalised)', () => {
    render(<StatusBadge status="paid" />);
    expect(screen.getByText('Paid')).toBeInTheDocument();
  });

  it('renders a custom label when provided', () => {
    render(<StatusBadge status="overdue" label="Overdue!" />);
    expect(screen.getByText('Overdue!')).toBeInTheDocument();
  });

  it('renders as a <span> element', () => {
    const { container } = render(<StatusBadge status="pending" />);
    expect(container.querySelector('span')).toBeInTheDocument();
  });

  // ── CSS variant mapping ────────────────────────────────────────────────────

  it.each([
    ['paid',      'ck-badge-success'],
    ['Paid',      'ck-badge-success'],  // case-insensitive
    ['overdue',   'ck-badge-danger'],
    ['pending',   'ck-badge-warning'],
    ['partial',   'ck-badge-warning'],
    ['approved',  'ck-badge-success'],
    ['rejected',  'ck-badge-danger'],
    ['open',      'ck-badge-danger'],
    ['closed',    'ck-badge-success'],
    ['active',    'ck-badge-success'],
    ['inactive',  'ck-badge-neutral'],
    ['submitted', 'ck-badge-info'],
    ['unknown',   'ck-badge-neutral'],  // unknown → neutral
  ])('status "%s" gets CSS class %s', (status, expectedClass) => {
    const { container } = render(<StatusBadge status={status} />);
    expect(container.firstChild).toHaveClass(expectedClass);
  });

  it('always includes the base ck-badge class', () => {
    const { container } = render(<StatusBadge status="paid" />);
    expect(container.firstChild).toHaveClass('ck-badge');
  });

  it('forwards an extra className onto the span', () => {
    const { container } = render(<StatusBadge status="paid" className="ml-2" />);
    expect(container.firstChild).toHaveClass('ml-2');
  });
});

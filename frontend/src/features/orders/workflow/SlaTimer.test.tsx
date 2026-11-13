import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SlaTimer } from './SlaTimer';

const NOW = Date.parse('2026-11-13T06:30:00Z');
const SECOND = 1000;
const HOUR = 3_600_000;
const iso = (ms: number) => new Date(ms).toISOString();
const sla = { value: 4, unit: 'HOURS' as const, source: 'DEFAULT' as const };
const timer = () => document.querySelector('.ck-wf-timer') as HTMLElement | null;

beforeEach(() => { vi.useFakeTimers(); vi.setSystemTime(NOW); });
afterEach(() => { cleanup(); vi.useRealTimers(); });

describe('the stage SLA timer', () => {
  it('runs from the server start and due times and ticks every second', () => {
    const startedAt = NOW - 2 * SECOND;
    render(<SlaTimer step={{ status: 'ACTIVE', startedAt: iso(startedAt), dueAt: iso(startedAt + 4 * HOUR), sla }} offsetMs={0} />);
    expect(screen.getByText('SLA · 4 hours · 24/7')).toBeInTheDocument();
    expect(timer()).toHaveAttribute('data-state', 'RUNNING');
    expect(timer()).toHaveTextContent('0h 0m 2s elapsed · 3h 59m 58s remaining');
    act(() => { vi.advanceTimersByTime(3 * SECOND); });
    expect(timer()).toHaveTextContent('0h 0m 5s elapsed · 3h 59m 55s remaining');
  });

  it('changes its spoken label once a minute, not every second, and is not a live region', () => {
    const startedAt = NOW - 2 * SECOND;
    render(<SlaTimer step={{ status: 'ACTIVE', startedAt: iso(startedAt), dueAt: iso(startedAt + 4 * HOUR), sla }} offsetMs={0} />);
    const spoken = () => timer()?.querySelector('.ck-sr-only')?.textContent;
    const first = spoken();
    expect(first).toBe('SLA running: 3 hours 59 minutes remaining');
    act(() => { vi.advanceTimersByTime(5 * SECOND); });
    expect(spoken()).toBe(first);
    act(() => { vi.advanceTimersByTime(60 * SECOND); });
    expect(spoken()).toBe('SLA running: 3 hours 58 minutes remaining');
    expect(timer()?.closest('[aria-live]')).toBeNull();
  });

  it('reports a breach as soon as the deadline passes, counting up', () => {
    render(<SlaTimer step={{ status: 'ACTIVE', startedAt: iso(NOW - 4 * HOUR), dueAt: iso(NOW + SECOND), sla }} offsetMs={0} />);
    expect(timer()).toHaveAttribute('data-state', 'RUNNING');
    act(() => { vi.advanceTimersByTime(3 * SECOND); });
    expect(timer()).toHaveAttribute('data-state', 'BREACHED');
    expect(timer()).toHaveTextContent('SLA BREACHED · overdue by 0h 0m 2s');
  });

  it('corrects for a wrong device clock with the server offset', () => {
    // The device is 2 hours behind the server: the stage is already overdue by an hour.
    render(<SlaTimer step={{ status: 'ACTIVE', startedAt: iso(NOW - 3 * HOUR), dueAt: iso(NOW + HOUR), sla }} offsetMs={2 * HOUR} />);
    expect(timer()).toHaveTextContent('SLA BREACHED · overdue by 1h 0m 0s');
  });

  it('says completed, pending and untimed stages in words, and stops ticking', () => {
    const { rerender } = render(<SlaTimer step={{ status: 'COMPLETED', startedAt: iso(NOW - HOUR), dueAt: iso(NOW + HOUR), sla }} offsetMs={0} />);
    expect(timer()).toHaveAttribute('data-state', 'COMPLETED');
    expect(timer()).toHaveTextContent('Completed');
    rerender(<SlaTimer step={{ status: 'LOCKED', startedAt: null, dueAt: null, sla }} offsetMs={0} />);
    expect(timer()).toHaveTextContent('Pending · timer starts when previous stage is completed');
    rerender(<SlaTimer step={{ status: 'ACTIVE', startedAt: iso(NOW), dueAt: null, sla: null }} offsetMs={0} />);
    expect(timer()).toBeNull();
    expect(screen.getByText('No SLA for this stage')).toBeInTheDocument();
    expect(vi.getTimerCount()).toBe(0);
  });
});

import { describe, expect, it } from 'vitest';
import {
  clockOffset, dueSummary, formatDuration, formatDurationSpoken, formatShortDuration, formatStamp, serverTime, slaLabel,
  stageStatusLine, stageTimer, upcomingDueLabel, upcomingDueTone,
} from './slaFormat';

const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
const IST = 'Asia/Kolkata';
const NOW = Date.parse('2026-11-13T06:30:00Z');
const iso = (ms: number) => new Date(ms).toISOString();

describe('durations', () => {
  it('formats a clock to the second, with days only when there are any', () => {
    expect(formatDuration(2 * DAY + 5 * HOUR + 51 * MINUTE + 26 * SECOND)).toBe('2d 5h 51m 26s');
    expect(formatDuration(2 * SECOND)).toBe('0h 0m 2s');
    expect(formatDuration(3 * HOUR + 59 * MINUTE + 57 * SECOND)).toBe('3h 59m 57s');
    expect(formatDuration(0)).toBe('0h 0m 0s');
    expect(formatDuration(-(2 * SECOND + 900))).toBe('0h 0m 2s');
  });

  it('can stop at minutes, and spell the duration out for assistive technology', () => {
    expect(formatDuration(2 * DAY + 5 * HOUR + 51 * MINUTE + 26 * SECOND, 'minutes')).toBe('2d 5h 51m');
    expect(formatDurationSpoken(2 * DAY + 5 * HOUR + 51 * MINUTE + 26 * SECOND)).toBe('2 days 5 hours 51 minutes');
    expect(formatDurationSpoken(HOUR + MINUTE)).toBe('1 hour 1 minute');
    expect(formatDurationSpoken(30 * SECOND)).toBe('less than a minute');
  });

  it('shortens a due distance for lists', () => {
    expect(formatShortDuration(2 * DAY + 5 * HOUR + 51 * MINUTE)).toBe('2d 5h');
    expect(formatShortDuration(3 * HOUR)).toBe('3h');
    expect(formatShortDuration(3 * HOUR + 20 * MINUTE)).toBe('3h 20m');
    expect(formatShortDuration(45 * MINUTE + 10 * SECOND)).toBe('45m');
    expect(formatShortDuration(30 * SECOND)).toBe('<1m');
  });
});

describe('slaLabel', () => {
  it('describes a stage\'s time slot like the prototype header', () => {
    expect(slaLabel({ value: 4, unit: 'HOURS' })).toBe('SLA · 4 hours · 24/7');
    expect(slaLabel({ value: 1, unit: 'HOURS' })).toBe('SLA · 1 hour · 24/7');
    expect(slaLabel({ value: 2, unit: 'WORKING_DAYS' })).toBe('SLA · 2 working days · Mon–Fri');
    expect(slaLabel(null)).toBe('No SLA for this stage');
  });
});

describe('stageTimer', () => {
  const sla = { value: 4, unit: 'HOURS' as const, source: 'DEFAULT' as const };

  it('runs from the server\'s start and due times', () => {
    const startedAt = NOW - 2 * SECOND;
    const timer = stageTimer({ status: 'ACTIVE', startedAt: iso(startedAt), dueAt: iso(startedAt + 4 * HOUR), sla }, NOW);
    expect(timer).toEqual({
      state: 'RUNNING',
      text: '0h 0m 2s elapsed · 3h 59m 58s remaining',
      spoken: 'SLA running: 3 hours 59 minutes remaining',
    });
  });

  it('reports a breach once now is past due', () => {
    const dueAt = NOW - (2 * DAY + 5 * HOUR + 51 * MINUTE + 26 * SECOND);
    const timer = stageTimer({ status: 'ACTIVE', startedAt: iso(dueAt - 4 * HOUR), dueAt: iso(dueAt), sla }, NOW);
    expect(timer).toEqual({
      state: 'BREACHED',
      text: 'SLA BREACHED · overdue by 2d 5h 51m 26s',
      spoken: 'SLA breached: overdue by 2 days 5 hours 51 minutes',
    });
  });

  it('shows only the remaining time when the server sent no start', () => {
    expect(stageTimer({ status: 'ACTIVE', startedAt: null, dueAt: iso(NOW + HOUR), sla }, NOW).text).toBe('1h 0m 0s remaining');
  });

  it('is still running at the exact due instant (breached means now > due)', () => {
    expect(stageTimer({ status: 'ACTIVE', startedAt: iso(NOW - 4 * HOUR), dueAt: iso(NOW), sla }, NOW).state).toBe('RUNNING');
  });

  it('names completed, pending and untimed stages', () => {
    expect(stageTimer({ status: 'COMPLETED', startedAt: iso(NOW - HOUR), dueAt: iso(NOW + HOUR), sla }, NOW).text).toBe('Completed');
    expect(stageTimer({ status: 'LOCKED', startedAt: null, dueAt: null, sla }, NOW))
      .toMatchObject({ state: 'PENDING', text: 'Pending · timer starts when previous stage is completed' });
    expect(stageTimer({ status: 'ACTIVE', startedAt: iso(NOW), dueAt: null, sla: null }, NOW))
      .toMatchObject({ state: 'NO_SLA', text: 'No SLA for this stage' });
  });
});

describe('clock skew', () => {
  it('ticks timers on server time', () => {
    const offset = clockOffset('2026-11-13T06:30:05Z', NOW);
    expect(offset).toBe(5 * SECOND);
    expect(serverTime(offset, NOW + MINUTE)).toBe(NOW + MINUTE + 5 * SECOND);
    expect(clockOffset('not a date', NOW)).toBe(0);
  });
});

describe('dates in the school\'s time zone', () => {
  it('formats a stamp as "13 Nov, 12:19 am"', () => {
    expect(formatStamp('2026-11-12T18:49:00Z', IST)).toBe('13 Nov, 12:19 am');
    expect(formatStamp('2026-11-16T10:30:00Z', IST)).toBe('16 Nov, 4:00 pm');
  });

  it('writes each stage list status line', () => {
    expect(stageStatusLine({ status: 'COMPLETED', completedAt: '2026-11-12T18:49:00Z', dueAt: null, overdue: false }, IST))
      .toBe('Completed · 13 Nov, 12:19 am');
    expect(stageStatusLine({ status: 'ACTIVE', completedAt: null, dueAt: '2026-11-16T10:30:00Z', overdue: false }, IST))
      .toBe('In progress · due 16 Nov, 4:00 pm');
    expect(stageStatusLine({ status: 'ACTIVE', completedAt: null, dueAt: '2026-10-25T22:30:00Z', overdue: true }, IST))
      .toBe('Overdue · was due 26 Oct, 4:00 am');
    expect(stageStatusLine({ status: 'ACTIVE', completedAt: null, dueAt: null, overdue: false }, IST)).toBe('In progress');
    expect(stageStatusLine({ status: 'LOCKED', completedAt: null, dueAt: null, overdue: false }, IST)).toBe('Pending');
  });

  it('falls back to the default school zone when the zone is blank or unknown', () => {
    expect(formatStamp('2026-11-12T18:49:00Z', '')).toBe('13 Nov, 12:19 am');
    expect(formatStamp('2026-11-12T18:49:00Z', 'Mars/Olympus_Mons')).toBe('13 Nov, 12:19 am');
    expect(formatStamp('2026-11-12T18:49:00Z', 'UTC')).toBe('12 Nov, 6:49 pm');
    expect(formatStamp('not a date', IST)).toBe('not a date');
  });

  it('turns overdue on time when given a live clock', () => {
    const step = { status: 'ACTIVE' as const, completedAt: null, dueAt: iso(NOW - MINUTE), overdue: false };
    expect(stageStatusLine(step, IST, NOW)).toMatch(/^Overdue · was due /);
  });
});

describe('dueSummary (list Due column)', () => {
  it('colours overdue and due-soon rows and dates the rest', () => {
    expect(dueSummary(iso(NOW - (2 * DAY + 5 * HOUR + 10 * MINUTE)), NOW, IST)).toEqual({ tone: 'danger', text: 'Overdue 2d 5h' });
    expect(dueSummary(iso(NOW + 3 * HOUR), NOW, IST)).toEqual({ tone: 'warning', text: 'Due in 3h' });
    expect(dueSummary(iso(NOW + 4 * HOUR), NOW, IST)).toEqual({ tone: 'warning', text: 'Due in 4h' });
    expect(dueSummary('2026-11-16T10:30:00Z', NOW, IST)).toEqual({ tone: 'neutral', text: '16 Nov, 4:00 pm' });
    expect(dueSummary(null, NOW, IST)).toEqual({ tone: 'none', text: '—' });
  });
});

describe('upcoming actions', () => {
  it('labels each row by its server-computed due state', () => {
    const at = '2026-11-16T10:30:00Z';
    expect(upcomingDueLabel({ done: true, dueState: 'NONE', dueAt: null }, IST)).toBe('Completed');
    expect(upcomingDueLabel({ done: false, dueState: 'OVERDUE', dueAt: at }, IST)).toBe('Overdue');
    expect(upcomingDueLabel({ done: false, dueState: 'DUE_TODAY', dueAt: at }, IST)).toBe('Due today');
    expect(upcomingDueLabel({ done: false, dueState: 'DUE_TOMORROW', dueAt: at }, IST)).toBe('Due tomorrow');
    expect(upcomingDueLabel({ done: false, dueState: 'LATER', dueAt: at }, IST)).toBe('16 Nov');
    expect(upcomingDueLabel({ done: false, dueState: 'NONE', dueAt: null }, IST)).toBe('');
    expect(upcomingDueTone({ done: false, dueState: 'OVERDUE' })).toBe('danger');
    expect(upcomingDueTone({ done: false, dueState: 'DUE_TODAY' })).toBe('warning');
    expect(upcomingDueTone({ done: true, dueState: 'OVERDUE' })).toBe('neutral');
  });
});

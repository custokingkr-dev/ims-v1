import type { StepStatus, UpcomingAction } from '../../../generated/orderWorkflowClient';
import { DEFAULT_SCHOOL_TIME_ZONE } from '../../../utils/timeZones';

/**
 * SLA clocks and due dates on the order workflow screens (design D23, D39, sections 4.2-4.4). The server owns every
 * clock: it sends each stage's startedAt and dueAt, plus its own `now` so the browser can correct for a wrong device
 * clock. These helpers only turn those instants into words. Breached means now is strictly after the deadline, as on
 * the server. Status is always said in words, never by colour alone; the tone is a hint for the caller's styling.
 */

const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;
/** "Due soon" in lists: the same 4 hours as the server's DUE_SOON filter. */
export const DUE_SOON_MS = 4 * HOUR;

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

interface Parts { days: number; hours: number; minutes: number; seconds: number }

/** Whole units of a distance in time. The sign is dropped: callers say "elapsed", "remaining" or "overdue by". */
function parts(ms: number): Parts {
  const total = Math.floor(Math.abs(Number.isFinite(ms) ? ms : 0) / SECOND);
  return {
    days: Math.floor(total / 86400),
    hours: Math.floor((total % 86400) / 3600),
    minutes: Math.floor((total % 3600) / 60),
    seconds: total % 60,
  };
}

/** A live clock: "2d 5h 51m 26s", "0h 0m 2s". Days appear only when there are any; 'minutes' drops the seconds. */
export function formatDuration(ms: number, precision: 'seconds' | 'minutes' = 'seconds'): string {
  const { days, hours, minutes, seconds } = parts(ms);
  const clock = precision === 'seconds' ? `${hours}h ${minutes}m ${seconds}s` : `${hours}h ${minutes}m`;
  return days > 0 ? `${days}d ${clock}` : clock;
}

const plural = (count: number, unit: string) => `${count} ${unit}${count === 1 ? '' : 's'}`;

/**
 * The same distance spelled out to the minute, for an accessible label that only changes once a minute (the visible
 * timer ticks every second and is not a live region): "2 days 5 hours 51 minutes", "less than a minute".
 */
export function formatDurationSpoken(ms: number): string {
  const { days, hours, minutes } = parts(ms);
  const words = [
    days > 0 ? plural(days, 'day') : '',
    hours > 0 ? plural(hours, 'hour') : '',
    minutes > 0 ? plural(minutes, 'minute') : '',
  ].filter(Boolean);
  return words.length ? words.join(' ') : 'less than a minute';
}

/** A due distance for a list cell: "2d 5h", "3h 20m", "45m", "<1m". */
export function formatShortDuration(ms: number): string {
  const { days, hours, minutes } = parts(ms);
  if (days > 0) return hours > 0 ? `${days}d ${hours}h` : `${days}d`;
  if (hours > 0) return minutes > 0 ? `${hours}h ${minutes}m` : `${hours}h`;
  return minutes > 0 ? `${minutes}m` : '<1m';
}

export interface SlaSpec {
  value: number;
  unit: 'HOURS' | 'WORKING_DAYS';
}

/** The stage header's time slot: "SLA · 4 hours · 24/7", "SLA · 2 working days · Mon–Fri", or "No SLA for this stage". */
export function slaLabel(sla: SlaSpec | null | undefined): string {
  if (!sla) return 'No SLA for this stage';
  return sla.unit === 'HOURS'
    ? `SLA · ${plural(sla.value, 'hour')} · 24/7`
    : `SLA · ${plural(sla.value, 'working day')} · Mon–Fri`;
}

export type TimerState = 'COMPLETED' | 'RUNNING' | 'BREACHED' | 'PENDING' | 'NO_SLA';

export interface StageTimer {
  state: TimerState;
  /** The visible text; the caller adds the state's icon ("✓", "●", "⚠", "○"). */
  text: string;
  /** An accessible label precise to the minute. */
  spoken: string;
}

export interface TimedStep {
  status: StepStatus;
  startedAt: string | null;
  dueAt: string | null;
  sla: SlaSpec | null;
}

const instant = (iso: string | null | undefined): number | null => {
  if (!iso) return null;
  const ms = Date.parse(iso);
  return Number.isNaN(ms) ? null : ms;
};

/**
 * A stage's timer at `nowMs` (server time: see serverTime). Completed and pending stages have no clock; an active stage
 * without a deadline has no SLA. An active stage runs until now passes its deadline, then reports the breach.
 */
export function stageTimer(step: TimedStep, nowMs: number): StageTimer {
  if (step.status === 'COMPLETED') return { state: 'COMPLETED', text: 'Completed', spoken: 'Stage completed' };
  if (step.status === 'LOCKED') {
    const text = 'Pending · timer starts when previous stage is completed';
    return { state: 'PENDING', text, spoken: 'Pending: the timer starts when the previous stage is completed' };
  }
  const due = instant(step.dueAt);
  if (!step.sla || due === null) return { state: 'NO_SLA', text: 'No SLA for this stage', spoken: 'No SLA for this stage' };
  if (nowMs > due) {
    return {
      state: 'BREACHED',
      text: `SLA BREACHED · overdue by ${formatDuration(nowMs - due)}`,
      spoken: `SLA breached: overdue by ${formatDurationSpoken(nowMs - due)}`,
    };
  }
  const started = instant(step.startedAt);
  const remaining = `${formatDuration(due - nowMs)} remaining`;
  return {
    state: 'RUNNING',
    text: started === null ? remaining : `${formatDuration(Math.max(0, nowMs - started))} elapsed · ${remaining}`,
    spoken: `SLA running: ${formatDurationSpoken(due - nowMs)} remaining`,
  };
}

/** Server time minus device time, from the `now` a view carried when it arrived; 0 when `now` is unreadable. */
export function clockOffset(serverNow: string | null | undefined, deviceNowMs: number): number {
  const server = instant(serverNow);
  return server === null ? 0 : server - deviceNowMs;
}

/** The server's time now, from the device clock and a clockOffset. */
export function serverTime(offsetMs: number, deviceNowMs: number = Date.now()): number {
  return deviceNowMs + offsetMs;
}

interface ZonedParts { year: number; month: number; day: number; hour: number; minute: number }

const formatters = new Map<string, Intl.DateTimeFormat>();

/** The school's zone, falling back to the default school zone when it is blank or unknown to this browser. */
function formatterFor(timeZone: string | null | undefined): Intl.DateTimeFormat {
  const zone = timeZone?.trim() || DEFAULT_SCHOOL_TIME_ZONE;
  let formatter = formatters.get(zone);
  if (!formatter) {
    const options: Intl.DateTimeFormatOptions = {
      year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', hourCycle: 'h23',
    };
    try {
      formatter = new Intl.DateTimeFormat('en-GB', { ...options, timeZone: zone });
    } catch {
      formatter = new Intl.DateTimeFormat('en-GB', { ...options, timeZone: DEFAULT_SCHOOL_TIME_ZONE });
    }
    formatters.set(zone, formatter);
  }
  return formatter;
}

/** Calendar fields in the zone. Numbers only, so no locale or ICU version changes a month name or "am"/"AM". */
function zoned(ms: number, timeZone: string | null | undefined): ZonedParts {
  const values: Record<string, number> = {};
  for (const part of formatterFor(timeZone).formatToParts(new Date(ms))) {
    if (part.type !== 'literal') values[part.type] = Number(part.value);
  }
  return { year: values.year, month: values.month, day: values.day, hour: values.hour % 24, minute: values.minute };
}

/** A day in the zone: "16 Nov". */
export function formatDayMonth(iso: string, timeZone: string | null | undefined): string {
  const ms = instant(iso);
  if (ms === null) return iso;
  const { month, day } = zoned(ms, timeZone);
  return `${day} ${MONTHS[month - 1]}`;
}

/** An instant in the zone, as the prototype writes it: "13 Nov, 12:19 am", "16 Nov, 4:00 pm". */
export function formatStamp(iso: string, timeZone: string | null | undefined): string {
  const ms = instant(iso);
  if (ms === null) return iso;
  const { month, day, hour, minute } = zoned(ms, timeZone);
  const hour12 = hour % 12 === 0 ? 12 : hour % 12;
  return `${day} ${MONTHS[month - 1]}, ${hour12}:${String(minute).padStart(2, '0')} ${hour < 12 ? 'am' : 'pm'}`;
}

export interface ListedStep {
  status: StepStatus;
  completedAt: string | null;
  dueAt: string | null;
  overdue: boolean;
}

/**
 * The stage list's status line under each title (section 4.3): the stage's real status, never the one being viewed.
 * With `nowMs` an active stage turns overdue as soon as its deadline passes, without waiting for a reload.
 */
export function stageStatusLine(step: ListedStep, timeZone: string | null | undefined, nowMs?: number): string {
  if (step.status === 'COMPLETED') return step.completedAt ? `Completed · ${formatStamp(step.completedAt, timeZone)}` : 'Completed';
  if (step.status === 'LOCKED') return 'Pending';
  const due = instant(step.dueAt);
  if (due === null) return 'In progress';
  const overdue = step.overdue || (nowMs !== undefined && nowMs > due);
  return overdue
    ? `Overdue · was due ${formatStamp(step.dueAt as string, timeZone)}`
    : `In progress · due ${formatStamp(step.dueAt as string, timeZone)}`;
}

export type DueTone = 'danger' | 'warning' | 'neutral' | 'none';

export interface DueSummary {
  tone: DueTone;
  text: string;
}

/** The list and board Due cell (section 4.2): "Overdue 2d 5h", "Due in 3h" (within 4 hours), a date, or "—". */
export function dueSummary(dueAt: string | null | undefined, nowMs: number, timeZone: string | null | undefined): DueSummary {
  const due = instant(dueAt);
  if (due === null) return { tone: 'none', text: '—' };
  if (nowMs > due) return { tone: 'danger', text: `Overdue ${formatShortDuration(nowMs - due)}` };
  if (due - nowMs <= DUE_SOON_MS) return { tone: 'warning', text: `Due in ${formatShortDuration(due - nowMs)}` };
  return { tone: 'neutral', text: formatStamp(dueAt as string, timeZone) };
}

type UpcomingDue = Pick<UpcomingAction, 'done' | 'dueState' | 'dueAt'>;

/** An Upcoming actions row's due word (D39), from the state the server computed in the school's zone. */
export function upcomingDueLabel(action: UpcomingDue, timeZone: string | null | undefined): string {
  if (action.done) return 'Completed';
  switch (action.dueState) {
    case 'OVERDUE': return 'Overdue';
    case 'DUE_TODAY': return 'Due today';
    case 'DUE_TOMORROW': return 'Due tomorrow';
    case 'LATER': return action.dueAt ? formatDayMonth(action.dueAt, timeZone) : '';
    default: return '';
  }
}

export function upcomingDueTone(action: Pick<UpcomingAction, 'done' | 'dueState'>): 'danger' | 'warning' | 'neutral' {
  if (action.done) return 'neutral';
  if (action.dueState === 'OVERDUE') return 'danger';
  if (action.dueState === 'DUE_TODAY') return 'warning';
  return 'neutral';
}

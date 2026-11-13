import { AlertTriangle, CheckCircle2, Circle, Clock, Dot } from 'lucide-react';
import { slaLabel, stageTimer, type TimerState } from '../shared/slaFormat';
import { useServerClock } from './hooks';
import type { StepView } from './types';

const MINUTE = 60_000;

const ICONS: Record<TimerState, typeof Clock> = {
  COMPLETED: CheckCircle2,
  RUNNING: Dot,
  BREACHED: AlertTriangle,
  PENDING: Circle,
  NO_SLA: Circle,
};

/**
 * The stage header's SLA (section 4.3): the time slot, then a live timer computed from the server's start and due times
 * and corrected by the clock offset. The visible text ticks every second; it is not a live region, and the accessible
 * label beside it changes once a minute so a screen reader is never flooded.
 */
export function SlaTimer({ step, offsetMs }: { step: Pick<StepView, 'status' | 'startedAt' | 'dueAt' | 'sla'>; offsetMs: number }) {
  const ticking = step.status === 'ACTIVE' && !!step.sla && !!step.dueAt;
  const now = useServerClock(offsetMs, 1000, ticking);
  const timer = stageTimer(step, now);
  // The spoken label is computed at the start of the current minute, so it only changes once a minute.
  const spoken = stageTimer(step, Math.floor(now / MINUTE) * MINUTE).spoken;
  const Icon = ICONS[timer.state];
  return (
    <div className="ck-wf-sla">
      <div className="ck-wf-sla-slot"><Clock size={14} aria-hidden="true" /> {slaLabel(step.sla)}</div>
      {timer.state !== 'NO_SLA' && (
        <div className={`ck-wf-timer is-${timer.state.toLowerCase()}`} data-state={timer.state}>
          <Icon size={14} aria-hidden="true" />
          <span aria-hidden="true">{timer.text}</span>
          <span className="ck-sr-only">{spoken}</span>
        </div>
      )}
    </div>
  );
}

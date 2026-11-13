import { Check, ChevronLeft, ChevronRight, Lock } from 'lucide-react';
import { stageStatusLine } from '../shared/slaFormat';
import type { Stage } from './types';

const STATE_WORDS = { COMPLETED: 'completed', ACTIVE: 'in progress', LOCKED: 'locked' } as const;

function stepName(stage: Stage, viewing: boolean): string {
  return `Step ${stage.view.step}: ${stage.spec.title}, ${STATE_WORDS[stage.view.status]}${viewing ? ', viewing' : ''}`;
}

/**
 * The horizontal stepper (section 4.3): every stage of the order's path, completed ones ticked, the current one ringed and
 * the viewed one filled. Each is a button that opens the stage. With less room only the current and viewed labels show
 * (workflow.css); below a 768 px window it collapses to "Step n · Title (k of N)".
 */
export function Stepper({ stages, viewing, onView, narrow = false }: {
  stages: Stage[]; viewing: number; onView: (step: number) => void;
  /** Below a 768 px window: one line with previous and next buttons. */
  narrow?: boolean;
}) {
  const index = Math.max(0, stages.findIndex((stage) => stage.view.step === viewing));
  if (narrow) {
    const stage = stages[index];
    return (
      <nav className="ck-wf-stepper is-narrow" aria-label="Workflow stages">
        <button type="button" className="ck-btn ck-btn-ghost ck-btn-sm" aria-label="Previous stage" disabled={index === 0} onClick={() => onView(stages[index - 1].view.step)}>
          <ChevronLeft size={16} aria-hidden="true" />
        </button>
        <span className="ck-wf-strong">Step {stage?.view.step} · {stage?.spec.title} <span className="ck-wf-muted">({index + 1} of {stages.length})</span></span>
        <button type="button" className="ck-btn ck-btn-ghost ck-btn-sm" aria-label="Next stage" disabled={index >= stages.length - 1} onClick={() => onView(stages[index + 1].view.step)}>
          <ChevronRight size={16} aria-hidden="true" />
        </button>
      </nav>
    );
  }
  return (
    <nav className="ck-wf-stepper" aria-label="Workflow stages">
      <ol>
        {stages.map((stage) => {
          const done = stage.view.status === 'COMPLETED';
          const current = stage.view.status === 'ACTIVE';
          const isViewing = stage.view.step === viewing;
          const classes = ['ck-wf-step', done && 'is-done', current && 'is-current', isViewing && 'is-viewing', stage.view.status === 'LOCKED' && 'is-locked']
            .filter(Boolean).join(' ');
          return (
            <li key={stage.view.step} className={classes} aria-current={current ? 'step' : undefined}>
              <button type="button" onClick={() => onView(stage.view.step)} aria-label={stepName(stage, isViewing)}>
                <span className="ck-wf-step-dot" aria-hidden="true">{done ? <Check size={14} strokeWidth={3} /> : stage.view.step}</span>
                <span className="ck-wf-step-label" aria-hidden="true">{stage.spec.title}</span>
              </button>
            </li>
          );
        })}
      </ol>
    </nav>
  );
}

/**
 * The left "ORDER WORKFLOW n" list (section 4.3): each stage with its real status line, never the status of the stage
 * that happens to be open. Below a 1024 px window it becomes a "Stage" select.
 */
export function StageList({ stages, viewing, onView, timeZone, now, compact = false }: {
  stages: Stage[]; viewing: number; onView: (step: number) => void; timeZone: string; now: number;
  /** Below a 1024 px window the list becomes a select. */
  compact?: boolean;
}) {
  if (compact) {
    return (
      <div className="ck-wf-stage-select">
        <label htmlFor="ck-wf-stage-select" className="ck-wf-kpi-l">Stage</label>
        <select id="ck-wf-stage-select" value={viewing} onChange={(e) => onView(Number(e.target.value))}>
          {stages.map((stage) => (
            <option key={stage.view.step} value={stage.view.step}>{stage.view.step}. {stage.spec.title} — {stageStatusLine(stage.view, timeZone, now)}</option>
          ))}
        </select>
      </div>
    );
  }
  return (
    <nav className="ck-wf-stages" aria-label="Order workflow stages">
      <header className="ck-wf-stages-h"><span>Order workflow</span><span>{stages.length}</span></header>
      <ol>
        {stages.map((stage) => {
          const done = stage.view.status === 'COMPLETED';
          const locked = stage.view.status === 'LOCKED';
          const line = stageStatusLine(stage.view, timeZone, now);
          const late = line.startsWith('Overdue');
          const isViewing = stage.view.step === viewing;
          return (
            <li key={stage.view.step}>
              <button type="button" className={`ck-wf-stage-row${isViewing ? ' is-viewing' : ''}${done ? ' is-done' : ''}${late ? ' is-late' : ''}`}
                aria-current={isViewing ? 'true' : undefined} onClick={() => onView(stage.view.step)}>
                <span className="ck-wf-stage-badge" aria-hidden="true">{done ? <Check size={14} strokeWidth={3} /> : locked ? <Lock size={12} /> : stage.view.step}</span>
                <span className="ck-wf-stage-text">
                  <span className="ck-wf-strong">{stage.spec.title}</span>
                  <span className="ck-wf-stage-line">{line}</span>
                </span>
              </button>
            </li>
          );
        })}
      </ol>
    </nav>
  );
}

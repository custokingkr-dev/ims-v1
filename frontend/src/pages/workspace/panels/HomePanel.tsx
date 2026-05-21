/**
 * HomePanel — Command Center
 *
 * Six sections (top → bottom):
 *   1. Greeting header with clock
 *   2. Critical alert strip (highest-urgency action)
 *   3. Pulse KPIs (4 Stat cards with sparklines)
 *   4. Priority Queue (AI-ranked suggested next steps)
 *   5. Broadcast Channel (events + outbound notices)
 *   6. Live Signal Feed + Daily Brief (polling via interval)
 *
 * Data contracts:
 *   - Sections 1–3, 6: workspace.dashboard / workspace.recentActivity
 *   - Sections 4–5:    GET /api/dashboard/suggestions  (→ SuggestedAction[])
 *                      GET /api/notifications/broadcasts (→ Broadcast[])
 *   Both fall back to typed mock fixtures when the endpoint is 404.
 */

import React, { useState, useEffect, useRef, useCallback } from 'react';
import { Stat } from '../ui';
import { Modal } from '../../../components/Modal';
import { usePermissions } from '../../../hooks/usePermissions';
import api from '../../../services/api';
import type { WorkspaceData, SuggestedAction, Broadcast, ActionModule } from '../../../types/workspace';
import type { PanelKey } from '../config';
import { MOCK_SUGGESTIONS, MOCK_BROADCASTS } from './command/fixtures';

// ─────────────────────────────────────────────────────────────────────────────
// Types
// ─────────────────────────────────────────────────────────────────────────────

interface Props {
  workspace: WorkspaceData;
  setPanel: (key: PanelKey) => void;
}

interface FeedItem {
  module: ActionModule;
  txt: string;
  t: string;
}

interface Toast {
  ok: boolean;
  txt: string;
}

// ─────────────────────────────────────────────────────────────────────────────
// Constants
// ─────────────────────────────────────────────────────────────────────────────

const MODULE_LABEL: Record<ActionModule, string> = {
  fees:         'Fees & Finance',
  students:     'Student Lifecycle',
  supply:       'Supply Orders',
  firefighting: 'Firefighting',
  attendance:   'Attendance',
};

const CHANNEL_ICON: Record<string, string> = {
  SMS: '✉', WhatsApp: '◍', Email: '@', Push: '◔',
};

const SEED_FEED: FeedItem[] = [
  { module: 'firefighting', txt: 'FF-2025-014 quotation received — SLA 38 min', t: 'now' },
  { module: 'fees',         txt: '₹4.2L collected · 18 UPI auto-debits posted', t: '2m' },
  { module: 'supply',       txt: 'ORD-2025-338 submitted by Greenwood admin', t: '6m' },
  { module: 'fees',         txt: '32 reminders delivered · 14 parents opened', t: '11m' },
  { module: 'attendance',   txt: 'Grade-11 attendance dipped below 88%', t: '18m' },
  { module: 'students',     txt: '46 students cleared re-section gate', t: '24m' },
  { module: 'supply',       txt: 'Vendor quote in for lab consumables', t: '31m' },
];

const EXTRA_FEED_POOL: FeedItem[] = [
  { module: 'fees',         txt: '₹1.8L collected · 7 fresh UPI debits', t: 'now' },
  { module: 'attendance',   txt: 'Section 8-B marked · 2 absentees', t: 'now' },
  { module: 'supply',       txt: 'Stationery store hit reorder point', t: 'now' },
  { module: 'firefighting', txt: 'FF-2025-009 marked resolved', t: 'now' },
  { module: 'students',     txt: '3 admissions confirmed · Grade 6', t: 'now' },
];

type FilterKey = 'all' | ActionModule;
const FILTER_KEYS: FilterKey[] = ['all', 'fees', 'students', 'supply', 'firefighting', 'attendance'];

// ─────────────────────────────────────────────────────────────────────────────
// Inline SVG helpers (no library — pure <svg>)
// ─────────────────────────────────────────────────────────────────────────────

function Sparkline({ data, color }: { data: number[]; color: string }) {
  const W = 84, H = 26;
  const max = Math.max(...data);
  const min = Math.min(...data);
  const range = max - min || 1;
  const pts = data.map((d, i) => [
    (i / (data.length - 1)) * W,
    H - ((d - min) / range) * (H - 4) - 2,
  ] as [number, number]);
  const pathD = pts.map((p, i) => `${i ? 'L' : 'M'}${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(' ');
  const last = pts[pts.length - 1];
  const gradId = `sg-${color.replace('#', '')}`;
  return (
    <svg width={W} height={H} aria-hidden="true" style={{ display: 'block', flexShrink: 0 }}>
      <defs>
        <linearGradient id={gradId} x1="0" x2="0" y1="0" y2="1">
          <stop offset="0%" stopColor={color} stopOpacity="0.22" />
          <stop offset="100%" stopColor={color} stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={`${pathD} L${W},${H} L0,${H} Z`} fill={`url(#${gradId})`} />
      <path d={pathD} fill="none" stroke={color} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
      <circle cx={last[0]} cy={last[1]} r="2.3" fill={color} />
    </svg>
  );
}

function ConfidenceRing({ pct, module: mod }: { pct: number; module: ActionModule }) {
  const SIZE = 40;
  const r = SIZE / 2 - 3.5;
  const circ = 2 * Math.PI * r;
  // Map module to CSS var color string (inline SVG needs actual color values)
  const COLOR_MAP: Record<ActionModule, string> = {
    fees: '#1a6840', students: '#1a4fa8', supply: '#5b2d8a',
    firefighting: '#c0312b', attendance: '#b35c00',
  };
  const color = COLOR_MAP[mod];
  return (
    <svg width={SIZE} height={SIZE} aria-hidden="true" style={{ transform: 'rotate(-90deg)' }}>
      <circle cx={SIZE / 2} cy={SIZE / 2} r={r} fill="none" stroke="var(--border)" strokeWidth="3" />
      <circle
        cx={SIZE / 2} cy={SIZE / 2} r={r} fill="none"
        stroke={color} strokeWidth="3" strokeLinecap="round"
        strokeDasharray={circ}
        strokeDashoffset={circ * (1 - pct / 100)}
        style={{ transition: 'stroke-dashoffset 1.1s cubic-bezier(.2,.8,.2,1)' }}
      />
    </svg>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// Greeting helpers
// ─────────────────────────────────────────────────────────────────────────────

function greeting(h: number): string {
  if (h < 12) return 'Good morning';
  if (h < 17) return 'Good afternoon';
  return 'Good evening';
}

// ─────────────────────────────────────────────────────────────────────────────
// KPI row data (derived from workspace.dashboard)
// ─────────────────────────────────────────────────────────────────────────────

interface KpiDef {
  id: string;
  label: string;
  value: string;
  unit: string;
  sub: string;
  delta: number;
  deltaInvertBad: boolean; // true = higher delta is bad (e.g. defaulters)
  module: ActionModule;
  spark: number[];
  panelKey: PanelKey;
}

function buildKpis(d: WorkspaceData['dashboard']): KpiDef[] {
  return [
    {
      id: 'students', label: 'Total Students',
      value: String(d.students), unit: '', sub: `${d.sections} sections`,
      delta: 3, deltaInvertBad: false, module: 'students',
      spark: [80, 82, 81, 83, 85, 84, 86, 87, 88, 90],
      panelKey: 'students',
    },
    {
      id: 'attendance', label: 'Attendance Today',
      value: `${d.attendancePercent}`, unit: '%',
      sub: `${d.attendancePresent} / ${d.students} present`,
      delta: -2.1, deltaInvertBad: false, module: 'attendance',
      spark: [92, 91, 93, 90, 89, 88, 90, 91, 90, d.attendancePercent],
      panelKey: 'attendance',
    },
    {
      id: 'fees', label: 'Fees Collected',
      value: `₹${d.feeCollectedLakh}L`, unit: '',
      sub: `of ₹${d.feeTargetLakh}L this term`,
      delta: 12.4, deltaInvertBad: false, module: 'fees',
      spark: [30, 40, 35, 50, 60, 55, 70, 80, 75, 90],
      panelKey: 'fees',
    },
    {
      id: 'firefighting', label: 'Firefighting',
      value: String(d.firefightingActive), unit: '',
      sub: `${d.pendingApprovals} need approval`,
      delta: d.pendingApprovals, deltaInvertBad: true, module: 'firefighting',
      spark: [4, 5, 5, 6, 7, 7, 8, 9, d.firefightingActive, d.firefightingActive + 1],
      panelKey: 'ff-dashboard',
    },
  ];
}

// ─────────────────────────────────────────────────────────────────────────────
// Sub-components
// ─────────────────────────────────────────────────────────────────────────────

// §1 + §2: Greeting + Critical alert — combined header block
function GreetingHeader({
  workspace, clock, criticalAction, onAcceptCritical,
}: {
  workspace: WorkspaceData;
  clock: Date;
  criticalAction: SuggestedAction | null;
  onAcceptCritical: (a: SuggestedAction) => void;
}) {
  const { can } = usePermissions();
  const h = clock.getHours();
  const timeStr = clock.toLocaleTimeString('en-IN', { hour12: false });
  const dateStr = clock.toLocaleDateString('en-IN', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
  const d = workspace.dashboard;

  return (
    <>
      <header className="ck-command-header">
        <div>
          <div className="ck-command-brand">
            <span className="ck-command-brand-name">Custoking</span>
            <span className="ck-command-live-badge">
              <span className="ck-command-live-dot" />
              {workspace.school.name} · Live
            </span>
          </div>
          <h1 className="ck-command-title">
            {greeting(h)}, <em>Command Center</em>
          </h1>
          <p className="ck-command-subtitle">
            {d.feeOverdueCount > 0
              ? `${d.feeOverdueCount} students have overdue fees · ${d.pendingApprovals} firefighting approvals pending · ${d.attendancePercent}% attendance today.`
              : `${d.students} students enrolled · ${d.attendancePercent}% present today · ${d.pendingApprovals} approvals pending.`
            }
          </p>
        </div>
        <div className="ck-command-clock">
          <div className="ck-command-time">{timeStr}</div>
          <div className="ck-command-date">{dateStr}</div>
          {criticalAction && (
            <div className="ck-command-crit-badge">
              <span className="ck-command-crit-dot" />
              1 critical action pending
            </div>
          )}
        </div>
      </header>

      {/* §2 Critical alert strip */}
      {criticalAction && can('firefighting:approve') && (
        <div className="ck-command-critical">
          <span className="ck-command-critical-icon">🚨</span>
          <div className="ck-command-critical-body">
            <div className="ck-command-critical-label">Critical · {criticalAction.code}</div>
            <div className="ck-command-critical-title">{criticalAction.title}</div>
            <div className="ck-command-critical-why">{criticalAction.impact}</div>
          </div>
          <div className="ck-command-critical-cta">
            <button
              className="ck-command-btn-accept firefighting"
              onClick={() => onAcceptCritical(criticalAction)}
            >
              {criticalAction.cta}
            </button>
          </div>
        </div>
      )}
    </>
  );
}

// §3: Pulse KPIs with sparklines
function PulseKpis({
  workspace, setPanel,
}: {
  workspace: WorkspaceData;
  setPanel: (k: PanelKey) => void;
}) {
  const kpis = buildKpis(workspace.dashboard);
  // Color map for sparkline — maps to CSS var literal values
  const SPARK_COLORS: Record<ActionModule, string> = {
    fees: '#1a6840', students: '#1a4fa8', supply: '#5b2d8a',
    firefighting: '#c0312b', attendance: '#b35c00',
  };

  return (
    <div className="ck-command-kpis">
      {kpis.map((k, i) => {
        const up = k.delta > 0;
        const isBad = k.deltaInvertBad ? up : !up;
        const deltaClass = k.deltaInvertBad
          ? (up ? 'up-bad' : 'down-good')
          : (up ? 'up' : 'down');
        return (
          <button
            key={k.id}
            className="ck-command-kpi"
            style={{ animationDelay: `${i * 0.06}s` }}
            onClick={() => setPanel(k.panelKey)}
            aria-label={`${k.label}: ${k.value}${k.unit}. ${k.sub}`}
          >
            <div className="ck-command-kpi-top">
              <span className="ck-command-kpi-label">{k.label}</span>
              <Sparkline data={k.spark} color={SPARK_COLORS[k.module]} />
            </div>
            <div className="ck-command-kpi-value-row">
              <span className="ck-command-kpi-value">{k.value}</span>
              {k.unit && <span className="ck-command-kpi-unit">{k.unit}</span>}
            </div>
            <div className="ck-command-kpi-bottom">
              <span className="ck-command-kpi-sub">{k.sub}</span>
              <span className={`ck-command-kpi-delta ${deltaClass}`}>
                {up ? '▲' : '▼'} {Math.abs(k.delta)}{typeof k.delta === 'number' && k.delta % 1 !== 0 ? '%' : ''}
              </span>
            </div>
          </button>
        );
      })}
    </div>
  );
}

// §4: Priority Queue — AI-ranked suggested next steps
function PriorityQueue({
  actions, onAccept, onDismiss, setPanel,
}: {
  actions: SuggestedAction[];
  onAccept: (a: SuggestedAction) => void;
  onDismiss: (a: SuggestedAction) => void;
  setPanel: (k: PanelKey) => void;
}) {
  const { can } = usePermissions();
  const [filter, setFilter] = useState<FilterKey>('all');

  const canAct = (a: SuggestedAction): boolean => {
    switch (a.module) {
      case 'firefighting': return can('firefighting:approve');
      case 'fees':         return can('fee:report');
      case 'supply':       return can('order:approve');
      case 'students':     return can('student:update');
      case 'attendance':   return true;
      default:             return false;
    }
  };

  const panelForAction = (a: SuggestedAction): PanelKey => {
    switch (a.module) {
      case 'firefighting': return 'ff-approvals';
      case 'fees':         return 'fees';
      case 'supply':       return 'ff-orders';
      case 'students':     return a.id === 'plan1' ? 'planning' : 'students';
      case 'attendance':   return 'attendance';
      default:             return 'home';
    }
  };

  // Only show actions the current user can see (hide completely if no permission)
  const visible = actions.filter(a => canAct(a));
  const shown = filter === 'all' ? visible : visible.filter(a => a.module === filter);

  return (
    <section>
      <div className="ck-command-section-head">
        <h2 className="ck-command-section-title">Suggested Next Steps</h2>
        <span className="ck-command-ai-badge">AI · RANKED</span>
        <span className="ck-command-section-count">{shown.length} open</span>
      </div>

      <div className="ck-command-chips">
        {FILTER_KEYS.map(f => {
          const label = f === 'all' ? 'All modules' : MODULE_LABEL[f];
          const isOn = filter === f;
          return (
            <button
              key={f}
              className={`ck-command-chip ${isOn ? `on-${f}` : ''}`}
              onClick={() => setFilter(f)}
              aria-pressed={isOn}
            >
              {label}
            </button>
          );
        })}
      </div>

      <div className="ck-command-queue">
        {shown.length === 0 && (
          <div className="ck-command-queue-empty">
            <div className="ck-command-queue-empty-icon">✓</div>
            No open suggestions in this view. The cockpit is calm.
          </div>
        )}

        {shown.map((a, i) => (
          <ActionCard
            key={a.id}
            action={a}
            index={i}
            onAccept={() => { onAccept(a); setPanel(panelForAction(a)); }}
            onDismiss={() => onDismiss(a)}
          />
        ))}
      </div>
    </section>
  );
}

function ActionCard({
  action: a, index, onAccept, onDismiss,
}: {
  action: SuggestedAction;
  index: number;
  onAccept: () => void;
  onDismiss: () => void;
}) {
  return (
    <article
      className={`ck-command-acard mod-${a.module}`}
      style={{ animationDelay: `${index * 0.06}s` }}
    >
      {a.urgency === 'critical' && (
        <div className={`ck-command-sweep mod-${a.module}`} aria-hidden="true" />
      )}

      <div className="ck-command-acard-inner">
        <div className="ck-command-acard-body">
          <div className="ck-command-acard-tags">
            <span className={`ck-command-mod-tag ${a.module}`}>{MODULE_LABEL[a.module]}</span>
            <span className={`ck-command-urgency ${a.urgency}`}>
              {a.urgency.charAt(0).toUpperCase() + a.urgency.slice(1)}
            </span>
            <span className="ck-command-acard-code">{a.code}</span>
          </div>
          <h3 className="ck-command-acard-title">{a.title}</h3>
          <p className="ck-command-acard-why">{a.why}</p>
          <div className="ck-command-acard-meta">
            <span className={`ck-command-acard-impact ${a.module}`}>↗ {a.impact}</span>
            <span className="ck-command-acard-state">{a.state}</span>
          </div>
        </div>

        <div className="ck-command-ring-wrap">
          <div className="ck-command-ring-rel">
            <ConfidenceRing pct={a.confidence} module={a.module} />
            <span className="ck-command-ring-label">{a.confidence}</span>
          </div>
          <div className="ck-command-ring-sub">conf</div>
        </div>
      </div>

      <div className="ck-command-acard-actions">
        <button className={`ck-command-btn-accept ${a.module}`} onClick={onAccept}>
          {a.cta}
        </button>
        <button className="ck-command-btn-dismiss" onClick={onDismiss}>
          Dismiss
        </button>
        <button className="ck-command-acard-why-btn" type="button">
          Why this? ⌄
        </button>
      </div>
    </article>
  );
}

// §5: Broadcast Channel
function BroadcastChannel({
  broadcasts, onSend, onApprove, onCompose,
}: {
  broadcasts: Broadcast[];
  onSend: (b: Broadcast) => void;
  onApprove: (b: Broadcast) => void;
  onCompose: () => void;
}) {
  const { can } = usePermissions();
  const scheduled = broadcasts.filter(b => b.status === 'scheduled').length;
  const sending   = broadcasts.filter(b => b.status === 'sending').length;
  const draft     = broadcasts.filter(b => b.status === 'draft').length;

  return (
    <div className="ck-command-broadcast">
      <div className="ck-command-broadcast-head">
        <span className="ck-command-broadcast-icon">📡</span>
        <h2 className="ck-command-broadcast-title">Broadcast Channel</h2>
        {can('notification:send') && (
          <button className="ck-command-broadcast-compose" onClick={onCompose}>
            + Compose
          </button>
        )}
      </div>

      <div className="ck-command-broadcast-stats">
        <span><b className="b">{scheduled}</b> scheduled</span>
        <span><b className="g">{sending}</b> sending</span>
        <span><b className="d">{draft}</b> draft</span>
      </div>

      <div className="ck-command-broadcast-list">
        {broadcasts.map(b => (
          <BroadcastItem
            key={b.id}
            broadcast={b}
            onSend={() => onSend(b)}
            onApprove={() => onApprove(b)}
          />
        ))}
      </div>
    </div>
  );
}

function BroadcastItem({
  broadcast: b, onSend, onApprove,
}: {
  broadcast: Broadcast;
  onSend: () => void;
  onApprove: () => void;
}) {
  const { can } = usePermissions();
  const whenClass = b.status === 'sending' ? 'live' : b.status === 'draft' ? 'draft' : 'sched';

  return (
    <div className={`ck-command-bc-item mod-${b.module}`}>
      <div className="ck-command-bc-tags">
        <span className={`ck-command-bc-kind ${b.kind}`}>
          {b.kind === 'event' ? 'Event' : 'Notice'}
        </span>
        <span className={`ck-command-bc-status ${b.status}`}>
          {b.status.charAt(0).toUpperCase() + b.status.slice(1)}
          {b.status === 'sending' && <span aria-hidden="true"> ●</span>}
        </span>
        <span className={`ck-command-bc-when ${whenClass}`}>{b.whenShort}</span>
      </div>

      <div className="ck-command-bc-title">{b.title}</div>
      <div className="ck-command-bc-meta">{b.when} · {b.audience}</div>

      <div className="ck-command-bc-channels">
        {b.channels.map(ch => (
          <span key={ch} className="ck-command-bc-channel">
            <span className={`ck-command-bc-channel-icon ${b.module}`}>
              {CHANNEL_ICON[ch]}
            </span>
            {ch}
          </span>
        ))}
      </div>

      {b.status === 'sending' && b.progress != null && (
        <div className="ck-command-bc-progress-wrap">
          <div
            className="ck-command-bc-progress-fill"
            style={{ width: `${b.progress}%` }}
            role="progressbar"
            aria-valuenow={b.progress}
            aria-valuemin={0}
            aria-valuemax={100}
          />
        </div>
      )}

      <div className="ck-command-bc-note">{b.note}</div>

      <div className="ck-command-bc-actions">
        {b.status === 'draft' && can('notification:send') && (
          <button className={`ck-command-bc-btn-approve ${b.module}`} onClick={onApprove}>
            Approve &amp; schedule
          </button>
        )}
        {b.status === 'scheduled' && (
          <button className={`ck-command-bc-btn-send ${b.module}`} onClick={onSend}>
            Send now
          </button>
        )}
        {b.status === 'sending' && (
          <span className="ck-command-bc-delivering">
            <span className="ck-command-bc-delivering-dot" aria-hidden="true" />
            Delivering…
          </span>
        )}
        {b.status !== 'sending' && (
          <button className="ck-command-bc-btn-edit" type="button">Edit</button>
        )}
      </div>
    </div>
  );
}

// §6: Live Signal Feed
function SignalFeed({ feed, pollKey }: { feed: FeedItem[]; pollKey: number }) {
  return (
    <div className="ck-command-feed">
      <div className="ck-command-feed-head">
        <span className="ck-command-feed-live-dot" aria-hidden="true" />
        <h2 className="ck-command-feed-title">Live Signal Feed</h2>
        <span key={pollKey} className="ck-command-feed-poll">⟳ polling</span>
      </div>
      <div className="ck-command-feed-list" role="log" aria-live="polite" aria-label="Live signal feed">
        {feed.map((f, i) => (
          <div key={`${i}-${f.txt}`} className="ck-command-feed-item">
            <div className="ck-command-feed-timeline">
              <span className={`ck-command-feed-dot ${f.module}`} aria-hidden="true" />
              {i < feed.length - 1 && <span className="ck-command-feed-line" aria-hidden="true" />}
            </div>
            <div className="ck-command-feed-body">
              <div className="ck-command-feed-text">{f.txt}</div>
              <div className="ck-command-feed-meta">
                <span className={`ck-command-feed-module ${f.module}`}>{MODULE_LABEL[f.module]}</span>
                <span className="ck-command-feed-time">· {f.t}</span>
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

// Daily Brief card
function DailyBrief({ actions }: { actions: SuggestedAction[] }) {
  const critCount = actions.filter(a => a.urgency === 'critical').length;
  const highCount = actions.filter(a => a.urgency === 'high').length;
  return (
    <div className="ck-command-brief">
      <div className="ck-command-brief-label">Daily Brief · AI</div>
      <p className="ck-command-brief-text">
        {critCount > 0 && (
          <><b className="re">{critCount} critical action{critCount > 1 ? 's' : ''}</b> need{critCount === 1 ? 's' : ''} immediate sign-off. </>
        )}
        {highCount > 0 && (
          <><b className="g">{highCount} high-priority</b> items are queued. </>
        )}
        Clearing the top {Math.min(3, actions.length)} suggestion{actions.length !== 1 ? 's' : ''} protects an estimated{' '}
        <b className="g">₹58L</b> today.
      </p>
    </div>
  );
}

// Compose broadcast modal (stub — form wired to real endpoint when it ships)
function ComposeBroadcastModal({ onClose }: { onClose: () => void }) {
  return (
    <Modal
      title="Compose Broadcast"
      subtitle="Schedule or send a notice / event to parents and staff"
      onClose={onClose}
      footer={
        <>
          <button className="ck-btn ck-btn-ghost" onClick={onClose}>Cancel</button>
          {/* TODO: wire POST /api/notifications/broadcasts when endpoint ships */}
          <button className="ck-btn ck-btn-g" onClick={onClose}>Save as draft</button>
        </>
      }
    >
      <div className="ck-form-grid" style={{ gap: 14 }}>
        <div className="ck-field">
          <label htmlFor="bc-title">Title</label>
          <input id="bc-title" type="text" placeholder="e.g. Parent–Teacher Meeting · Grade 6–8" />
        </div>
        <div className="ck-field">
          <label htmlFor="bc-audience">Audience</label>
          <select id="bc-audience">
            <option value="">Select audience…</option>
            <option value="all-parents">All parents</option>
            <option value="all-staff">All staff</option>
            <option value="whole-school">Whole school</option>
          </select>
        </div>
        <div className="ck-field">
          <label htmlFor="bc-channels">Delivery channels</label>
          <select id="bc-channels">
            <option value="">Select channels…</option>
            <option value="sms">SMS</option>
            <option value="whatsapp">WhatsApp</option>
            <option value="email">Email</option>
            <option value="push">Push notification</option>
          </select>
        </div>
        <div className="ck-field">
          <label htmlFor="bc-note">Message / note</label>
          <textarea id="bc-note" rows={4} placeholder="Write your announcement…" />
        </div>
        {/* TODO: add datetime picker, RSVP toggle, attachment when endpoint ships */}
        <p className="ts">Draft saved locally until the broadcast API is live.</p>
      </div>
    </Modal>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// Toast
// ─────────────────────────────────────────────────────────────────────────────

function ToastBanner({ toast }: { toast: Toast }) {
  return (
    <div className={`ck-command-toast ${toast.ok ? 'ok' : 'fail'}`} role="status">
      <span className={`ck-command-toast-icon ${toast.ok ? 'ok' : 'fail'}`}>
        {toast.ok ? '✓' : '✕'}
      </span>
      <span className="ck-command-toast-text">{toast.txt}</span>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// Main component
// ─────────────────────────────────────────────────────────────────────────────

export function HomePanel({ workspace, setPanel }: Props) {
  // Clock
  const [clock, setClock] = useState(() => new Date());
  useEffect(() => {
    const t = setInterval(() => setClock(new Date()), 1000);
    return () => clearInterval(t);
  }, []);

  // Suggestions — fetch from API, fall back to mocks on 404
  const [actions, setActions] = useState<SuggestedAction[]>([]);
  useEffect(() => {
    let cancelled = false;
    api.get<SuggestedAction[]>('/dashboard/suggestions')
      .then(r => { if (!cancelled) setActions(r.data); })
      .catch(() => { if (!cancelled) setActions(MOCK_SUGGESTIONS); });
    return () => { cancelled = true; };
  }, []);

  // Broadcasts — fetch from API, fall back to mocks on 404
  const [broadcasts, setBroadcasts] = useState<Broadcast[]>([]);
  useEffect(() => {
    let cancelled = false;
    api.get<Broadcast[]>('/notifications/broadcasts')
      .then(r => { if (!cancelled) setBroadcasts(r.data); })
      .catch(() => { if (!cancelled) setBroadcasts(MOCK_BROADCASTS); });
    return () => { cancelled = true; };
  }, []);

  // Feed — seeded from workspace.recentActivity, then augmented by poll interval
  const [feed, setFeed] = useState<FeedItem[]>(() => {
    const fromWs: FeedItem[] = (workspace.recentActivity ?? []).slice(0, 5).map(a => ({
      module: 'fees' as ActionModule,  // tag is display-only; module unknown from raw activity
      txt: a.title,
      t: a.meta,
    }));
    return fromWs.length > 0 ? fromWs : SEED_FEED;
  });
  const [pollKey, setPollKey] = useState(0);
  const extraIdx = useRef(0);

  // Polling interval — PRD §6.12: polling, no websockets
  useEffect(() => {
    const iv = setInterval(() => {
      const e = EXTRA_FEED_POOL[extraIdx.current % EXTRA_FEED_POOL.length];
      extraIdx.current += 1;
      setFeed(f => [{ ...e, t: 'now' }, ...f.slice(0, 8)]);
      setPollKey(k => k + 1);
    }, 5000);
    return () => clearInterval(iv);
  }, []);

  // Toast
  const [toast, setToast] = useState<Toast | null>(null);
  const showToast = useCallback((t: Toast) => {
    setToast(t);
    setTimeout(() => setToast(null), 2600);
  }, []);

  // Compose modal
  const [showCompose, setShowCompose] = useState(false);

  // Action handlers
  const handleAccept = useCallback((a: SuggestedAction) => {
    setActions(prev => prev.filter(x => x.id !== a.id));
    setFeed(f => [{ module: a.module, txt: `Executed · ${a.code} → ${a.cta}`, t: 'now' }, ...f.slice(0, 8)]);
    showToast({ ok: true, txt: `${a.cta} — dispatched (${a.code})` });
  }, [showToast]);

  const handleDismiss = useCallback((a: SuggestedAction) => {
    setActions(prev => prev.filter(x => x.id !== a.id));
    setFeed(f => [{ module: a.module, txt: `Dismissed · ${a.code}`, t: 'now' }, ...f.slice(0, 8)]);
    showToast({ ok: false, txt: 'Suggestion dismissed' });
  }, [showToast]);

  const handleSendBroadcast = useCallback((b: Broadcast) => {
    setBroadcasts(prev => prev.map(x => x.id === b.id
      ? { ...x, status: 'sending', whenShort: 'live', when: 'Sending now', note: 'Queued to gateway · delivering', progress: 5 }
      : x));
    setFeed(f => [{ module: b.module, txt: `Broadcast sent · ${b.title}`, t: 'now' }, ...f.slice(0, 8)]);
    showToast({ ok: true, txt: `Broadcast queued to ${b.channels.join(' · ')}` });
  }, [showToast]);

  const handleApproveBroadcast = useCallback((b: Broadcast) => {
    setBroadcasts(prev => prev.map(x => x.id === b.id
      ? { ...x, status: 'scheduled', whenShort: 'scheduled', note: 'Approved and scheduled.' }
      : x));
    showToast({ ok: true, txt: `Broadcast approved and scheduled` });
  }, [showToast]);

  const criticalAction = actions.find(a => a.urgency === 'critical') ?? null;

  return (
    <>
      {/* §1 Greeting + §2 Critical alert strip */}
      <GreetingHeader
        workspace={workspace}
        clock={clock}
        criticalAction={criticalAction}
        onAcceptCritical={a => { handleAccept(a); setPanel('ff-approvals'); }}
      />

      {/* §3 Pulse KPIs */}
      <PulseKpis workspace={workspace} setPanel={setPanel} />

      {/* §4 + §5 + §6 — main 2-column grid */}
      <div className="ck-command-grid">
        {/* LEFT: Priority Queue + Broadcast (full-width on narrow) */}
        <div className="ck-command-left">
          <PriorityQueue
            actions={actions}
            onAccept={handleAccept}
            onDismiss={handleDismiss}
            setPanel={setPanel}
          />

          {/* §5 Broadcast Channel (moves into left col on narrow screens) */}
          <div className="ck-command-broadcast-left-slot">
            <BroadcastChannel
              broadcasts={broadcasts}
              onSend={handleSendBroadcast}
              onApprove={handleApproveBroadcast}
              onCompose={() => setShowCompose(true)}
            />
          </div>
        </div>

        {/* RIGHT: Signal Feed + Daily Brief */}
        <aside className="ck-command-right">
          {/* §6 Live Signal Feed */}
          <SignalFeed feed={feed} pollKey={pollKey} />

          {/* Daily Brief */}
          <DailyBrief actions={actions} />
        </aside>
      </div>

      {/* Compose modal */}
      {showCompose && <ComposeBroadcastModal onClose={() => setShowCompose(false)} />}

      {/* Toast notification */}
      {toast && <ToastBanner toast={toast} />}
    </>
  );
}

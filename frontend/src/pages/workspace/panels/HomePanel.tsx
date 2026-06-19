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
import type { WorkspaceData, SuggestedAction, Broadcast, BroadcastStatus, ActionModule, ActionUrgency, DeliveryChannel } from '../../../types/workspace';
import type { PanelKey } from '../config';
import { MOCK_SUGGESTIONS, MOCK_BROADCASTS } from './command/fixtures';
import type { CommandCentreCard, PolCode } from './command/commandCentreTypes';
import { deriveCommandCentreCards, panelForCard } from './command/commandCentreUtils';
import { ProofOfLifeModal } from './command/ProofOfLifeModals';
import { fetchCommandCenterMetrics } from '../../../api/dashboardCommandCenterApi';
import type { DashboardCommandCenterResponse } from '../../../types/dashboardCommandCenter';
import { ActionInsightCard } from '../dashboard/components/ActionInsightCard';
import { FeeDefaultersDrawer } from '../dashboard/drawers/FeeDefaultersDrawer';
import { ClassPhotographyDrawer } from '../dashboard/drawers/ClassPhotographyDrawer';
import { StudentReviewDrawer } from '../dashboard/drawers/StudentReviewDrawer';
import { LowAttendanceDrawer } from '../dashboard/drawers/LowAttendanceDrawer';
import { VendorDuesDrawer } from '../dashboard/drawers/VendorDuesDrawer';
import { ReorderSignalsDrawer } from '../dashboard/drawers/ReorderSignalsDrawer';

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

// Backend API response shapes (kept local — only HomePanel maps these)
interface BackendAction {
  id: string;
  module: string;
  urgency: string;
  confidence: number;
  title: string;
  reason: string | null;
  impact: string | null;
  currentState: string | null;
  targetState: string | null;
  ctaLabel: string | null;
}

interface BackendBroadcast {
  id: string;
  module: string | null;
  title: string;
  message: string | null;
  audienceType: string;
  channels: string[];
  status: string;
  scheduledAt: string | null;
  sentAt: string | null;
  createdAt: string;
}

interface BackendFeedItem {
  id: string;
  module: string;
  title: string;
  createdAt: string;
}

interface DailyBriefData {
  summary: string;
  recommendedNextStep: string;
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

type FilterKey = 'all' | ActionModule;
const FILTER_KEYS: FilterKey[] = ['all', 'fees', 'students', 'supply', 'firefighting', 'attendance'];

// ─────────────────────────────────────────────────────────────────────────────
// Backend → frontend type mappers
// ─────────────────────────────────────────────────────────────────────────────

const VALID_MODULES = new Set<string>(['fees', 'students', 'supply', 'firefighting', 'attendance']);

function coerceModule(raw: string | null | undefined): ActionModule {
  const m = (raw ?? '').toLowerCase();
  return VALID_MODULES.has(m) ? (m as ActionModule) : 'fees';
}

function relativeTime(iso: string): string {
  const diffMin = Math.floor((Date.now() - new Date(iso).getTime()) / 60000);
  if (diffMin < 1) return 'now';
  if (diffMin < 60) return `${diffMin}m`;
  return `${Math.floor(diffMin / 60)}h`;
}

function mapBackendAction(a: BackendAction): CommandCentreCard {
  const state = a.currentState && a.targetState
    ? `${a.currentState} → ${a.targetState}`
    : (a.currentState ?? '');
  return {
    id: a.id,
    module: coerceModule(a.module),
    urgency: a.urgency.toLowerCase() as ActionUrgency,
    confidence: a.confidence,
    code: `CC-${a.id.slice(-6).toUpperCase()}`,
    title: a.title,
    why: a.reason ?? '',
    impact: a.impact ?? '',
    state,
    cta: a.ctaLabel ?? 'Review',
  };
}

function mapBackendBroadcast(b: BackendBroadcast): Broadcast {
  const statusMap: Record<string, BroadcastStatus> = {
    DRAFT: 'draft', SENT: 'sending', SCHEDULED: 'scheduled',
  };
  const status: BroadcastStatus = statusMap[b.status] ?? 'draft';
  const refStr = b.scheduledAt ?? b.sentAt ?? b.createdAt;
  const dt = new Date(refStr);
  const diffDays = Math.round((dt.getTime() - Date.now()) / 86400000);
  let when = '', whenShort = '';
  if (status === 'sending') {
    when = 'Sent'; whenShort = 'live';
  } else if (diffDays <= 0) {
    when = 'Today'; whenShort = 'today';
  } else if (diffDays === 1) {
    when = 'Tomorrow'; whenShort = 'tomorrow';
  } else {
    when = `In ${diffDays} days`; whenShort = `in ${diffDays}d`;
  }
  const audienceLabels: Record<string, string> = {
    ALL_PARENTS: 'All Parents', ALL_STAFF: 'All Staff', WHOLE_SCHOOL: 'Whole School',
    GRADE_PARENTS: 'Grade Parents', CLASS_PARENTS: 'Class Parents',
  };
  return {
    id: b.id,
    kind: 'notice',
    status,
    module: coerceModule(b.module),
    title: b.title,
    when,
    whenShort,
    audience: audienceLabels[b.audienceType] ?? b.audienceType,
    channels: b.channels as DeliveryChannel[],
    note: b.message ?? '',
  };
}

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
  workspace, criticalAction, onAcceptCritical,
}: {
  workspace: WorkspaceData;
  criticalAction: CommandCentreCard | null;
  onAcceptCritical: (a: CommandCentreCard) => void;
}) {
  const { can } = usePermissions();
  const h = new Date().getHours();
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
          {criticalAction && (
            <div className="ck-command-crit-badge" style={{ marginTop: 10 }}>
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

// §3b: Action Insights — real-time metrics from backend command-center endpoint
function ActionInsightsSection({
  metrics, setPanel, onOpenFeeDefaulters, onOpenClassPhotography, onOpenStudentReview, onOpenLowAttendance, onOpenVendorDues, onOpenReorderSignals,
}: {
  metrics: DashboardCommandCenterResponse | null;
  setPanel: (k: PanelKey) => void;
  onOpenFeeDefaulters: () => void;
  onOpenClassPhotography: () => void;
  onOpenStudentReview: () => void;
  onOpenLowAttendance: () => void;
  onOpenVendorDues: () => void;
  onOpenReorderSignals: () => void;
}) {
  if (!metrics) return null;

  const { fees, photography, lifecycle, attendance, vendorDues, reorderSignals } = metrics;
  const overdueRupees = Math.round(fees.totalOverdueAmountPaise / 100);
  const feeVariant = fees.defaulterCount > 0 ? 'danger' : 'ok';
  const attVariant = attendance.sectionsBelowThresholdCount > 0 ? 'warn' : 'ok';
  const photoCollectedRupees = Math.round(photography.collectedAmount / 100);
  const photoPendingRupees = Math.round(photography.pendingAmount / 100);

  return (
    <section>
      <div className="ck-command-section-head">
        <h2 className="ck-command-section-title">Action Insights</h2>
        <span className="ck-command-ai-badge">LIVE</span>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(240px, 1fr))', gap: 10 }}>
        <ActionInsightCard
          module="fees"
          title="Fee Defaulters"
          description="Students with outstanding fee balance in the active academic year."
          metrics={[
            { value: fees.defaulterCount, label: 'defaulters', variant: feeVariant },
            ...(overdueRupees > 0 ? [{ value: `₹${overdueRupees.toLocaleString('en-IN')}`, label: 'overdue', variant: 'danger' as const }] : []),
            ...(fees.oldestDueDays > 0 ? [{ value: `${fees.oldestDueDays}d`, label: 'oldest due', variant: 'warn' as const }] : []),
          ]}
          ctaLabel="View Defaulters"
          onCta={onOpenFeeDefaulters}
        />
        <ActionInsightCard
          module="attendance"
          title="Low Attendance Sections"
          description={`Sections below ${attendance.thresholdPercent}% attendance today.`}
          metrics={[
            { value: attendance.sectionsBelowThresholdCount, label: 'sections below threshold', variant: attVariant },
          ]}
          ctaLabel="View Sections"
          onCta={onOpenLowAttendance}
        />
        <ActionInsightCard
          module="photography"
          title="Class Photography"
          description="Student contribution status for the upcoming photography event."
          metrics={[
            ...(photoCollectedRupees > 0 ? [{ value: `₹${photoCollectedRupees.toLocaleString('en-IN')}`, label: 'collected', variant: 'ok' as const }] : []),
            ...(photoPendingRupees > 0 ? [{ value: `₹${photoPendingRupees.toLocaleString('en-IN')}`, label: 'pending', variant: 'warn' as const }] : []),
            ...(photography.eventId == null ? [{ value: '—', label: 'no active event' }] : []),
          ]}
          ctaLabel="View Payments"
          onCta={onOpenClassPhotography}
        />
        <ActionInsightCard
          module="students"
          title="Student Lifecycle"
          description="Students pending annual review or with extended absence."
          metrics={[
            { value: lifecycle.pendingReviewCount, label: 'pending review', variant: lifecycle.pendingReviewCount > 0 ? 'warn' : 'ok' },
            { value: lifecycle.longAbsenceCount, label: 'long absence', variant: lifecycle.longAbsenceCount > 0 ? 'warn' : 'ok' },
          ]}
          ctaLabel="Review Students"
          onCta={onOpenStudentReview}
        />
        <ActionInsightCard
          module="orders"
          title="Vendor Payment Dues"
          description="Approved orders and firefighting requests with outstanding vendor payment."
          metrics={[
            { value: (vendorDues?.catalogOrderCount ?? 0) + (vendorDues?.firefightingCount ?? 0), label: 'unpaid orders', variant: ((vendorDues?.catalogOrderCount ?? 0) + (vendorDues?.firefightingCount ?? 0)) > 0 ? 'warn' : 'ok' },
            ...(vendorDues?.totalDuesPaise > 0 ? [{ value: `₹${Math.round(vendorDues.totalDuesPaise / 100).toLocaleString('en-IN')}`, label: 'total due', variant: 'warn' as const }] : []),
          ]}
          ctaLabel="View Dues"
          onCta={onOpenVendorDues}
        />
        <ActionInsightCard
          module="orders"
          title="Inventory Reorder Signals"
          description="Supply categories predicted to need reordering based on historical order cadence."
          metrics={[
            { value: reorderSignals?.alertCount ?? 0, label: 'categories need attention', variant: (reorderSignals?.alertCount ?? 0) > 0 ? 'warn' : 'ok' },
          ]}
          ctaLabel="View Signals"
          onCta={onOpenReorderSignals}
        />
      </div>
    </section>
  );
}

// §4: Priority Queue — AI-ranked suggested next steps
function PriorityQueue({
  actions, onAccept, onDismiss, onPrimaryModal, onSecondary, setPanel,
}: {
  actions: CommandCentreCard[];
  onAccept: (a: CommandCentreCard) => void;
  onDismiss: (a: CommandCentreCard) => void;
  onPrimaryModal: (a: CommandCentreCard) => void;
  onSecondary: (a: CommandCentreCard) => void;
  setPanel: (k: PanelKey) => void;
}) {
  const { can } = usePermissions();
  const [filter, setFilter] = useState<FilterKey>('all');

  const canAct = (a: CommandCentreCard): boolean => {
    switch (a.module) {
      case 'firefighting': return can('firefighting:approve');
      case 'fees':         return can('fee:report');
      case 'supply':       return can('order:approve');
      case 'students':     return can('student:update');
      case 'attendance':   return true;
      default:             return false;
    }
  };

  const moduleFallback = (mod: string): string => {
    switch (mod) {
      case 'firefighting': return 'ff-approvals';
      case 'fees':         return 'fees';
      case 'supply':       return 'orders';
      case 'students':     return 'students';
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

        {shown.map((a, i) => {
          const primaryHandler = a.primaryPolCode
            ? () => onPrimaryModal(a)
            : () => { onAccept(a); setPanel(panelForCard(a, moduleFallback) as PanelKey); };

          const secondaryHandler = a.cta2
            ? () => onSecondary(a)
            : undefined;

          return (
            <ActionCard
              key={a.id}
              action={a}
              index={i}
              onPrimary={primaryHandler}
              onSecondary={secondaryHandler}
              onDismiss={() => onDismiss(a)}
            />
          );
        })}
      </div>
    </section>
  );
}

function ActionCard({
  action: a, index, onPrimary, onSecondary, onDismiss,
}: {
  action: CommandCentreCard;
  index: number;
  onPrimary: () => void;
  onSecondary?: () => void;
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
        <button className={`ck-command-btn-accept ${a.module}`} onClick={onPrimary}>
          {a.cta}
        </button>
        {a.cta2 && onSecondary && (
          <button className="ck-command-btn-secondary" onClick={onSecondary}>
            {a.cta2}
          </button>
        )}
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
function DailyBrief({ brief, actions }: { brief: DailyBriefData | null; actions: CommandCentreCard[] }) {
  if (brief) {
    return (
      <div className="ck-command-brief">
        <div className="ck-command-brief-label">Daily Brief · AI</div>
        <p className="ck-command-brief-text">{brief.summary}</p>
        {brief.recommendedNextStep && (
          <p className="ck-command-brief-text ts">{brief.recommendedNextStep}</p>
        )}
      </div>
    );
  }
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
        Clearing the top {Math.min(3, actions.length)} suggestion{actions.length !== 1 ? 's' : ''} protects operational continuity today.
      </p>
    </div>
  );
}

// Compose broadcast modal — wired to POST /notifications/broadcasts
function ComposeBroadcastModal({
  onClose,
  onCreated,
}: {
  onClose: () => void;
  onCreated: (b: Broadcast) => void;
}) {
  const [title, setTitle] = useState('');
  const [audience, setAudience] = useState('');
  const [channel, setChannel] = useState('');
  const [note, setNote] = useState('');
  const [saving, setSaving] = useState(false);

  async function handleSave() {
    if (!title.trim() || !audience || !channel) return;
    setSaving(true);
    try {
      const r = await api.post<BackendBroadcast>('/notifications/broadcasts', {
        title,
        message: note,
        audienceType: audience,
        channels: [channel],
        module: 'fees',
      });
      onCreated(mapBackendBroadcast(r.data));
      onClose();
    } catch {
      // stay open on error
    } finally {
      setSaving(false);
    }
  }

  return (
    <Modal
      title="Compose Broadcast"
      subtitle="Schedule or send a notice / event to parents and staff"
      onClose={onClose}
      footer={
        <>
          <button className="ck-btn ck-btn-ghost" onClick={onClose}>Cancel</button>
          <button className="ck-btn ck-btn-g" onClick={handleSave} disabled={saving}>
            {saving ? 'Saving…' : 'Save as draft'}
          </button>
        </>
      }
    >
      <div className="ck-form-grid" style={{ gap: 14 }}>
        <div className="ck-field">
          <label htmlFor="bc-title">Title</label>
          <input
            id="bc-title" type="text" placeholder="e.g. Parent–Teacher Meeting · Grade 6–8"
            value={title} onChange={e => setTitle(e.target.value)}
          />
        </div>
        <div className="ck-field">
          <label htmlFor="bc-audience">Audience</label>
          <select id="bc-audience" value={audience} onChange={e => setAudience(e.target.value)}>
            <option value="">Select audience…</option>
            <option value="ALL_PARENTS">All parents</option>
            <option value="ALL_STAFF">All staff</option>
            <option value="WHOLE_SCHOOL">Whole school</option>
            <option value="GRADE_PARENTS">Grade parents</option>
            <option value="CLASS_PARENTS">Class parents</option>
          </select>
        </div>
        <div className="ck-field">
          <label htmlFor="bc-channels">Delivery channel</label>
          <select id="bc-channels" value={channel} onChange={e => setChannel(e.target.value)}>
            <option value="">Select channel…</option>
            <option value="SMS">SMS</option>
            <option value="WhatsApp">WhatsApp</option>
            <option value="Email">Email</option>
            <option value="Push">Push notification</option>
          </select>
        </div>
        <div className="ck-field">
          <label htmlFor="bc-note">Message / note</label>
          <textarea
            id="bc-note" rows={4} placeholder="Write your announcement…"
            value={note} onChange={e => setNote(e.target.value)}
          />
        </div>
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

  // Action insights — structured metrics from /dashboard/command-center
  const [commandCenterMetrics, setCommandCenterMetrics] = useState<DashboardCommandCenterResponse | null>(null);
  useEffect(() => {
    let cancelled = false;
    fetchCommandCenterMetrics()
      .then(data => { if (!cancelled) setCommandCenterMetrics(data); })
      .catch(() => { /* non-critical: section hidden on error */ });
    return () => { cancelled = true; };
  }, []);

  // Fee Defaulters drawer
  const [showFeeDefaulters, setShowFeeDefaulters] = useState(false);

  // Class Photography drawer
  const [showClassPhotography, setShowClassPhotography] = useState(false);

  // Student Review drawer
  const [showStudentReview, setShowStudentReview] = useState(false);

  // Low Attendance drawer
  const [showLowAttendance, setShowLowAttendance] = useState(false);

  // Vendor Dues drawer
  const [showVendorDues, setShowVendorDues] = useState(false);

  // Reorder Signals drawer
  const [showReorderSignals, setShowReorderSignals] = useState(false);

  // Actions — from backend, fall back to derived workspace data then mocks
  const [actions, setActions] = useState<CommandCentreCard[]>([]);
  useEffect(() => {
    let cancelled = false;
    api.get<BackendAction[]>('/command-centre/actions')
      .then(r => { if (!cancelled) setActions(r.data.map(mapBackendAction)); })
      .catch(() => {
        if (!cancelled) {
          const derived = deriveCommandCentreCards(workspace);
          setActions(derived.length > 0 ? derived : (MOCK_SUGGESTIONS as CommandCentreCard[]));
        }
      });
    return () => { cancelled = true; };
  }, [workspace]);

  // Broadcasts — from backend, fall back to mocks
  const [broadcasts, setBroadcasts] = useState<Broadcast[]>([]);
  useEffect(() => {
    let cancelled = false;
    api.get<BackendBroadcast[]>('/notifications/broadcasts')
      .then(r => { if (!cancelled) setBroadcasts(r.data.map(mapBackendBroadcast)); })
      .catch(() => { if (!cancelled) setBroadcasts(MOCK_BROADCASTS); });
    return () => { cancelled = true; };
  }, []);

  // Daily brief — from backend
  const [brief, setBrief] = useState<DailyBriefData | null>(null);
  useEffect(() => {
    api.get<DailyBriefData>('/command-centre/brief')
      .then(r => setBrief(r.data))
      .catch(() => { /* use local fallback */ });
  }, []);

  // Feed — initial fetch from backend, then poll every 15s for new items
  const [feed, setFeed] = useState<FeedItem[]>(SEED_FEED);
  const [pollKey, setPollKey] = useState(0);
  const seenFeedIds = useRef<Set<string>>(new Set());

  useEffect(() => {
    let cancelled = false;
    api.get<BackendFeedItem[]>('/command-centre/feed?limit=20')
      .then(r => {
        if (cancelled) return;
        const mapped = r.data.map(item => ({
          _id: item.id,
          module: coerceModule(item.module),
          txt: item.title,
          t: relativeTime(item.createdAt),
        }));
        mapped.forEach(f => seenFeedIds.current.add(f._id));
        setFeed(mapped.map(({ _id: _, ...rest }) => rest));
      })
      .catch(() => { /* keep SEED_FEED */ });
    return () => { cancelled = true; };
  }, []);

  // Poll backend feed every 15s, prepend genuinely new items
  useEffect(() => {
    const iv = setInterval(() => {
      api.get<BackendFeedItem[]>('/command-centre/feed?limit=5')
        .then(r => {
          const newItems = r.data
            .filter(item => !seenFeedIds.current.has(item.id))
            .map(item => ({
              _id: item.id,
              module: coerceModule(item.module),
              txt: item.title,
              t: relativeTime(item.createdAt),
            }));
          newItems.forEach(f => seenFeedIds.current.add(f._id));
          if (newItems.length > 0) {
            setFeed(prev => [
              ...newItems.map(({ _id: _, ...rest }) => rest),
              ...prev.slice(0, 15),
            ]);
          }
        })
        .catch(() => { /* polling error ignored */ })
        .finally(() => setPollKey(k => k + 1));
    }, 15000);
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

  // Proof-of-life modal state
  const [polState, setPolState] = useState<{ card: CommandCentreCard; code: PolCode } | null>(null);

  // Action handlers — optimistic UI with backend persistence
  const handleAccept = useCallback((a: CommandCentreCard) => {
    setActions(prev => prev.filter(x => x.id !== a.id));
    setFeed(f => [{ module: a.module, txt: `Executed · ${a.cta} (${a.code})`, t: 'now' }, ...f.slice(0, 8)]);
    showToast({ ok: true, txt: `${a.cta} — dispatched (${a.code})` });
    api.post(`/command-centre/actions/${a.id}/accept`).catch(() => {
      setActions(prev => [a, ...prev]);
      showToast({ ok: false, txt: 'Failed to confirm — please retry' });
    });
  }, [showToast]);

  const handleDismiss = useCallback((a: CommandCentreCard) => {
    setActions(prev => prev.filter(x => x.id !== a.id));
    setFeed(f => [{ module: a.module, txt: `Dismissed · ${a.code}`, t: 'now' }, ...f.slice(0, 8)]);
    showToast({ ok: false, txt: 'Suggestion dismissed' });
    api.post(`/command-centre/actions/${a.id}/dismiss`, { reason: 'Dismissed by user' }).catch(() => {
      setActions(prev => [a, ...prev]);
    });
  }, [showToast]);

  const handlePrimaryModal = useCallback((a: CommandCentreCard) => {
    if (a.primaryPolCode) setPolState({ card: a, code: a.primaryPolCode });
  }, []);

  const handleSecondaryAction = useCallback((a: CommandCentreCard) => {
    if (a.cta2PanelKey) {
      setPanel(a.cta2PanelKey);
    } else if (a.cta2PolCode) {
      setPolState({ card: a, code: a.cta2PolCode });
    }
  }, [setPanel]);

  const handleSendBroadcast = useCallback((b: Broadcast) => {
    setBroadcasts(prev => prev.map(x => x.id === b.id
      ? { ...x, status: 'sending' as BroadcastStatus, whenShort: 'live', when: 'Sending now', note: 'Queued to gateway · delivering', progress: 5 }
      : x));
    setFeed(f => [{ module: b.module, txt: `Broadcast sent · ${b.title}`, t: 'now' }, ...f.slice(0, 8)]);
    showToast({ ok: true, txt: `Broadcast queued to ${b.channels.join(' · ')}` });
    api.post(`/notifications/broadcasts/${b.id}/send`).catch(() => { /* optimistic kept */ });
  }, [showToast]);

  const handleApproveBroadcast = useCallback((b: Broadcast) => {
    setBroadcasts(prev => prev.map(x => x.id === b.id
      ? { ...x, status: 'scheduled' as BroadcastStatus, whenShort: 'scheduled', note: 'Approved and scheduled.' }
      : x));
    showToast({ ok: true, txt: 'Broadcast approved and scheduled' });
  }, [showToast]);

  const handleBroadcastCreated = useCallback((b: Broadcast) => {
    setBroadcasts(prev => [b, ...prev]);
    showToast({ ok: true, txt: 'Broadcast saved as draft' });
  }, [showToast]);

  const criticalAction = actions.find(a => a.urgency === 'critical') ?? null;

  return (
    <>
      {/* §1 Greeting + §2 Critical alert strip */}
      <GreetingHeader
        workspace={workspace}
        criticalAction={criticalAction}
        onAcceptCritical={a => {
          handleAccept(a);
          setPanel(panelForCard(a, mod => {
            switch (mod) {
              case 'firefighting': return 'ff-approvals';
              case 'fees':         return 'fees';
              case 'supply':       return 'orders';
              case 'students':     return 'students';
              case 'attendance':   return 'attendance';
              default:             return 'home';
            }
          }) as PanelKey);
        }}
      />

      {/* §3 Pulse KPIs */}
      <PulseKpis workspace={workspace} setPanel={setPanel} />

      {/* §4 + §5 + §6 — main 2-column grid */}
      {/* §3b Action Insights */}
      <ActionInsightsSection
        metrics={commandCenterMetrics}
        setPanel={setPanel}
        onOpenFeeDefaulters={() => setShowFeeDefaulters(true)}
        onOpenClassPhotography={() => setShowClassPhotography(true)}
        onOpenStudentReview={() => setShowStudentReview(true)}
        onOpenLowAttendance={() => setShowLowAttendance(true)}
        onOpenVendorDues={() => setShowVendorDues(true)}
        onOpenReorderSignals={() => setShowReorderSignals(true)}
      />

      <div className="ck-command-grid">
        {/* LEFT: Priority Queue + Broadcast (full-width on narrow) */}
        <div className="ck-command-left">
          <PriorityQueue
            actions={actions}
            onAccept={handleAccept}
            onDismiss={handleDismiss}
            onPrimaryModal={handlePrimaryModal}
            onSecondary={handleSecondaryAction}
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
          <DailyBrief brief={brief} actions={actions} />
        </aside>
      </div>

      {/* Compose modal */}
      {showCompose && (
        <ComposeBroadcastModal
          onClose={() => setShowCompose(false)}
          onCreated={handleBroadcastCreated}
        />
      )}

      {/* Proof-of-life modals */}
      {polState && (
        <ProofOfLifeModal
          polCode={polState.code}
          card={polState.card}
          workspace={workspace}
          onClose={() => setPolState(null)}
          showToast={showToast}
        />
      )}

      {/* Toast notification */}
      {toast && <ToastBanner toast={toast} />}

      {/* Fee Defaulters drawer */}
      <FeeDefaultersDrawer
        open={showFeeDefaulters}
        onClose={() => setShowFeeDefaulters(false)}
        onMetricsRefresh={data => setCommandCenterMetrics(data)}
      />

      {/* Class Photography drawer */}
      <ClassPhotographyDrawer
        open={showClassPhotography}
        onClose={() => setShowClassPhotography(false)}
        onMetricsRefresh={data => setCommandCenterMetrics(data)}
      />

      {/* Student Lifecycle Review drawer */}
      <StudentReviewDrawer
        open={showStudentReview}
        onClose={() => setShowStudentReview(false)}
        onMetricsRefresh={() => {
          fetchCommandCenterMetrics()
            .then(data => setCommandCenterMetrics(data))
            .catch(() => { /* non-critical */ });
        }}
      />

      {/* Low Attendance drawer */}
      <LowAttendanceDrawer
        open={showLowAttendance}
        onClose={() => setShowLowAttendance(false)}
      />

      {/* Vendor Dues drawer */}
      <VendorDuesDrawer
        open={showVendorDues}
        onClose={() => setShowVendorDues(false)}
      />

      {/* Reorder Signals drawer */}
      <ReorderSignalsDrawer
        open={showReorderSignals}
        onClose={() => setShowReorderSignals(false)}
      />
    </>
  );
}

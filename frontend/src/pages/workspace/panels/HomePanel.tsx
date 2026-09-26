/**
 * HomePanel — Command Center
 *
 * Six sections (top → bottom):
 *   1. Greeting header with clock
 *   2. Critical alert strip (highest-urgency action)
 *   3. Pulse KPIs (4 Stat cards with sparklines)
 *   4. Priority Queue (source-backed suggested next steps)
 *   5. Broadcast Channel (events + outbound notices)
 *   6. Live Signal Feed + Daily Brief (polling via interval)
 *
 * Data contracts:
 *   - KPI cards: workspace.dashboard plus GET /dashboard/command-center
 *   - Actions: GET /reporting/command-center/actions; workspace-derived actions are labeled degraded
 *   - Broadcasts: GET /notifications/broadcasts
 *   - Feed/brief: GET /reporting/command-center/feed and GET /reporting/command-center/summary
 * Failures are surfaced in-panel so operators know when live dashboard data is degraded.
 */

import { useState, useEffect, useRef, useCallback } from 'react';
import { ArrowUpRight } from 'lucide-react';
import { usePermissions } from '../../../hooks/usePermissions';
import api from '../../../services/api';
import type { WorkspaceData, ActionModule, ActionUrgency } from '../../../types/workspace';
import type { PanelKey } from '../config';
import type { CommandCentreCard } from './command/commandCentreTypes';
import { deriveCommandCentreCards, panelForCard } from './command/commandCentreUtils';
import { BroadcastDrafts } from '../dashboard/components/BroadcastDrafts';
import { fetchCommandCenterMetrics } from '../../../api/dashboardCommandCenterApi';
import type { DashboardCommandCenterResponse } from '../../../types/dashboardCommandCenter';
import { formatSchoolCurrency } from '../../../utils/schoolLocalization';
import { ActionInsightCard } from '../dashboard/components/ActionInsightCard';
import { FeeDefaultersDrawer } from '../dashboard/drawers/FeeDefaultersDrawer';
import { ClassPhotographyDrawer } from '../dashboard/drawers/ClassPhotographyDrawer';
import { StudentReviewDrawer } from '../dashboard/drawers/StudentReviewDrawer';
import { LowAttendanceDrawer } from '../dashboard/drawers/LowAttendanceDrawer';
import { VendorDuesDrawer } from '../dashboard/drawers/VendorDuesDrawer';
import { ReorderSignalsDrawer } from '../dashboard/drawers/ReorderSignalsDrawer';
import {
  canAccessDashboardModule,
  dashboardFilterLabel,
  dashboardModuleLabel,
  filterKeysForDashboardAccess,
  matchesDashboardFilter,
  type DashboardModuleAccess,
} from './dashboardAccess';

// ─────────────────────────────────────────────────────────────────────────────
// Types
// ─────────────────────────────────────────────────────────────────────────────

interface Props {
  workspace: WorkspaceData;
  setPanel: (key: PanelKey) => void;
  moduleAccess: DashboardModuleAccess;
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
  sourceType?: string | null;
  sourceId?: string | null;
  createdAt?: string | null;
  title: string;
  reason: string | null;
  impact: string | null;
  currentState: string | null;
  targetState: string | null;
  ctaLabel: string | null;
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
  fees:         dashboardModuleLabel('fees'),
  students:     dashboardModuleLabel('students'),
  supply:       dashboardModuleLabel('supply'),
  firefighting: dashboardModuleLabel('firefighting'),
  attendance:   dashboardModuleLabel('attendance'),
};

type FilterKey = ReturnType<typeof filterKeysForDashboardAccess>[number];

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
    ? `Current: ${a.currentState} · Suggested: ${a.targetState}`
    : (a.currentState ?? '');
  return {
    id: a.id,
    module: coerceModule(a.module),
    urgency: a.urgency.toLowerCase() as ActionUrgency,
    sourceKind: 'server',
    sourceLabel: a.sourceType ? a.sourceType.replace(/_/g, ' ').toLowerCase() : 'Command center record',
    sourceReference: a.sourceId ?? undefined,
    recordedAt: a.createdAt ?? undefined,
    loadedAt: new Date().toISOString(),
    code: `CC-${a.id.slice(-6).toUpperCase()}`,
    title: a.title,
    why: a.reason ?? '',
    impact: a.impact ?? '',
    state,
    cta: a.ctaLabel ?? 'Review',
  };
}

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
  status: string;
  tone: 'ok' | 'warn' | 'neutral';
  module: ActionModule;
  panelKey: PanelKey;
}

function buildKpis(d: WorkspaceData['dashboard'], moduleAccess: DashboardModuleAccess, school: WorkspaceData['school']): KpiDef[] {
  const attendanceState = d.attendanceState
    ?? (d.attendanceSubmittedSections ? 'SUBMITTED' : 'NOT_STARTED');
  const submittedSections = d.attendanceSubmittedSections ?? 0;
  const feesConfigured = d.feesConfigured ?? Number(d.feeTargetLakh) > 0;
  const kpis: KpiDef[] = [
    {
      id: 'students', label: 'Total Students',
      value: String(d.students), unit: '', sub: `${d.sections} active ${d.sections === 1 ? 'section' : 'sections'}`,
      status: 'Enrolled', tone: 'neutral', module: 'students',
      panelKey: 'students',
    },
    {
      id: 'attendance', label: 'Attendance Today',
      value: attendanceState === 'NOT_STARTED' ? 'Pending' : `${d.attendancePercent}`,
      unit: attendanceState === 'NOT_STARTED' ? '' : '%',
      sub: attendanceState === 'NOT_STARTED'
        ? 'Daily register not submitted'
        : `${d.attendancePresent} present · ${submittedSections}/${d.sections} sections`,
      status: attendanceState === 'SUBMITTED' ? 'Submitted' : attendanceState === 'PARTIAL' ? 'In progress' : 'Not started',
      tone: attendanceState === 'SUBMITTED' ? 'ok' : 'warn',
      module: 'attendance',
      panelKey: 'attendance',
    },
    {
      id: 'fees', label: 'Fees Collected',
      value: feesConfigured ? formatSchoolCurrency(Number(d.feeCollectedLakh) * 100_000, school) : 'Not set',
      unit: '',
      sub: feesConfigured ? `of ${formatSchoolCurrency(Number(d.feeTargetLakh) * 100_000, school)} this academic year` : 'No active fee target',
      status: d.feeOverdueCount > 0 ? `${d.feeOverdueCount} overdue` : feesConfigured ? 'On track' : 'Setup needed',
      tone: d.feeOverdueCount > 0 || !feesConfigured ? 'warn' : 'ok',
      module: 'fees',
      panelKey: 'fees',
    },
    {
      id: 'firefighting', label: 'Urgent Procurement',
      value: String(d.firefightingActive), unit: '',
      sub: `${d.pendingApprovals} need approval`,
      status: d.pendingApprovals > 0 ? 'Needs review' : 'Clear',
      tone: d.pendingApprovals > 0 ? 'warn' : 'ok',
      module: 'firefighting',
      panelKey: 'ff-dashboard',
    },
  ];
  return kpis.filter((k) => canAccessDashboardModule(k.module, moduleAccess));
}

// ─────────────────────────────────────────────────────────────────────────────
// Sub-components
// ─────────────────────────────────────────────────────────────────────────────

// §1 + §2: Greeting + Critical alert — combined header block
function GreetingHeader({
  workspace, criticalAction, moduleAccess, onAcceptCritical,
}: {
  workspace: WorkspaceData;
  criticalAction: CommandCentreCard | null;
  moduleAccess: DashboardModuleAccess;
  onAcceptCritical: (a: CommandCentreCard) => void;
}) {
  const { can } = usePermissions();
  const h = Number(new Intl.DateTimeFormat('en', {
    hour: '2-digit', hourCycle: 'h23', timeZone: workspace.school.timeZone || 'Asia/Kolkata',
  }).formatToParts(new Date()).find((part) => part.type === 'hour')?.value || new Date().getHours());
  const d = workspace.dashboard;
  const summaryParts: string[] = [];
  if (moduleAccess.erp) {
    summaryParts.push(`${d.students} students enrolled`);
    if (d.attendanceState === 'NOT_STARTED' || !d.attendanceSubmittedSections) {
      summaryParts.push('attendance is awaiting submission');
    } else {
      summaryParts.push(`${d.attendancePercent}% attendance today`);
    }
    if (d.feesConfigured ?? Number(d.feeTargetLakh) > 0) {
      summaryParts.push(`${d.feeOverdueCount} fee accounts overdue`);
    } else {
      summaryParts.push('fee target is not configured');
    }
  }
  if (moduleAccess.supplyOs) {
    summaryParts.push(`${d.pendingApprovals} urgent procurement approvals pending`);
  }
  const summary = summaryParts.length > 0
    ? `${summaryParts.join('; ')}.`
    : 'Dashboard access is limited for this school.';

  return (
    <>
      <header className="ck-command-header">
        <div>
          <div className="ck-command-brand">
            <span className="ck-command-brand-name">{greeting(h)}</span>
            <span className="ck-command-live-badge">
              {workspace.school.meta}
            </span>
          </div>
          <h1 className="ck-command-title">School <em>dashboard</em></h1>
          <p className="ck-command-subtitle">
            {summary}
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
              Review {MODULE_LABEL[criticalAction.module].toLowerCase()}
            </button>
          </div>
        </div>
      )}
    </>
  );
}

// §3: Pulse KPIs with sparklines
function PulseKpis({
  workspace, setPanel, moduleAccess,
}: {
  workspace: WorkspaceData;
  setPanel: (k: PanelKey) => void;
  moduleAccess: DashboardModuleAccess;
}) {
  const kpis = buildKpis(workspace.dashboard, moduleAccess, workspace.school);
  if (kpis.length === 0) return null;

  return (
    <div className="ck-command-kpis">
      {kpis.map((k, i) => {
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
              <span className={`ck-command-kpi-status ${k.tone}`}>{k.status}</span>
            </div>
            <div className="ck-command-kpi-value-row">
              <span className="ck-command-kpi-value">{k.value}</span>
              {k.unit && <span className="ck-command-kpi-unit">{k.unit}</span>}
            </div>
            <div className="ck-command-kpi-bottom">
              <span className="ck-command-kpi-sub">{k.sub}</span>
              <ArrowUpRight className="ck-command-kpi-link" size={16} aria-hidden="true" />
            </div>
          </button>
        );
      })}
    </div>
  );
}

// §3b: Action Insights — real-time metrics from backend command-center endpoint
function ActionInsightsSection({
  metrics, school, moduleAccess, onOpenFeeDefaulters, onOpenClassPhotography, onOpenStudentReview, onOpenLowAttendance, onOpenVendorDues, onOpenReorderSignals,
}: {
  metrics: DashboardCommandCenterResponse | null;
  school: WorkspaceData['school'];
  moduleAccess: DashboardModuleAccess;
  onOpenFeeDefaulters: () => void;
  onOpenClassPhotography: () => void;
  onOpenStudentReview: () => void;
  onOpenLowAttendance: () => void;
  onOpenVendorDues: () => void;
  onOpenReorderSignals: () => void;
}) {
  if (!metrics || (!moduleAccess.erp && !moduleAccess.supplyOs)) return null;

  const { fees, photography, lifecycle, attendance, vendorDues, reorderSignals } = metrics;
  // The guard above proves metrics exists, not that its sections do. Every field
  // below is read unconditionally, so one missing section would throw here and
  // the ErrorBoundary would replace the entire workspace rather than this card.
  if (!fees || !photography || !lifecycle || !attendance || !vendorDues || !reorderSignals) return null;
  const overdueRupees = fees.totalOverdueAmountPaise / 100;
  const feeVariant = fees.defaulterCount > 0 ? 'danger' : 'ok';
  const attVariant = attendance.sectionsBelowThresholdCount > 0 ? 'warn' : 'ok';
  const photoCollectedRupees = photography.collectedAmount / 100;
  const photoPendingRupees = photography.pendingAmount / 100;

  return (
    <section>
      <div className="ck-command-section-head">
        <h2 className="ck-command-section-title">Action Insights</h2>
        <span className="ck-command-ai-badge">CURRENT</span>
      </div>
      <div className="ck-command-insights-grid">
        {moduleAccess.erp && (
          <>
        <ActionInsightCard
          module="fees"
          title="Fee Defaulters"
          description="Students with outstanding fee balance in the active academic year."
          metrics={[
            { value: fees.defaulterCount, label: 'defaulters', variant: feeVariant },
            ...(overdueRupees > 0 ? [{ value: formatSchoolCurrency(overdueRupees, school), label: 'overdue', variant: 'danger' as const }] : []),
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
          </>
        )}
        {moduleAccess.supplyOs && (
          <>
        <ActionInsightCard
          module="photography"
          title="Class Photography"
          description="Student contribution status for the upcoming photography event."
          metrics={[
            ...(photoCollectedRupees > 0 ? [{ value: formatSchoolCurrency(photoCollectedRupees, school), label: 'collected', variant: 'ok' as const }] : []),
            ...(photoPendingRupees > 0 ? [{ value: formatSchoolCurrency(photoPendingRupees, school), label: 'pending', variant: 'warn' as const }] : []),
            ...(photography.eventId == null ? [{ value: '—', label: 'no active event' }] : []),
          ]}
          ctaLabel="View Payments"
          onCta={onOpenClassPhotography}
        />
          </>
        )}
        {moduleAccess.erp && (
          <>
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
          </>
        )}
        {moduleAccess.supplyOs && (
          <>
        <ActionInsightCard
          module="orders"
          title="Vendor Payment Dues"
          description="Approved orders and urgent procurement requests with outstanding vendor payment."
          metrics={[
            { value: (vendorDues?.catalogOrderCount ?? 0) + (vendorDues?.firefightingCount ?? 0), label: 'unpaid orders', variant: ((vendorDues?.catalogOrderCount ?? 0) + (vendorDues?.firefightingCount ?? 0)) > 0 ? 'warn' : 'ok' },
            ...(vendorDues?.totalDuesPaise > 0 ? [{ value: formatSchoolCurrency(vendorDues.totalDuesPaise / 100, school), label: 'total due', variant: 'warn' as const }] : []),
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
          </>
        )}
      </div>
    </section>
  );
}

// §4: Priority Queue — source-backed suggested next steps
function PriorityQueue({
  actions, moduleAccess, onAccept, onDismiss, pendingActionId, setPanel,
}: {
  actions: CommandCentreCard[];
  moduleAccess: DashboardModuleAccess;
  onAccept: (a: CommandCentreCard) => void;
  onDismiss: (a: CommandCentreCard) => void;
  pendingActionId: string | null;
  setPanel: (k: PanelKey) => void;
}) {
  const { can } = usePermissions();
  const [filter, setFilter] = useState<FilterKey>('all');
  const filterKeys = filterKeysForDashboardAccess(moduleAccess);
  const activeFilter = filterKeys.includes(filter) ? filter : 'all';

  useEffect(() => {
    if (!filterKeys.includes(filter)) setFilter('all');
  }, [filter, moduleAccess.erp, moduleAccess.supplyOs]);

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
  const visible = actions.filter(a => canAccessDashboardModule(a.module, moduleAccess) && canAct(a));
  const shown = visible.filter(a => matchesDashboardFilter(a.module, activeFilter));

  return (
    <section>
      <div className="ck-command-section-head">
        <h2 className="ck-command-section-title">Suggested Next Steps</h2>
        <span className="ck-command-section-count">{shown.length} open</span>
      </div>

      <div className="ck-command-chips">
        {filterKeys.map(f => {
          const label = dashboardFilterLabel(f);
          const isOn = activeFilter === f;
          const onClass = f === 'urgentProcurement' ? 'on-firefighting' : `on-${f}`;
          return (
            <button
              key={f}
              className={`ck-command-chip ${isOn ? onClass : ''}`}
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
            No open suggestions in this view. Use the module navigation to review other work.
          </div>
        )}

        {shown.map((a, i) => {
          const primaryHandler = () => setPanel(panelForCard(a, moduleFallback) as PanelKey);
          const secondaryHandler = a.cta2PanelKey ? () => setPanel(a.cta2PanelKey!) : undefined;

          return (
            <ActionCard
              key={a.id}
              action={a}
              index={i}
              onPrimary={primaryHandler}
              onSecondary={secondaryHandler}
              onDismiss={() => onDismiss(a)}
              onAcknowledge={a.sourceKind === 'server' ? () => onAccept(a) : undefined}
              pending={pendingActionId !== null}
            />
          );
        })}
      </div>
    </section>
  );
}

function ActionCard({
  action: a, index, onPrimary, onSecondary, onDismiss, onAcknowledge, pending,
}: {
  action: CommandCentreCard;
  index: number;
  onPrimary: () => void;
  onSecondary?: () => void;
  onDismiss: () => void;
  onAcknowledge?: () => void;
  pending: boolean;
}) {
  const [showReason, setShowReason] = useState(false);
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


      </div>

      <div className="ck-command-acard-actions">
        <button className={`ck-command-btn-accept ${a.module}`} onClick={onPrimary}>
          Review {MODULE_LABEL[a.module].toLowerCase()}
        </button>
        {a.cta2 && onSecondary && (
          <button className="ck-command-btn-secondary" onClick={onSecondary}>
            {a.cta2}
          </button>
        )}
        {onAcknowledge && <button className="ck-command-btn-secondary" disabled={pending} onClick={onAcknowledge}>Acknowledge suggestion</button>}
        <button className="ck-command-btn-dismiss" disabled={pending} onClick={onDismiss}>
          {a.sourceKind === 'server' ? 'Dismiss suggestion' : 'Hide for this view'}
        </button>
        <button className="ck-command-acard-why-btn" type="button" aria-expanded={showReason} aria-controls={`reason-${a.id}`} onClick={() => setShowReason(value => !value)}>
          Why this?
        </button>
      </div>
      {showReason && <div id={`reason-${a.id}`} style={{ padding: '0 18px 18px', fontSize: 14, overflowWrap: 'anywhere' }}>
        <p><strong>Reason:</strong> {a.why || 'No reason was supplied by the source.'}</p>
        <p><strong>Source:</strong> {a.sourceLabel || 'Workspace summary'}{a.sourceReference ? ` · ${a.sourceReference}` : ''}</p>
        <p><strong>Recorded:</strong> {a.recordedAt && Number.isFinite(Date.parse(a.recordedAt)) ? new Date(a.recordedAt).toLocaleString() : 'Source update time not supplied.'}</p>
        {a.loadedAt && <p><strong>Loaded:</strong> {new Date(a.loadedAt).toLocaleString()}</p>}
        <p>Review opens the relevant module. Acknowledging or dismissing a suggestion does not complete its task.</p>
      </div>}
    </article>
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
        <div className="ck-command-brief-label">Daily brief</div>
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
      <div className="ck-command-brief-label">Daily brief</div>
      <p className="ck-command-brief-text">
        {critCount > 0 && (
          <><b className="re">{critCount} critical action{critCount > 1 ? 's' : ''}</b> need{critCount === 1 ? 's' : ''} review. </>
        )}
        {highCount > 0 && (
          <><b className="g">{highCount} high-priority</b> items are queued. </>
        )}
        {actions.length === 0 ? 'No open suggestions are available in this view.' : 'Review each source record before taking action.'}
      </p>
    </div>
  );
}

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

function DashboardLoadingState() {
  return (
    <div className="ck-dashboard-loading" role="status" aria-live="polite">
      <span className="ck-dashboard-loading-label">Loading current school data...</span>
      <div className="ck-dashboard-loading-kpis" aria-hidden="true">
        {[0, 1, 2, 3].map(index => (
          <span key={index} className="ck-dashboard-loading-kpi" />
        ))}
      </div>
      <div className="ck-dashboard-loading-body" aria-hidden="true" />
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// Main component
// ─────────────────────────────────────────────────────────────────────────────

export function HomePanel({ workspace, setPanel, moduleAccess }: Props) {

  // Action insights — structured metrics from /dashboard/command-center
  const [commandCenterMetrics, setCommandCenterMetrics] = useState<DashboardCommandCenterResponse | null>(null);
  const [dataIssues, setDataIssues] = useState<Record<string, string>>({});
  const [initialLoadsCompleted, setInitialLoadsCompleted] = useState<Set<string>>(() => new Set());
  const setDataIssue = useCallback((key: string, message: string | null) => {
    setDataIssues(current => {
      if (message && current[key] === message) return current;
      if (!message && current[key] == null) return current;
      const next = { ...current };
      if (message) {
        next[key] = message;
      } else {
        delete next[key];
      }
      return next;
    });
  }, []);
  const markInitialLoadComplete = useCallback((key: string) => {
    setInitialLoadsCompleted(current => {
      if (current.has(key)) return current;
      const next = new Set(current);
      next.add(key);
      return next;
    });
  }, []);

  useEffect(() => {
    let cancelled = false;
    const loadMetrics = () => {
      if (document.visibilityState === 'hidden') return;
      fetchCommandCenterMetrics()
        .then(data => {
          if (!cancelled) {
            setCommandCenterMetrics(data);
            setDataIssue('metrics', null);
          }
        })
        .catch(() => {
          if (!cancelled) setDataIssue('metrics', 'Command-center metrics could not be loaded.');
        })
        .finally(() => {
          if (!cancelled) markInitialLoadComplete('metrics');
        });
    };
    const refreshWhenVisible = () => {
      if (document.visibilityState === 'visible') loadMetrics();
    };

    loadMetrics();
    const intervalId = window.setInterval(loadMetrics, 60_000);
    document.addEventListener('visibilitychange', refreshWhenVisible);
    return () => {
      cancelled = true;
      window.clearInterval(intervalId);
      document.removeEventListener('visibilitychange', refreshWhenVisible);
    };
  }, [markInitialLoadComplete, setDataIssue]);

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

  // Actions — from backend, with explicit degraded state if local workspace derivation is used
  const [actions, setActions] = useState<CommandCentreCard[]>([]);
  useEffect(() => {
    let cancelled = false;
    api.get<BackendAction[]>('/reporting/command-center/actions')
      .then(r => {
        if (!cancelled) {
          setActions(r.data.map(mapBackendAction));
          setDataIssue('actions', null);
        }
      })
      .catch(() => {
        if (!cancelled) {
          const derived = deriveCommandCentreCards(workspace);
          setActions(derived);
          setDataIssue('actions', 'Live command-center actions could not be loaded; showing workspace-derived actions.');
        }
      })
      .finally(() => {
        if (!cancelled) markInitialLoadComplete('actions');
      });
    return () => { cancelled = true; };
  }, [workspace, markInitialLoadComplete, setDataIssue]);

  // Daily brief — from backend
  const [brief, setBrief] = useState<DailyBriefData | null>(null);
  useEffect(() => {
    let cancelled = false;
    api.get<DailyBriefData>('/reporting/command-center/summary')
      .then(r => {
        if (!cancelled) {
          setBrief(r.data);
          setDataIssue('brief', null);
        }
      })
      .catch(() => {
        if (!cancelled) setDataIssue('brief', 'Daily brief could not be loaded.');
      })
      .finally(() => {
        if (!cancelled) markInitialLoadComplete('brief');
      });
    return () => { cancelled = true; };
  }, [markInitialLoadComplete, setDataIssue]);

  // Feed — initial fetch from backend, then poll every 15s for new items
  const [feed, setFeed] = useState<FeedItem[]>([]);
  const [pollKey, setPollKey] = useState(0);
  const seenFeedIds = useRef<Set<string>>(new Set());

  useEffect(() => {
    let cancelled = false;
    api.get<BackendFeedItem[]>('/reporting/command-center/feed?limit=20')
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
        setDataIssue('feed', null);
      })
      .catch(() => {
        if (!cancelled) setDataIssue('feed', 'Live signal feed could not be loaded.');
      })
      .finally(() => {
        if (!cancelled) markInitialLoadComplete('feed');
      });
    return () => { cancelled = true; };
  }, [markInitialLoadComplete, setDataIssue]);

  // Poll backend feed every 15s, prepend genuinely new items
  useEffect(() => {
    const iv = setInterval(() => {
      api.get<BackendFeedItem[]>('/reporting/command-center/feed?limit=5')
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
          setDataIssue('feed', null);
        })
        .catch(() => setDataIssue('feed', 'Live signal feed polling is failing.'))
        .finally(() => setPollKey(k => k + 1));
    }, 15000);
    return () => clearInterval(iv);
  }, [setDataIssue]);

  // Toast
  const [toast, setToast] = useState<Toast | null>(null);
  const showToast = useCallback((t: Toast) => {
    setToast(t);
    setTimeout(() => setToast(null), 2600);
  }, []);

  // Acknowledgement records a decision about a suggestion; work happens in its module.
  const [pendingActionId, setPendingActionId] = useState<string | null>(null);
  const actionInFlight = useRef(false);
  const [actionError, setActionError] = useState('');
  const recordAction = useCallback(async (a: CommandCentreCard, decision: 'accept' | 'dismiss') => {
    if (actionInFlight.current) return;
    if (a.sourceKind !== 'server') {
      if (decision === 'dismiss') {
        setActions(current => current.filter(item => item.id !== a.id));
        showToast({ ok: true, txt: 'Suggestion hidden for this view. The source record is unchanged.' });
      }
      return;
    }
    actionInFlight.current = true;
    setPendingActionId(a.id);
    setActionError('');
    try {
      await api.post(`/reporting/command-center/actions/${a.id}/${decision}`, decision === 'dismiss' ? { reason: 'Dismissed by user' } : undefined);
      setActions(current => current.filter(item => item.id !== a.id));
      showToast({ ok: true, txt: decision === 'accept'
        ? 'Suggestion acknowledged. Complete the work in its module.'
        : 'Suggestion dismissed. The source record is unchanged.' });
    } catch {
      setActionError(`${decision === 'accept' ? 'Acknowledgement' : 'Dismissal'} was not saved. The suggestion is still open; try again.`);
    } finally {
      actionInFlight.current = false;
      setPendingActionId(null);
    }
  }, [showToast]);

  const dashboardActions = actions.filter((a) => canAccessDashboardModule(a.module, moduleAccess));
  const visibleFeed = feed.filter((f) => canAccessDashboardModule(f.module, moduleAccess));
  const criticalAction = dashboardActions.find(a => a.urgency === 'critical') ?? null;
  const dataIssueMessages = Object.values(dataIssues);
  const dashboardReady = initialLoadsCompleted.size === 4;

  if (!dashboardReady) {
    return (
      <>
        <GreetingHeader
          workspace={workspace}
          criticalAction={null}
          moduleAccess={moduleAccess}
          onAcceptCritical={() => undefined}
        />
        <DashboardLoadingState />
      </>
    );
  }

  return (
    <>
      {/* §1 Greeting + §2 Critical alert strip */}
      <GreetingHeader
        workspace={workspace}
        criticalAction={criticalAction}
        moduleAccess={moduleAccess}
        onAcceptCritical={a => {
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

      {dataIssueMessages.length > 0 ? (
        <div className="ck-alert ck-alert-am" style={{ margin: '0 0 16px' }}>
          <span>!</span>
          <div>
            <strong>Some live dashboard data did not load.</strong>
            <div>{dataIssueMessages.join(' ')}</div>
          </div>
        </div>
      ) : null}

      {/* §3 Pulse KPIs */}
      <PulseKpis workspace={workspace} setPanel={setPanel} moduleAccess={moduleAccess} />

      {/* §4 + §5 + §6 — main 2-column grid */}
      {/* §3b Action Insights */}
      <details style={{ marginBottom: 20 }}>
        <summary style={{ cursor: 'pointer', padding: '12px 0', fontWeight: 600 }}>Operational details by module</summary>
      <ActionInsightsSection
        metrics={commandCenterMetrics}
        school={workspace.school}
        moduleAccess={moduleAccess}
        onOpenFeeDefaulters={() => setShowFeeDefaulters(true)}
        onOpenClassPhotography={() => setShowClassPhotography(true)}
        onOpenStudentReview={() => setShowStudentReview(true)}
        onOpenLowAttendance={() => setShowLowAttendance(true)}
        onOpenVendorDues={() => setShowVendorDues(true)}
        onOpenReorderSignals={() => setShowReorderSignals(true)}
      />
      </details>

      <div className="ck-command-grid">
        {/* LEFT: Priority Queue + Broadcast (full-width on narrow) */}
        <div className="ck-command-left">
          {actionError && <p role="alert">{actionError}</p>}
          <PriorityQueue
            actions={dashboardActions}
            moduleAccess={moduleAccess}
            onAccept={a => void recordAction(a, 'accept')}
            onDismiss={a => void recordAction(a, 'dismiss')}
            pendingActionId={pendingActionId}
            setPanel={setPanel}
          />

          {/* §5 Broadcast Channel (moves into left col on narrow screens) */}
          <div className="ck-command-broadcast-left-slot">
            <details>
              <summary style={{ cursor: 'pointer', padding: '12px 0', fontWeight: 600 }}>Broadcast drafts and approvals</summary>
              <BroadcastDrafts module={moduleAccess.erp ? 'fees' : 'supply'} />
            </details>
          </div>
        </div>

        {/* RIGHT: Signal Feed + Daily Brief */}
        <aside className="ck-command-right">
          {/* §6 Live Signal Feed */}
          <details>
            <summary style={{ cursor: 'pointer', padding: '12px 0', fontWeight: 600 }}>Recent activity and daily brief</summary>
          <SignalFeed feed={visibleFeed} pollKey={pollKey} />

          {/* Daily Brief */}
          <DailyBrief brief={brief} actions={dashboardActions} />
          </details>
        </aside>
      </div>

      {/* Toast notification */}
      {toast && <ToastBanner toast={toast} />}

      {/* Fee Defaulters drawer */}
      <FeeDefaultersDrawer
        open={showFeeDefaulters}
        onClose={() => setShowFeeDefaulters(false)}
        onMetricsRefresh={data => {
          setCommandCenterMetrics(data);
          setDataIssue('metrics', null);
        }}
      />

      {/* Class Photography drawer */}
      <ClassPhotographyDrawer
        open={showClassPhotography}
        onClose={() => setShowClassPhotography(false)}
        onMetricsRefresh={data => {
          setCommandCenterMetrics(data);
          setDataIssue('metrics', null);
        }}
      />

      {/* Student Lifecycle Review drawer */}
      <StudentReviewDrawer
        open={showStudentReview}
        onClose={() => setShowStudentReview(false)}
        onMetricsRefresh={() => {
          fetchCommandCenterMetrics()
            .then(data => {
              setCommandCenterMetrics(data);
              setDataIssue('metrics', null);
            })
            .catch(() => setDataIssue('metrics', 'Command-center metrics could not be refreshed.'));
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

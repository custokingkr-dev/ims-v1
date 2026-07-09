import type { WorkspaceData } from '../../../../types/workspace';
import type { CommandCentreCard } from './commandCentreTypes';

/**
 * Derives Command Centre cards from live workspace data.
 *
 * Data precedence per card:
 *   1. Specific nested workspace field (fees.summary, firefighting.requests, orders)
 *   2. workspace.dashboard aggregate counters
 *   Cards are omitted when the workspace payload does not carry the required count
 *   (profile completeness, promotion readiness, per-section attendance gaps).
 *
 * Cards are sorted: critical → high → medium → low.
 * Cards whose count/condition evaluates to zero are omitted rather than shown empty.
 */
export function deriveCommandCentreCards(ws: WorkspaceData): CommandCentreCard[] {
  const cards: CommandCentreCard[] = [];

  // ── 1. Overdue Fees ───────────────────────────────────────────────────────
  const overdueCount =
    ws.fees?.summary?.overdueCount ?? ws.dashboard.feeOverdueCount ?? 0;
  const outstanding = ws.fees?.summary?.outstanding ?? 0;

  if (overdueCount > 0) {
    const outstandingLakh = outstanding > 0
      ? `₹${(outstanding / 10_000_000).toFixed(1)}L outstanding`
      : `${overdueCount} students unpaid`;
    cards.push({
      id: 'cc-fee-overdue',
      module: 'fees',
      urgency: overdueCount > 50 ? 'high' : 'medium',
      confidence: 95,
      code: `FEES-OVERDUE-${overdueCount}`,
      title: `${overdueCount} student${overdueCount !== 1 ? 's' : ''} have overdue fees`,
      why: `${overdueCount} families have missed the payment deadline. A reminder before 6 PM recovers dues 3× faster.`,
      impact: outstandingLakh,
      state: 'Overdue → Reminded',
      cta: 'Send reminders',
      count: overdueCount,
      amount: outstanding,
      primaryPolCode: 'FEE_REMINDER',
      cta2: 'View students',
      cta2PanelKey: 'fees',
    });
  }

  // ── 2. Today's Collection ─────────────────────────────────────────────────
  const collected = ws.fees?.summary?.collected ?? 0;
  const target = ws.fees?.summary?.target ?? 0;
  const progressPct = target > 0 ? Math.round((collected / target) * 100) : null;
  const collectedLakh = collected > 0
    ? `₹${(collected / 10_000_000).toFixed(1)}L`
    : ws.dashboard.feeCollectedLakh
      ? `₹${ws.dashboard.feeCollectedLakh}L`
      : null;

  cards.push({
    id: 'cc-fee-collection',
    module: 'fees',
    urgency: 'medium',
    confidence: 88,
    code: 'FEES-COLLECTION',
    title: collectedLakh
      ? `${collectedLakh} collected this term`
      : 'Review today\'s fee collections',
    why: progressPct != null
      ? `${progressPct}% of term target collected. Review daily breakdown and download the summary.`
      : 'Track fee collection progress and download the summary report for review.',
    impact: target > 0
      ? `Target: ₹${(target / 10_000_000).toFixed(1)}L · ${progressPct}% done`
      : 'Collection summary available',
    state: 'Active collection period',
    cta: 'Review collections',
    amount: collected,
    cta2: 'Download summary',
    cta2PolCode: 'FEE_DOWNLOAD',
  });

  // ── 3. Incomplete Student Profiles ────────────────────────────────────────
  // Workspace does not carry profile-completeness counts — card omitted (no real source).

  // ── 4. Year-end Promotion Review ──────────────────────────────────────────
  // Workspace does not carry promotion-readiness data — card omitted (no real source).

  // ── 5. Urgent Procurement Orders Awaiting Approval ────────────────────────
  const submittedOrders = ws.orders?.filter(o => o.status === 'SUBMITTED') ?? [];
  if (submittedOrders.length > 0) {
    const totalValue = submittedOrders.reduce(
      (sum, o) => sum + (o.totalAmount ?? o.subtotal ?? 0),
      0,
    );
    cards.push({
      id: 'cc-orders-pending',
      module: 'supply',
      urgency: 'high',
      confidence: 93,
      code: `ORD-PENDING-${submittedOrders.length}`,
      title: `${submittedOrders.length} urgent procurement order${submittedOrders.length !== 1 ? 's' : ''} awaiting approval`,
      why: `${submittedOrders.length} urgent procurement order${submittedOrders.length !== 1 ? 's' : ''} need approval. Delays extend vendor lead time and risk stock-outs.`,
      impact: totalValue > 0
        ? `₹${totalValue.toLocaleString('en-IN')} pending approval`
        : `${submittedOrders.length} orders pending`,
      state: 'SUBMITTED → APPROVED',
      cta: 'Review orders',
      count: submittedOrders.length,
      amount: totalValue * 100,
      cta2: 'View order value',
      cta2PolCode: 'ORDER_VALUE',
    });
  }

  // ── 6. Approved Orders Awaiting Fulfilment ────────────────────────────────
  const approvedOrders = ws.orders?.filter(o => o.status === 'APPROVED') ?? [];
  if (approvedOrders.length > 0) {
    cards.push({
      id: 'cc-orders-delayed',
      module: 'supply',
      urgency: 'medium',
      confidence: 85,
      code: `ORD-APPROVED-${approvedOrders.length}`,
      title: `${approvedOrders.length} approved order${approvedOrders.length !== 1 ? 's' : ''} awaiting fulfilment`,
      why: `${approvedOrders.length} order${approvedOrders.length !== 1 ? 's are' : ' is'} approved but not yet fulfilled. Follow up to confirm dispatch timeline.`,
      impact: 'Vendor follow-up needed',
      state: 'APPROVED → FULFILLED',
      cta: 'Follow up with vendor',
      count: approvedOrders.length,
      primaryPolCode: 'ORDER_FOLLOWUP',
      cta2: 'Escalate',
      cta2PolCode: 'ORDER_ESCALATE',
    });
  }

  // ── 7. Urgent Procurement Requests Missing Quotation ──────────────────────
  const ffOpen = ws.firefighting?.requests?.filter(r => r.status === 'OPEN') ?? [];
  if (ffOpen.length > 0) {
    cards.push({
      id: 'cc-ff-drafts',
      module: 'firefighting',
      urgency: 'high',
      confidence: 91,
      code: `FF-OPEN-${ffOpen.length}`,
      title: `${ffOpen.length} urgent procurement request${ffOpen.length !== 1 ? 's' : ''} awaiting quotation`,
      why: `${ffOpen.length} open request${ffOpen.length !== 1 ? 's' : ''} need a quotation before they can proceed to approval. Safety SLAs may be at risk.`,
      impact: 'Safety compliance · quotation needed',
      state: 'OPEN → IN_REVIEW',
      cta: 'View requests',
      count: ffOpen.length,
      cta2: 'Add quotation',
      cta2PolCode: 'FF_QUOTATION_ADD',
    });
  }

  // ── 8. Urgent Procurement Approvals Pending ───────────────────────────────
  const ffInReview = ws.firefighting?.requests?.filter(r => r.status === 'IN_REVIEW') ?? [];
  const ffApprovalCount = ffInReview.length > 0
    ? ffInReview.length
    : ws.dashboard.pendingApprovals ?? 0;

  if (ffApprovalCount > 0) {
    cards.push({
      id: 'cc-ff-approval',
      module: 'firefighting',
      urgency: 'critical',
      confidence: 97,
      code: `FF-APPROVE-${ffApprovalCount}`,
      title: `${ffApprovalCount} urgent procurement request${ffApprovalCount !== 1 ? 's' : ''} pending approval`,
      why: `${ffApprovalCount} request${ffApprovalCount !== 1 ? 's' : ''} in review need sign-off. Safety equipment cannot be dispatched without approval.`,
      impact: 'Safety dispatch blocked · act now',
      state: 'IN_REVIEW → APPROVED',
      cta: 'Approve requests',
      count: ffApprovalCount,
      cta2: 'View quotations',
      cta2PolCode: 'FF_QUOTATION_VIEW',
    });
  }

  // ── 9. Attendance Not Marked Today ────────────────────────────────────────
  // Workspace does not carry per-section attendance gap data — card omitted (no real source).

  // ── 10. Low Attendance Alert (conditional) ────────────────────────────────
  const attendancePct = ws.dashboard.attendancePercent ?? 100;
  if (attendancePct < 88) {
    cards.push({
      id: 'cc-attendance-low',
      module: 'attendance',
      urgency: attendancePct < 80 ? 'high' : 'medium',
      confidence: 86,
      code: 'ATT-LOW',
      title: `School attendance at ${attendancePct}% — below threshold`,
      why: `Today's attendance (${attendancePct}%) is below the 88% threshold. Identifying low-attendance sections and notifying parents reduces chronic absenteeism.`,
      impact: `${100 - attendancePct}% absent today · target 88%`,
      state: `${attendancePct}% → 88% target`,
      cta: 'Review low attendance',
      primaryPolCode: 'ATTENDANCE_LOW',
      cta2: 'Notify parents',
      cta2PolCode: 'PARENT_NOTIFY',
    });
  }

  // Sort: critical → high → medium → low
  const ORDER: Record<string, number> = { critical: 0, high: 1, medium: 2, low: 3 };
  cards.sort((a, b) => (ORDER[a.urgency] ?? 9) - (ORDER[b.urgency] ?? 9));

  return cards;
}

/** Returns the primary navigation panel for a derived card's main CTA. */
export function panelForCard(
  a: { id: string; module: string },
  fallbackPanel: (module: string) => string,
): string {
  switch (a.id) {
    case 'cc-ff-drafts':          return 'ff-dashboard';
    case 'cc-ff-approval':        return 'ff-approvals';
    case 'cc-orders-pending':     return 'orders';
    case 'cc-student-profiles':   return 'students';
    case 'cc-attendance-pending': return 'attendance';
    case 'cc-fee-collection':     return 'fees';
    default:                      return fallbackPanel(a.module);
  }
}

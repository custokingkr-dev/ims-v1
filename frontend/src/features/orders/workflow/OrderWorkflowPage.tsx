import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ArrowLeft, RefreshCw } from 'lucide-react';
import { useAuth } from '../../../contexts/AuthContext';
import { orderHeadline } from '../shared/orderLabel';
import { clockOffset } from '../shared/slaFormat';
import { gateFailures, isConflict, workflowApi, workflowError } from './api';
import { WorkflowContext, useDesignFiles, useDirectory, type CallPreset, type WorkflowContextValue } from './context';
import { NARROW, SINGLE_COLUMN, Toast, useMediaQuery, useServerClock, useToast } from './hooks';
import { defaultStageOrder, stagesOf, tabsOf } from './model';
import { Sidebar } from './Sidebar';
import { StagePanel } from './StagePanel';
import { StageList, Stepper } from './Stepper';
import { SummaryRow } from './SummaryRow';
import type { GateFailure, OrderWorkflowSummary, OrderWorkflowView, Tab, WorkflowTemplate } from './types';
import './workflow.css';

interface Notice { text: string; unmet: GateFailure[] }

/**
 * One order's workflow (section 4.3): the summary row, the stepper, the stage list, the stage panel rendered from the
 * server's template, and the sidebar widgets. The server decides everything that may be done (each step's `can` and
 * `unmet`); this page only shows it and sends what the user does.
 */
export function OrderWorkflowPage({ orderId, initial, onBack, onChanged, backLabel = 'Order workflows' }: {
  orderId: string;
  initial?: OrderWorkflowView;
  onBack: () => void;
  /** Told after any change, so the list behind can refresh. */
  onChanged?: () => void;
  backLabel?: string;
}) {
  const { user } = useAuth();
  const [view, setView] = useState<OrderWorkflowView | null>(initial ?? null);
  const [template, setTemplate] = useState<WorkflowTemplate | null>(null);
  const [summary, setSummary] = useState<OrderWorkflowSummary | null>(null);
  const [summaryError, setSummaryError] = useState('');
  const [loadError, setLoadError] = useState('');
  const [busy, setBusy] = useState(false);
  const [viewing, setViewing] = useState<number | null>(null);
  const [tab, setTab] = useState<Tab>('WORK');
  const [drafts, setDrafts] = useState<Record<number, Record<string, string>>>({});
  const [callPreset, setCallPreset] = useState<CallPreset | null>(null);
  const [callsVersion, setCallsVersion] = useState(0);
  const [notice, setNotice] = useState<Notice | null>(null);
  /** Bumped by Retry after a failed load. */
  const [attempt, setAttempt] = useState(0);
  /** Bumped by Refresh, which re-reads everything the page shows. */
  const [refreshes, setRefreshes] = useState(0);
  const { toast, show } = useToast();
  const designFiles = useDesignFiles(orderId);
  const directory = useDirectory();
  const changed = useRef(onChanged);
  changed.current = onChanged;
  // Section 4.3's window breakpoints (workflow.css explains why the room cannot stand in for them): below 1024 px the stage
  // list is a select, below 768 px the stepper is one line.
  const compact = useMediaQuery(SINGLE_COLUMN);
  const narrow = useMediaQuery(NARROW);

  const load = useCallback(async () => {
    setLoadError('');
    try {
      const next = await workflowApi.view(orderId);
      const nextTemplate = await workflowApi.template(next.definitionId);
      setView(next); setTemplate(nextTemplate);
    } catch (e) {
      setLoadError(workflowError(e, 'This order’s workflow could not be loaded.'));
    }
  }, [orderId]);

  useEffect(() => {
    let active = true;
    if (initial && initial.orderId === orderId) {
      workflowApi.template(initial.definitionId).then((next) => { if (active) setTemplate(next); },
        (e: unknown) => { if (active) setLoadError(workflowError(e, 'The workflow template could not be loaded.')); });
    } else {
      setView(null); setTemplate(null); setViewing(null);
      void load();
    }
    return () => { active = false; };
    // Runs for a new order and for Retry, which is offered only while the view or template is missing: with `initial`, only
    // the template can have failed. Refresh re-reads the view itself (`refresh` below), so `initial` is never shown again.
  }, [orderId, attempt]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    let active = true;
    setSummary(null); setSummaryError('');
    workflowApi.summary(orderId).then((next) => { if (active) setSummary(next); },
      (e: unknown) => { if (active) setSummaryError(workflowError(e, 'The school’s order could not be loaded.')); });
    return () => { active = false; };
  }, [orderId, attempt, refreshes]);

  const offsetMs = useMemo(() => (view ? clockOffset(view.now, Date.now()) : 0), [view]);
  const now = useServerClock(offsetMs, 30_000);
  const stages = useMemo(() => (view && template ? stagesOf(view, template) : []), [view, template]);
  const viewingOrder = viewing != null && stages.some((stage) => stage.view.step === viewing) ? viewing : view ? defaultStageOrder(view) : 1;
  const index = stages.findIndex((stage) => stage.view.step === viewingOrder);
  const stage = stages[index];
  const current = stages.find((item) => item.view.status === 'ACTIVE');
  const currentOverdue = !!current && (current.view.overdue || (!!current.view.dueAt && now > Date.parse(current.view.dueAt)));

  const reload = useCallback(async () => {
    try { setView(await workflowApi.view(orderId)); } catch (e) { setNotice({ text: workflowError(e, 'The order could not be reloaded.'), unmet: [] }); }
  }, [orderId]);

  /**
   * Refresh: the order view (stages, gates, notes, assignee, upcoming actions), its summary, design files and calls. Before
   * the page has loaded, it is a Retry.
   */
  const refresh = () => {
    setNotice(null);
    setRefreshes((value) => value + 1);
    setCallsVersion((value) => value + 1);
    void designFiles.reload();
    if (view && template) void reload(); else setAttempt((value) => value + 1);
  };

  const mutate = useCallback<WorkflowContextValue['mutate']>(async (action, success) => {
    setBusy(true); setNotice(null);
    try {
      const next = await action();
      setView(next);
      if (success) show(success);
      changed.current?.();
      return next;
    } catch (e) {
      const unmet = gateFailures(e);
      if (isConflict(e)) {
        await reload();
        setNotice({ text: `${workflowError(e)} The order has been reloaded with the latest changes.`, unmet: [] });
      } else {
        setNotice({ text: workflowError(e), unmet });
        if (unmet.length) await reload();
      }
      return null;
    } finally { setBusy(false); }
  }, [reload, show]);

  const run = useCallback<WorkflowContextValue['run']>(async (action, success) => {
    setBusy(true); setNotice(null);
    try {
      await action();
      await reload();
      if (success) show(success);
      changed.current?.();
      return true;
    } catch (e) {
      setNotice({ text: workflowError(e), unmet: [] });
      return false;
    } finally { setBusy(false); }
  }, [reload, show]);

  const goToStage = useCallback((order: number, nextTab?: Tab) => {
    setViewing(order);
    if (nextTab) setTab(nextTab);
    setNotice(null);
  }, []);

  const onDraft = useCallback((step: number, next: Record<string, string>) => setDrafts((all) => ({ ...all, [step]: next })), []);

  const openCallForm = useCallback((preset: CallPreset) => {
    setCallPreset(preset);
    const withTab = stages.find((item) => item.view.step === viewingOrder && tabsOf(item.spec).includes('SCHOOL_VENDOR'))
      ?? stages.find((item) => item.view.status === 'ACTIVE' && tabsOf(item.spec).includes('SCHOOL_VENDOR'))
      ?? stages.find((item) => tabsOf(item.spec).includes('SCHOOL_VENDOR'));
    if (withTab) { setViewing(withTab.view.step); setTab('SCHOOL_VENDOR'); }
  }, [stages, viewingOrder]);

  const callLogged = useCallback(() => {
    setCallPreset(null);
    setCallsVersion((value) => value + 1);
    changed.current?.();
    void reload();
  }, [reload]);

  const headline = view ? orderHeadline(view) : orderId;
  const top = (
    <div className="ck-wf-top">
      <button type="button" className="ck-btn ck-btn-ghost ck-btn-sm" onClick={onBack}><ArrowLeft size={14} aria-hidden="true" /> {backLabel}</button>
      <nav aria-label="Breadcrumb" className="ck-wf-crumbs">
        <ol>
          <li><button type="button" className="ck-wf-text-btn" onClick={onBack}>{backLabel}</button></li>
          <li aria-current="page">{headline}</li>
        </ol>
      </nav>
      <button type="button" className="ck-btn ck-btn-ghost ck-btn-sm" disabled={busy} onClick={refresh}>
        <RefreshCw size={14} aria-hidden="true" /> Refresh
      </button>
    </div>
  );

  if (loadError && (!view || !template)) {
    return (
      <div className="ck-wf">
        {top}
        <div className="ck-alert ck-alert-re" role="alert"><div>{loadError}</div></div>
        <div><button type="button" className="ck-btn ck-btn-ghost" onClick={() => setAttempt((value) => value + 1)}><RefreshCw size={14} aria-hidden="true" /> Retry</button></div>
      </div>
    );
  }
  if (!view || !template || !stage || !user) {
    return <div className="ck-wf">{top}<p className="ck-wf-muted" role="status">Loading the order workflow…</p></div>;
  }

  const context: WorkflowContextValue = {
    view, template, stages, summary, summaryError,
    user: { userId: user.userId, fullName: user.fullName ?? '', superadmin: user.role === 'SUPERADMIN' },
    offsetMs, busy, mutate, run, reload, toast: show, goToStage, openCallForm, callPreset, designFiles, directory, callsVersion, callLogged,
  };

  return (
    <WorkflowContext.Provider value={context}>
      <div className="ck-wf">
        {top}
        <SummaryRow overdue={currentOverdue} />
        <Stepper stages={stages} viewing={viewingOrder} onView={(order) => goToStage(order)} narrow={narrow} />
        <div className="ck-wf-body">
          <StageList stages={stages} viewing={viewingOrder} onView={(order) => goToStage(order)} timeZone={view.timeZone} now={now} compact={compact} />
          <div className="ck-wf-main">
            {notice && (
              <div className="ck-alert ck-alert-re" role="alert">
                <div>
                  {notice.text}
                  {notice.unmet.length > 0 && <ul className="ck-wf-plain-list">{notice.unmet.map((item, position) => <li key={`${item.code}-${position}`}>{item.message}</li>)}</ul>}
                </div>
              </div>
            )}
            <StagePanel
              key={stage.view.step}
              stage={stage}
              previous={stages[index - 1]}
              next={stages[index + 1]}
              overdue={stage === current && currentOverdue}
              tab={tab}
              onTab={setTab}
              draft={drafts[stage.view.step] ?? {}}
              onDraft={onDraft}
            />
          </div>
          <Sidebar />
        </div>
        <Toast toast={toast} />
      </div>
    </WorkflowContext.Provider>
  );
}

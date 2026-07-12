import { useMemo, useState } from 'react';
import { ModuleShell } from '../ui';
import { Modal } from '../../../components/Modal';
import type { WorkspaceData, PanelKey } from '../../workspace/config';
import { currentFinancialYearLabel, financialYearHistoryOptions, formatMoney } from '../utils';
import api from '../../../services/api';

/** Parse display amount strings like "₹1,80,000" → 180000. Returns 0 on non-numeric. */
function parseAmountStr(s: string): number {
  const n = parseInt(s.replace(/[₹,\s]/g, ''), 10);
  return isNaN(n) ? 0 : n;
}

type TabKey = 'all' | 'student' | 'events' | 'office' | 'exams' | 'ff' | 'pkg';
type BarFilter = 'all' | 'supply' | 'school' | 'event' | 'exam' | 'ff';

interface ModalData { title: string; desc: string; amount: string; type: string; deadline: string; }

interface Props {
  workspace: WorkspaceData;
  onRefresh: () => Promise<void>;
  setPanel?: (k: PanelKey) => void;
}

// ── Fixture data (isolated — trivially removable when real endpoints are wired) ──
interface TlBar { rowType: string; label: string; left: string; width: string; top?: string; height?: string; fontSize?: string; modal: ModalData; }

const TL_BARS: TlBar[] = [
  { rowType: 'supply', label: 'Uniforms',  left: '0%',  width: '14%', modal: { title: 'Uniforms', desc: '487 students · 3 items per student · Class 6–12 · School logo embroidered', amount: '₹4,86,000', type: 'supply', deadline: 'May 15' } },
  { rowType: 'supply', label: 'Notebooks', left: '5%',  width: '10%', modal: { title: 'Notebooks + stationery', desc: '487 student kits · 6 notebooks + diary + graph book + stationery per student', amount: '₹2,34,000', type: 'supply', deadline: 'May 20' } },
  { rowType: 'supply', label: 'ID Cards',  left: '4%',  width: '9%',  modal: { title: 'ID Cards', desc: '487 student + 68 staff + 20 spare · PVC laminated · Lanyard included', amount: '₹62,000', type: 'supply', deadline: 'May 18' } },
  { rowType: 'school', label: 'Term 1 · Jun 2 – Sep 10',  left: '17%', width: '30%', top: '2px', height: '26px', fontSize: '11px', modal: { title: 'Term 1', desc: 'Jun 2 – Sep 10 · 14 weeks · 487 students across 18 sections', amount: '—', type: 'school', deadline: 'Jun 2' } },
  { rowType: 'school', label: 'Term 2 · Sep 18 – Dec 20', left: '50%', width: '31%', top: '2px', height: '26px', fontSize: '11px', modal: { title: 'Term 2', desc: 'Sep 18 – Dec 20 · 13 weeks · Exam prep quarter · Sports Day and Annual Day this term', amount: '—', type: 'school', deadline: 'Sep 18' } },
  { rowType: 'event', label: "Teacher's Day", left: '28%', width: '7%', modal: { title: "Teacher's Day", desc: 'Sep 5 · Sash×10 · Certificates×50 · Decoration · Budget ₹30,000', amount: '₹30,000', type: 'event', deadline: 'Aug 22' } },
  { rowType: 'event', label: 'Annual Day',   left: '50%', width: '8%', modal: { title: 'Annual Day', desc: 'Sep 28 · Trophies×20 · Certificates×200 · Stage backdrop 12×8ft · F&B', amount: '₹92,000', type: 'event', deadline: 'Sep 10' } },
  { rowType: 'event', label: 'Sports Day',   left: '71%', width: '7%', modal: { title: 'Sports Day', desc: 'Nov 14 · Medals×30 · Jerseys by house · Trophies×5 · Event banners', amount: '₹58,000', type: 'event', deadline: 'Oct 20' } },
  { rowType: 'event', label: 'X-mas',        left: '82%', width: '7%', modal: { title: 'Christmas Carnival', desc: 'Dec 18 · Décor · Costume contest · Prizes · Printing', amount: '₹18,000', type: 'event', deadline: 'Dec 5' } },
  { rowType: 'exam',  label: 'T1 Exams',    left: '17%', width: '6%', modal: { title: 'Term 1 Exams', desc: 'Jul 28 – Aug 8 · Answer sheets, graph sheets, admit cards to be printed and distributed', amount: '—', type: 'exam', deadline: 'Jul 20' } },
  { rowType: 'exam',  label: 'Mid-term',    left: '50%', width: '6%', modal: { title: 'Mid-term Exams', desc: 'Oct 6–18 · Board-pattern papers · Hall tickets · Supervised hall setup', amount: '—', type: 'exam', deadline: 'Sep 25' } },
  { rowType: 'exam',  label: 'Annual Exams',left: '80%', width: '8%', modal: { title: 'Annual Exams', desc: 'Dec 8–22 · Final exams · All printing, answer booklets, practical kits pre-ordered', amount: '—', type: 'exam', deadline: 'Nov 20' } },
  { rowType: 'ff',    label: 'Lab equip.',  left: '17%', width: '8%', modal: { title: 'Lab Equipment (suggested)', desc: 'Raised as firefighting 2 years running · Chemistry + Physics consumables · Plan now and save 18%', amount: '₹1,80,000', type: 'ff', deadline: 'Jun 15' } },
  { rowType: 'ff',    label: 'Library',     left: '58%', width: '7%', modal: { title: 'Library shelving (FF-006)', desc: 'Currently in approval pipeline · Quotes submitted · Consider planning annually', amount: '₹75,000', type: 'ff', deadline: 'Oct 1' } },
];

const TL_ROW_ORDER = ['supply', 'school', 'event', 'exam', 'ff'] as const;
const TL_ROW_LABELS: Record<string, string> = { supply: 'Supply', school: 'Terms', event: 'Events', exam: 'Exams', ff: 'Plan FF' };

interface AiSuggestion { label: string; conf: string; confCls: string; sub: string; budget: string; source: string; modal: ModalData; }
const AI_SUGGESTIONS: AiSuggestion[] = [
  { label: 'Lab equipment', conf: '90% conf.', confCls: 'ap-is-conf-high', sub: 'Recurring FF · 2 yrs in a row', budget: '₹1,80,000', source: 'Source: firefighting history', modal: { title: 'Lab Equipment (AI suggested)', desc: 'Raised as firefighting urgently 2 consecutive years. Planning now avoids 18% emergency premium.', amount: '₹1,80,000', type: 'ff', deadline: 'Jun 15' } },
  { label: 'Housekeeping supplies', conf: '85%', confCls: 'ap-is-conf-high', sub: 'Past orders · Term 1 + 3', budget: '₹38,400', source: 'Source: catalog order history', modal: { title: 'Housekeeping supplies (AI suggested)', desc: 'Ordered every Term 1 and Term 3 for last 2 years. Scale 1.04× for enrolment growth.', amount: '₹38,400', type: 'supply', deadline: 'Jun 1' } },
  { label: 'Sports jerseys', conf: '75%', confCls: 'ap-is-conf-med', sub: 'Pre-Sports Day · Oct pattern', budget: '₹28,800', source: 'Source: catalog order history', modal: { title: 'Sports jerseys (AI suggested)', desc: 'Pre-Sports Day order placed every October for 2 years. 4 houses × ~24 students each.', amount: '₹28,800', type: 'event', deadline: 'Oct 20' } },
  { label: 'Furniture', conf: '60%', confCls: 'ap-is-conf-med', sub: 'Recurring FF · class expansion', budget: '₹96,000', source: 'Source: firefighting history', modal: { title: 'Furniture (AI suggested)', desc: '2 urgent furniture requests in the last completed year. Class 6 expansion likely needs 24 new units.', amount: '₹96,000', type: 'ff', deadline: 'Jul 1' } },
];

interface EventItem { icon: string; name: string; qty: string; price: string; }
interface EventCard { icon: string; title: string; date: string; statusLabel: string; statusCls: string; items: EventItem[]; total: string; footer: string; orderBtn: string; modal: ModalData; }
const EVENTS: EventCard[] = [
  {
    icon: '🎓', title: "Teacher's Day", date: 'Sep 5, 2025 · Order by Aug 22',
    statusLabel: 'Plan now', statusCls: 'ap-pill-am',
    items: [
      { icon: '🎀', name: 'Sash — "Respected Teacher"', qty: '×10', price: '₹3,000' },
      { icon: '📜', name: 'Appreciation certificates', qty: '×50', price: '₹2,000' },
      { icon: '🎭', name: 'Costumes + props (students)', qty: '×1 set', price: '₹8,000' },
      { icon: '🎈', name: 'Decoration + flowers', qty: '×1 lot', price: '₹5,000' },
      { icon: '🍰', name: 'F&B — tea + snacks', qty: '×68 staff', price: '₹8,000' },
      { icon: '📦', name: 'Gift hampers', qty: '×10', price: '₹4,000' },
    ],
    total: '₹30,000', footer: 'Annual budget: ₹30,000 · Last year: ₹28,200', orderBtn: 'Place order',
    modal: { title: "Teacher's Day — place order", desc: 'All items confirmed. Custoking will source sashes, certificates, costumes, and decoration. Lead time 14 days.', amount: '₹30,000', type: 'event', deadline: 'Aug 22' },
  },
  {
    icon: '🏆', title: 'Annual Day', date: 'Sep 28, 2025 · Order by Sep 10',
    statusLabel: 'In plan', statusCls: 'ap-pill-b',
    items: [
      { icon: '🥇', name: 'Trophy — gold, 6in resin base', qty: '×20', price: '₹9,600' },
      { icon: '📜', name: 'Certificates — A4 GSM150 colour', qty: '×200', price: '₹7,000' },
      { icon: '🎨', name: 'Stage backdrop 12×8ft flex', qty: '×1', price: '₹4,800' },
      { icon: '🎤', name: 'Sound + light rental', qty: '×1 day', price: '₹22,000' },
      { icon: '🍽', name: 'F&B — guests + staff', qty: '×200 pax', price: '₹48,000' },
    ],
    total: '₹92,000', footer: 'Last year: ₹87,400 · +5.3% vs last year', orderBtn: 'Lock & order',
    modal: { title: 'Annual Day — place order', desc: 'All items confirmed. Custoking will coordinate backdrop printing, trophy engraving, and F&B vendor. Lead time 18 days.', amount: '₹92,000', type: 'event', deadline: 'Sep 10' },
  },
  {
    icon: '⚽', title: 'Sports Day', date: 'Nov 14, 2025 · Order jerseys by Oct 20',
    statusLabel: 'Not started', statusCls: 'ap-pill-gr',
    items: [
      { icon: '🥇', name: 'Medals — gold/silver/bronze', qty: '×30', price: '₹4,500' },
      { icon: '🏆', name: 'Trophies — house cups', qty: '×5', price: '₹6,000' },
      { icon: '👕', name: 'House jerseys (4 colours)', qty: '×96', price: '₹28,800' },
      { icon: '🎉', name: 'Event banners + standees', qty: '×6', price: '₹8,400' },
      { icon: '📋', name: 'Score sheets + certificates', qty: '×100', price: '₹3,500' },
      { icon: '🍎', name: 'F&B — students + parents', qty: '×300 pax', price: '₹7,000' },
    ],
    total: '₹58,200', footer: 'Last year: ₹51,200', orderBtn: 'Add to plan',
    modal: { title: 'Sports Day — add to plan', desc: 'Adding Sports Day package to the annual plan. Jerseys must be ordered by Oct 20 for Nov 14 event.', amount: '₹58,200', type: 'event', deadline: 'Oct 20' },
  },
  {
    icon: '📝', title: 'Exam stationery — 3 terms', date: 'T1: Jul 28 · T2: Oct 6 · Annual: Dec 8',
    statusLabel: 'Lock in', statusCls: 'ap-pill-pu',
    items: [
      { icon: '📓', name: 'Answer booklets 40pg', qty: '×1500', price: '₹9,000' },
      { icon: '📐', name: 'Graph sheets', qty: '×600', price: '₹1,800' },
      { icon: '🪪', name: 'Admit cards (printed)', qty: '×1500', price: '₹3,000' },
      { icon: '✏', name: 'Hall stationery kits', qty: '×18 halls', price: '₹2,700' },
      { icon: '📋', name: 'Supervisor sheets + logs', qty: '×3 terms', price: '₹1,200' },
    ],
    total: '₹17,700', footer: 'All 3 terms combined · Lock in Term 1 before Jun 2', orderBtn: 'Lock all 3 terms',
    modal: { title: 'Lock exam stationery — 3 terms', desc: 'All 3 exam windows pre-ordered in one go. Custoking delivers per-term as needed. No scrambling before each exam.', amount: '₹17,700', type: 'exam', deadline: 'Jun 1' },
  },
];

interface OfficeCard { icon: string; title: string; sub: string; amount: string; statusLabel: string; statusCls: string; action: string; modal: ModalData; }
const OFFICE_CARDS: OfficeCard[] = [
  { icon: '🧹', title: 'Housekeeping', sub: 'Weekly 3-day · 4 staff', amount: '₹38,400 / 3 months', statusLabel: 'Planned', statusCls: 'sg', action: 'Order →', modal: { title: 'Housekeeping contract', desc: 'Weekly 3-day service · 4 staff · Floor cleaning, washroom sanitation, dustbin management', amount: '₹38,400', type: 'supply', deadline: 'Jun 1' } },
  { icon: '🩺', title: 'Health & safety', sub: 'First aid + compliance', amount: '₹24,800', statusLabel: 'Planned', statusCls: 'sg', action: 'Order →', modal: { title: 'Health & safety supplies', desc: 'First aid kits ×6 · Hand sanitizer ×24 · Gloves ×10 boxes · Fire extinguisher check · Annual compliance', amount: '₹24,800', type: 'supply', deadline: 'Jun 1' } },
  { icon: '🖨', title: 'Admin printing', sub: 'Circulars, receipts, reports', amount: '₹12,000', statusLabel: 'Draft', statusCls: 'sam', action: 'Configure →', modal: { title: 'Admin printing annual', desc: 'Circulars, fee receipts, letters, hall tickets, report cards · Estimated 8,000 pages per year · A4 80 GSM', amount: '₹12,000', type: 'supply', deadline: 'Jun 1' } },
  { icon: '👔', title: 'Staff uniforms', sub: '68 staff members', amount: '₹68,000', statusLabel: 'Not ordered', statusCls: 'sr', action: 'Order →', modal: { title: 'Staff uniforms', desc: '68 staff · Shirt + trouser or saree as applicable · School logo embroidered · Annual replacement', amount: '₹68,000', type: 'supply', deadline: 'Jun 1' } },
];

interface ClassRow { cls: string; meta: string; need: string; students: number; items: number; amount: string; pct: number; pctColor: string; statusLabel: string; statusCls: string; due: string; dueColor: string; modal: ModalData; }
const CLASS_ROWS: ClassRow[] = [
  { cls: 'Class 6',   meta: 'Sec A, B, C',        need: 'Uniform + ID + notebooks', students: 82, items: 3, amount: '₹84,000',   pct: 0,   pctColor: 'var(--re)', statusLabel: 'Not ordered', statusCls: 'sr',  due: 'May 15',    dueColor: 'var(--re)', modal: { title: 'Class 6 — Supply plan',   desc: '82 students · Uniform + ID card + notebook kit · Size survey from section teachers pending', amount: '₹84,000',   type: 'supply',    deadline: 'May 15' } },
  { cls: 'Class 7',   meta: 'Sec A, B',            need: 'Uniform + notebooks',       students: 74, items: 2, amount: '₹76,200',   pct: 0,   pctColor: 'var(--re)', statusLabel: 'Not ordered', statusCls: 'sr',  due: 'May 15',    dueColor: 'var(--re)', modal: { title: 'Class 7 — Supply plan',   desc: '74 students · Uniform + notebooks + stationery kit', amount: '₹76,200',   type: 'supply',    deadline: 'May 15' } },
  { cls: 'Class 8',   meta: 'Sec A, B',            need: 'Notebooks + stationery',   students: 70, items: 2, amount: '₹34,000',   pct: 30,  pctColor: 'var(--am)', statusLabel: 'Draft',       statusCls: 'sam', due: 'May 20',    dueColor: 'var(--am)', modal: { title: 'Class 8 — Supply plan',   desc: '70 students · Notebooks + stationery kit only · Uniforms reused from prior year', amount: '₹34,000',   type: 'supply',    deadline: 'May 20' } },
  { cls: 'Class 9',   meta: 'Sec A, B, C',        need: 'Uniform + lab kit',         students: 96, items: 3, amount: '₹1,32,000', pct: 100, pctColor: 'var(--g)',  statusLabel: 'In transit',  statusCls: 'sg',  due: 'Placed ✓',  dueColor: 'var(--g)',  modal: { title: 'Class 9 — Supply plan',   desc: '96 students · Uniform + lab kit · Already placed and in transit', amount: '₹1,32,000', type: 'supply',    deadline: 'Placed' } },
  { cls: 'Class 10',  meta: 'Sec A, B, C',        need: 'Stationery + ID card',      students: 88, items: 2, amount: '₹42,000',   pct: 50,  pctColor: 'var(--am)', statusLabel: 'Draft',       statusCls: 'sam', due: 'May 18',    dueColor: 'var(--am)', modal: { title: 'Class 10 — Supply plan',  desc: '88 students · Stationery + ID cards · Board year · Extra stationery recommended', amount: '₹42,000',   type: 'supply',    deadline: 'May 18' } },
  { cls: 'Class 11–12', meta: 'Science + Commerce', need: 'Lab supplies + notebooks', students: 77, items: 4, amount: '₹98,000',   pct: -1,  pctColor: 'var(--b)',  statusLabel: 'Suggested',   statusCls: 'sb2', due: 'Plan first', dueColor: 'var(--b)', modal: { title: 'Class 11–12 — Supply plan', desc: '77 students · Science + Commerce streams · Lab supplies + notebooks · Suggestion from intelligence engine', amount: '₹98,000', type: 'suggested', deadline: 'Jun 1' } },
];

const TYPE_LABEL: Record<string, string> = { supply: 'Supply order', school: 'School calendar', event: 'Event', exam: 'Exam planner', ff: 'Firefighting → plan', suggested: 'AI suggestion' };
const TYPE_COLOR: Record<string, string> = { supply: 'var(--b)', school: 'var(--g)', event: 'var(--am)', exam: 'var(--pu)', ff: 'var(--re)', suggested: 'var(--b)' };

// ── Component ─────────────────────────────────────────────────────────────────
export function PlanningPanel({ workspace, onRefresh: _onRefresh, setPanel }: Props) {
  const [activeTab, setActiveTab] = useState<TabKey>('all');
  const [barFilter, setBarFilter] = useState<BarFilter>('all');
  const [modal, setModal] = useState<ModalData | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [planBusy, setPlanBusy] = useState(false);
  const financialYearStartMonth = Number(workspace.school?.financialYearStartMonth || 4);
  const currentYearLabel = currentFinancialYearLabel(new Date(), financialYearStartMonth);
  const planningYearTabs = financialYearHistoryOptions(3, new Date(), financialYearStartMonth);
  const planningStartYear = Number(currentYearLabel.slice(0, 4)) || new Date().getFullYear();

  const studentCount = workspace.school?.students ?? workspace.dashboard?.students ?? '—';

  // Real data from workspace
  const ffRequests = (workspace.firefighting?.requests ?? []) as Array<{ code: string; category?: string; status: string; estimatedAmount?: number; description?: string }>;
  const planTerms = (workspace.annualPlan?.terms ?? []) as Array<{ term: string; category: string; status: string; quantity: string; amount: number }>;

  const todayPct = useMemo(() => {
    const now = new Date();
    const start = new Date(now.getFullYear(), 3, 1);
    const end   = new Date(now.getFullYear(), 11, 31);
    return Math.max(0, Math.min(100, (now.getTime() - start.getTime()) / (end.getTime() - start.getTime()) * 100));
  }, []);

  const daysToT2 = useMemo(() => {
    const now = new Date();
    const t2 = new Date(now.getFullYear(), 8, 18);
    const diff = Math.ceil((t2.getTime() - now.getTime()) / 86400000);
    return diff > 0 ? diff : 0;
  }, []);

  function showToast(msg: string) {
    setToast(msg);
    setTimeout(() => setToast(null), 3500);
  }

  /** POST a single item to the annual plan. schoolId is resolved server-side from the JWT. */
  async function addPlanItem(category: string, description: string, estimatedAmount: number): Promise<void> {
    setPlanBusy(true);
    try {
      await api.post('/supply/annual-plan/items', { category, description, estimatedAmount });
      showToast(`"${category}" added to annual plan.`);
    } catch {
      showToast('Could not add to plan — please try again.');
    } finally {
      setPlanBusy(false);
    }
  }

  /** POST all AI suggestions as annual-plan items in parallel. */
  async function addAllSuggestions(): Promise<void> {
    setPlanBusy(true);
    try {
      await Promise.all(
        AI_SUGGESTIONS.map(s =>
          api.post('/supply/annual-plan/items', {
            category: s.label,
            description: s.sub,
            estimatedAmount: parseAmountStr(s.budget),
          })
        )
      );
      showToast('All 4 suggestions added to annual plan.');
    } catch {
      showToast('Could not accept all suggestions — please try again.');
    } finally {
      setPlanBusy(false);
    }
  }

  /** POST /supply/annual-plan/confirm — lock the plan and notify Custoking. */
  async function confirmPlan(): Promise<void> {
    setPlanBusy(true);
    try {
      await api.post('/supply/annual-plan/confirm');
      showToast('Annual plan confirmed and Custoking notified.');
    } catch {
      showToast('Could not confirm plan — please try again.');
    } finally {
      setPlanBusy(false);
    }
  }

  const show = (tab: TabKey) => activeTab === 'all' || activeTab === tab;

  const TABS: [TabKey, React.ReactNode][] = [
    ['all',     'Command center'],
    ['student', <span key="s">Student supplies <span className="ap-tab-badge ap-tab-badge-r">3</span></span>],
    ['events',  <span key="e">Events <span className="ap-tab-badge ap-tab-badge-am">4</span></span>],
    ['office',  'Office & non-academic'],
    ['exams',   <span key="x">Exam planner <span className="ap-tab-badge ap-tab-badge-g">3 terms</span></span>],
    ['ff',      'Firefighting → plan'],
    ['pkg',     'School package'],
  ];

  return (
    <ModuleShell
      title="Annual plan"
      subtitle={`Map your full-year supply requirements by term — lock in pricing early`}
      actions={
        <>
          <button className="ck-btn ck-btn-ghost" disabled title="Coming soon">
            Export PDF
          </button>
          <button className="ck-btn ck-btn-g" disabled={planBusy} onClick={confirmPlan}>
            {planBusy ? 'Saving…' : 'Save plan'}
          </button>
        </>
      }
    >
      {/* Year tabs + stats */}
      <div className="ap-subheader">
        <div className="ap-year-tabs">
          {planningYearTabs.map(y => (
            <button key={y} className={`ap-ytab${y === currentYearLabel ? ' on' : ''}`}
              onClick={() => { if (y !== currentYearLabel) showToast(`Year ${y} view - coming soon`); }}>
              {y}
            </button>
          ))}
        </div>
        <div className="ap-hstats">
          <div className="ap-hstat"><div className="ap-hstat-v" style={{ color: 'var(--re)' }}>3</div><div className="ap-hstat-l">Urgent</div></div>
          <div className="ap-hstat"><div className="ap-hstat-v" style={{ color: 'var(--am)' }}>4</div><div className="ap-hstat-l">Suggested</div></div>
          <div className="ap-hstat"><div className="ap-hstat-v" style={{ color: 'var(--g)' }}>₹15.9L</div><div className="ap-hstat-l">Total planned</div></div>
          <div className="ap-hstat"><div className="ap-hstat-v" style={{ color: 'var(--b)' }}>{daysToT2}</div><div className="ap-hstat-l">Days to T2</div></div>
        </div>
      </div>

      {/* Section tabs */}
      <div className="ap-tabs">
        {TABS.map(([key, label]) => (
          <button key={key} className={`ap-tab${activeTab === key ? ' on' : ''}`} onClick={() => setActiveTab(key)}>
            {label}
          </button>
        ))}
      </div>

      <div className="ap-body">

        {/* Intelligence banner */}
        {(show('all') || activeTab === 'student') && (
          <div className="ap-intel-banner">
            <div className="ap-ib-icon">✦</div>
            <div className="ap-ib-text">
              <h3>4 planning suggestions from your history</h3>
              <p>Based on 2 years of supply orders and firefighting requests — lab equipment raised urgently twice, uniforms placed every April. Accept all to pre-fill your annual plan. Schools that plan annually save 12% vs ad-hoc ordering.</p>
            </div>
            <div className="ap-ib-actions">
              <button className="ap-ib-btn ap-ib-btn-w" disabled={planBusy} onClick={addAllSuggestions}>{planBusy ? 'Adding…' : 'Accept all 4'}</button>
              <button className="ap-ib-btn ap-ib-btn-outline" onClick={() => setActiveTab('ff')}>Review each →</button>
            </div>
          </div>
        )}

        {/* Alert strip */}
        {show('all') && (
          <div className="ap-alert-strip">
            <div className="ap-alert-strip-icon">⚠</div>
            <div className="ap-alert-strip-text">
              <strong>3 order deadlines within 12 days</strong>
              <span>Uniforms → May 15 · ID cards → May 18 · Notebooks → May 20. All required before Term 1 starts Jun 2.</span>
            </div>
            <div className="ap-alert-strip-action">
              <button className="ap-btn ap-btn-or" onClick={() => setPanel?.('catalog')}>Order now →</button>
            </div>
          </div>
        )}

        {/* Timeline */}
        {show('all') && (
          <div className="ap-timeline-wrap">
            <div className="ap-tl-header">
              <div>
                <div className="ap-tl-title">Full-year planning timeline</div>
                <div style={{ fontSize: 11, color: 'var(--ink3)', marginTop: 2 }}>Apr {planningStartYear} - Dec {planningStartYear} · Click any bar for detail</div>
              </div>
              <div className="ap-tl-filters">
                {(['all', 'supply', 'school', 'event', 'exam', 'ff'] as BarFilter[]).map(f => (
                  <button key={f} className={`ap-tl-chip${barFilter === f ? ' on' : ''}`} onClick={() => setBarFilter(f)}>
                    {f === 'all' ? 'All' : f === 'school' ? 'Terms' : f.charAt(0).toUpperCase() + f.slice(1)}
                  </button>
                ))}
              </div>
            </div>
            <div className="ap-tl-scroll">
              <div className="ap-tl-inner">
                <div className="ap-tl-months">
                  <div className="ap-tl-mlabel">Category</div>
                  {['Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'].map(m => (
                    <div key={m} className="ap-tl-mlabel">{m}</div>
                  ))}
                </div>
                <div className="ap-tl-rows">
                  <div className="ap-tl-grid-bg">
                    {Array.from({ length: 9 }).map((_, i) => <div key={i} className="ap-tl-gcol" />)}
                  </div>
                  <div className="ap-tl-today-line" style={{ left: `${todayPct}%` }}>
                    <div className="ap-tl-today-pip" />
                    <div className="ap-tl-today-tag">Today</div>
                  </div>
                  {TL_ROW_ORDER.map(rowType => {
                    const bars = TL_BARS.filter(b => b.rowType === rowType);
                    const visible = barFilter === 'all' || barFilter === rowType;
                    return (
                      <div key={rowType} className="ap-tl-row" style={{ opacity: visible ? 1 : 0.15, transition: 'opacity .2s', minHeight: rowType === 'school' ? 36 : undefined }}>
                        <div className="ap-tl-row-label">{TL_ROW_LABELS[rowType]}</div>
                        <div className="ap-tl-row-track" style={{ height: rowType === 'school' ? 32 : undefined }}>
                          {bars.map((bar, i) => (
                            <div
                              key={i}
                              className={`ap-tl-bar ap-bar-${bar.rowType}`}
                              style={{ left: bar.left, width: bar.width, top: bar.top ?? '3px', height: bar.height ?? '22px', fontSize: bar.fontSize }}
                              onClick={() => setModal(bar.modal)}
                            >
                              <div className="ap-tl-bar-dot" />
                              {bar.label}
                            </div>
                          ))}
                        </div>
                      </div>
                    );
                  })}
                </div>
                <div className="ap-tl-legend">
                  {[
                    { color: '#a0caee', label: 'Supply orders' },
                    { color: 'var(--g2)', label: 'School terms' },
                    { color: '#f5c878', label: 'Events' },
                    { color: '#c5b0e8', label: 'Exams' },
                    { color: '#f0a09a', label: 'Firefighting → plan' },
                  ].map(l => (
                    <div key={l.label} className="ap-tl-leg">
                      <div className="ap-tl-leg-dot" style={{ background: l.color }} />
                      {l.label}
                    </div>
                  ))}
                </div>
              </div>
            </div>
          </div>
        )}

        {/* Budget row */}
        {show('all') && (
          <div className="ap-budget-row">
            {[
              { label: 'Annual budget snapshot', val: '₹15,93,200', sub: `Total planned ${currentYearLabel}`, fill: '60%', color: 'var(--g)' },
              { label: 'Supply orders', val: '₹8,60,000', sub: 'Uniforms, books, ID', fill: '62%', color: 'var(--b)' },
              { label: 'Events', val: '₹1,98,000', sub: '4 events this year', fill: '22%', color: 'var(--am)' },
              { label: 'Office / non-acad.', val: '₹92,000', sub: 'Housekeeping, health', fill: '15%', color: 'var(--pu)' },
              { label: 'Suggested (FF → plan)', val: '₹2,55,000', sub: 'Pending acceptance', fill: '35%', color: 'var(--re)', opacity: 0.5 },
            ].map(c => (
              <div key={c.label} className="ap-budget-col">
                <div className="ap-budget-col-label">{c.label}</div>
                <div className="ap-budget-col-val">{c.val}</div>
                <div className="ap-budget-col-sub">{c.sub}</div>
                <div className="ap-budget-bar-wrap">
                  <div className="ap-budget-track">
                    <div className="ap-budget-fill" style={{ width: c.fill, background: c.color, opacity: c.opacity }} />
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}

        {/* Student supplies */}
        {show('student') && (
          <div>
            <div className="ap-sec-head">
              <div>
                <div className="ap-sec-title">Student supplies by class</div>
                <div className="ap-sec-sub">Term 1 order window — 3 items not yet ordered</div>
              </div>
              <div style={{ display: 'flex', gap: 8 }}>
                <button className="ap-btn ap-btn-ghost" disabled={planBusy} onClick={confirmPlan}>{planBusy ? 'Confirming…' : 'Confirm all classes'}</button>
                <button className="ap-btn ap-btn-g" onClick={() => setPanel?.('catalog')}>+ Add item</button>
              </div>
            </div>
            <div className="ap-two-col">
              <div className="ap-table-card">
                <div className="ap-table-card-head">
                  <div><div className="ap-tc-title">Class-wise supply plan</div><div className="ap-tc-sub">Click a row to configure quantities and items</div></div>
                  <div style={{ fontSize: 11, color: 'var(--ink3)' }}>{studentCount} students total</div>
                </div>
                <div className="ck-table-wrap">
                <table className="ap-plan-table">
                  <thead>
                    <tr><th>Class</th><th>Need</th><th>Students</th><th>Items</th><th>Est. amount</th><th>Progress</th><th>Status</th><th>Order by</th></tr>
                  </thead>
                  <tbody>
                    {CLASS_ROWS.map((row, i) => (
                      <tr key={i} style={{ cursor: 'pointer' }} onClick={() => setModal(row.modal)}>
                        <td><div className="ap-class-cell">{row.cls}</div><div className="ap-class-meta">{row.meta}</div></td>
                        <td>{row.need}</td>
                        <td><strong>{row.students}</strong></td>
                        <td>{row.items} items</td>
                        <td className="mono">{row.amount}</td>
                        <td>
                          <div className="ap-mini-bar-wrap">
                            <div style={{ fontSize: 10, fontWeight: 700, color: row.pctColor }}>{row.pct === -1 ? 'Suggested' : `${row.pct}%`}</div>
                            {row.pct >= 0 && <div className="ap-mini-bar"><div className="ap-mini-bar-fill" style={{ width: `${row.pct}%`, background: row.pctColor }} /></div>}
                          </div>
                        </td>
                        <td><span className={`ck-status ${row.statusCls}`}>{row.statusLabel}</span></td>
                        <td style={{ fontWeight: 700, color: row.dueColor, fontSize: 11 }}>{row.due}</td>
                      </tr>
                    ))}
                    {/* Real plan items from workspace */}
                    {planTerms.map((t, i) => (
                      <tr key={`real-${i}`} style={{ cursor: 'pointer' }} onClick={() => setModal({ title: t.category, desc: `${t.term} · ${t.quantity}`, amount: `₹${formatMoney(t.amount)}`, type: 'supply', deadline: t.status })}>
                        <td colSpan={2}><div className="ap-class-cell">{t.category}</div><div className="ap-class-meta">{t.term}</div></td>
                        <td>—</td><td>—</td>
                        <td className="mono">₹{formatMoney(t.amount)}</td>
                        <td>—</td>
                        <td><span className={`ck-status ${t.status === 'Planned' ? 'sg' : 'sam'}`}>{t.status}</span></td>
                        <td>—</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                </div>
              </div>
              {/* AI suggestions sidebar */}
              <div className="ap-intel-sidebar">
                <div className="ap-is-header">
                  <div className="ap-is-title">AI suggestions</div>
                  <div className="ap-is-count">4 new</div>
                </div>
                <div className="ap-is-items">
                  {AI_SUGGESTIONS.map((s, i) => (
                    <div key={i} className="ap-is-item" onClick={() => setModal(s.modal)}>
                      <div className="ap-is-item-head">
                        <div className="ap-is-item-label">{s.label}</div>
                        <div className={`ap-is-conf ${s.confCls}`}>{s.conf}</div>
                      </div>
                      <div className="ap-is-item-sub">{s.sub}</div>
                      <div className="ap-is-item-budget">{s.budget}</div>
                      <div className="ap-is-item-source">{s.source}</div>
                    </div>
                  ))}
                </div>
                <div className="ap-is-footer">
                  <button className="ap-btn ap-btn-g" style={{ flex: 1, padding: 8 }} disabled={planBusy} onClick={addAllSuggestions}>{planBusy ? 'Adding…' : 'Accept all'}</button>
                  <button className="ap-btn ap-btn-ghost" style={{ flex: 1, padding: 8 }} onClick={() => showToast('Review mode opens each suggestion individually.')}>Review each</button>
                </div>
              </div>
            </div>
          </div>
        )}

        {/* Events */}
        {show('events') && (
          <div>
            <div className="ap-sec-head">
              <div>
                <div className="ap-sec-title">Event planning — this academic year</div>
                <div className="ap-sec-sub">Prices fetched from Custoking catalog · Customise quantities · Lock in early for availability</div>
              </div>
              <button className="ap-btn ap-btn-ghost" onClick={() => showToast("Add new event to this year's plan.")}>+ Add event</button>
            </div>
            <div className="ap-event-grid">
              {EVENTS.map((ev, i) => (
                <div key={i} className="ap-event-card">
                  <div className="ap-ec-header">
                    <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between' }}>
                      <div>
                        <div className="ap-ec-icon">{ev.icon}</div>
                        <div className="ap-ec-title">{ev.title}</div>
                        <div className="ap-ec-date">{ev.date}</div>
                      </div>
                      <span className={`ap-ec-status-pill ${ev.statusCls}`}>{ev.statusLabel}</span>
                    </div>
                  </div>
                  <div className="ap-ec-body">
                    <div className="ap-ec-line-items">
                      {ev.items.map((item, j) => (
                        <div key={j} className="ap-ec-item">
                          <div className="ap-ec-item-name"><span>{item.icon}</span>{item.name}</div>
                          <div className="ap-ec-item-qty">{item.qty}</div>
                          <div className="ap-ec-item-price">{item.price}</div>
                        </div>
                      ))}
                    </div>
                    <div className="ap-ec-divider" />
                    <div className="ap-ec-total">
                      <div className="ap-ec-total-label">{ev.footer}</div>
                      <div className="ap-ec-total-val">{ev.total}</div>
                    </div>
                  </div>
                  <div className="ap-ec-footer">
                    <button className="ap-ec-btn ap-ec-btn-g" onClick={() => setModal(ev.modal)}>{ev.orderBtn}</button>
                    <button className="ap-ec-btn ap-ec-btn-ghost" onClick={() => showToast('Customise quantities, add or remove items, and adjust budget.')}>Customise</button>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Exam planner (specific tab only) */}
        {activeTab === 'exams' && (
          <div>
            <div className="ap-sec-head">
              <div>
                <div className="ap-sec-title">Exam planner - {currentYearLabel}</div>
                <div className="ap-sec-sub">3 exam windows this year · All stationery pre-orderable in one batch</div>
              </div>
            </div>
            {[
              { term: 'Term 1 Exams', dates: 'Jul 28 – Aug 8 · Answer sheets, admit cards, graph sheets to be printed', status: 'Order by Jul 20', color: 'var(--am)' },
              { term: 'Mid-term Exams', dates: 'Oct 6–18 · Board-pattern papers · Hall tickets · Supervised hall setup', status: 'Order by Sep 25', color: 'var(--b)' },
              { term: 'Annual Exams', dates: 'Dec 8–22 · Final exams · All printing, answer booklets, practical kits', status: 'Plan by Nov 20', color: 'var(--g)' },
            ].map((ex, i) => (
              <div key={i} className="ap-exam-strip" style={{ marginBottom: 10, cursor: 'pointer' }}
                onClick={() => setModal({ title: ex.term, desc: ex.dates, amount: '—', type: 'exam', deadline: ex.status })}>
                <span>📋</span>
                <div>
                  <strong>{ex.term}</strong>
                  <span style={{ display: 'block', marginTop: 2 }}>{ex.dates}</span>
                </div>
                <div style={{ marginLeft: 'auto', fontWeight: 700, fontSize: 11, color: ex.color }}>{ex.status}</div>
              </div>
            ))}
            <div style={{ marginTop: 16 }}>
              {EVENTS.filter(e => e.title.startsWith('Exam')).map((ev, i) => (
                <div key={i} className="ap-event-card" style={{ maxWidth: 440 }}>
                  <div className="ap-ec-header">
                    <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between' }}>
                      <div><div className="ap-ec-icon">{ev.icon}</div><div className="ap-ec-title">{ev.title}</div><div className="ap-ec-date">{ev.date}</div></div>
                      <span className={`ap-ec-status-pill ${ev.statusCls}`}>{ev.statusLabel}</span>
                    </div>
                  </div>
                  <div className="ap-ec-body">
                    <div className="ap-ec-line-items">
                      {ev.items.map((item, j) => (
                        <div key={j} className="ap-ec-item">
                          <div className="ap-ec-item-name"><span>{item.icon}</span>{item.name}</div>
                          <div className="ap-ec-item-qty">{item.qty}</div>
                          <div className="ap-ec-item-price">{item.price}</div>
                        </div>
                      ))}
                    </div>
                    <div className="ap-ec-divider" />
                    <div className="ap-ec-total"><div className="ap-ec-total-label">{ev.footer}</div><div className="ap-ec-total-val">{ev.total}</div></div>
                  </div>
                  <div className="ap-ec-footer">
                    <button className="ap-ec-btn ap-ec-btn-g" onClick={() => setModal(ev.modal)}>{ev.orderBtn}</button>
                    <button className="ap-ec-btn ap-ec-btn-ghost" onClick={() => showToast('Term-by-term order options coming soon.')}>Term by term</button>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Office & non-academic */}
        {show('office') && (
          <div>
            <div className="ap-sec-head">
              <div>
                <div className="ap-sec-title">Office & non-academic needs</div>
                <div className="ap-sec-sub">Housekeeping, health safety, printing, administration</div>
              </div>
            </div>
            <div className="ap-office-grid">
              {OFFICE_CARDS.map((c, i) => (
                <div key={i} className="ap-office-card" onClick={() => setModal(c.modal)}>
                  <div className="ap-office-icon">{c.icon}</div>
                  <div className="ap-office-title">{c.title}</div>
                  <div className="ap-office-sub">{c.sub}</div>
                  <div className="ap-office-amount">{c.amount}</div>
                  <div className="ap-office-footer">
                    <span className={`ck-status ${c.statusCls}`}>{c.statusLabel}</span>
                    <button className="ap-office-action" onClick={e => { e.stopPropagation(); setPanel?.('catalog'); }}>{c.action}</button>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Firefighting → plan */}
        {show('ff') && (
          <div>
            <div className="ap-sec-head">
              <div>
                <div className="ap-sec-title">Firefighting → convert to plan</div>
                <div className="ap-sec-sub">
                  {ffRequests.length > 0
                    ? `${ffRequests.length} active FF request${ffRequests.length !== 1 ? 's' : ''} · plan recurring items now to avoid emergency premium`
                    : '3 active FF requests · 2 recurring patterns detected — plan them now to avoid emergency premium'}
                </div>
              </div>
            </div>
            <div className="ap-table-card">
              <div className="ck-table-wrap">
              <table className="ap-plan-table">
                <thead>
                  <tr><th>FF Request</th><th>Category</th><th>Pattern</th><th>Est. amount</th><th>Status</th><th>Recommendation</th><th></th></tr>
                </thead>
                <tbody>
                  {ffRequests.slice(0, 4).map(req => (
                    <tr key={req.code}>
                      <td><div style={{ fontWeight: 700 }}>{req.code}</div><div style={{ fontSize: 10, color: 'var(--ink3)' }}>{req.description ?? req.category ?? '—'}</div></td>
                      <td>{req.category ?? '—'}</td>
                      <td><span className="ck-status sgr">New</span></td>
                      <td className="mono">{req.estimatedAmount ? `₹${req.estimatedAmount.toLocaleString('en-IN')}` : '—'}</td>
                      <td><span className={`ck-status ${req.status === 'OPEN' ? 'sr' : req.status === 'IN_REVIEW' ? 'sb2' : 'sam'}`}>{req.status}</span></td>
                      <td style={{ fontSize: 11, color: 'var(--ink3)' }}>Monitor — review for patterns</td>
                      <td><button className="ap-btn ap-btn-ghost ap-btn-sm" onClick={() => setPanel?.('ff-dashboard')}>View FF</button></td>
                    </tr>
                  ))}
                  {/* Fixture rows — shown when no real FF requests */}
                  {ffRequests.length === 0 && (
                    <>
                      <tr>
                        <td><div style={{ fontWeight: 700 }}>FF-2025-009</div><div style={{ fontSize: 10, color: 'var(--ink3)' }}>Projector bulb replacement</div></td>
                        <td>Electronics</td><td><span className="ck-status sr">3rd time</span></td>
                        <td className="mono">₹18,000</td><td><span className="ck-status sam">Awaiting approval</span></td>
                        <td style={{ fontSize: 11, color: 'var(--g)', fontWeight: 600 }}>Add as AMC line item in plan</td>
                        <td><button className="ap-btn ap-btn-g ap-btn-sm" onClick={() => setModal({ title: 'Add to plan: Projector AMC', desc: 'Convert recurring projector replacement into an annual maintenance contract. ₹18,000/yr covers all bulbs and servicing.', amount: '₹18,000', type: 'supply', deadline: 'Jun 1' })}>Add to plan</button></td>
                      </tr>
                      <tr>
                        <td><div style={{ fontWeight: 700 }}>FF-2025-006</div><div style={{ fontSize: 10, color: 'var(--ink3)' }}>Library shelving</div></td>
                        <td>Furniture</td><td><span className="ck-status sr">2nd time</span></td>
                        <td className="mono">₹75,000</td><td><span className="ck-status sb2">Quotes submitted</span></td>
                        <td style={{ fontSize: 11, color: 'var(--g)', fontWeight: 600 }}>Plan annual furniture budget</td>
                        <td><button className="ap-btn ap-btn-g ap-btn-sm" onClick={() => setModal({ title: 'Add to plan: Furniture budget', desc: 'Create an annual furniture budget line item to cover recurring needs. Avoids repeated firefighting.', amount: '₹75,000', type: 'supply', deadline: 'Jul 1' })}>Add to plan</button></td>
                      </tr>
                      <tr>
                        <td><div style={{ fontWeight: 700 }}>Recurring pattern</div><div style={{ fontSize: 10, color: 'var(--ink3)' }}>Lab equipment (suggested)</div></td>
                        <td>Lab</td><td><span className="ck-status sr">2 yrs running</span></td>
                        <td className="mono">₹1,80,000</td><td><span className="ck-status sg">Suggested (AI)</span></td>
                        <td style={{ fontSize: 11, color: 'var(--g)', fontWeight: 600 }}>Pre-order Term 1 · save 18%</td>
                        <td><button className="ap-btn ap-btn-g ap-btn-sm" onClick={() => setModal({ title: 'Add to plan: Lab equipment', desc: 'Accept AI suggestion and pre-order chemistry + physics consumables for Term 1. Avoids 18% emergency premium.', amount: '₹1,80,000', type: 'ff', deadline: 'Jun 15' })}>Accept & plan</button></td>
                      </tr>
                      <tr>
                        <td><div style={{ fontWeight: 700 }}>FF-2025-008</div><div style={{ fontSize: 10, color: 'var(--ink3)' }}>Canteen exhaust fan</div></td>
                        <td>Civil / infra</td><td><span className="ck-status sgr">First time</span></td>
                        <td className="mono">₹32,000</td><td><span className="ck-status sr">Draft</span></td>
                        <td style={{ fontSize: 11, color: 'var(--ink3)' }}>Monitor — single occurrence</td>
                        <td><button className="ap-btn ap-btn-ghost ap-btn-sm" onClick={() => setPanel?.('ff-dashboard')}>Keep as FF</button></td>
                      </tr>
                    </>
                  )}
                </tbody>
              </table>
              </div>
            </div>
          </div>
        )}

        {/* School package */}
        {show('pkg') && (
          <div className="ap-pkg-card">
            <div className="ap-pkg-icon">🏫</div>
            <div className="ap-pkg-text">
              <h3>New school starter package</h3>
              <p>For schools onboarding to Custoking — a pre-configured annual plan template based on school size, board, and location. Includes pricing, quantities, order windows, and event templates. Customise and go live in minutes.</p>
              <div className="ap-pkg-items">
                {['Student supplies auto-configured', '3 exam windows', "Teacher's Day ₹30,000 budget", 'Annual Day template', 'Office & housekeeping', 'Catalog prices pre-fetched', 'FF patterns imported'].map(item => (
                  <span key={item} className="ap-pkg-item">{item}</span>
                ))}
              </div>
            </div>
            <div className="ap-pkg-actions">
              <div className="ap-pkg-price">₹0</div>
              <div className="ap-pkg-price-sub">Free onboarding · prices from catalog</div>
              <button className="ap-btn ap-btn-g" style={{ width: '100%', marginTop: 8, padding: 10, borderRadius: 8 }} onClick={() => showToast('Launching new school onboarding wizard...')}>Create package →</button>
              <button className="ap-btn ap-btn-ghost" style={{ width: '100%', marginTop: 4, padding: 10, borderRadius: 8, background: 'rgba(255,255,255,.1)', borderColor: 'rgba(255,255,255,.2)', color: '#fff' }} onClick={() => showToast('Preview template for new school...')}>Preview template</button>
            </div>
          </div>
        )}

      </div>{/* /ap-body */}

      {/* Item detail modal */}
      {modal && (
        <Modal
          title={modal.title}
          onClose={() => setModal(null)}
          footer={
            <>
              <button className="ck-btn ck-btn-ghost" onClick={() => setModal(null)}>Close</button>
              <button
                className="ck-btn ck-btn-g"
                disabled={planBusy}
                onClick={async () => {
                  if (modal) {
                    await addPlanItem(modal.title, modal.desc, parseAmountStr(modal.amount));
                  }
                  setModal(null);
                  setPanel?.('catalog');
                }}
              >{planBusy ? 'Adding…' : 'Add to plan & order'}</button>
            </>
          }
        >
          <div className="ap-modal-row"><div className="ap-modal-key">Type</div><div className="ap-modal-val" style={{ color: TYPE_COLOR[modal.type] ?? 'var(--ink2)' }}>{TYPE_LABEL[modal.type] ?? modal.type}</div></div>
          <div className="ap-modal-row"><div className="ap-modal-key">Details</div><div className="ap-modal-val" style={{ maxWidth: 300, textAlign: 'right', lineHeight: 1.5 }}>{modal.desc}</div></div>
          <div className="ap-modal-row"><div className="ap-modal-key">Estimated amount</div><div className="ap-modal-val" style={{ fontSize: 18, fontFamily: "'Fraunces',serif", color: 'var(--g)' }}>{modal.amount}</div></div>
          <div className="ap-modal-row"><div className="ap-modal-key">Order / action by</div><div className="ap-modal-val">{modal.deadline}</div></div>
        </Modal>
      )}

      {/* Toast */}
      {toast && (
        <div className="ck-command-toast ok">
          <span className="ck-command-toast-icon ok">✓</span>
          <span className="ck-command-toast-text">{toast}</span>
        </div>
      )}
    </ModuleShell>
  );
}

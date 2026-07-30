import { useMemo, useState } from 'react';
import {
  AlertTriangle,
  ArrowUpRight,
  BadgePercent,
  Bell,
  BookOpen,
  Building2,
  CalendarClock,
  CalendarDays,
  Check,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  CircleDollarSign,
  Clock3,
  Copy,
  CreditCard,
  Download,
  FileText,
  Filter,
  GraduationCap,
  GripVertical,
  IndianRupee,
  LayoutDashboard,
  MoreHorizontal,
  PanelRightOpen,
  Pencil,
  Plus,
  Receipt,
  Redo2,
  Search,
  Send,
  Settings,
  ShieldCheck,
  SlidersHorizontal,
  Sparkles,
  Undo2,
  UserRound,
  Users,
  X,
} from 'lucide-react';

type Screen = 'fees' | 'configuration' | 'timetable';
type TimetableView = 'Class' | 'Teacher' | 'Room';

const feeRows = [
  { name: 'Aarav Mehta', admission: 'ADM-2418', className: 'Grade 8 · A', nextDue: '05 Aug', due: '₹18,500', status: 'Overdue', overdue: '18 days' },
  { name: 'Diya Sharma', admission: 'ADM-2472', className: 'Grade 7 · B', nextDue: '10 Aug', due: '₹12,250', status: 'Partial', overdue: '₹8,000 paid' },
  { name: 'Kabir Nair', admission: 'ADM-2391', className: 'Grade 9 · A', nextDue: '12 Aug', due: '₹21,000', status: 'Due soon', overdue: 'In 4 days' },
  { name: 'Meera Iyer', admission: 'ADM-2506', className: 'Grade 6 · C', nextDue: '15 Aug', due: '₹9,750', status: 'Scheduled', overdue: 'AutoPay enabled' },
];

const feePlans = [
  { id: 'primary', name: 'Primary School', classes: 'Grades 1–5', students: 642, total: '₹67,500', state: 'Published', updated: '24 Jul' },
  { id: 'middle', name: 'Middle School', classes: 'Grades 6–8', students: 388, total: '₹82,500', state: 'Draft', updated: '29 Jul' },
  { id: 'senior', name: 'Senior School', classes: 'Grades 9–12', students: 401, total: '₹96,000', state: 'Published', updated: '18 Jul' },
];

const planItems: Record<string, Array<{ name: string; frequency: string; amount: string; required: boolean }>> = {
  primary: [
    { name: 'Tuition fee', frequency: 'Quarterly', amount: '₹48,000', required: true },
    { name: 'Annual resource fee', frequency: 'Annual', amount: '₹8,500', required: true },
    { name: 'Technology & LMS', frequency: 'Annual', amount: '₹6,000', required: true },
    { name: 'Activity programme', frequency: 'Annual', amount: '₹5,000', required: false },
  ],
  middle: [
    { name: 'Tuition fee', frequency: 'Quarterly', amount: '₹60,000', required: true },
    { name: 'Laboratory fee', frequency: 'Annual', amount: '₹7,500', required: true },
    { name: 'Technology & LMS', frequency: 'Annual', amount: '₹7,000', required: true },
    { name: 'Activity programme', frequency: 'Annual', amount: '₹8,000', required: false },
  ],
  senior: [
    { name: 'Tuition fee', frequency: 'Quarterly', amount: '₹72,000', required: true },
    { name: 'Laboratory fee', frequency: 'Annual', amount: '₹10,000', required: true },
    { name: 'Technology & LMS', frequency: 'Annual', amount: '₹7,000', required: true },
    { name: 'Career programme', frequency: 'Annual', amount: '₹7,000', required: false },
  ],
};

const timetableRows = [
  {
    day: 'Monday',
    date: '03 Aug',
    lessons: [
      { subject: 'Mathematics', teacher: 'A. Verma', room: '8A', tone: 'blue' },
      { subject: 'English', teacher: 'R. D’Souza', room: '8A', tone: 'violet' },
      { subject: 'Physics', teacher: 'N. Menon', room: 'Lab 2', tone: 'teal' },
      { subject: 'Lunch', teacher: '', room: '', tone: 'break' },
      { subject: 'History', teacher: 'S. Khan', room: '8A', tone: 'amber' },
      { subject: 'Hindi', teacher: 'P. Joshi', room: '8A', tone: 'rose' },
      { subject: 'Sports', teacher: 'V. Rao', room: 'Field', tone: 'green' },
    ],
  },
  {
    day: 'Tuesday',
    date: '04 Aug',
    lessons: [
      { subject: 'Chemistry', teacher: 'K. Pillai', room: 'Lab 1', tone: 'teal' },
      { subject: 'Mathematics', teacher: 'A. Verma', room: '8A', tone: 'blue' },
      { subject: 'Geography', teacher: 'M. Roy', room: '8A', tone: 'amber' },
      { subject: 'Lunch', teacher: '', room: '', tone: 'break' },
      { subject: 'English', teacher: 'R. D’Souza', room: '8A', tone: 'violet' },
      { subject: 'Computer Sc.', teacher: 'T. Shah', room: 'Lab 3', tone: 'cyan' },
      { subject: 'Art', teacher: 'I. Bose', room: 'Studio', tone: 'rose' },
    ],
  },
  {
    day: 'Wednesday',
    date: '05 Aug',
    lessons: [
      { subject: 'English', teacher: 'R. D’Souza', room: '8A', tone: 'violet' },
      { subject: 'Biology', teacher: 'N. Menon', room: 'Lab 2', tone: 'green' },
      { subject: 'Hindi', teacher: 'P. Joshi', room: '8A', tone: 'rose' },
      { subject: 'Lunch', teacher: '', room: '', tone: 'break' },
      { subject: 'Mathematics', teacher: 'A. Verma', room: '8A', tone: 'blue' },
      { subject: 'Library', teacher: 'D. Sen', room: 'Library', tone: 'amber' },
      { subject: 'Club period', teacher: 'Multiple', room: 'Various', tone: 'slate' },
    ],
  },
  {
    day: 'Thursday',
    date: '06 Aug',
    lessons: [
      { subject: 'Physics', teacher: 'N. Menon', room: 'Lab 2', tone: 'teal' },
      { subject: 'History', teacher: 'S. Khan', room: '8A', tone: 'amber' },
      { subject: 'Mathematics', teacher: 'A. Verma', room: '8A', tone: 'blue' },
      { subject: 'Lunch', teacher: '', room: '', tone: 'break' },
      { subject: 'Computer Sc.', teacher: 'T. Shah', room: 'Lab 3', tone: 'cyan', conflict: true },
      { subject: 'English', teacher: 'R. D’Souza', room: '8A', tone: 'violet' },
      { subject: 'Sports', teacher: 'V. Rao', room: 'Field', tone: 'green' },
    ],
  },
  {
    day: 'Friday',
    date: '07 Aug',
    lessons: [
      { subject: 'Mathematics', teacher: 'A. Verma', room: '8A', tone: 'blue' },
      { subject: 'Geography', teacher: 'M. Roy', room: '8A', tone: 'amber' },
      { subject: 'Chemistry', teacher: 'K. Pillai', room: 'Lab 1', tone: 'teal' },
      { subject: 'Lunch', teacher: '', room: '', tone: 'break' },
      { subject: 'Hindi', teacher: 'P. Joshi', room: '8A', tone: 'rose' },
      { subject: 'Class circle', teacher: 'A. Verma', room: '8A', tone: 'slate' },
      { subject: 'Activity', teacher: 'Multiple', room: 'Various', tone: 'green' },
    ],
  },
];

function IconButton({ label, children, active = false }: { label: string; children: React.ReactNode; active?: boolean }) {
  return (
    <button className={`dpx-icon-button${active ? ' is-active' : ''}`} type="button" title={label} aria-label={label}>
      {children}
    </button>
  );
}

function AppNavButton({
  active,
  icon,
  label,
  onClick,
}: {
  active: boolean;
  icon: React.ReactNode;
  label: string;
  onClick: () => void;
}) {
  return (
    <button className={`dpx-app-nav-item${active ? ' is-active' : ''}`} type="button" onClick={onClick}>
      {icon}
      <span>{label}</span>
      {active ? <span className="dpx-nav-active-mark" /> : null}
    </button>
  );
}

function Metric({
  label,
  value,
  note,
  tone = 'default',
  icon,
}: {
  label: string;
  value: string;
  note: string;
  tone?: 'default' | 'green' | 'amber' | 'red';
  icon: React.ReactNode;
}) {
  return (
    <div className={`dpx-metric dpx-metric-${tone}`}>
      <div className="dpx-metric-label">
        <span className="dpx-metric-icon">{icon}</span>
        {label}
      </div>
      <strong>{value}</strong>
      <span>{note}</span>
    </div>
  );
}

function FeesOverview({ onOpenConfiguration }: { onOpenConfiguration: () => void }) {
  const [status, setStatus] = useState('All statuses');

  return (
    <>
      <header className="dpx-page-header">
        <div>
          <div className="dpx-eyebrow">Finance operations</div>
          <h1>Fee management</h1>
          <p>Collections, receivables, and parent follow-up for 2026–27.</p>
        </div>
        <div className="dpx-header-actions">
          <button className="dpx-button dpx-button-secondary" type="button" onClick={onOpenConfiguration}>
            <SlidersHorizontal size={16} />
            Configure fees
          </button>
          <button className="dpx-button dpx-button-primary" type="button">
            <Plus size={16} />
            Record payment
          </button>
        </div>
      </header>

      <div className="dpx-context-bar">
        <div className="dpx-context-item">
          <CalendarDays size={16} />
          <span>Academic year</span>
          <strong>2026–27</strong>
          <ChevronDown size={14} />
        </div>
        <div className="dpx-context-divider" />
        <div className="dpx-context-item">
          <Building2 size={16} />
          <span>Campus</span>
          <strong>Main campus</strong>
          <ChevronDown size={14} />
        </div>
        <div className="dpx-context-spacer" />
        <span className="dpx-sync-state"><CheckCircle2 size={14} /> Reconciled at 10:42 AM</span>
      </div>

      <section className="dpx-metric-grid" aria-label="Fee summary">
        <Metric label="Collection rate" value="78.4%" note="+4.6% from last month" tone="green" icon={<ArrowUpRight size={15} />} />
        <Metric label="Collected" value="₹2.84 Cr" note="of ₹3.62 Cr billed" icon={<IndianRupee size={15} />} />
        <Metric label="Receivables" value="₹78.3 L" note="612 student accounts" tone="amber" icon={<CalendarClock size={15} />} />
        <Metric label="Overdue" value="₹24.6 L" note="138 need follow-up" tone="red" icon={<AlertTriangle size={15} />} />
      </section>

      <section className="dpx-fee-insights">
        <div className="dpx-surface dpx-collection-panel">
          <div className="dpx-section-heading">
            <div>
              <h2>Collection pulse</h2>
              <p>Monthly billed versus collected</p>
            </div>
            <button className="dpx-inline-select" type="button">Last 6 months <ChevronDown size={14} /></button>
          </div>
          <div className="dpx-chart-legend">
            <span><i className="dpx-legend-dot dpx-dot-billed" /> Billed</span>
            <span><i className="dpx-legend-dot dpx-dot-collected" /> Collected</span>
          </div>
          <div className="dpx-bar-chart" aria-label="Monthly collection chart">
            {[
              ['Mar', 48, 42],
              ['Apr', 76, 69],
              ['May', 58, 53],
              ['Jun', 90, 76],
              ['Jul', 72, 62],
              ['Aug', 54, 36],
            ].map(([month, billed, collected]) => (
              <div className="dpx-bar-group" key={month}>
                <div className="dpx-bars">
                  <span className="dpx-bar dpx-bar-billed" style={{ height: `${billed}%` }} />
                  <span className="dpx-bar dpx-bar-collected" style={{ height: `${collected}%` }} />
                </div>
                <span>{month}</span>
              </div>
            ))}
          </div>
        </div>

        <div className="dpx-surface dpx-aging-panel">
          <div className="dpx-section-heading">
            <div>
              <h2>Receivables aging</h2>
              <p>₹78.3 L total outstanding</p>
            </div>
            <IconButton label="Open aging report"><ArrowUpRight size={17} /></IconButton>
          </div>
          <div className="dpx-aging-list">
            {[
              ['Not due', '₹41.8 L', 53, 'green'],
              ['1–15 days', '₹18.2 L', 23, 'blue'],
              ['16–30 days', '₹10.4 L', 13, 'amber'],
              ['30+ days', '₹7.9 L', 10, 'red'],
            ].map(([label, value, width, tone]) => (
              <div className="dpx-aging-row" key={label}>
                <div><span>{label}</span><strong>{value}</strong></div>
                <div className="dpx-progress-track"><span className={`dpx-progress-${tone}`} style={{ width: `${width}%` }} /></div>
              </div>
            ))}
          </div>
          <button className="dpx-text-button" type="button">View 138 overdue accounts <ChevronRight size={15} /></button>
        </div>

        <div className="dpx-surface dpx-actions-panel">
          <div className="dpx-section-heading">
            <div>
              <h2>Today</h2>
              <p>Cash desk activity</p>
            </div>
            <span className="dpx-live-pill"><i /> Live</span>
          </div>
          <div className="dpx-today-total">
            <span>Collected</span>
            <strong>₹4,82,750</strong>
            <small>47 transactions</small>
          </div>
          <div className="dpx-payment-modes">
            <div><CreditCard size={16} /><span>Online / UPI</span><strong>₹3.21 L</strong></div>
            <div><CircleDollarSign size={16} /><span>Cash</span><strong>₹1.08 L</strong></div>
            <div><FileText size={16} /><span>Cheque / bank</span><strong>₹53.7 K</strong></div>
          </div>
        </div>
      </section>

      <section className="dpx-surface dpx-ledger">
        <div className="dpx-ledger-toolbar">
          <div>
            <h2>Accounts requiring attention</h2>
            <p>Prioritised by due date and amount</p>
          </div>
          <div className="dpx-table-actions">
            <label className="dpx-search">
              <Search size={16} />
              <input aria-label="Search student accounts" placeholder="Search student or admission no." />
            </label>
            <button className="dpx-filter-button" type="button" onClick={() => setStatus(status === 'All statuses' ? 'Overdue' : 'All statuses')}>
              <Filter size={15} />
              {status}
              <ChevronDown size={14} />
            </button>
            <IconButton label="Export accounts"><Download size={17} /></IconButton>
          </div>
        </div>
        <div className="dpx-table-wrap">
          <table className="dpx-data-table">
            <thead>
              <tr>
                <th>Student</th>
                <th>Class</th>
                <th>Next due</th>
                <th>Outstanding</th>
                <th>Status</th>
                <th>Follow-up</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {feeRows.filter((row) => status === 'All statuses' || row.status === status).map((row) => (
                <tr key={row.admission}>
                  <td>
                    <div className="dpx-student-cell">
                      <span className="dpx-avatar">{row.name.split(' ').map((part) => part[0]).join('')}</span>
                      <div><strong>{row.name}</strong><span>{row.admission}</span></div>
                    </div>
                  </td>
                  <td>{row.className}</td>
                  <td>{row.nextDue}</td>
                  <td className="dpx-money-cell">{row.due}</td>
                  <td><span className={`dpx-status dpx-status-${row.status.toLowerCase().replace(' ', '-')}`}>{row.status}</span></td>
                  <td className="dpx-muted-cell">{row.overdue}</td>
                  <td>
                    <div className="dpx-row-actions">
                      <IconButton label={`Send reminder to ${row.name}`}><Send size={15} /></IconButton>
                      <IconButton label={`More actions for ${row.name}`}><MoreHorizontal size={16} /></IconButton>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </>
  );
}

function FeeConfiguration() {
  const [activePlan, setActivePlan] = useState('middle');
  const [configTab, setConfigTab] = useState('Fee plans');
  const selectedPlan = feePlans.find((plan) => plan.id === activePlan) ?? feePlans[0];
  const selectedItems = planItems[activePlan];

  return (
    <>
      <header className="dpx-page-header">
        <div>
          <div className="dpx-eyebrow">Fee management / Configuration</div>
          <h1>Fee configuration</h1>
          <p>Build, review, and publish billing policy for the academic year.</p>
        </div>
        <div className="dpx-header-actions">
          <button className="dpx-button dpx-button-secondary" type="button">
            <Copy size={16} />
            Copy from 2025–26
          </button>
          <button className="dpx-button dpx-button-primary" type="button">
            <Plus size={16} />
            New fee plan
          </button>
        </div>
      </header>

      <div className="dpx-config-tabs" role="tablist" aria-label="Fee configuration sections">
        {[
          ['Fee plans', <FileText size={16} />],
          ['Concessions', <BadgePercent size={16} />],
          ['Late-fee rules', <CalendarClock size={16} />],
          ['Payment settings', <CreditCard size={16} />],
          ['Receipt & tax', <Receipt size={16} />],
        ].map(([label, icon]) => (
          <button
            className={configTab === label ? 'is-active' : ''}
            type="button"
            role="tab"
            aria-selected={configTab === label}
            key={String(label)}
            onClick={() => setConfigTab(String(label))}
          >
            {icon}
            {label}
          </button>
        ))}
      </div>

      {configTab === 'Fee plans' ? (
        <section className="dpx-config-workbench">
          <aside className="dpx-config-plans">
            <div className="dpx-pane-heading">
              <div><h2>Fee plans</h2><span>3 plans · 1 draft</span></div>
              <IconButton label="Add fee plan"><Plus size={17} /></IconButton>
            </div>
            <label className="dpx-search dpx-search-compact">
              <Search size={15} />
              <input aria-label="Search fee plans" placeholder="Search plans" />
            </label>
            <div className="dpx-plan-list">
              {feePlans.map((plan) => (
                <button className={`dpx-plan-item${activePlan === plan.id ? ' is-active' : ''}`} type="button" key={plan.id} onClick={() => setActivePlan(plan.id)}>
                  <span className="dpx-plan-icon"><GraduationCap size={17} /></span>
                  <span className="dpx-plan-copy">
                    <strong>{plan.name}</strong>
                    <small>{plan.classes} · {plan.students} students</small>
                    <span>
                      <i className={`dpx-state-dot dpx-state-${plan.state.toLowerCase()}`} />
                      {plan.state}
                      <em>Updated {plan.updated}</em>
                    </span>
                  </span>
                  <ChevronRight size={16} />
                </button>
              ))}
            </div>
            <div className="dpx-config-note">
              <ShieldCheck size={18} />
              <p><strong>Year-bound configuration</strong><span>Published plans are locked. Create a revision to make changes.</span></p>
            </div>
          </aside>

          <div className="dpx-config-editor">
            <div className="dpx-editor-header">
              <div>
                <div className="dpx-editor-title-row">
                  <h2>{selectedPlan.name}</h2>
                  <span className={`dpx-status dpx-status-${selectedPlan.state.toLowerCase()}`}>{selectedPlan.state}</span>
                </div>
                <p>{selectedPlan.classes} · Academic year 2026–27</p>
              </div>
              <div className="dpx-header-actions">
                <button className="dpx-button dpx-button-secondary dpx-button-small" type="button"><Pencil size={15} /> Edit details</button>
                {selectedPlan.state === 'Draft' ? (
                  <button className="dpx-button dpx-button-primary dpx-button-small" type="button"><Check size={15} /> Review & publish</button>
                ) : (
                  <button className="dpx-button dpx-button-secondary dpx-button-small" type="button"><Copy size={15} /> Create revision</button>
                )}
              </div>
            </div>

            <div className="dpx-plan-facts">
              <div><span>Annual total</span><strong>{selectedPlan.total}</strong></div>
              <div><span>Billing cycle</span><strong>Quarterly</strong></div>
              <div><span>Students assigned</span><strong>{selectedPlan.students}</strong></div>
              <div><span>First due date</span><strong>10 Apr 2026</strong></div>
            </div>

            <div className="dpx-fee-head-section">
              <div className="dpx-section-heading">
                <div><h3>Fee heads</h3><p>Items that make up this plan</p></div>
                <button className="dpx-button dpx-button-secondary dpx-button-small" type="button"><Plus size={15} /> Add fee head</button>
              </div>
              <div className="dpx-fee-head-table">
                <div className="dpx-fee-head-row dpx-fee-head-header">
                  <span>Fee head</span><span>Frequency</span><span>Optional</span><span>Amount</span><span />
                </div>
                {selectedItems.map((item) => (
                  <div className="dpx-fee-head-row" key={item.name}>
                    <span className="dpx-fee-name"><GripVertical size={15} /><i><IndianRupee size={15} /></i><strong>{item.name}</strong></span>
                    <span>{item.frequency}</span>
                    <span><i className={`dpx-toggle${item.required ? '' : ' is-on'}`}><b /></i>{item.required ? 'Required' : 'Optional'}</span>
                    <strong>{item.amount}</strong>
                    <IconButton label={`Edit ${item.name}`}><MoreHorizontal size={16} /></IconButton>
                  </div>
                ))}
                <div className="dpx-fee-total-row"><span>Annual plan total</span><strong>{selectedPlan.total}</strong></div>
              </div>
            </div>

            <div className="dpx-installment-section">
              <div className="dpx-section-heading">
                <div><h3>Installment schedule</h3><p>4 equal installments · 15-day grace period</p></div>
                <button className="dpx-text-button" type="button">Edit schedule <Pencil size={14} /></button>
              </div>
              <div className="dpx-installment-timeline">
                {[
                  ['Q1', '10 Apr 2026', '25%'],
                  ['Q2', '10 Jul 2026', '25%'],
                  ['Q3', '10 Oct 2026', '25%'],
                  ['Q4', '10 Jan 2027', '25%'],
                ].map(([quarter, date, portion], index) => (
                  <div className="dpx-installment" key={quarter}>
                    <span className="dpx-installment-node">{index + 1}</span>
                    <div><strong>{quarter}</strong><span>{date}</span><small>{portion} of annual total</small></div>
                  </div>
                ))}
              </div>
            </div>
          </div>

          <aside className="dpx-config-summary">
            <div className="dpx-pane-heading">
              <div><h2>Applied policy</h2><span>Live plan preview</span></div>
              <PanelRightOpen size={18} />
            </div>
            <div className="dpx-policy-block">
              <span className="dpx-policy-label">Assignment</span>
              <div className="dpx-policy-line"><GraduationCap size={16} /><span><strong>{selectedPlan.classes}</strong><small>All sections · New and existing students</small></span></div>
            </div>
            <div className="dpx-policy-block">
              <span className="dpx-policy-label">Concessions</span>
              <div className="dpx-policy-line"><BadgePercent size={16} /><span><strong>3 automatic rules</strong><small>Sibling, staff ward, early payment</small></span></div>
              <button className="dpx-text-button" type="button" onClick={() => setConfigTab('Concessions')}>View rules <ChevronRight size={14} /></button>
            </div>
            <div className="dpx-policy-block">
              <span className="dpx-policy-label">Late fee</span>
              <div className="dpx-policy-line"><CalendarClock size={16} /><span><strong>Slab-based penalty</strong><small>₹250 after 15 days · ₹500 after 30</small></span></div>
            </div>
            <div className="dpx-policy-block">
              <span className="dpx-policy-label">Parent payment options</span>
              <div className="dpx-method-pills"><span>UPI</span><span>Cards</span><span>Net banking</span><span>Cheque</span></div>
            </div>
            <div className="dpx-impact-box">
              <span>Estimated annual billing</span>
              <strong>₹3.20 Cr</strong>
              <small>{selectedPlan.students} students before concessions</small>
            </div>
          </aside>
        </section>
      ) : (
        <section className="dpx-config-placeholder dpx-surface">
          <div className="dpx-placeholder-icon">
            {configTab === 'Concessions' ? <BadgePercent size={24} /> : configTab === 'Late-fee rules' ? <CalendarClock size={24} /> : configTab === 'Payment settings' ? <CreditCard size={24} /> : <Receipt size={24} />}
          </div>
          <div>
            <span>Configuration workspace</span>
            <h2>{configTab}</h2>
            <p>This secondary configuration view is represented in the prototype to validate the information architecture.</p>
          </div>
          <button className="dpx-button dpx-button-primary" type="button"><Plus size={16} /> Add rule</button>
        </section>
      )}
    </>
  );
}

function TimetableStudio() {
  const [view, setView] = useState<TimetableView>('Class');
  const [conflictResolved, setConflictResolved] = useState(false);
  const [issuesOpen, setIssuesOpen] = useState(true);
  const [selectedCell, setSelectedCell] = useState('');
  const conflicts = conflictResolved ? 0 : 1;
  const health = conflictResolved ? 100 : 96;

  const displayRows = useMemo(() => {
    if (!conflictResolved) return timetableRows;
    return timetableRows.map((row) => ({
      ...row,
      lessons: row.lessons.map((lesson) => lesson.conflict ? { ...lesson, conflict: false, teacher: 'T. Shah', room: 'Lab 3B' } : lesson),
    }));
  }, [conflictResolved]);

  return (
    <>
      <header className="dpx-page-header dpx-timetable-header">
        <div>
          <div className="dpx-eyebrow">Academic planning</div>
          <h1>Timetable studio</h1>
          <p>Build, validate, and publish schedules across classes, teachers, and rooms.</p>
        </div>
        <div className="dpx-header-actions">
          <div className="dpx-save-state"><CheckCircle2 size={15} /> All changes saved</div>
          <button className="dpx-button dpx-button-secondary" type="button"><FileText size={16} /> Export</button>
          <button className="dpx-button dpx-button-primary" type="button"><Check size={16} /> Publish timetable</button>
        </div>
      </header>

      <div className="dpx-timetable-controls">
        <div className="dpx-segmented" aria-label="Timetable view">
          {(['Class', 'Teacher', 'Room'] as TimetableView[]).map((item) => (
            <button className={view === item ? 'is-active' : ''} type="button" key={item} onClick={() => setView(item)}>
              {item === 'Class' ? <GraduationCap size={15} /> : item === 'Teacher' ? <UserRound size={15} /> : <Building2 size={15} />}
              {item}
            </button>
          ))}
        </div>
        <div className="dpx-divider-vertical" />
        <button className="dpx-control-select" type="button"><CalendarDays size={15} /><span>2026–27</span><ChevronDown size={14} /></button>
        <button className="dpx-control-select dpx-control-wide" type="button">
          {view === 'Class' ? <GraduationCap size={15} /> : view === 'Teacher' ? <UserRound size={15} /> : <Building2 size={15} />}
          <span>{view === 'Class' ? 'Grade 8 · Section A' : view === 'Teacher' ? 'Anita Verma · Mathematics' : 'Science Lab 2'}</span>
          <ChevronDown size={14} />
        </button>
        <button className="dpx-control-select" type="button"><FileText size={15} /><span>Working draft v3</span><ChevronDown size={14} /></button>
        <div className="dpx-control-spacer" />
        <IconButton label="Undo"><Undo2 size={17} /></IconButton>
        <IconButton label="Redo"><Redo2 size={17} /></IconButton>
        <button className={`dpx-issue-toggle${issuesOpen ? ' is-active' : ''}`} type="button" onClick={() => setIssuesOpen(!issuesOpen)}>
          <AlertTriangle size={15} />
          {conflicts} {conflicts === 1 ? 'issue' : 'issues'}
        </button>
      </div>

      <section className={`dpx-timetable-workbench${issuesOpen ? ' has-issues' : ''}`}>
        <aside className="dpx-schedule-rail">
          <div className="dpx-pane-heading">
            <div><h2>To schedule</h2><span>7 periods remaining</span></div>
            <IconButton label="Filter unscheduled lessons"><Filter size={16} /></IconButton>
          </div>
          <label className="dpx-search dpx-search-compact">
            <Search size={15} />
            <input aria-label="Search unscheduled lessons" placeholder="Search subjects" />
          </label>
          <div className="dpx-unscheduled-list">
            {[
              ['Mathematics', 'A. Verma', '2 periods', 'blue'],
              ['Physics lab', 'N. Menon', 'Double period', 'teal'],
              ['English', 'R. D’Souza', '1 period', 'violet'],
              ['Sports', 'V. Rao', '2 periods', 'green'],
            ].map(([subject, teacher, count, tone]) => (
              <button className={`dpx-unscheduled dpx-lesson-${tone}`} type="button" key={subject}>
                <GripVertical size={14} />
                <span><strong>{subject}</strong><small>{teacher}</small></span>
                <em>{count}</em>
              </button>
            ))}
          </div>
          <div className="dpx-rail-summary">
            <span>Weekly allocation</span>
            <div><strong>33 / 40</strong><small>periods placed</small></div>
            <div className="dpx-progress-track"><span className="dpx-progress-green" style={{ width: '82.5%' }} /></div>
          </div>
          <button className="dpx-button dpx-button-ai" type="button">
            <Sparkles size={16} />
            Auto-place remaining
          </button>
        </aside>

        <div className="dpx-schedule-canvas">
          <div className="dpx-schedule-health">
            <div>
              <span className={`dpx-health-score${health === 100 ? ' is-complete' : ''}`}><ShieldCheck size={17} /> {health}% schedule health</span>
              <span>40 periods · 9 subjects · 8 teachers · 3 rooms</span>
            </div>
            <div className="dpx-health-checks">
              <span><Check size={13} /> Subject quotas</span>
              <span><Check size={13} /> Teacher load</span>
              <span className={conflicts ? 'has-warning' : ''}>{conflicts ? <AlertTriangle size={13} /> : <Check size={13} />} Room conflicts</span>
            </div>
          </div>
          <div className="dpx-grid-scroll">
            <div className="dpx-schedule-grid">
              <div className="dpx-grid-corner">Week 14</div>
              {[
                ['P1', '08:00–08:45'],
                ['P2', '08:45–09:30'],
                ['P3', '09:45–10:30'],
                ['Break', '10:30–10:50'],
                ['P4', '10:50–11:35'],
                ['P5', '11:35–12:20'],
                ['P6', '12:20–01:05'],
              ].map(([period, time]) => (
                <div className={`dpx-period-head${period === 'Break' ? ' is-break' : ''}`} key={period}>
                  <strong>{period}</strong><span>{time}</span>
                </div>
              ))}
              {displayRows.flatMap((row) => [
                <div className="dpx-day-head" key={`${row.day}-head`}>
                  <strong>{row.day}</strong><span>{row.date}</span>
                </div>,
                ...row.lessons.map((lesson, index) => {
                  const cellId = `${row.day}-${index}`;
                  return (
                    <button
                      type="button"
                      className={`dpx-lesson dpx-lesson-${lesson.tone}${lesson.conflict ? ' has-conflict' : ''}${selectedCell === cellId ? ' is-selected' : ''}`}
                      key={cellId}
                      onClick={() => setSelectedCell(selectedCell === cellId ? '' : cellId)}
                    >
                      {lesson.tone === 'break' ? (
                        <span className="dpx-break-label"><Clock3 size={14} /> {lesson.subject}</span>
                      ) : (
                        <>
                          <strong>{lesson.subject}</strong>
                          <span>{lesson.teacher}</span>
                          <small>{lesson.room}</small>
                          {lesson.conflict ? <i><AlertTriangle size={12} /> Conflict</i> : null}
                        </>
                      )}
                    </button>
                  );
                }),
              ])}
            </div>
          </div>
        </div>

        {issuesOpen ? (
          <aside className="dpx-issues-panel">
            <div className="dpx-pane-heading">
              <div><h2>Schedule check</h2><span>{conflicts ? '1 issue needs attention' : 'Ready to publish'}</span></div>
              <IconButton label="Close schedule check"><X size={17} /></IconButton>
            </div>
            {conflicts ? (
              <>
                <div className="dpx-issue-summary">
                  <span><AlertTriangle size={18} /></span>
                  <div><strong>1 hard conflict</strong><small>Resolve before publishing</small></div>
                </div>
                <div className="dpx-issue-card">
                  <div className="dpx-issue-card-title"><span>Room double-booked</span><em>Hard conflict</em></div>
                  <p><strong>Computer Lab 3</strong> is assigned to Grade 8A and Grade 9B on Thursday, P4.</p>
                  <div className="dpx-conflict-slots">
                    <span><i className="dpx-swatch dpx-swatch-cyan" /> Grade 8A · Computer Science</span>
                    <span><i className="dpx-swatch dpx-swatch-amber" /> Grade 9B · Robotics</span>
                  </div>
                  <div className="dpx-suggestion">
                    <span><Sparkles size={14} /> Suggested fix</span>
                    <strong>Move Grade 8A to Computer Lab 3B</strong>
                    <small>Available · Capacity 32 · Same building</small>
                  </div>
                  <button className="dpx-button dpx-button-primary dpx-button-full" type="button" onClick={() => setConflictResolved(true)}>
                    <Check size={15} />
                    Apply suggested fix
                  </button>
                  <button className="dpx-text-button dpx-text-button-center" type="button">Choose another room</button>
                </div>
                <div className="dpx-soft-constraints">
                  <h3>Preferences</h3>
                  <div><CheckCircle2 size={15} /><span><strong>Teacher gaps</strong><small>No avoidable gaps</small></span></div>
                  <div><CheckCircle2 size={15} /><span><strong>Subject spread</strong><small>Balanced across week</small></span></div>
                  <div><AlertTriangle size={15} /><span><strong>Morning preference</strong><small>1 science class outside preference</small></span></div>
                </div>
              </>
            ) : (
              <div className="dpx-all-clear">
                <span><CheckCircle2 size={25} /></span>
                <h3>No blocking issues</h3>
                <p>Teachers, rooms, subject quotas, and scheduling rules are valid.</p>
                <div><Check size={14} /> Room conflict resolved</div>
              </div>
            )}
          </aside>
        ) : null}
      </section>
    </>
  );
}

export default function FeeTimetableDesignPreview() {
  const [screen, setScreen] = useState<Screen>('fees');
  const [mobileNavOpen, setMobileNavOpen] = useState(false);

  return (
    <div className="dpx-shell">
      <aside className={`dpx-sidebar${mobileNavOpen ? ' is-open' : ''}`}>
        <div className="dpx-brand">
          <span className="dpx-brand-mark">G</span>
          <span><strong>Greenfield</strong><small>International School</small></span>
          <button className="dpx-mobile-close" type="button" aria-label="Close navigation" onClick={() => setMobileNavOpen(false)}><X size={18} /></button>
        </div>
        <div className="dpx-year-switch">
          <CalendarDays size={15} />
          <span><small>Academic year</small><strong>2026–27</strong></span>
          <ChevronDown size={14} />
        </div>
        <nav className="dpx-app-nav" aria-label="Prototype navigation">
          <span className="dpx-nav-label">Workspace</span>
          <AppNavButton active={false} icon={<LayoutDashboard size={18} />} label="Overview" onClick={() => undefined} />
          <AppNavButton active={false} icon={<Users size={18} />} label="Students" onClick={() => undefined} />
          <AppNavButton active={screen === 'timetable'} icon={<CalendarDays size={18} />} label="Timetable" onClick={() => { setScreen('timetable'); setMobileNavOpen(false); }} />
          <div className="dpx-nav-group">
            <AppNavButton active={screen === 'fees' || screen === 'configuration'} icon={<IndianRupee size={18} />} label="Fee management" onClick={() => { setScreen('fees'); setMobileNavOpen(false); }} />
            {(screen === 'fees' || screen === 'configuration') ? (
              <div className="dpx-subnav">
                <button className={screen === 'fees' ? 'is-active' : ''} type="button" onClick={() => setScreen('fees')}>Overview & collections</button>
                <button className={screen === 'configuration' ? 'is-active' : ''} type="button" onClick={() => setScreen('configuration')}>Configuration</button>
              </div>
            ) : null}
          </div>
          <AppNavButton active={false} icon={<BookOpen size={18} />} label="Academics" onClick={() => undefined} />
          <AppNavButton active={false} icon={<UserRound size={18} />} label="Staff & HR" onClick={() => undefined} />
          <span className="dpx-nav-label dpx-nav-label-system">System</span>
          <AppNavButton active={false} icon={<FileText size={18} />} label="Reports" onClick={() => undefined} />
          <AppNavButton active={false} icon={<Settings size={18} />} label="Settings" onClick={() => undefined} />
        </nav>
        <div className="dpx-sidebar-footer">
          <span className="dpx-avatar dpx-avatar-user">SK</span>
          <span><strong>Sonia Kapoor</strong><small>School administrator</small></span>
          <MoreHorizontal size={17} />
        </div>
      </aside>

      {mobileNavOpen ? <button className="dpx-nav-scrim" type="button" aria-label="Close navigation" onClick={() => setMobileNavOpen(false)} /> : null}

      <main className="dpx-main">
        <div className="dpx-topbar">
          <button className="dpx-mobile-menu" type="button" aria-label="Open navigation" onClick={() => setMobileNavOpen(true)}>
            <span /><span /><span />
          </button>
          <div className="dpx-breadcrumb">
            <span>IMS</span><ChevronRight size={13} />
            <strong>{screen === 'fees' ? 'Fee management' : screen === 'configuration' ? 'Fee configuration' : 'Timetable studio'}</strong>
          </div>
          <div className="dpx-topbar-actions">
            <button className="dpx-global-search" type="button"><Search size={16} /><span>Search IMS</span><kbd>⌘ K</kbd></button>
            <IconButton label="Notifications"><Bell size={18} /></IconButton>
            <IconButton label="Help"><span className="dpx-help-icon">?</span></IconButton>
          </div>
        </div>
        <div className={`dpx-content dpx-content-${screen}`}>
          {screen === 'fees' ? <FeesOverview onOpenConfiguration={() => setScreen('configuration')} /> : null}
          {screen === 'configuration' ? <FeeConfiguration /> : null}
          {screen === 'timetable' ? <TimetableStudio /> : null}
        </div>
      </main>
    </div>
  );
}

import { useCallback, useEffect, useMemo, useState } from 'react';
import { AlertTriangle, CheckCircle2, ChevronLeft, ChevronRight, RefreshCw, ShieldCheck } from 'lucide-react';
import api from '../../../services/api';
import { usePermissions } from '../../../hooks/usePermissions';
import { useAuth } from '../../../contexts/AuthContext';
import { ModuleShell } from '../ui';
import './GuardianDataReviewPanel.css';

type ReviewStatus = 'PENDING' | 'STALE' | 'DECIDED' | 'DEFERRED' | 'ESCALATED';

interface ReviewCase {
  caseId: string;
  caseSnapshotSha256: string;
  schoolId: number;
  studentId: number;
  admissionNo?: string;
  studentName: string;
  relationship: string;
  fieldName: string;
  legacyValue?: string;
  normalizedValue?: string;
  guardianId?: string;
  issueBucket: string;
  linkedStudents: number;
  phoneClusterGuardians: number;
  identityCandidates: number;
  contactVerifiedAt?: string;
  decision?: string;
  decisionNotes?: string;
  reviewStatus: ReviewStatus;
  recommendedDecision: string;
}

interface ReviewSummary {
  totalCases: number;
  distinctStudents: number;
  reviewed: number;
  remaining: number;
  decided: number;
  deferred: number;
  escalated: number;
  progressPercent: number;
  buckets: Array<{ bucket: string; status: string; cases: number; students: number }>;
}

interface CasePage {
  content: ReviewCase[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  last: boolean;
}

const BUCKETS = [
  'PLACEHOLDER_CLUSTER', 'PLACEHOLDER_CANDIDATE', 'IDENTITY_CANDIDATE', 'LINKED_CONFLICT',
  'MISSING_RELATIONSHIP', 'PROJECTION_MISSING', 'CASE_ONLY',
];
const STATUSES: ReviewStatus[] = ['PENDING', 'STALE', 'ESCALATED', 'DEFERRED', 'DECIDED'];
const DECISIONS = [
  ['ACCEPT_NORMALIZED', 'Approve canonical value'],
  ['KEEP_LEGACY', 'Keep legacy value'],
  ['CLEAR_PLACEHOLDER', 'Confirm placeholder removal'],
  ['CONFIRM_SHARED_IDENTITY', 'Confirm shared identity'],
  ['RESOLVE_IN_STUDENT_EDITOR', 'Resolve in student editor'],
  ['ESCALATE', 'Escalate for identity review'],
  ['DEFER', 'Defer'],
] as const;

const humanize = (value: string) => value.toLowerCase().split('_')
  .map((part) => part.charAt(0).toUpperCase() + part.slice(1)).join(' ');

const errorMessage = (error: unknown) =>
  (error as { response?: { data?: { message?: string; detail?: string } } })?.response?.data?.message
  || (error as { response?: { data?: { detail?: string } } })?.response?.data?.detail
  || (error instanceof Error ? error.message : 'The guardian review queue could not be loaded.');

export function GuardianDataReviewPanel() {
  const { can } = usePermissions();
  const { user } = useAuth();
  const multiSchool = user?.role === 'SUPERADMIN' || user?.role === 'OPERATIONS' || can('platform:admin');
  const canManage = can('student:update') || can('platform:admin');
  const [schools, setSchools] = useState<Array<{ id: number; name: string; shortCode?: string }>>([]);
  const [schoolId, setSchoolId] = useState<number | ''>('');
  const [summary, setSummary] = useState<ReviewSummary | null>(null);
  const [pageData, setPageData] = useState<CasePage | null>(null);
  const [bucket, setBucket] = useState('');
  const [status, setStatus] = useState<ReviewStatus | ''>('PENDING');
  const [searchInput, setSearchInput] = useState('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [savingCaseId, setSavingCaseId] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [drafts, setDrafts] = useState<Record<string, { decision: string; notes: string }>>({});

  const params = useMemo(() => ({
    ...(schoolId ? { schoolId } : {}),
    ...(bucket ? { bucket } : {}),
    ...(status ? { status } : {}),
    ...(search ? { search } : {}),
    page,
    size: 25,
  }), [schoolId, bucket, status, search, page]);

  useEffect(() => {
    if (!multiSchool) return;
    api.get('/students/export/context').then((response) => {
      const options = Array.isArray(response.data?.schools) ? response.data.schools : [];
      setSchools(options);
      if (user?.role === 'OPERATIONS' && options.length > 0) setSchoolId(options[0].id);
    }).catch((err) => setError(errorMessage(err)));
  }, [multiSchool, user?.role]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [summaryResponse, casesResponse] = await Promise.all([
        api.get('/guardian-data-review/summary'),
        api.get('/guardian-data-review/cases', { params }),
      ]);
      setSummary(summaryResponse.data as ReviewSummary);
      setPageData(casesResponse.data as CasePage);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setLoading(false);
    }
  }, [params]);

  useEffect(() => { void load(); }, [load]);

  const bucketTotals = useMemo(() => {
    const totals = new Map<string, number>();
    for (const row of summary?.buckets || []) {
      totals.set(row.bucket, (totals.get(row.bucket) || 0) + row.cases);
    }
    return totals;
  }, [summary]);

  const saveDecision = async (reviewCase: ReviewCase) => {
    if (!canManage) return;
    const draft = drafts[reviewCase.caseId] || {
      decision: reviewCase.recommendedDecision,
      notes: '',
    };
    setSavingCaseId(reviewCase.caseId);
    setError('');
    setNotice('');
    try {
      await api.post(
        `/guardian-data-review/cases/${encodeURIComponent(reviewCase.caseId)}/decisions`,
        {
          caseSnapshotSha256: reviewCase.caseSnapshotSha256,
          decision: draft.decision,
          notes: draft.notes,
        },
        {
          params: { schoolId: reviewCase.schoolId },
          headers: { 'Idempotency-Key': globalThis.crypto?.randomUUID?.() || `${Date.now()}-${reviewCase.caseId}` },
        },
      );
      setNotice('Decision recorded. No student or guardian value was changed automatically.');
      await load();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSavingCaseId('');
    }
  };

  return (
    <ModuleShell
      title="Guardian data review"
      subtitle="Resolve legacy-to-canonical guardian mismatches with tenant scope, snapshots, and an immutable decision trail."
    >
      <section className="gdr-shell" aria-busy={loading}>
        <div className="gdr-command-bar">
          <div>
            <span className="gdr-eyebrow"><ShieldCheck size={14} /> Governed correction queue</span>
            <h2>{summary?.remaining ?? '—'} fields require a decision</h2>
            <p>{summary?.distinctStudents ?? '—'} affected students · decisions never mutate records directly</p>
          </div>
          <button className="ck-btn ck-btn-ghost" onClick={() => void load()} disabled={loading}>
            <RefreshCw size={15} className={loading ? 'gdr-spin' : ''} /> Refresh evidence
          </button>
        </div>

        <div className="gdr-progress-card">
          <div className="gdr-progress-copy">
            <strong>{summary?.progressPercent ?? 0}% reviewed</strong>
            <span>{summary?.reviewed ?? 0} of {summary?.totalCases ?? 0} field decisions</span>
          </div>
          <div className="gdr-progress-track" role="progressbar" aria-valuemin={0} aria-valuemax={100}
               aria-valuenow={summary?.progressPercent ?? 0}>
            <span style={{ width: `${Math.min(summary?.progressPercent ?? 0, 100)}%` }} />
          </div>
          <div className="gdr-stat-grid">
            <div><span>Pending</span><strong>{summary?.remaining ?? 0}</strong></div>
            <div><span>Decided</span><strong>{summary?.decided ?? 0}</strong></div>
            <div><span>Escalated</span><strong>{summary?.escalated ?? 0}</strong></div>
            <div><span>Deferred</span><strong>{summary?.deferred ?? 0}</strong></div>
          </div>
        </div>

        <div className="gdr-bucket-strip" aria-label="Issue groups">
          <button className={!bucket ? 'on' : ''} onClick={() => { setBucket(''); setPage(0); }}>
            <span>All mismatches</span><strong>{summary?.totalCases ?? 0}</strong>
          </button>
          {BUCKETS.map((value) => (
            <button key={value} className={bucket === value ? 'on' : ''}
                    onClick={() => { setBucket(value); setPage(0); }}>
              <span>{humanize(value)}</span><strong>{bucketTotals.get(value) ?? 0}</strong>
            </button>
          ))}
        </div>

        <div className="gdr-filters">
          <form onSubmit={(event) => { event.preventDefault(); setSearch(searchInput.trim()); setPage(0); }}>
            <label>
              <span>Student or admission no.</span>
              <input value={searchInput} onChange={(event) => setSearchInput(event.target.value)}
                     placeholder="Search the review queue" />
            </label>
            <button className="ck-btn ck-btn-primary" type="submit">Search</button>
          </form>
          {multiSchool && (
            <label>
              <span>School</span>
              <select value={schoolId} onChange={(event) => {
                setSchoolId(event.target.value ? Number(event.target.value) : '');
                setPage(0);
              }}>
                {user?.role !== 'OPERATIONS' && <option value="">All schools</option>}
                {schools.map((school) => <option key={school.id} value={school.id}>{school.name}</option>)}
              </select>
            </label>
          )}
          <label>
            <span>Status</span>
            <select value={status} onChange={(event) => { setStatus(event.target.value as ReviewStatus | ''); setPage(0); }}>
              <option value="">All statuses</option>
              {STATUSES.map((value) => <option key={value} value={value}>{humanize(value)}</option>)}
            </select>
          </label>
        </div>

        {error && <div className="gdr-alert error"><AlertTriangle size={17} />{error}</div>}
        {notice && <div className="gdr-alert success"><CheckCircle2 size={17} />{notice}</div>}

        <div className="gdr-table-wrap">
          <table className="gdr-table">
            <thead>
              <tr>
                <th>Student</th><th>Issue</th><th>Legacy record</th><th>Canonical guardian</th>
                <th>Impact</th><th>Decision</th>
              </tr>
            </thead>
            <tbody>
              {!loading && (pageData?.content.length ?? 0) === 0 && (
                <tr><td colSpan={6} className="gdr-empty">No cases match these filters.</td></tr>
              )}
              {(pageData?.content || []).map((reviewCase) => {
                const draft = drafts[reviewCase.caseId] || {
                  decision: reviewCase.recommendedDecision,
                  notes: reviewCase.decisionNotes || '',
                };
                const highImpact = reviewCase.linkedStudents > 1 || reviewCase.phoneClusterGuardians >= 10;
                return (
                  <tr key={reviewCase.caseId}>
                    <td>
                      <strong>{reviewCase.studentName}</strong>
                      <small>{reviewCase.admissionNo || 'No admission number'} · {schools.find((school) => school.id === reviewCase.schoolId)?.name || `School ${reviewCase.schoolId}`}</small>
                    </td>
                    <td>
                      <span className={`gdr-status ${reviewCase.reviewStatus.toLowerCase()}`}>{humanize(reviewCase.reviewStatus)}</span>
                      <small>{humanize(reviewCase.issueBucket)} · {humanize(reviewCase.relationship)} {reviewCase.fieldName}</small>
                    </td>
                    <td className="gdr-value">{reviewCase.legacyValue || <em>Empty</em>}</td>
                    <td className="gdr-value">{reviewCase.normalizedValue || <em>Empty</em>}</td>
                    <td>
                      {highImpact ? <span className="gdr-impact warn"><AlertTriangle size={13} /> Shared identity</span>
                        : <span className="gdr-impact">Single-record scope</span>}
                      <small>{reviewCase.linkedStudents} linked students · {reviewCase.identityCandidates} identity candidates</small>
                    </td>
                    <td className="gdr-decision-cell">
                      {canManage ? (
                        <>
                          <select value={draft.decision} onChange={(event) => setDrafts((current) => ({
                            ...current,
                            [reviewCase.caseId]: { ...draft, decision: event.target.value },
                          }))}>
                            {DECISIONS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
                          </select>
                          <input value={draft.notes} maxLength={2000} placeholder="Decision note (optional)"
                                 onChange={(event) => setDrafts((current) => ({
                                   ...current,
                                   [reviewCase.caseId]: { ...draft, notes: event.target.value },
                                 }))} />
                          <button className="ck-btn ck-btn-primary ck-btn-sm"
                                  disabled={savingCaseId === reviewCase.caseId}
                                  onClick={() => void saveDecision(reviewCase)}>
                            {savingCaseId === reviewCase.caseId ? 'Recording…' : 'Record decision'}
                          </button>
                        </>
                      ) : <span className="gdr-readonly">Read-only access</span>}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>

        <div className="gdr-footer">
          <span>Showing {pageData?.content.length ?? 0} of {pageData?.totalElements ?? 0} cases</span>
          <div>
            <button className="ck-btn ck-btn-ghost ck-btn-sm" disabled={page <= 0 || loading}
                    onClick={() => setPage((value) => Math.max(0, value - 1))}>
              <ChevronLeft size={15} /> Previous
            </button>
            <span>Page {(pageData?.page ?? 0) + 1} of {Math.max(pageData?.totalPages ?? 0, 1)}</span>
            <button className="ck-btn ck-btn-ghost ck-btn-sm" disabled={pageData?.last !== false || loading}
                    onClick={() => setPage((value) => value + 1)}>
              Next <ChevronRight size={15} />
            </button>
          </div>
        </div>

        <aside className="gdr-governance-note">
          <ShieldCheck size={20} />
          <div><strong>Execution boundary</strong><span>Recorded decisions become review evidence. Guardian values, permissions, and consent events stay unchanged until a separately generated, exact-hash production plan is approved and executed.</span></div>
        </aside>
      </section>
    </ModuleShell>
  );
}

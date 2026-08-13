import { useEffect, useState } from 'react';
import api from '../../../services/api';
import { ModuleShell, Field, Stat } from '../ui';
import { formatMoney } from '../utils';
import { DEFAULT_SCHOOL_TIME_ZONE, SCHOOL_TIME_ZONES } from '../../../utils/timeZones';
import { DEFAULT_SCHOOL_LOCALIZATION, SCHOOL_COUNTRY_PRESETS, formatSchoolCurrency, localizationForCountry } from '../../../utils/schoolLocalization';

type RoleAssignmentRow = {
  userId: number;
  userEmail?: string;
  userFullName?: string;
  schoolId: number | null;
  roleName: string;
  active: boolean;
};

type AccountSummary = {
  userId: number;
  email: string;
  fullName?: string;
};

function addAccountOnce(list: AccountSummary[], account: AccountSummary) {
  if (!account.email) return;
  if (!list.some((value) => value.userId === account.userId || value.email.toLowerCase() === account.email.toLowerCase())) {
    list.push(account);
  }
}

function accountList(school: any, listField: 'adminAccounts' | 'operatorAccounts', fallbackField: 'adminEmail' | 'operationsEmail') {
  const accounts = Array.isArray(school[listField]) ? school[listField] : [];
  if (accounts.length > 0) return accounts as AccountSummary[];
  return school[fallbackField] ? [{ userId: 0, email: school[fallbackField] }] : [];
}

function renderAccountList(accounts: AccountSummary[]) {
  if (accounts.length === 0) return <span style={{ color: 'var(--ink3)' }}>-</span>;
  return (
    <div style={{ display: 'grid', gap: 4, minWidth: 180 }}>
      {accounts.map((account) => (
        <span key={`${account.userId}:${account.email}`} style={{ overflowWrap: 'anywhere', fontSize: 13 }}>
          {account.email}
        </span>
      ))}
    </div>
  );
}

const YEAR_START_MONTHS = [
  ['1', 'January'],
  ['2', 'February'],
  ['3', 'March'],
  ['4', 'April'],
  ['5', 'May'],
  ['6', 'June'],
  ['7', 'July'],
  ['8', 'August'],
  ['9', 'September'],
  ['10', 'October'],
  ['11', 'November'],
  ['12', 'December'],
] as const;

const MAX_CLASS_COUNT = 15;
const EMPTY_ONBOARD_FORM = { name: '', shortCode: '', city: '', state: '', contactEmail: '', contactPhone: '', classCount: '15', sectionCount: '2', academicYearStartMonth: '4', financialYearStartMonth: '4', ...DEFAULT_SCHOOL_LOCALIZATION };

function academicStartLabel(value?: number | string) {
  const month = String(value || 4);
  return YEAR_START_MONTHS.find(([id]) => id === month)?.[1] || 'April';
}

function financialStartLabel(value?: number | string) {
  const month = String(value || 4);
  return YEAR_START_MONTHS.find(([id]) => id === month)?.[1] || 'April';
}

function setupStatus(school: any) {
  const hasAdmin = accountList(school, 'adminAccounts', 'adminEmail').length > 0;
  const driveReady = school.photoImportFolder?.status === 'READY';
  return { ready: hasAdmin && driveReady, hasAdmin, driveReady };
}

export function SaSchoolsPanel() {
  const [saSchools, setSaSchools] = useState<any[]>([]);
  const [saSchoolsLoading, setSaSchoolsLoading] = useState(false);
  const [saSchoolsError, setSaSchoolsError] = useState('');
  const [saOnboardOpen, setSaOnboardOpen] = useState(false);
  const [saOnboardForm, setSaOnboardForm] = useState(EMPTY_ONBOARD_FORM);
  const [saOnboardErrors, setSaOnboardErrors] = useState<Record<string, string>>({});
  const [saOnboardSaving, setSaOnboardSaving] = useState(false);
  const [toast, setToast] = useState<string | null>(null);
  const [editSchool, setEditSchool] = useState<any | null>(null);
  const [editForm, setEditForm] = useState({ classCount: '15', sectionCount: '2', ...DEFAULT_SCHOOL_LOCALIZATION });
  const [editError, setEditError] = useState('');
  const [editSaving, setEditSaving] = useState(false);

  const openEdit = (school: any) => {
    setEditSchool(school);
    setEditForm({
      classCount: String(school.configuredClassCount ?? MAX_CLASS_COUNT),
      sectionCount: String(school.configuredSectionCount ?? 2),
      timeZone: school.timeZone || DEFAULT_SCHOOL_TIME_ZONE,
      countryCode: school.countryCode || DEFAULT_SCHOOL_LOCALIZATION.countryCode,
      locale: school.locale || DEFAULT_SCHOOL_LOCALIZATION.locale,
      currencyCode: school.currencyCode || DEFAULT_SCHOOL_LOCALIZATION.currencyCode,
      phoneRegion: school.phoneRegion || school.countryCode || DEFAULT_SCHOOL_LOCALIZATION.phoneRegion,
    });
    setEditError('');
  };

  const submitEdit = async () => {
    const classCount = Number(editForm.classCount || 0);
    const sectionCount = Number(editForm.sectionCount || 0);
    if (!Number.isInteger(classCount) || classCount < 1 || classCount > MAX_CLASS_COUNT) { setEditError(`Classes must be between 1 and ${MAX_CLASS_COUNT}`); return; }
    if (!Number.isInteger(sectionCount) || sectionCount < 1 || sectionCount > 26) { setEditError('Sections must be between 1 and 26'); return; }
    setEditError(''); setEditSaving(true);
    try {
      await api.put(`/schools/${editSchool.id}/structure`, { classCount, sectionCount });
      await api.patch(`/schools/${editSchool.id}`, {
        timeZone: editForm.timeZone,
        countryCode: editForm.countryCode,
        locale: editForm.locale,
        currencyCode: editForm.currencyCode,
        phoneRegion: editForm.phoneRegion,
      });
      setToast(`${editSchool.name} structure updated`);
      setEditSchool(null);
      await loadSaSchools();
    } catch (e: any) {
      setEditError(e?.response?.data?.message || 'Update failed. Please try again.');
    } finally {
      setEditSaving(false);
    }
  };

  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(null), 3500);
    return () => clearTimeout(t);
  }, [toast]);

  const loadSaSchools = async () => {
    setSaSchoolsLoading(true); setSaSchoolsError('');
    try {
      const [schoolsRes, assignmentsRes] = await Promise.all([
        api.get('/sa/schools'),
        api.get<RoleAssignmentRow[]>('/rbac/user-role-assignments', { params: { active: true, limit: 500 } })
          .catch(() => ({ data: [] as RoleAssignmentRow[] })),
      ]);
      const schools = Array.isArray(schoolsRes.data) ? schoolsRes.data : [];
      const accountMap: Record<number, { adminAccounts: AccountSummary[]; operatorAccounts: AccountSummary[] }> = {};
      for (const row of assignmentsRes.data || []) {
        if (row.schoolId == null || row.active === false) continue;
        const role = (row.roleName || '').toUpperCase();
        if (role !== 'ADMIN' && role !== 'OPERATIONS') continue;
        const account = {
          userId: row.userId,
          email: row.userEmail || `user #${row.userId}`,
          fullName: row.userFullName,
        };
        const bucket = accountMap[row.schoolId] ?? { adminAccounts: [], operatorAccounts: [] };
        if (role === 'ADMIN') addAccountOnce(bucket.adminAccounts, account);
        if (role === 'OPERATIONS') addAccountOnce(bucket.operatorAccounts, account);
        accountMap[row.schoolId] = bucket;
      }
      setSaSchools(schools.map((school: any) => ({ ...school, ...(accountMap[school.id] ?? {}) })));
    } catch (e: any) {
      setSaSchoolsError(e?.response?.data?.message || 'Failed to load schools.');
    } finally {
      setSaSchoolsLoading(false);
    }
  };

  useEffect(() => { void loadSaSchools(); }, []);

  const applyOnboardCountry = (countryCode: string) => {
    const localization = localizationForCountry(countryCode);
    setSaOnboardForm((current) => ({ ...current, ...(localization || { countryCode, phoneRegion: countryCode }) }));
  };

  const applyEditCountry = (countryCode: string) => {
    const localization = localizationForCountry(countryCode);
    setEditForm((current) => ({ ...current, ...(localization || { countryCode, phoneRegion: countryCode }) }));
  };

  const retryDriveProvisioning = async (school: any) => {
    try {
      const response = await api.post(`/student-photo-imports/folders/${school.id}/provision`);
      setToast(response.data?.status === 'READY' ? `${school.name} Drive folder is ready` : `${school.name} Drive status: ${response.data?.status || 'unknown'}`);
      await loadSaSchools();
    } catch (e: any) {
      setToast(e?.response?.data?.message || `Drive provisioning failed for ${school.name}`);
    }
  };

  const submitSaOnboard = async () => {
    const errors: Record<string, string> = {};
    if (!saOnboardForm.name) errors.name = 'School name is required';
    if (!saOnboardForm.shortCode) errors.shortCode = 'Short code is required';
    if (!saOnboardForm.city) errors.city = 'City is required';
    const classCount = Number(saOnboardForm.classCount || 0);
    const sectionCount = Number(saOnboardForm.sectionCount || 0);
    const academicYearStartMonth = Number(saOnboardForm.academicYearStartMonth || 0);
    const financialYearStartMonth = Number(saOnboardForm.financialYearStartMonth || 0);
    if (!Number.isInteger(classCount) || classCount < 1 || classCount > MAX_CLASS_COUNT) errors.classCount = `Classes must be between 1 and ${MAX_CLASS_COUNT}`;
    if (!Number.isInteger(sectionCount) || sectionCount < 1 || sectionCount > 26) errors.sectionCount = 'Sections must be between 1 and 26';
    if (!Number.isInteger(academicYearStartMonth) || academicYearStartMonth < 1 || academicYearStartMonth > 12) errors.academicYearStartMonth = 'Select a valid start month';
    if (!Number.isInteger(financialYearStartMonth) || financialYearStartMonth < 1 || financialYearStartMonth > 12) errors.financialYearStartMonth = 'Select a valid start month';
    if (Object.keys(errors).length) { setSaOnboardErrors(errors); return; }
    setSaOnboardErrors({}); setSaOnboardSaving(true);
    try {
      const response = await api.post('/schools', { ...saOnboardForm, classCount, sectionCount, academicYearStartMonth, financialYearStartMonth });
      const folderStatus = response.data?.photoImportFolder?.status;
      setToast(folderStatus === 'READY'
        ? `${saOnboardForm.name} created. Add an administrator to complete setup.`
        : `${saOnboardForm.name} created. Drive setup needs attention (${folderStatus || 'unknown'}).`);
      setSaOnboardOpen(false);
      setSaOnboardForm(EMPTY_ONBOARD_FORM);
      await loadSaSchools();
    } catch (e: any) {
      setSaOnboardErrors({ _: e?.response?.data?.message || 'Save failed. Please try again.' });
    } finally {
      setSaOnboardSaving(false);
    }
  };

  return (
    <>
      <ModuleShell title="School accounts" subtitle="All schools with admin and order value stats" actions={<button className="ck-btn ck-btn-g" onClick={() => setSaOnboardOpen(true)}>+ Onboard school</button>}>
        {!saSchoolsLoading && saSchools.length > 0 && (() => {
          const activeSchools = saSchools.filter((s: any) => s.active).length;
          const totalOrders = saSchools.reduce((n: number, s: any) => n + (s.ordersYTD ?? 0), 0);
          const totalGmvPaise = saSchools.reduce((n: number, s: any) => n + Number(s.gmvYTD ?? 0), 0);
          const pendingSetup = saSchools.filter((school: any) => !setupStatus(school).ready).length;
          return (
            <div className="ck-stats ck-s4" style={{ marginBottom: 18 }}>
              <Stat label="Active schools" value={activeSchools} sub={`${saSchools.length} total onboarded`} pill="Platform" tone="blue" />
              <Stat label="Orders YTD" value={totalOrders} sub="Supply orders across all schools" pill="All schools" tone="green" />
              <Stat label="Order value YTD" value={`₹${formatMoney(totalGmvPaise / 100)}`} sub="Platform GMV" pill="Gross" tone="orange" />
              <Stat label="Pending setup" value={pendingSetup} sub="Administrator or Drive action needed" pill={pendingSetup > 0 ? 'Action needed' : 'All set'} tone={pendingSetup > 0 ? 'red' : 'green'} />
            </div>
          );
        })()}
        <div className="ck-card">
          {saSchoolsLoading ? <div style={{ padding: 16 }}>Loading schools…</div>
          : saSchoolsError ? <div style={{ padding: 16 }}>{saSchoolsError}</div>
          : <table className="ck-table">
            <thead><tr><th>School</th><th>Setup</th><th>Short code</th><th>City</th><th>Timezone</th><th>Classes</th><th>Sections / class</th><th>Academic start</th><th>Financial start</th><th>Admins</th><th>Operators</th><th>Orders YTD</th><th>Order Value YTD</th><th>ERP since</th><th></th></tr></thead>
            <tbody>
              {saSchools.length === 0
                ? <tr><td colSpan={15}><div className="ts">No schools found.</div></td></tr>
                : saSchools.map((school: any) => (
                  <tr key={school.id}>
                    <td><div className="tb">{school.name}</div><div className="ts">{school.active ? 'Active' : 'Inactive'} · {school.countryCode || 'IN'} · {school.currencyCode || 'INR'}</div></td>
                    <td><span className={`ck-pill ${setupStatus(school).ready ? 'ck-pill-gr' : 'ck-pill-am'}`}>{setupStatus(school).ready ? 'Ready' : 'Action needed'}</span><div className="ts">Admin {setupStatus(school).hasAdmin ? 'ready' : 'missing'} · Drive {school.photoImportFolder?.status || 'unknown'}</div></td>
                    <td>{school.shortCode || '—'}</td>
                    <td>{school.city || '—'}</td>
                    <td>{school.timeZone || DEFAULT_SCHOOL_TIME_ZONE}</td>
                    <td>{school.configuredClassCount ?? '—'}</td>
                    <td>{school.configuredSectionCount ?? '—'}</td>
                    <td>{academicStartLabel(school.academicYearStartMonth)}</td>
                    <td>{financialStartLabel(school.financialYearStartMonth)}</td>
                    <td>{renderAccountList(accountList(school, 'adminAccounts', 'adminEmail'))}</td>
                    <td>{renderAccountList(accountList(school, 'operatorAccounts', 'operationsEmail'))}</td>
                    <td>{school.ordersYTD ?? 0}</td>
                    <td>{formatSchoolCurrency(Number(school.gmvYTD || 0) / 100, school)}</td>
                    <td>{school.erpSince || '—'}</td>
                    <td>
                      <div className="ck-actions-inline">
                        <button className="ck-btn ck-btn-ghost" onClick={() => openEdit(school)}>Edit structure</button>
                        {school.photoImportFolder?.status !== 'READY' ? <button className="ck-btn ck-btn-ghost" onClick={() => void retryDriveProvisioning(school)}>Retry Drive</button> : null}
                      </div>
                    </td>
                  </tr>
                ))}
            </tbody>
          </table>}
        </div>
      </ModuleShell>

      {saOnboardOpen && (
        <div className="ck-modal-bg" onClick={() => setSaOnboardOpen(false)}>
          <div className="ck-modal" onClick={(e) => e.stopPropagation()}>
            <div className="ck-modal-h">
              <div className="ck-modal-title">Onboard school</div>
              <button className="ck-modal-x" onClick={() => setSaOnboardOpen(false)}>×</button>
            </div>
            <div className="ck-modal-body">
              {saOnboardErrors._ && <div className="ck-alert ck-alert-re" style={{ marginBottom: 16 }}><span>✕</span><div>{saOnboardErrors._}</div></div>}
              <div className="ck-form-grid ck-fg-2">
                <Field label="School name *"><input value={saOnboardForm.name} onChange={(e) => setSaOnboardForm({ ...saOnboardForm, name: e.target.value })} /></Field>
                <Field label="Short code *"><input value={saOnboardForm.shortCode} onChange={(e) => setSaOnboardForm({ ...saOnboardForm, shortCode: e.target.value })} /></Field>
                <Field label="City *"><input value={saOnboardForm.city} onChange={(e) => setSaOnboardForm({ ...saOnboardForm, city: e.target.value })} /></Field>
                <Field label="State"><input value={saOnboardForm.state} onChange={(e) => setSaOnboardForm({ ...saOnboardForm, state: e.target.value })} /></Field>
                <Field label="No. of classes *">
                  <input type="number" min={1} max={MAX_CLASS_COUNT} value={saOnboardForm.classCount} onChange={(e) => setSaOnboardForm({ ...saOnboardForm, classCount: e.target.value })} />
                  {saOnboardErrors.classCount ? <div className="ts" style={{ color: 'var(--re)', marginTop: 6 }}>{saOnboardErrors.classCount}</div> : <div className="ts" style={{ marginTop: 6 }}>Order: Nursery/Pre-Nursery/Playgroup, LKG, UKG, then 1 to 12</div>}
                </Field>
                <Field label="Sections per class *">
                  <input type="number" min={1} max={26} value={saOnboardForm.sectionCount} onChange={(e) => setSaOnboardForm({ ...saOnboardForm, sectionCount: e.target.value })} />
                  {saOnboardErrors.sectionCount ? <div className="ts" style={{ color: 'var(--re)', marginTop: 6 }}>{saOnboardErrors.sectionCount}</div> : <div className="ts" style={{ marginTop: 6 }}>Creates sections A to {String.fromCharCode(64 + Math.max(1, Math.min(26, Number(saOnboardForm.sectionCount || 2))))}</div>}
                </Field>
                <Field label="Academic year starts *">
                  <select value={saOnboardForm.academicYearStartMonth} onChange={(e) => setSaOnboardForm({ ...saOnboardForm, academicYearStartMonth: e.target.value })}>
                    {YEAR_START_MONTHS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
                  </select>
                  {saOnboardErrors.academicYearStartMonth ? <div className="ts" style={{ color: 'var(--re)', marginTop: 6 }}>{saOnboardErrors.academicYearStartMonth}</div> : null}
                </Field>
                <Field label="Financial year starts *">
                  <select value={saOnboardForm.financialYearStartMonth} onChange={(e) => setSaOnboardForm({ ...saOnboardForm, financialYearStartMonth: e.target.value })}>
                    {YEAR_START_MONTHS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
                  </select>
                  {saOnboardErrors.financialYearStartMonth ? <div className="ts" style={{ color: 'var(--re)', marginTop: 6 }}>{saOnboardErrors.financialYearStartMonth}</div> : null}
                </Field>
                <Field label="Timezone *"><select value={saOnboardForm.timeZone} onChange={(e) => setSaOnboardForm({ ...saOnboardForm, timeZone: e.target.value })}>{SCHOOL_TIME_ZONES.map((timeZone) => <option key={timeZone} value={timeZone}>{timeZone}</option>)}</select></Field>
                <Field label="Country *"><select value={saOnboardForm.countryCode} onChange={(e) => applyOnboardCountry(e.target.value)}>{SCHOOL_COUNTRY_PRESETS.map((country) => <option key={country.countryCode} value={country.countryCode}>{country.name}</option>)}</select></Field>
                <Field label="Locale *"><input value={saOnboardForm.locale} onChange={(e) => setSaOnboardForm({ ...saOnboardForm, locale: e.target.value })} /></Field>
                <Field label="Currency *"><input value={saOnboardForm.currencyCode} maxLength={3} onChange={(e) => setSaOnboardForm({ ...saOnboardForm, currencyCode: e.target.value.toUpperCase() })} /></Field>
                <Field label="Contact email"><input value={saOnboardForm.contactEmail} onChange={(e) => setSaOnboardForm({ ...saOnboardForm, contactEmail: e.target.value })} /></Field>
                <Field label="Contact phone"><input value={saOnboardForm.contactPhone} onChange={(e) => setSaOnboardForm({ ...saOnboardForm, contactPhone: e.target.value })} /></Field>
              </div>
            </div>
            <div className="ck-modal-foot">
              <button className="ck-btn ck-btn-ghost" onClick={() => setSaOnboardOpen(false)}>Cancel</button>
              <button className="ck-btn ck-btn-g" disabled={saOnboardSaving} onClick={submitSaOnboard}>{saOnboardSaving ? 'Saving…' : 'Create school'}</button>
            </div>
          </div>
        </div>
      )}

      {editSchool && (
        <div className="ck-modal-bg" onClick={() => setEditSchool(null)}>
          <div className="ck-modal" role="dialog" onClick={(e) => e.stopPropagation()}>
            <div className="ck-modal-h">
              <div className="ck-modal-title">Edit structure — {editSchool.name}</div>
              <button className="ck-modal-x" onClick={() => setEditSchool(null)}>×</button>
            </div>
            <div className="ck-modal-body">
              {editError && <div className="ck-alert ck-alert-re" style={{ marginBottom: 16 }}><span>✕</span><div>{editError}</div></div>}
              <div className="ck-form-grid ck-fg-2">
                <Field label="No. of classes *"><input type="number" min={1} max={MAX_CLASS_COUNT} value={editForm.classCount} onChange={(e) => setEditForm({ ...editForm, classCount: e.target.value })} /></Field>
                <Field label="Sections per class *"><input type="number" min={1} max={26} value={editForm.sectionCount} onChange={(e) => setEditForm({ ...editForm, sectionCount: e.target.value })} /></Field>
                <Field label="Timezone *"><select value={editForm.timeZone} onChange={(e) => setEditForm({ ...editForm, timeZone: e.target.value })}>{SCHOOL_TIME_ZONES.map((timeZone) => <option key={timeZone} value={timeZone}>{timeZone}</option>)}</select></Field>
                <Field label="Country *"><select value={editForm.countryCode} onChange={(e) => applyEditCountry(e.target.value)}>{SCHOOL_COUNTRY_PRESETS.map((country) => <option key={country.countryCode} value={country.countryCode}>{country.name}</option>)}</select></Field>
                <Field label="Locale *"><input value={editForm.locale} onChange={(e) => setEditForm({ ...editForm, locale: e.target.value })} /></Field>
                <Field label="Currency *"><input value={editForm.currencyCode} maxLength={3} onChange={(e) => setEditForm({ ...editForm, currencyCode: e.target.value.toUpperCase() })} /></Field>
              </div>
              <div className="ts" style={{ marginTop: 10 }}>Reducing a count is blocked if a removed class or section still has students.</div>
            </div>
            <div className="ck-modal-foot">
              <button className="ck-btn ck-btn-ghost" onClick={() => setEditSchool(null)}>Cancel</button>
              <button className="ck-btn ck-btn-g" disabled={editSaving} onClick={submitEdit}>{editSaving ? 'Saving…' : 'Save changes'}</button>
            </div>
          </div>
        </div>
      )}

      {toast && (
        <div className="ck-command-toast ok">
          {toast}
        </div>
      )}
    </>
  );
}

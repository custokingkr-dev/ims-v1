import { useEffect, useMemo, useState } from 'react';
import type { FormEvent } from 'react';
import { Navigate, Link } from 'react-router-dom';
import api from '../services/api';
import { useAuth } from '../contexts/AuthContext';
import { usePermissions } from '../hooks/usePermissions';
import {
  MODULE_CHILD_CODES,
  MODULE_GROUPS,
  expandModuleGroupSelections,
  groupModuleSelections,
} from './workspace/config';

type SchoolRow = {
  id: number;
  name: string;
  shortCode: string;
  city?: string;
  state?: string;
  active: boolean;
  adminEmail?: string;
  operationsEmail?: string;
  academicYearStartMonth?: number;
  financialYearStartMonth?: number;
};

type ModuleEntitlement = {
  moduleCode: string;
  enabled: boolean;
};

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

type SchoolAccounts = {
  admins: AccountSummary[];
  operators: AccountSummary[];
};

type AccountEditTarget = {
  account: AccountSummary;
  role: 'ADMIN' | 'OPERATIONS';
  contextLabel?: string;
};

type OperatorAccountRow = AccountSummary & {
  schoolIds: number[];
  schoolNames: string[];
};

type UserDirectoryRow = {
  id: number;
  fullName?: string;
  email?: string;
  role?: string;
};

const defaultSchoolForm = {
  name: '',
  shortCode: '',
  city: '',
  state: '',
  contactEmail: '',
  contactPhone: '',
  classCount: '15',
  sectionCount: '2',
  academicYearStartMonth: '4',
  financialYearStartMonth: '4'
};

const defaultAdminForm = {
  fullName: '',
  email: '',
  temporaryPassword: ''
};

const defaultOpsForm = {
  fullName: '',
  email: '',
  temporaryPassword: ''
};

const defaultAccountEditForm = {
  fullName: '',
  email: '',
  password: ''
};

const defaultModuleSelections = (): Record<string, boolean> =>
  Object.fromEntries(MODULE_GROUPS.map((m) => [m.code, true]));

const emptyAccounts: SchoolAccounts = { admins: [], operators: [] };

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

function academicStartLabel(value?: number | string) {
  const month = String(value || 4);
  return YEAR_START_MONTHS.find(([id]) => id === month)?.[1] || 'April';
}

function financialStartLabel(value?: number | string) {
  const month = String(value || 4);
  return YEAR_START_MONTHS.find(([id]) => id === month)?.[1] || 'April';
}

function addAccountOnce(list: AccountSummary[], account: AccountSummary) {
  if (!account.email) return;
  const exists = list.some((item) => item.userId === account.userId || item.email.toLowerCase() === account.email.toLowerCase());
  if (!exists) list.push(account);
}

function renderAccountEmails(
  accounts: AccountSummary[],
  fallback?: string,
  actions?: {
    onEdit?: (account: AccountSummary) => void;
    onManageSchools?: (account: AccountSummary) => void;
  },
) {
  const display = accounts.length > 0
    ? accounts
    : fallback
      ? [{ userId: 0, email: fallback }]
      : [];

  if (display.length === 0) {
    return <span style={{ color: '#9ca3af' }}>-</span>;
  }

  return (
    <div style={{ display: 'grid', gap: 4, minWidth: 180 }}>
      {display.map((account) => (
        <span
          key={`${account.userId}:${account.email}`}
          title={account.fullName || account.email}
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 6,
            flexWrap: 'wrap',
            overflowWrap: 'anywhere',
            fontSize: 13,
            lineHeight: 1.35,
          }}
        >
          <span>{account.email}</span>
          {account.userId > 0 && actions?.onEdit && (
            <button
              type="button"
              className="ck-btn ck-btn-ghost"
              style={{ padding: '2px 8px', fontSize: 11, minHeight: 0 }}
              onClick={() => actions.onEdit?.(account)}
            >
              Edit
            </button>
          )}
          {account.userId > 0 && actions?.onManageSchools && (
            <button
              type="button"
              className="ck-btn ck-btn-ghost"
              style={{ padding: '2px 8px', fontSize: 11, minHeight: 0 }}
              onClick={() => actions.onManageSchools?.(account)}
            >
              Schools
            </button>
          )}
        </span>
      ))}
    </div>
  );
}

export default function SchoolManagementPage() {
  const { user } = useAuth();
  const { can } = usePermissions();
  const [schools, setSchools] = useState<SchoolRow[]>([]);
  const [accountsBySchool, setAccountsBySchool] = useState<Record<number, SchoolAccounts>>({});
  const [operatorUsers, setOperatorUsers] = useState<AccountSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [showSchoolModal, setShowSchoolModal] = useState(false);
  const [showAdminModal, setShowAdminModal] = useState(false);
  const [showOpsModal, setShowOpsModal] = useState(false);
  const [accountEditTarget, setAccountEditTarget] = useState<AccountEditTarget | null>(null);
  const [operatorSchoolTarget, setOperatorSchoolTarget] = useState<AccountSummary | null>(null);
  const [showModulesModal, setShowModulesModal] = useState(false);
  const [schoolForm, setSchoolForm] = useState(defaultSchoolForm);
  const [adminForm, setAdminForm] = useState(defaultAdminForm);
  const [opsForm, setOpsForm] = useState(defaultOpsForm);
  const [opsSchoolIds, setOpsSchoolIds] = useState<Set<number>>(new Set());
  const [accountEditForm, setAccountEditForm] = useState(defaultAccountEditForm);
  const [operatorSchoolIds, setOperatorSchoolIds] = useState<Set<number>>(new Set());
  const [operatorSchoolsLoading, setOperatorSchoolsLoading] = useState(false);
  const [selectedSchool, setSelectedSchool] = useState<SchoolRow | null>(null);
  const [saving, setSaving] = useState(false);
  const [moduleSelections, setModuleSelections] = useState<Record<string, boolean>>(defaultModuleSelections);
  const [currentEntitlements, setCurrentEntitlements] = useState<Record<string, boolean>>({});
  const [modulesLoading, setModulesLoading] = useState(false);

  const activeCount = useMemo(() => schools.filter((school) => school.active).length, [schools]);

  const operatorAccounts = useMemo<OperatorAccountRow[]>(() => {
    const byKey = new Map<string, OperatorAccountRow>();
    for (const user of operatorUsers) {
      if (user.userId <= 0) continue;
      byKey.set(String(user.userId), {
        ...user,
        schoolIds: [],
        schoolNames: [],
      });
    }
    for (const school of schools) {
      const accounts = accountsBySchool[school.id]?.operators ?? [];
      for (const account of accounts) {
        if (account.userId <= 0) continue;
        const key = String(account.userId);
        const current = byKey.get(key) ?? {
          ...account,
          schoolIds: [],
          schoolNames: [],
        };
        if (!current.schoolIds.includes(school.id)) {
          current.schoolIds.push(school.id);
          current.schoolNames.push(school.name);
        }
        byKey.set(key, current);
      }
    }
    return Array.from(byKey.values()).sort((a, b) => a.email.localeCompare(b.email));
  }, [accountsBySchool, operatorUsers, schools]);

  const loadSchools = async () => {
    try {
      setLoading(true);
      setError('');
      const res = await api.get<SchoolRow[]>('/schools');
      setSchools(res.data || []);
    } catch (err: any) {
      setError(err?.response?.data?.message || err?.message || 'Unable to load schools.');
    } finally {
      setLoading(false);
    }
  };

  const loadAccountAssignments = async () => {
    try {
      const res = await api.get<RoleAssignmentRow[]>('/rbac/user-role-assignments', {
        params: { active: true, limit: 500 },
      });
      const map: Record<number, SchoolAccounts> = {};
      for (const row of res.data || []) {
        if (row.schoolId == null || row.active === false) continue;
        const role = (row.roleName || '').toUpperCase();
        if (role !== 'ADMIN' && role !== 'OPERATIONS') continue;
        const account: AccountSummary = {
          userId: row.userId,
          email: row.userEmail || `user #${row.userId}`,
          fullName: row.userFullName,
        };
        const bucket = map[row.schoolId] ?? { admins: [], operators: [] };
        if (role === 'ADMIN') addAccountOnce(bucket.admins, account);
        if (role === 'OPERATIONS') addAccountOnce(bucket.operators, account);
        map[row.schoolId] = bucket;
      }
      setAccountsBySchool(map);
    } catch {
      setAccountsBySchool({});
    }
  };

  const loadOperatorUsers = async () => {
    try {
      const res = await api.get<UserDirectoryRow[]>('/users', {
        params: { role: 'OPERATIONS', active: true, limit: 500 },
      });
      setOperatorUsers((res.data || [])
        .map((row) => ({
          userId: row.id,
          email: row.email || `user #${row.id}`,
          fullName: row.fullName,
        }))
        .filter((row) => row.userId > 0));
    } catch {
      setOperatorUsers([]);
    }
  };

  const loadAll = async () => {
    await Promise.all([loadSchools(), loadAccountAssignments(), loadOperatorUsers()]);
  };

  useEffect(() => {
    if (can('school:read')) {
      void loadAll();
    }
  }, []);

  if (!user) return <Navigate to="/login" replace />;
  if (!can('school:read')) return <Navigate to="/dashboard" replace />;

  const accountsFor = (school: SchoolRow) => accountsBySchool[school.id] ?? emptyAccounts;

  const openAdminModal = (school: SchoolRow) => {
    setSelectedSchool(school);
    setAdminForm(defaultAdminForm);
    setError('');
    setNotice('');
    setShowAdminModal(true);
  };

  const openOpsModal = () => {
    setSelectedSchool(null);
    setOpsForm(defaultOpsForm);
    setOpsSchoolIds(new Set());
    setError('');
    setNotice('');
    setShowOpsModal(true);
  };

  const openAccountEdit = (role: 'ADMIN' | 'OPERATIONS', account: AccountSummary, contextLabel?: string) => {
    setAccountEditTarget({ role, account, contextLabel });
    setAccountEditForm({
      fullName: account.fullName || '',
      email: account.email || '',
      password: '',
    });
    setError('');
    setNotice('');
  };

  const openOperatorSchools = async (account: AccountSummary) => {
    setOperatorSchoolTarget(account);
    setOperatorSchoolIds(new Set());
    setOperatorSchoolsLoading(true);
    setError('');
    setNotice('');
    try {
      const res = await api.get<Array<{ schoolId: number }>>(`/rbac/users/${account.userId}/operator-schools`);
      setOperatorSchoolIds(new Set((res.data || []).map((row) => Number(row.schoolId)).filter(Boolean)));
    } catch (err: any) {
      setError(err?.response?.data?.message || err?.message || 'Unable to load operator school assignments.');
      setOperatorSchoolTarget(null);
    } finally {
      setOperatorSchoolsLoading(false);
    }
  };

  const openModulesModal = async (school: SchoolRow) => {
    setSelectedSchool(school);
    setModulesLoading(true);
    setShowModulesModal(true);
    setError('');
    setNotice('');
    try {
      const res = await api.get<ModuleEntitlement[]>(`/schools/${school.id}/modules`);
      const enabledCodes = (res.data || [])
        .filter((entitlement) => entitlement.enabled)
        .map((entitlement) => entitlement.moduleCode);
      setCurrentEntitlements(groupModuleSelections(enabledCodes));
    } catch (err: any) {
      setError(err?.response?.data?.message || err?.message || 'Could not load module entitlements. Please try again.');
      setShowModulesModal(false);
      setSelectedSchool(null);
    } finally {
      setModulesLoading(false);
    }
  };

  const saveModuleSelections = async (schoolId: number, selections: Record<string, boolean>) => {
    const expanded = expandModuleGroupSelections(selections);
    await Promise.all(
      MODULE_CHILD_CODES.map((code) =>
        api.put(`/schools/${schoolId}/modules/${code}`, { enabled: !!expanded[code] })
      )
    );
  };

  const submitSchool = async (e: FormEvent) => {
    e.preventDefault();
    try {
      setSaving(true);
      setError('');
      const res = await api.post<{ id: number }>('/schools', {
        ...schoolForm,
        classCount: Number(schoolForm.classCount || MAX_CLASS_COUNT),
        sectionCount: Number(schoolForm.sectionCount || 2),
        academicYearStartMonth: Number(schoolForm.academicYearStartMonth || 4),
        financialYearStartMonth: Number(schoolForm.financialYearStartMonth || 4),
      });
      const schoolId = res.data?.id;
      if (schoolId) {
        await saveModuleSelections(schoolId, moduleSelections);
      }
      setShowSchoolModal(false);
      setSchoolForm(defaultSchoolForm);
      setModuleSelections(defaultModuleSelections());
      setNotice('School created successfully.');
      await loadAll();
    } catch (err: any) {
      setError(err?.response?.data?.message || err?.message || 'Unable to create school.');
    } finally {
      setSaving(false);
    }
  };

  const submitAdmin = async (e: FormEvent) => {
    e.preventDefault();
    if (!selectedSchool) return;
    try {
      setSaving(true);
      setError('');
      await api.post(`/schools/${selectedSchool.id}/admin`, adminForm);
      setShowAdminModal(false);
      setSelectedSchool(null);
      setNotice('Admin account added successfully.');
      await loadAll();
    } catch (err: any) {
      setError(err?.response?.data?.message || err?.message || 'Unable to create admin account.');
    } finally {
      setSaving(false);
    }
  };

  const submitOps = async (e: FormEvent) => {
    e.preventDefault();
    const schoolIds = Array.from(opsSchoolIds).sort((a, b) => a - b);
    if (schoolIds.length === 0) {
      setError('Select at least one school for this operator.');
      return;
    }
    try {
      setSaving(true);
      setError('');
      const primarySchoolId = schoolIds[0];
      const res = await api.post<{ userId?: number }>(`/schools/${primarySchoolId}/operations-user`, opsForm);
      const userId = Number(res.data?.userId);
      if (userId) {
        await api.post(`/rbac/users/${userId}/operator-schools`, { schoolIds });
      }
      setShowOpsModal(false);
      setOpsSchoolIds(new Set());
      setNotice('Operator account created and school assignments updated.');
      await loadAll();
    } catch (err: any) {
      setError(err?.response?.data?.message || err?.message || 'Unable to create or assign operator account.');
    } finally {
      setSaving(false);
    }
  };

  const submitAccountEdit = async (e: FormEvent) => {
    e.preventDefault();
    if (!accountEditTarget) return;
    try {
      setSaving(true);
      setError('');
      await api.patch(`/users/${accountEditTarget.account.userId}`, {
        fullName: accountEditForm.fullName,
        email: accountEditForm.email,
      });
      if (accountEditForm.password.trim()) {
        await api.post(`/users/${accountEditTarget.account.userId}/password-reset`, {
          password: accountEditForm.password.trim(),
        });
      }
      setAccountEditTarget(null);
      setAccountEditForm(defaultAccountEditForm);
      setNotice(`${accountEditTarget.role === 'ADMIN' ? 'Admin' : 'Operator'} account updated successfully.`);
      await loadAll();
    } catch (err: any) {
      setError(err?.response?.data?.message || err?.message || 'Unable to update account.');
    } finally {
      setSaving(false);
    }
  };

  const toggleOperatorSchool = (schoolId: number, checked: boolean) => {
    setOperatorSchoolIds((prev) => {
      const next = new Set(prev);
      if (checked) next.add(schoolId);
      else next.delete(schoolId);
      return next;
    });
  };

  const toggleOpsCreateSchool = (schoolId: number, checked: boolean) => {
    setOpsSchoolIds((prev) => {
      const next = new Set(prev);
      if (checked) next.add(schoolId);
      else next.delete(schoolId);
      return next;
    });
  };

  const submitOperatorSchools = async () => {
    if (!operatorSchoolTarget) return;
    try {
      setSaving(true);
      setError('');
      await api.post(`/rbac/users/${operatorSchoolTarget.userId}/operator-schools`, {
        schoolIds: Array.from(operatorSchoolIds).sort((a, b) => a - b),
      });
      setOperatorSchoolTarget(null);
      setOperatorSchoolIds(new Set());
      setNotice('Operator school assignments updated.');
      await loadAll();
    } catch (err: any) {
      setError(err?.response?.data?.message || err?.message || 'Unable to update operator school assignments.');
    } finally {
      setSaving(false);
    }
  };

  const submitModules = async () => {
    if (!selectedSchool) return;
    try {
      setSaving(true);
      setError('');
      await saveModuleSelections(selectedSchool.id, currentEntitlements);
      setShowModulesModal(false);
      setNotice(`Modules updated for ${selectedSchool.name}.`);
      setSelectedSchool(null);
    } catch (err: any) {
      setError(err?.response?.data?.message || err?.message || 'Unable to update modules.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="page-stack">
      <section className="hero">
        <div className="hero-row">
          <div>
            <div className="section-label">Superadmin rollout</div>
            <h1 className="page-title">School <em>management</em></h1>
            <p className="page-subtitle">Onboard branches, manage school admins, assign operators, and control module access.</p>
          </div>
          <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
            <span className="badge">{schools.length} schools</span>
            <span className="badge">{activeCount} active</span>
            {can('school:create') && (
              <button onClick={() => { setNotice(''); setError(''); setShowSchoolModal(true); }} className="ck-btn ck-btn-g">Add School</button>
            )}
            <Link to="/dashboard" className="ck-btn ck-btn-ghost">Back to Dashboard</Link>
          </div>
        </div>
      </section>

      {notice && <div className="hint">{notice}</div>}
      {error && <div className="error" style={{ marginBottom: 12 }}>{error}</div>}

      <div className="card">
        <div className="section-head">
          <div>
            <h2>Schools</h2>
            <p className="section-copy">Only SUPERADMIN can view and manage this onboarding table.</p>
          </div>
        </div>
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>School Name</th>
                <th>Short Code</th>
                <th>City</th>
                <th>Academic Start</th>
                <th>Financial Start</th>
                <th>Admin Emails</th>
                <th>Operator Emails</th>
                <th>Status</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {loading && (
                <tr><td colSpan={9}>Loading schools...</td></tr>
              )}
              {!loading && schools.length === 0 && (
                <tr><td colSpan={9}>No schools created yet.</td></tr>
              )}
              {schools.map((school) => {
                const accounts = accountsFor(school);
                return (
                  <tr key={school.id}>
                    <td>{school.name}</td>
                    <td>{school.shortCode}</td>
                    <td>{school.city || '-'}</td>
                    <td>{academicStartLabel(school.academicYearStartMonth)}</td>
                    <td>{financialStartLabel(school.financialYearStartMonth)}</td>
                    <td>{renderAccountEmails(accounts.admins, school.adminEmail, {
                      onEdit: (account) => openAccountEdit('ADMIN', account, school.name),
                    })}</td>
                    <td>{renderAccountEmails(accounts.operators, school.operationsEmail)}</td>
                    <td><span className="badge">{school.active ? 'Active' : 'Inactive'}</span></td>
                    <td style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                      {can('school:update') && (
                        <button className="ck-btn ck-btn-ghost" onClick={() => openAdminModal(school)}>
                          Add Admin
                        </button>
                      )}
                      {can('school:update') && (
                        <button className="ck-btn ck-btn-ghost" onClick={() => openModulesModal(school)}>
                          Modules
                        </button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>

      <div className="card">
        <div className="section-head">
          <div>
            <h2>Operator Accounts</h2>
            <p className="section-copy">Create operators once, then assign the schools whose orders they can see.</p>
          </div>
          {can('school:update') && (
            <button className="ck-btn ck-btn-g" onClick={openOpsModal}>
              Create Operator
            </button>
          )}
        </div>
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Operator</th>
                <th>Name</th>
                <th>Assigned Schools</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {loading && (
                <tr><td colSpan={4}>Loading operator accounts...</td></tr>
              )}
              {!loading && operatorAccounts.length === 0 && (
                <tr><td colSpan={4}>No operator accounts created yet.</td></tr>
              )}
              {operatorAccounts.map((account) => (
                <tr key={account.userId}>
                  <td style={{ overflowWrap: 'anywhere' }}>{account.email}</td>
                  <td>{account.fullName || '-'}</td>
                  <td style={{ minWidth: 220 }}>
                    {account.schoolNames.length > 0 ? (
                      <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                        {account.schoolNames.map((name, index) => (
                          <span className="badge" key={`${account.schoolIds[index]}:${name}`}>{name}</span>
                        ))}
                      </div>
                    ) : (
                      <span style={{ color: '#9ca3af' }}>-</span>
                    )}
                  </td>
                  <td style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                    <button
                      type="button"
                      className="ck-btn ck-btn-ghost"
                      onClick={() => openAccountEdit('OPERATIONS', account, 'Operator Accounts')}
                    >
                      Edit
                    </button>
                    <button
                      type="button"
                      className="ck-btn ck-btn-ghost"
                      onClick={() => openOperatorSchools(account)}
                    >
                      Schools
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {showSchoolModal && (
        <div className="ck-modal-bg" onClick={() => !saving && setShowSchoolModal(false)}>
          <div className="ck-modal" onClick={(e) => e.stopPropagation()} style={{ maxWidth: 600 }}>
            <div className="ck-modal-h">
              <div className="ck-modal-title">Add school</div>
              <button type="button" className="ck-modal-x" onClick={() => !saving && setShowSchoolModal(false)}>x</button>
            </div>
            <form onSubmit={submitSchool}>
              <div className="ck-modal-body">
                <div className="ck-form-grid ck-fg-2">
                  <div className="ck-field"><label>School Name</label><input value={schoolForm.name} onChange={(e) => setSchoolForm((s) => ({ ...s, name: e.target.value }))} required /></div>
                  <div className="ck-field"><label>Short Code</label><input value={schoolForm.shortCode} onChange={(e) => setSchoolForm((s) => ({ ...s, shortCode: e.target.value.toUpperCase() }))} required /></div>
                  <div className="ck-field"><label>City</label><input value={schoolForm.city} onChange={(e) => setSchoolForm((s) => ({ ...s, city: e.target.value }))} /></div>
                  <div className="ck-field"><label>State</label><input value={schoolForm.state} onChange={(e) => setSchoolForm((s) => ({ ...s, state: e.target.value }))} /></div>
                  <div className="ck-field"><label>No. of Classes</label><input type="number" min={1} max={MAX_CLASS_COUNT} value={schoolForm.classCount} onChange={(e) => setSchoolForm((s) => ({ ...s, classCount: e.target.value }))} required /></div>
                  <div className="ck-field"><label>Sections per Class</label><input type="number" min={1} max={26} value={schoolForm.sectionCount} onChange={(e) => setSchoolForm((s) => ({ ...s, sectionCount: e.target.value }))} required /></div>
                  <div className="ck-field"><label>Academic Year Starts</label><select value={schoolForm.academicYearStartMonth} onChange={(e) => setSchoolForm((s) => ({ ...s, academicYearStartMonth: e.target.value }))}>{YEAR_START_MONTHS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></div>
                  <div className="ck-field"><label>Financial Year Starts</label><select value={schoolForm.financialYearStartMonth} onChange={(e) => setSchoolForm((s) => ({ ...s, financialYearStartMonth: e.target.value }))}>{YEAR_START_MONTHS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></div>
                  <div className="ck-field"><label>Contact Email</label><input type="email" value={schoolForm.contactEmail} onChange={(e) => setSchoolForm((s) => ({ ...s, contactEmail: e.target.value }))} /></div>
                  <div className="ck-field"><label>Contact Phone</label><input value={schoolForm.contactPhone} onChange={(e) => setSchoolForm((s) => ({ ...s, contactPhone: e.target.value }))} /></div>
                </div>

                <div style={{ marginTop: 20, borderTop: '1px solid var(--border, #e5e7eb)', paddingTop: 16 }}>
                  <div style={{ fontWeight: 600, marginBottom: 4 }}>Enabled Modules</div>
                  <div style={{ fontSize: 12, color: '#6b7280', marginBottom: 12 }}>
                    Select module groups for this school.
                  </div>
                  <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 10 }}>
                    {MODULE_GROUPS.map((m) => (
                      <label
                        key={m.code}
                        style={{ display: 'flex', alignItems: 'flex-start', gap: 10, cursor: 'pointer', padding: '8px 10px', borderRadius: 8, border: '1px solid var(--border, #e5e7eb)', background: moduleSelections[m.code] ? 'var(--g1, #f0fdf4)' : '#fafafa' }}
                      >
                        <input
                          type="checkbox"
                          checked={!!moduleSelections[m.code]}
                          onChange={(e) => setModuleSelections((s) => ({ ...s, [m.code]: e.target.checked }))}
                          style={{ marginTop: 2, accentColor: 'var(--g, #16a34a)' }}
                        />
                        <div>
                          <div style={{ fontWeight: 600, fontSize: 13 }}>{m.icon} {m.label}</div>
                          <div style={{ fontSize: 11, color: '#6b7280' }}>{m.desc}</div>
                        </div>
                      </label>
                    ))}
                  </div>
                </div>
              </div>
              <div className="ck-modal-foot">
                <button type="button" className="ck-btn ck-btn-ghost" onClick={() => !saving && setShowSchoolModal(false)}>Cancel</button>
                <button type="submit" className="ck-btn ck-btn-g" disabled={saving}>{saving ? 'Saving...' : 'Create School'}</button>
              </div>
            </form>
          </div>
        </div>
      )}

      {showAdminModal && selectedSchool && (
        <div className="ck-modal-bg" onClick={() => !saving && setShowAdminModal(false)}>
          <div className="ck-modal" onClick={(e) => e.stopPropagation()}>
            <div className="ck-modal-h">
              <div>
                <div className="ck-modal-title">Add admin account</div>
                <div className="section-copy" style={{ marginTop: 6 }}>{selectedSchool.name}</div>
              </div>
              <button type="button" className="ck-modal-x" onClick={() => !saving && setShowAdminModal(false)}>x</button>
            </div>
            <form onSubmit={submitAdmin}>
              <div className="ck-modal-body">
                <div className="ck-form-grid">
                  <div className="ck-field"><label>Full Name</label><input value={adminForm.fullName} onChange={(e) => setAdminForm((s) => ({ ...s, fullName: e.target.value }))} required /></div>
                  <div className="ck-field"><label>Email</label><input type="email" value={adminForm.email} onChange={(e) => setAdminForm((s) => ({ ...s, email: e.target.value }))} required /></div>
                  <div className="ck-field"><label>Temporary Password</label><input type="password" minLength={12} autoComplete="new-password" value={adminForm.temporaryPassword} onChange={(e) => setAdminForm((s) => ({ ...s, temporaryPassword: e.target.value }))} required /></div>
                </div>
              </div>
              <div className="ck-modal-foot">
                <button type="button" className="ck-btn ck-btn-ghost" onClick={() => !saving && setShowAdminModal(false)}>Cancel</button>
                <button type="submit" className="ck-btn ck-btn-g" disabled={saving}>{saving ? 'Saving...' : 'Save Admin'}</button>
              </div>
            </form>
          </div>
        </div>
      )}

      {showOpsModal && (
        <div className="ck-modal-bg" onClick={() => !saving && setShowOpsModal(false)}>
          <div className="ck-modal" onClick={(e) => e.stopPropagation()} style={{ maxWidth: 640 }}>
            <div className="ck-modal-h">
              <div>
                <div className="ck-modal-title">Create operator account</div>
                <div className="section-copy" style={{ marginTop: 6 }}>Select the schools this operator can manage.</div>
              </div>
              <button type="button" className="ck-modal-x" onClick={() => !saving && setShowOpsModal(false)}>x</button>
            </div>
            <form onSubmit={submitOps}>
              <div className="ck-modal-body">
                <div className="ck-form-grid">
                  <div className="ck-field"><label>Full Name</label><input value={opsForm.fullName} onChange={(e) => setOpsForm((s) => ({ ...s, fullName: e.target.value }))} required /></div>
                  <div className="ck-field"><label>Email</label><input type="email" value={opsForm.email} onChange={(e) => setOpsForm((s) => ({ ...s, email: e.target.value }))} required /></div>
                  <div className="ck-field"><label>Temporary Password</label><input type="password" minLength={12} autoComplete="new-password" value={opsForm.temporaryPassword} onChange={(e) => setOpsForm((s) => ({ ...s, temporaryPassword: e.target.value }))} required /></div>
                </div>
                <div style={{ marginTop: 18 }}>
                  <div style={{ fontWeight: 600, marginBottom: 8 }}>Schools</div>
                  <div style={{ display: 'grid', gap: 10, maxHeight: 260, overflow: 'auto' }}>
                    {schools.map((school) => (
                      <label
                        key={school.id}
                        style={{ display: 'flex', alignItems: 'center', gap: 10, cursor: 'pointer', padding: '8px 10px', borderRadius: 8, border: '1px solid var(--border, #e5e7eb)', background: opsSchoolIds.has(school.id) ? 'var(--g1, #f0fdf4)' : '#fafafa' }}
                      >
                        <input
                          type="checkbox"
                          checked={opsSchoolIds.has(school.id)}
                          onChange={(e) => toggleOpsCreateSchool(school.id, e.target.checked)}
                          style={{ accentColor: 'var(--g, #16a34a)' }}
                        />
                        <span style={{ fontWeight: 600, fontSize: 13 }}>{school.name}</span>
                        <span style={{ color: '#6b7280', fontSize: 12 }}>{school.shortCode || school.city || ''}</span>
                      </label>
                    ))}
                  </div>
                </div>
              </div>
              <div className="ck-modal-foot">
                <button type="button" className="ck-btn ck-btn-ghost" onClick={() => !saving && setShowOpsModal(false)}>Cancel</button>
                <button type="submit" className="ck-btn ck-btn-g" disabled={saving || opsSchoolIds.size === 0}>{saving ? 'Saving...' : 'Create Operator'}</button>
              </div>
            </form>
          </div>
        </div>
      )}

      {accountEditTarget && (
        <div className="ck-modal-bg" onClick={() => !saving && setAccountEditTarget(null)}>
          <div className="ck-modal" onClick={(e) => e.stopPropagation()}>
            <div className="ck-modal-h">
              <div>
                <div className="ck-modal-title">Edit {accountEditTarget.role === 'ADMIN' ? 'admin' : 'operator'} account</div>
                {accountEditTarget.contextLabel && (
                  <div className="section-copy" style={{ marginTop: 6 }}>{accountEditTarget.contextLabel}</div>
                )}
              </div>
              <button type="button" className="ck-modal-x" onClick={() => !saving && setAccountEditTarget(null)}>x</button>
            </div>
            <form onSubmit={submitAccountEdit}>
              <div className="ck-modal-body">
                <div className="ck-form-grid">
                  <div className="ck-field"><label>Full Name</label><input value={accountEditForm.fullName} onChange={(e) => setAccountEditForm((s) => ({ ...s, fullName: e.target.value }))} required /></div>
                  <div className="ck-field"><label>Email</label><input type="email" value={accountEditForm.email} onChange={(e) => setAccountEditForm((s) => ({ ...s, email: e.target.value }))} required /></div>
                  <div className="ck-field"><label>New Password</label><input type="password" minLength={8} autoComplete="new-password" value={accountEditForm.password} onChange={(e) => setAccountEditForm((s) => ({ ...s, password: e.target.value }))} placeholder="Leave blank to keep current password" /></div>
                </div>
              </div>
              <div className="ck-modal-foot">
                <button type="button" className="ck-btn ck-btn-ghost" onClick={() => !saving && setAccountEditTarget(null)}>Cancel</button>
                <button type="submit" className="ck-btn ck-btn-g" disabled={saving}>{saving ? 'Saving...' : 'Save Account'}</button>
              </div>
            </form>
          </div>
        </div>
      )}

      {operatorSchoolTarget && (
        <div className="ck-modal-bg" onClick={() => !saving && setOperatorSchoolTarget(null)}>
          <div className="ck-modal" onClick={(e) => e.stopPropagation()} style={{ maxWidth: 620 }}>
            <div className="ck-modal-h">
              <div>
                <div className="ck-modal-title">Operator schools</div>
                <div className="section-copy" style={{ marginTop: 6 }}>{operatorSchoolTarget.email}</div>
              </div>
              <button type="button" className="ck-modal-x" onClick={() => !saving && setOperatorSchoolTarget(null)}>x</button>
            </div>
            <div className="ck-modal-body">
              {operatorSchoolsLoading ? (
                <div style={{ textAlign: 'center', padding: '24px 0', color: '#6b7280' }}>Loading schools...</div>
              ) : (
                <div style={{ display: 'grid', gap: 10, maxHeight: 360, overflow: 'auto' }}>
                  {schools.map((school) => (
                    <label
                      key={school.id}
                      style={{ display: 'flex', alignItems: 'center', gap: 10, cursor: 'pointer', padding: '8px 10px', borderRadius: 8, border: '1px solid var(--border, #e5e7eb)', background: operatorSchoolIds.has(school.id) ? 'var(--g1, #f0fdf4)' : '#fafafa' }}
                    >
                      <input
                        type="checkbox"
                        checked={operatorSchoolIds.has(school.id)}
                        onChange={(e) => toggleOperatorSchool(school.id, e.target.checked)}
                        style={{ accentColor: 'var(--g, #16a34a)' }}
                      />
                      <span style={{ fontWeight: 600, fontSize: 13 }}>{school.name}</span>
                      <span style={{ color: '#6b7280', fontSize: 12 }}>{school.shortCode || school.city || ''}</span>
                    </label>
                  ))}
                </div>
              )}
            </div>
            <div className="ck-modal-foot">
              <button type="button" className="ck-btn ck-btn-ghost" onClick={() => !saving && setOperatorSchoolTarget(null)}>Cancel</button>
              <button type="button" className="ck-btn ck-btn-g" disabled={saving || operatorSchoolsLoading} onClick={submitOperatorSchools}>
                {saving ? 'Saving...' : 'Save Schools'}
              </button>
            </div>
          </div>
        </div>
      )}

      {showModulesModal && selectedSchool && (
        <div className="ck-modal-bg" onClick={() => !saving && setShowModulesModal(false)}>
          <div className="ck-modal" onClick={(e) => e.stopPropagation()} style={{ maxWidth: 560 }}>
            <div className="ck-modal-h">
              <div>
                <div className="ck-modal-title">Manage modules</div>
                <div className="section-copy" style={{ marginTop: 6 }}>{selectedSchool.name}</div>
              </div>
              <button type="button" className="ck-modal-x" onClick={() => !saving && setShowModulesModal(false)}>x</button>
            </div>
            <div className="ck-modal-body">
              {modulesLoading ? (
                <div style={{ textAlign: 'center', padding: '24px 0', color: '#6b7280' }}>Loading modules...</div>
              ) : (
                <>
                  <div style={{ fontSize: 13, color: '#6b7280', marginBottom: 16 }}>
                    Toggle the module groups visible to this school.
                  </div>
                  <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 10 }}>
                    {MODULE_GROUPS.map((m) => (
                      <label
                        key={m.code}
                        style={{ display: 'flex', alignItems: 'flex-start', gap: 10, cursor: 'pointer', padding: '8px 10px', borderRadius: 8, border: '1px solid var(--border, #e5e7eb)', background: currentEntitlements[m.code] ? 'var(--g1, #f0fdf4)' : '#fafafa' }}
                      >
                        <input
                          type="checkbox"
                          checked={!!currentEntitlements[m.code]}
                          onChange={(e) => setCurrentEntitlements((s) => ({ ...s, [m.code]: e.target.checked }))}
                          style={{ marginTop: 2, accentColor: 'var(--g, #16a34a)' }}
                        />
                        <div>
                          <div style={{ fontWeight: 600, fontSize: 13 }}>{m.icon} {m.label}</div>
                          <div style={{ fontSize: 11, color: '#6b7280' }}>{m.desc}</div>
                        </div>
                      </label>
                    ))}
                  </div>
                </>
              )}
            </div>
            <div className="ck-modal-foot">
              <button type="button" className="ck-btn ck-btn-ghost" onClick={() => !saving && setShowModulesModal(false)}>Cancel</button>
              <button type="button" className="ck-btn ck-btn-g" disabled={saving || modulesLoading} onClick={submitModules}>
                {saving ? 'Saving...' : 'Save Modules'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

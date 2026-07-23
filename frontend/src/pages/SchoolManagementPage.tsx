import { useEffect, useMemo, useState } from 'react';
import type { FormEvent } from 'react';
import { Navigate, Link } from 'react-router-dom';
import {
  ArrowLeft,
  Building2,
  ChevronDown,
  CheckCircle2,
  ClipboardList,
  PackageCheck,
  Plus,
  Search,
  Settings2,
  ShieldCheck,
  SlidersHorizontal,
  UserCog,
  Users,
  X,
} from 'lucide-react';
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

type SchoolFilter = 'all' | 'active' | 'needsSetup';

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
  password: '',
  confirmPassword: ''
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

function displayAccounts(accounts: AccountSummary[], fallback?: string): AccountSummary[] {
  if (accounts.length > 0) return accounts;
  return fallback ? [{ userId: 0, email: fallback }] : [];
}

function hasDisplayAccounts(accounts: AccountSummary[], fallback?: string) {
  return displayAccounts(accounts, fallback).length > 0;
}

function schoolInitials(school: SchoolRow) {
  const words = school.name.trim().split(/\s+/).filter(Boolean);
  if (words.length >= 2) return `${words[0][0]}${words[1][0]}`.toUpperCase();
  return (school.shortCode || school.name).slice(0, 2).toUpperCase();
}

function accountTitle(account: AccountSummary) {
  return account.fullName || account.email;
}

function accountInitials(account: AccountSummary) {
  const source = account.fullName || account.email;
  const words = source.replace(/@.*/, '').trim().split(/[\s._-]+/).filter(Boolean);
  if (words.length >= 2) return `${words[0][0]}${words[1][0]}`.toUpperCase();
  return source.slice(0, 2).toUpperCase();
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
  const [adminMenuSchoolId, setAdminMenuSchoolId] = useState<number | null>(null);
  const [schoolSearch, setSchoolSearch] = useState('');
  const [schoolFilter, setSchoolFilter] = useState<SchoolFilter>('all');
  const [focusedSchoolId, setFocusedSchoolId] = useState<number | null>(null);
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

  const setupGapCount = useMemo(() => schools.filter((school) => {
    const accounts = accountsBySchool[school.id] ?? emptyAccounts;
    return !hasDisplayAccounts(accounts.admins, school.adminEmail)
      || !hasDisplayAccounts(accounts.operators, school.operationsEmail);
  }).length, [accountsBySchool, schools]);

  const unassignedOperatorCount = useMemo(
    () => operatorAccounts.filter((account) => account.schoolIds.length === 0).length,
    [operatorAccounts],
  );

  const operatorScopeCount = useMemo(
    () => operatorAccounts.reduce((count, account) => count + account.schoolIds.length, 0),
    [operatorAccounts],
  );

  const filteredSchools = useMemo(() => {
    const query = schoolSearch.trim().toLowerCase();
    return schools.filter((school) => {
      const accounts = accountsBySchool[school.id] ?? emptyAccounts;
      const adminAccounts = displayAccounts(accounts.admins, school.adminEmail);
      const operatorAccountsForSchool = displayAccounts(accounts.operators, school.operationsEmail);
      const needsSetup = adminAccounts.length === 0 || operatorAccountsForSchool.length === 0;
      if (schoolFilter === 'active' && !school.active) return false;
      if (schoolFilter === 'needsSetup' && !needsSetup) return false;
      if (!query) return true;
      const haystack = [
        school.name,
        school.shortCode,
        school.city,
        school.state,
        ...adminAccounts.map((account) => `${account.email} ${account.fullName || ''}`),
        ...operatorAccountsForSchool.map((account) => `${account.email} ${account.fullName || ''}`),
      ].filter(Boolean).join(' ').toLowerCase();
      return haystack.includes(query);
    });
  }, [accountsBySchool, schoolFilter, schoolSearch, schools]);

  const focusedSchool = useMemo(() => {
    if (filteredSchools.length === 0) {
      return focusedSchoolId == null ? schools[0] ?? null : schools.find((school) => school.id === focusedSchoolId) ?? schools[0] ?? null;
    }
    return filteredSchools.find((school) => school.id === focusedSchoolId) ?? filteredSchools[0];
  }, [filteredSchools, focusedSchoolId, schools]);

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
  const focusedAccounts = focusedSchool ? accountsFor(focusedSchool) : emptyAccounts;
  const focusedAdminAccounts = focusedSchool ? displayAccounts(focusedAccounts.admins, focusedSchool.adminEmail) : [];
  const focusedOperatorAccounts = focusedSchool ? displayAccounts(focusedAccounts.operators, focusedSchool.operationsEmail) : [];

  const openAdminModal = (school: SchoolRow) => {
    setAdminMenuSchoolId(null);
    setSelectedSchool(school);
    setAdminForm(defaultAdminForm);
    setError('');
    setNotice('');
    setShowAdminModal(true);
  };

  const openOpsModal = () => {
    setAdminMenuSchoolId(null);
    setSelectedSchool(null);
    setOpsForm(defaultOpsForm);
    setOpsSchoolIds(new Set());
    setError('');
    setNotice('');
    setShowOpsModal(true);
  };

  const openAccountEdit = (role: 'ADMIN' | 'OPERATIONS', account: AccountSummary, contextLabel?: string) => {
    setAdminMenuSchoolId(null);
    setAccountEditTarget({ role, account, contextLabel });
    setAccountEditForm({
      fullName: account.fullName || '',
      email: account.email || '',
      password: '',
      confirmPassword: '',
    });
    setError('');
    setNotice('');
  };

  const openOperatorSchools = async (account: AccountSummary) => {
    setAdminMenuSchoolId(null);
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
    setAdminMenuSchoolId(null);
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
      MODULE_CHILD_CODES.map((code) => (
        expanded[code]
          ? api.put(`/schools/${schoolId}/modules/${code}`, { enabled: true })
          : api.delete(`/schools/${schoolId}/modules/${code}`)
      ))
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
    const newPassword = accountEditForm.password.trim();
    const confirmPassword = accountEditForm.confirmPassword.trim();
    if (newPassword || confirmPassword) {
      if (!newPassword) {
        setError('Enter a new password before confirming it.');
        return;
      }
      if (newPassword !== confirmPassword) {
        setError('Password confirmation does not match.');
        return;
      }
    }
    try {
      setSaving(true);
      setError('');
      await api.patch(`/users/${accountEditTarget.account.userId}`, {
        fullName: accountEditForm.fullName,
        email: accountEditForm.email,
      });
      if (newPassword) {
        await api.post(`/users/${accountEditTarget.account.userId}/password-reset`, {
          password: newPassword,
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
    <div className="page-stack sms-page" onClick={() => setAdminMenuSchoolId(null)}>
      <section className="hero sms-hero">
        <div className="hero-row">
          <div className="sms-hero-copy">
            <div className="section-label">Superadmin rollout</div>
            <h1 className="page-title">School <em>management</em></h1>
            <p className="page-subtitle">Onboard branches, manage school admins, assign operators, and control module access.</p>
          </div>
          <div className="sms-hero-actions">
            <span className="badge">{schools.length} schools</span>
            <span className="badge">{activeCount} active</span>
            {can('school:create') && (
              <button type="button" onClick={() => { setNotice(''); setError(''); setShowSchoolModal(true); }} className="ck-btn ck-btn-g">
                <Plus size={16} /> Add School
              </button>
            )}
            <Link to="/dashboard" className="ck-btn ck-btn-ghost"><ArrowLeft size={16} /> Dashboard</Link>
          </div>
        </div>
      </section>

      {notice && <div className="hint">{notice}</div>}
      {error && <div className="error" style={{ marginBottom: 12 }}>{error}</div>}

      <section className="sms-stats" aria-label="School management summary">
        <div className="sms-stat">
          <div className="sms-stat-icon g"><Building2 size={18} /></div>
          <div className="sms-stat-body"><span>Total schools</span><strong>{schools.length}</strong><small>{activeCount} active</small></div>
        </div>
        <div className="sms-stat">
          <div className="sms-stat-icon am"><SlidersHorizontal size={18} /></div>
          <div className="sms-stat-body"><span>Setup gaps</span><strong>{setupGapCount}</strong><small>Missing admin or operator</small></div>
        </div>
        <div className="sms-stat">
          <div className="sms-stat-icon b"><Users size={18} /></div>
          <div className="sms-stat-body"><span>Operators</span><strong>{operatorAccounts.length}</strong><small>{unassignedOperatorCount} unassigned</small></div>
        </div>
        <div className="sms-stat">
          <div className="sms-stat-icon p"><PackageCheck size={18} /></div>
          <div className="sms-stat-body"><span>School scopes</span><strong>{operatorScopeCount}</strong><small>Operator-school assignments</small></div>
        </div>
      </section>

      <section className="sms-console">
        <div className="sms-main-panel">
          <div className="sms-panel-head">
            <div>
              <h2>Schools</h2>
              <p className="section-copy">Scan setup health and jump into the school-specific admin and module actions.</p>
            </div>
            <div className="sms-filter-row">
              <label className="sms-search">
                <Search size={16} />
                <input
                  aria-label="Search schools"
                  value={schoolSearch}
                  onChange={(event) => setSchoolSearch(event.target.value)}
                  placeholder="Search school, city, admin..."
                />
              </label>
              <div className="sms-segment" aria-label="School filter">
                {([
                  ['all', 'All'],
                  ['active', 'Active'],
                  ['needsSetup', 'Needs setup'],
                ] as const).map(([value, label]) => (
                  <button
                    key={value}
                    type="button"
                    className={schoolFilter === value ? 'on' : ''}
                    aria-pressed={schoolFilter === value}
                    onClick={() => setSchoolFilter(value)}
                  >
                    {label}
                  </button>
                ))}
              </div>
            </div>
          </div>

          <div className="sms-school-list">
            <div className="sms-school-row sms-school-header">
              <span>School</span>
              <span>Year</span>
              <span>Admins</span>
              <span>Operators</span>
              <span>Actions</span>
            </div>
            {loading && <div className="sms-empty">Loading schools...</div>}
            {!loading && schools.length === 0 && <div className="sms-empty">No schools created yet.</div>}
            {!loading && schools.length > 0 && filteredSchools.length === 0 && <div className="sms-empty">No schools match the current filter.</div>}
            {!loading && filteredSchools.map((school) => {
              const accounts = accountsFor(school);
              const adminAccounts = displayAccounts(accounts.admins, school.adminEmail);
              const editableAdminAccounts = adminAccounts.filter((account) => account.userId > 0);
              const operatorAccountsForSchool = displayAccounts(accounts.operators, school.operationsEmail);
              const needsAdmin = editableAdminAccounts.length === 0;
              const needsOperator = operatorAccountsForSchool.length === 0;
              const selected = focusedSchool?.id === school.id;
              const adminMenuOpen = adminMenuSchoolId === school.id;
              return (
                <div
                  key={school.id}
                  className={`sms-school-row ${selected ? 'selected' : ''}`}
                  role="button"
                  tabIndex={0}
                  aria-label={`Select ${school.name}`}
                  onClick={() => setFocusedSchoolId(school.id)}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' || event.key === ' ') {
                      event.preventDefault();
                      setFocusedSchoolId(school.id);
                    }
                  }}
                >
                  <div className="sms-school-title">
                    <span className="sms-school-mark">{schoolInitials(school)}</span>
                    <div>
                      <strong>{school.name}</strong>
                      <small>{school.shortCode || 'No code'}{school.city ? ` - ${school.city}` : ''}</small>
                      <div className="sms-chip-row">
                        <span className={`sms-chip ${school.active ? 'green' : 'red'}`}>{school.active ? 'Active' : 'Inactive'}</span>
                        {needsAdmin && <span className="sms-chip amber">Needs admin</span>}
                        {needsOperator && <span className="sms-chip amber">Needs operator</span>}
                      </div>
                    </div>
                  </div>
                  <div>
                    <strong>{academicStartLabel(school.academicYearStartMonth)}</strong>
                    <small>Financial: {financialStartLabel(school.financialYearStartMonth)}</small>
                  </div>
                  <div className="sms-chip-row">
                    {adminAccounts.length === 0
                      ? <span className="sms-chip red">No admin</span>
                      : adminAccounts.slice(0, 2).map((account) => (
                        <span className="sms-account-summary" key={`${school.id}:admin:${account.userId}:${account.email}`}>
                          <strong>{accountTitle(account)}</strong>
                          <small>{account.email}</small>
                        </span>
                      ))}
                    {adminAccounts.length > 2 && <span className="sms-chip">{adminAccounts.length - 2} more</span>}
                  </div>
                  <div className="sms-chip-row">
                    {operatorAccountsForSchool.length === 0
                      ? <span className="sms-chip amber">No operator</span>
                      : operatorAccountsForSchool.slice(0, 2).map((account) => (
                        <span className="sms-chip blue" key={`${school.id}:operator:${account.userId}:${account.email}`}>{account.email}</span>
                      ))}
                    {operatorAccountsForSchool.length > 2 && <span className="sms-chip">{operatorAccountsForSchool.length - 2} more</span>}
                  </div>
                  <div className="sms-row-actions">
                    {can('school:update') && (
                      <div className="sms-admin-menu-wrap" onClick={(event) => event.stopPropagation()}>
                        <button
                          type="button"
                          className={`ck-btn ${editableAdminAccounts.length === 0 ? 'ck-btn-g' : 'ck-btn-ghost'} ck-btn-sm`}
                          aria-haspopup={editableAdminAccounts.length > 0 ? 'menu' : undefined}
                          aria-expanded={editableAdminAccounts.length > 0 ? adminMenuOpen : undefined}
                          aria-label={editableAdminAccounts.length > 0 ? `Manage admins for ${school.name}` : `Add admin for ${school.name}`}
                          onClick={(event) => {
                            event.stopPropagation();
                            if (editableAdminAccounts.length === 0) {
                              openAdminModal(school);
                              return;
                            }
                            setFocusedSchoolId(school.id);
                            setAdminMenuSchoolId(adminMenuOpen ? null : school.id);
                          }}
                        >
                          <UserCog size={14} /> {editableAdminAccounts.length === 0 ? 'Add Admin' : 'Admin'}
                          {editableAdminAccounts.length > 0 && <ChevronDown size={14} />}
                        </button>
                        {adminMenuOpen && editableAdminAccounts.length > 0 && (
                          <div className="sms-admin-menu" role="menu" aria-label={`Admin users for ${school.name}`}>
                            <div className="sms-admin-menu-title">Select admin to edit</div>
                            {editableAdminAccounts.map((account) => (
                              <button
                                type="button"
                                role="menuitem"
                                key={`menu-admin:${school.id}:${account.userId}`}
                                onClick={() => openAccountEdit('ADMIN', account, school.name)}
                              >
                                <span>
                                  <strong>{accountTitle(account)}</strong>
                                  <small>{account.email}</small>
                                </span>
                              </button>
                            ))}
                            <button
                              type="button"
                              role="menuitem"
                              className="sms-admin-menu-add"
                              onClick={() => openAdminModal(school)}
                            >
                              <Plus size={14} /> Add new admin
                            </button>
                          </div>
                        )}
                      </div>
                    )}
                    {can('school:update') && (
                      <button
                        type="button"
                        className="ck-btn ck-btn-ghost ck-btn-sm"
                        onClick={(event) => { event.stopPropagation(); openModulesModal(school); }}
                      >
                        <Settings2 size={14} /> Modules
                      </button>
                    )}
                  </div>
                </div>
              );
            })}
          </div>

          {focusedSchool && (
            <div className="sms-school-detail">
              <div className="sms-detail-cell">
                <div className="sms-detail-label"><Building2 size={14} /> Selected school</div>
                <strong>{focusedSchool.name}</strong>
                <span>{focusedSchool.shortCode || 'No code'}{focusedSchool.city ? ` - ${focusedSchool.city}` : ''}</span>
              </div>
              <div className="sms-detail-cell">
                <div className="sms-detail-label"><ShieldCheck size={14} /> Admin accounts</div>
                <div className="sms-chip-row">
                  {focusedAdminAccounts.length === 0
                    ? <span className="sms-chip red">No admin assigned</span>
                    : focusedAdminAccounts.map((account) => (
                      <span className="sms-chip" key={`focused-admin:${account.userId}:${account.email}`}>
                        {accountTitle(account)}
                      </span>
                    ))}
                </div>
              </div>
              <div className="sms-detail-cell">
                <div className="sms-detail-label"><ClipboardList size={14} /> Operator scope</div>
                <div className="sms-chip-row">
                  {focusedOperatorAccounts.length === 0
                    ? <span className="sms-chip amber">No operator assigned</span>
                    : focusedOperatorAccounts.map((account) => (
                      <span className="sms-chip blue" key={`focused-operator:${account.userId}:${account.email}`}>
                        {accountTitle(account)}
                        {account.userId > 0 && (
                          <button type="button" aria-label={`Manage schools for ${accountTitle(account)}`} onClick={() => openOperatorSchools(account)}>Schools</button>
                        )}
                      </span>
                    ))}
                </div>
              </div>
              <div className="sms-detail-actions">
                {can('school:update') && <button type="button" className="ck-btn ck-btn-g" onClick={() => openModulesModal(focusedSchool)}><Settings2 size={15} /> Manage Modules</button>}
              </div>
            </div>
          )}
        </div>

        <aside className="sms-operator-panel" aria-label="Operator accounts">
          <div className="sms-panel-head compact">
            <div>
              <h2>Operator accounts</h2>
              <p className="section-copy">Assign each operator to the schools whose orders they can see.</p>
            </div>
            {can('school:update') && (
              <button type="button" className="ck-btn ck-btn-b ck-btn-sm" onClick={openOpsModal}>
                <Plus size={14} /> Create
              </button>
            )}
          </div>

          <div className="sms-operator-list">
            {loading && <div className="sms-empty">Loading operator accounts...</div>}
            {!loading && operatorAccounts.length === 0 && <div className="sms-empty">No operator accounts created yet.</div>}
            {!loading && operatorAccounts.map((account) => (
              <div className="sms-operator-row" key={account.userId}>
                <div className="sms-operator-top">
                  <div className="sms-operator-identity">
                    <span className="sms-account-mark">{accountInitials(account)}</span>
                    <div>
                      <strong>{account.fullName || 'Operator account'}</strong>
                      <small>{account.email}</small>
                    </div>
                  </div>
                  <span className={`sms-chip ${account.schoolIds.length === 0 ? 'amber' : 'green'}`}>
                    {account.schoolIds.length === 0 ? 'Unassigned' : `${account.schoolIds.length} schools`}
                  </span>
                </div>
                <div className="sms-chip-row">
                  {account.schoolNames.slice(0, 3).map((name, index) => (
                    <span className="sms-chip" key={`${account.userId}:school:${account.schoolIds[index]}`}>{name}</span>
                  ))}
                  {account.schoolNames.length > 3 && <span className="sms-chip">{account.schoolNames.length - 3} more</span>}
                  {account.schoolNames.length === 0 && <span className="sms-chip amber">Assign schools</span>}
                </div>
                <div className="sms-operator-actions">
                  <button type="button" className="ck-btn ck-btn-ghost ck-btn-sm" onClick={() => openAccountEdit('OPERATIONS', account, 'Operator Accounts')}>
                    <UserCog size={14} /> Edit
                  </button>
                  <button type="button" className="ck-btn ck-btn-ghost ck-btn-sm" onClick={() => openOperatorSchools(account)}>
                    <CheckCircle2 size={14} /> Schools
                  </button>
                </div>
              </div>
            ))}
          </div>
        </aside>
      </section>

      {showSchoolModal && (
        <div className="ck-modal-bg" onClick={() => !saving && setShowSchoolModal(false)}>
          <div className="ck-modal" role="dialog" aria-modal="true" aria-label="Add school" onClick={(e) => e.stopPropagation()} style={{ maxWidth: 640 }}>
            <div className="ck-modal-h">
              <div className="ck-modal-title">Add school</div>
              <button type="button" className="ck-modal-x" aria-label="Close add school" onClick={() => !saving && setShowSchoolModal(false)}><X size={16} /></button>
            </div>
            <form onSubmit={submitSchool}>
              <div className="ck-modal-body">
                <div className="ck-form-grid ck-fg-2">
                  <div className="ck-field"><label>School Name</label><input aria-label="School Name" value={schoolForm.name} onChange={(e) => setSchoolForm((s) => ({ ...s, name: e.target.value }))} required /></div>
                  <div className="ck-field"><label>Short Code</label><input aria-label="Short Code" value={schoolForm.shortCode} onChange={(e) => setSchoolForm((s) => ({ ...s, shortCode: e.target.value.toUpperCase() }))} required /></div>
                  <div className="ck-field"><label>City</label><input aria-label="City" value={schoolForm.city} onChange={(e) => setSchoolForm((s) => ({ ...s, city: e.target.value }))} /></div>
                  <div className="ck-field"><label>State</label><input aria-label="State" value={schoolForm.state} onChange={(e) => setSchoolForm((s) => ({ ...s, state: e.target.value }))} /></div>
                  <div className="ck-field"><label>No. of Classes</label><input aria-label="No. of Classes" type="number" min={1} max={MAX_CLASS_COUNT} value={schoolForm.classCount} onChange={(e) => setSchoolForm((s) => ({ ...s, classCount: e.target.value }))} required /></div>
                  <div className="ck-field"><label>Sections per Class</label><input aria-label="Sections per Class" type="number" min={1} max={26} value={schoolForm.sectionCount} onChange={(e) => setSchoolForm((s) => ({ ...s, sectionCount: e.target.value }))} required /></div>
                  <div className="ck-field"><label>Academic Year Starts</label><select aria-label="Academic Year Starts" value={schoolForm.academicYearStartMonth} onChange={(e) => setSchoolForm((s) => ({ ...s, academicYearStartMonth: e.target.value }))}>{YEAR_START_MONTHS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></div>
                  <div className="ck-field"><label>Financial Year Starts</label><select aria-label="Financial Year Starts" value={schoolForm.financialYearStartMonth} onChange={(e) => setSchoolForm((s) => ({ ...s, financialYearStartMonth: e.target.value }))}>{YEAR_START_MONTHS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></div>
                  <div className="ck-field"><label>Contact Email</label><input aria-label="Contact Email" type="email" value={schoolForm.contactEmail} onChange={(e) => setSchoolForm((s) => ({ ...s, contactEmail: e.target.value }))} /></div>
                  <div className="ck-field"><label>Contact Phone</label><input aria-label="Contact Phone" value={schoolForm.contactPhone} onChange={(e) => setSchoolForm((s) => ({ ...s, contactPhone: e.target.value }))} /></div>
                </div>

                <div className="sms-modal-section">
                  <div className="sms-modal-section-title">Enabled modules</div>
                  <div className="sms-modal-section-copy">
                    Select module groups for this school.
                  </div>
                  <div className="sms-choice-grid">
                    {MODULE_GROUPS.map((m) => (
                      <label
                        key={m.code}
                        className={`sms-choice ${moduleSelections[m.code] ? 'selected' : ''}`}
                      >
                        <input
                          aria-label={m.label}
                          type="checkbox"
                          checked={!!moduleSelections[m.code]}
                          onChange={(e) => setModuleSelections((s) => ({ ...s, [m.code]: e.target.checked }))}
                        />
                        <div>
                          <div className="sms-choice-title">{m.icon} {m.label}</div>
                          <div className="sms-choice-copy">{m.desc}</div>
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
          <div className="ck-modal" role="dialog" aria-modal="true" aria-label="Add admin account" onClick={(e) => e.stopPropagation()}>
            <div className="ck-modal-h">
              <div>
                <div className="ck-modal-title">Add admin account</div>
                <div className="section-copy" style={{ marginTop: 6 }}>{selectedSchool.name}</div>
              </div>
              <button type="button" className="ck-modal-x" aria-label="Close add admin account" onClick={() => !saving && setShowAdminModal(false)}><X size={16} /></button>
            </div>
            <form onSubmit={submitAdmin}>
              <div className="ck-modal-body">
                <div className="ck-form-grid">
                  <div className="ck-field"><label>Full Name</label><input aria-label="Full Name" value={adminForm.fullName} onChange={(e) => setAdminForm((s) => ({ ...s, fullName: e.target.value }))} required /></div>
                  <div className="ck-field"><label>Email</label><input aria-label="Email" type="email" value={adminForm.email} onChange={(e) => setAdminForm((s) => ({ ...s, email: e.target.value }))} required /></div>
                  <div className="ck-field"><label>Temporary Password</label><input aria-label="Temporary Password" type="password" minLength={12} autoComplete="new-password" value={adminForm.temporaryPassword} onChange={(e) => setAdminForm((s) => ({ ...s, temporaryPassword: e.target.value }))} required /></div>
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
          <div className="ck-modal" role="dialog" aria-modal="true" aria-label="Create operator account" onClick={(e) => e.stopPropagation()} style={{ maxWidth: 660 }}>
            <div className="ck-modal-h">
              <div>
                <div className="ck-modal-title">Create operator account</div>
                <div className="section-copy" style={{ marginTop: 6 }}>Select the schools this operator can manage.</div>
              </div>
              <button type="button" className="ck-modal-x" aria-label="Close create operator account" onClick={() => !saving && setShowOpsModal(false)}><X size={16} /></button>
            </div>
            <form onSubmit={submitOps}>
              <div className="ck-modal-body">
                <div className="ck-form-grid">
                  <div className="ck-field"><label>Full Name</label><input aria-label="Full Name" value={opsForm.fullName} onChange={(e) => setOpsForm((s) => ({ ...s, fullName: e.target.value }))} required /></div>
                  <div className="ck-field"><label>Email</label><input aria-label="Email" type="email" value={opsForm.email} onChange={(e) => setOpsForm((s) => ({ ...s, email: e.target.value }))} required /></div>
                  <div className="ck-field"><label>Temporary Password</label><input aria-label="Temporary Password" type="password" minLength={12} autoComplete="new-password" value={opsForm.temporaryPassword} onChange={(e) => setOpsForm((s) => ({ ...s, temporaryPassword: e.target.value }))} required /></div>
                </div>
                <div className="sms-modal-section">
                  <div className="sms-modal-section-title">Schools</div>
                  <div className="sms-choice-list">
                    {schools.map((school) => (
                      <label
                        key={school.id}
                        className={`sms-choice compact ${opsSchoolIds.has(school.id) ? 'selected' : ''}`}
                      >
                        <input
                          type="checkbox"
                          checked={opsSchoolIds.has(school.id)}
                          onChange={(e) => toggleOpsCreateSchool(school.id, e.target.checked)}
                        />
                        <span className="sms-choice-title">{school.name}</span>
                        <span className="sms-choice-copy">{school.shortCode || school.city || ''}</span>
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
          <div className="ck-modal" role="dialog" aria-modal="true" aria-label={`Edit ${accountEditTarget.role === 'ADMIN' ? 'admin' : 'operator'} account`} onClick={(e) => e.stopPropagation()}>
            <div className="ck-modal-h">
              <div>
                <div className="ck-modal-title">Edit {accountEditTarget.role === 'ADMIN' ? 'admin' : 'operator'} account</div>
                {accountEditTarget.contextLabel && (
                  <div className="section-copy" style={{ marginTop: 6 }}>{accountEditTarget.contextLabel}</div>
                )}
              </div>
              <button type="button" className="ck-modal-x" aria-label="Close edit account" onClick={() => !saving && setAccountEditTarget(null)}><X size={16} /></button>
            </div>
            <form onSubmit={submitAccountEdit}>
              <div className="ck-modal-body">
                <div className="ck-form-grid">
                  <div className="ck-field"><label>Full Name</label><input aria-label="Full Name" value={accountEditForm.fullName} onChange={(e) => setAccountEditForm((s) => ({ ...s, fullName: e.target.value }))} required /></div>
                  <div className="ck-field"><label>Email</label><input aria-label="Email" type="email" value={accountEditForm.email} onChange={(e) => setAccountEditForm((s) => ({ ...s, email: e.target.value }))} required /></div>
                  <div className="ck-field"><label>New Password</label><input aria-label="New Password" type="password" minLength={8} autoComplete="new-password" value={accountEditForm.password} onChange={(e) => setAccountEditForm((s) => ({ ...s, password: e.target.value }))} placeholder="Leave blank to keep current password" /></div>
                  <div className="ck-field"><label>Confirm Password</label><input aria-label="Confirm Password" type="password" minLength={8} autoComplete="new-password" value={accountEditForm.confirmPassword} onChange={(e) => setAccountEditForm((s) => ({ ...s, confirmPassword: e.target.value }))} placeholder="Required when changing password" /></div>
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
          <div className="ck-modal" role="dialog" aria-modal="true" aria-label="Operator schools" onClick={(e) => e.stopPropagation()} style={{ maxWidth: 640 }}>
            <div className="ck-modal-h">
              <div>
                <div className="ck-modal-title">Operator schools</div>
                <div className="section-copy" style={{ marginTop: 6 }}>{operatorSchoolTarget.email}</div>
              </div>
              <button type="button" className="ck-modal-x" aria-label="Close operator schools" onClick={() => !saving && setOperatorSchoolTarget(null)}><X size={16} /></button>
            </div>
            <div className="ck-modal-body">
              {operatorSchoolsLoading ? (
                <div style={{ textAlign: 'center', padding: '24px 0', color: '#6b7280' }}>Loading schools...</div>
              ) : (
                <div className="sms-choice-list tall">
                  {schools.map((school) => (
                    <label
                      key={school.id}
                      className={`sms-choice compact ${operatorSchoolIds.has(school.id) ? 'selected' : ''}`}
                    >
                      <input
                        type="checkbox"
                        checked={operatorSchoolIds.has(school.id)}
                        onChange={(e) => toggleOperatorSchool(school.id, e.target.checked)}
                      />
                      <span className="sms-choice-title">{school.name}</span>
                      <span className="sms-choice-copy">{school.shortCode || school.city || ''}</span>
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
          <div className="ck-modal" role="dialog" aria-modal="true" aria-label="Manage modules" onClick={(e) => e.stopPropagation()} style={{ maxWidth: 600 }}>
            <div className="ck-modal-h">
              <div>
                <div className="ck-modal-title">Manage modules</div>
                <div className="section-copy" style={{ marginTop: 6 }}>{selectedSchool.name}</div>
              </div>
              <button type="button" className="ck-modal-x" aria-label="Close manage modules" onClick={() => !saving && setShowModulesModal(false)}><X size={16} /></button>
            </div>
            <div className="ck-modal-body">
              {modulesLoading ? (
                <div style={{ textAlign: 'center', padding: '24px 0', color: '#6b7280' }}>Loading modules...</div>
              ) : (
                <>
                  <div className="sms-modal-section-copy" style={{ marginBottom: 16 }}>
                    Toggle the module groups visible to this school.
                  </div>
                  <div className="sms-choice-grid">
                    {MODULE_GROUPS.map((m) => (
                      <label
                        key={m.code}
                        className={`sms-choice ${currentEntitlements[m.code] ? 'selected' : ''}`}
                      >
                        <input
                          aria-label={m.label}
                          type="checkbox"
                          checked={!!currentEntitlements[m.code]}
                          onChange={(e) => setCurrentEntitlements((s) => ({ ...s, [m.code]: e.target.checked }))}
                        />
                        <div>
                          <div className="sms-choice-title">{m.icon} {m.label}</div>
                          <div className="sms-choice-copy">{m.desc}</div>
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

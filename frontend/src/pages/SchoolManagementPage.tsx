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

const defaultSchoolForm = {
  name: '',
  shortCode: '',
  city: '',
  state: '',
  contactEmail: '',
  contactPhone: '',
  classCount: '12',
  sectionCount: '2'
};

const defaultAdminForm = {
  fullName: '',
  email: '',
  temporaryPassword: 'Welcome@123'
};

const defaultOpsForm = {
  fullName: '',
  email: '',
  temporaryPassword: 'Welcome@123'
};

const defaultModuleSelections = (): Record<string, boolean> =>
  Object.fromEntries(MODULE_GROUPS.map((m) => [m.code, true]));

const emptyAccounts: SchoolAccounts = { admins: [], operators: [] };

function addAccountOnce(list: AccountSummary[], account: AccountSummary) {
  if (!account.email) return;
  const exists = list.some((item) => item.userId === account.userId || item.email.toLowerCase() === account.email.toLowerCase());
  if (!exists) list.push(account);
}

function renderAccountEmails(accounts: AccountSummary[], fallback?: string) {
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
            display: 'inline-block',
            overflowWrap: 'anywhere',
            fontSize: 13,
            lineHeight: 1.35,
          }}
        >
          {account.email}
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
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [showSchoolModal, setShowSchoolModal] = useState(false);
  const [showAdminModal, setShowAdminModal] = useState(false);
  const [showOpsModal, setShowOpsModal] = useState(false);
  const [showModulesModal, setShowModulesModal] = useState(false);
  const [schoolForm, setSchoolForm] = useState(defaultSchoolForm);
  const [adminForm, setAdminForm] = useState(defaultAdminForm);
  const [opsForm, setOpsForm] = useState(defaultOpsForm);
  const [selectedSchool, setSelectedSchool] = useState<SchoolRow | null>(null);
  const [saving, setSaving] = useState(false);
  const [moduleSelections, setModuleSelections] = useState<Record<string, boolean>>(defaultModuleSelections);
  const [currentEntitlements, setCurrentEntitlements] = useState<Record<string, boolean>>({});
  const [modulesLoading, setModulesLoading] = useState(false);

  const activeCount = useMemo(() => schools.filter((school) => school.active).length, [schools]);

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

  const loadAll = async () => {
    await Promise.all([loadSchools(), loadAccountAssignments()]);
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

  const openOpsModal = (school: SchoolRow) => {
    setSelectedSchool(school);
    setOpsForm(defaultOpsForm);
    setError('');
    setNotice('');
    setShowOpsModal(true);
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
        classCount: Number(schoolForm.classCount || 12),
        sectionCount: Number(schoolForm.sectionCount || 2),
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
    if (!selectedSchool) return;
    try {
      setSaving(true);
      setError('');
      await api.post(`/schools/${selectedSchool.id}/operations-user`, opsForm);
      setShowOpsModal(false);
      setSelectedSchool(null);
      setNotice('Operator account assigned to the school.');
      await loadAll();
    } catch (err: any) {
      setError(err?.response?.data?.message || err?.message || 'Unable to create or assign operator account.');
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
                <th>Admin Emails</th>
                <th>Operator Emails</th>
                <th>Status</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {loading && (
                <tr><td colSpan={7}>Loading schools...</td></tr>
              )}
              {!loading && schools.length === 0 && (
                <tr><td colSpan={7}>No schools created yet.</td></tr>
              )}
              {schools.map((school) => {
                const accounts = accountsFor(school);
                return (
                  <tr key={school.id}>
                    <td>{school.name}</td>
                    <td>{school.shortCode}</td>
                    <td>{school.city || '-'}</td>
                    <td>{renderAccountEmails(accounts.admins, school.adminEmail)}</td>
                    <td>{renderAccountEmails(accounts.operators, school.operationsEmail)}</td>
                    <td><span className="badge">{school.active ? 'Active' : 'Inactive'}</span></td>
                    <td style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                      {can('school:update') && (
                        <button className="ck-btn ck-btn-ghost" onClick={() => openAdminModal(school)}>
                          Add Admin
                        </button>
                      )}
                      {can('school:update') && (
                        <button className="ck-btn ck-btn-ghost" onClick={() => openOpsModal(school)}>
                          Add Operator
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
                  <div className="ck-field"><label>No. of Classes</label><input type="number" min={1} max={12} value={schoolForm.classCount} onChange={(e) => setSchoolForm((s) => ({ ...s, classCount: e.target.value }))} required /></div>
                  <div className="ck-field"><label>Sections per Class</label><input type="number" min={1} max={26} value={schoolForm.sectionCount} onChange={(e) => setSchoolForm((s) => ({ ...s, sectionCount: e.target.value }))} required /></div>
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
                  <div className="ck-field"><label>Temporary Password</label><input value={adminForm.temporaryPassword} onChange={(e) => setAdminForm((s) => ({ ...s, temporaryPassword: e.target.value }))} required /></div>
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

      {showOpsModal && selectedSchool && (
        <div className="ck-modal-bg" onClick={() => !saving && setShowOpsModal(false)}>
          <div className="ck-modal" onClick={(e) => e.stopPropagation()}>
            <div className="ck-modal-h">
              <div>
                <div className="ck-modal-title">Add operator account</div>
                <div className="section-copy" style={{ marginTop: 6 }}>{selectedSchool.name}</div>
              </div>
              <button type="button" className="ck-modal-x" onClick={() => !saving && setShowOpsModal(false)}>x</button>
            </div>
            <form onSubmit={submitOps}>
              <div className="ck-modal-body">
                <div className="ck-form-grid">
                  <div className="ck-field"><label>Full Name</label><input value={opsForm.fullName} onChange={(e) => setOpsForm((s) => ({ ...s, fullName: e.target.value }))} required /></div>
                  <div className="ck-field"><label>Email</label><input type="email" value={opsForm.email} onChange={(e) => setOpsForm((s) => ({ ...s, email: e.target.value }))} required /></div>
                  <div className="ck-field"><label>Temporary Password</label><input value={opsForm.temporaryPassword} onChange={(e) => setOpsForm((s) => ({ ...s, temporaryPassword: e.target.value }))} required /></div>
                </div>
              </div>
              <div className="ck-modal-foot">
                <button type="button" className="ck-btn ck-btn-ghost" onClick={() => !saving && setShowOpsModal(false)}>Cancel</button>
                <button type="submit" className="ck-btn ck-btn-g" disabled={saving}>{saving ? 'Saving...' : 'Save Operator'}</button>
              </div>
            </form>
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

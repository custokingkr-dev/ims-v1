import { useEffect, useMemo, useState } from 'react';
import { Navigate, Link } from 'react-router-dom';
import api from '../services/api';
import { useAuth } from '../contexts/AuthContext';
import { usePermissions } from '../hooks/usePermissions';
import { ALL_MODULES } from './workspace/config';

type SchoolRow = {
  id: number;
  name: string;
  shortCode: string;
  city: string;
  active: boolean;
  adminEmail: string;
  operationsEmail: string;
};

type ModuleEntitlement = {
  moduleCode: string;
  enabled: boolean;
};

type OperatorAssignmentRow = {
  userId: number;
  schoolId: number | null;
  roleName: string;
  active: boolean;
};

type OperatorSchoolRow = {
  schoolId: number;
  schoolName?: string;
};

type AssignTarget = {
  userId: number;
  email: string;
  schoolName: string;
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
  Object.fromEntries(ALL_MODULES.map((m) => [m.code, true]));

export default function SchoolManagementPage() {
  const { user } = useAuth();
  const { can } = usePermissions();
  const [schools, setSchools] = useState<SchoolRow[]>([]);
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

  // Module selections for new school creation (all enabled by default)
  const [moduleSelections, setModuleSelections] = useState<Record<string, boolean>>(defaultModuleSelections);

  // Module entitlements for an existing school being edited
  const [currentEntitlements, setCurrentEntitlements] = useState<Record<string, boolean>>({});
  const [modulesLoading, setModulesLoading] = useState(false);

  // Maps schoolId -> the userId of that school's currently-active OPERATIONS user.
  // Resolved from GET /rbac/user-role-assignments (superadmin-only) since the /schools
  // list itself does not surface the operator's userId, only its display email.
  const [operatorUserIdBySchool, setOperatorUserIdBySchool] = useState<Record<number, number>>({});
  const [showAssignModal, setShowAssignModal] = useState(false);
  const [assignTarget, setAssignTarget] = useState<AssignTarget | null>(null);
  const [assignSelectedSchoolIds, setAssignSelectedSchoolIds] = useState<Set<number>>(new Set());
  const [assignLoading, setAssignLoading] = useState(false);

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

  // Best-effort resolution of each school's active OPERATIONS user id, so the
  // "Assign schools" action knows which operator to sync. Non-critical: if this
  // call fails (or the caller lacks superadmin scope) the table still renders,
  // the Assign action just won't appear for affected rows.
  const loadOperatorAssignments = async () => {
    try {
      const res = await api.get<OperatorAssignmentRow[]>('/rbac/user-role-assignments', {
        params: { active: true, limit: 500 },
      });
      const map: Record<number, number> = {};
      for (const row of res.data || []) {
        if (row.schoolId != null && row.active && (row.roleName || '').toUpperCase() === 'OPERATIONS') {
          map[row.schoolId] = row.userId;
        }
      }
      setOperatorUserIdBySchool(map);
    } catch {
      // Swallow: see comment above.
    }
  };

  useEffect(() => {
    if (can('school:read')) {
      loadSchools();
      loadOperatorAssignments();
    }
  }, []);

  if (!user) return <Navigate to="/login" replace />;
  if (!can('school:read')) return <Navigate to="/dashboard" replace />;

  const openAdminModal = (school: SchoolRow) => {
    setSelectedSchool(school);
    setAdminForm({
      fullName: '',
      email: school.adminEmail || `admin@${school.shortCode.toLowerCase()}.custoking.com`,
      temporaryPassword: 'Welcome@123'
    });
    setShowAdminModal(true);
  };

  const openOpsModal = (school: SchoolRow) => {
    setSelectedSchool(school);
    setOpsForm({
      fullName: '',
      email: school.operationsEmail || `ops@${school.shortCode.toLowerCase()}.custoking.com`,
      temporaryPassword: 'Welcome@123'
    });
    setShowOpsModal(true);
  };

  const openModulesModal = async (school: SchoolRow) => {
    setSelectedSchool(school);
    setModulesLoading(true);
    setShowModulesModal(true);
    try {
      const res = await api.get<ModuleEntitlement[]>(`/schools/${school.id}/modules`);
      const map: Record<string, boolean> = Object.fromEntries(ALL_MODULES.map((m) => [m.code, false]));
      for (const e of res.data) {
        map[e.moduleCode] = e.enabled;
      }
      setCurrentEntitlements(map);
    } catch (err: any) {
      setError(err?.response?.data?.message || err?.message || 'Could not load module entitlements. Please try again.');
      setShowModulesModal(false);
      setSelectedSchool(null);
    } finally {
      setModulesLoading(false);
    }
  };

  const submitSchool = async (e: React.FormEvent) => {
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
        const enabledCodes = ALL_MODULES.filter((m) => moduleSelections[m.code]).map((m) => m.code);
        await Promise.all(
          enabledCodes.map((code) =>
            api.put(`/schools/${schoolId}/modules/${code}`, { enabled: true })
          )
        );
      }
      setShowSchoolModal(false);
      setSchoolForm(defaultSchoolForm);
      setModuleSelections(defaultModuleSelections());
      setNotice('School created successfully.');
      await loadSchools();
    } catch (err: any) {
      setError(err?.response?.data?.message || err?.message || 'Unable to create school.');
    } finally {
      setSaving(false);
    }
  };

  const submitAdmin = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedSchool) return;
    try {
      setSaving(true);
      setError('');
      await api.post(`/schools/${selectedSchool.id}/admin`, adminForm);
      setShowAdminModal(false);
      setSelectedSchool(null);
      setNotice('School admin created or reset successfully.');
      await loadSchools();
    } catch (err: any) {
      setError(err?.response?.data?.message || err?.message || 'Unable to create or reset admin.');
    } finally {
      setSaving(false);
    }
  };

  const submitOps = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedSchool) return;
    try {
      setSaving(true);
      setError('');
      const schoolId = selectedSchool.id;
      const res = await api.post<{ userId?: number }>(`/schools/${schoolId}/operations-user`, opsForm);
      const newOperatorUserId = res.data?.userId;
      if (newOperatorUserId) {
        // Provisioning response already carries the operator's userId — capture it so
        // the "Assign schools" action is available immediately, without waiting on a
        // reload of GET /rbac/user-role-assignments.
        setOperatorUserIdBySchool((m) => ({ ...m, [schoolId]: newOperatorUserId }));
      }
      setShowOpsModal(false);
      setSelectedSchool(null);
      setNotice('Operations user created or reset successfully.');
      await loadSchools();
    } catch (err: any) {
      setError(err?.response?.data?.message || err?.message || 'Unable to create or reset operations user.');
    } finally {
      setSaving(false);
    }
  };

  const openAssignModal = async (school: SchoolRow) => {
    const operatorUserId = operatorUserIdBySchool[school.id];
    if (!operatorUserId) {
      setError('No operations user found for this school yet. Create one first.');
      return;
    }
    setError('');
    setNotice('');
    setAssignTarget({ userId: operatorUserId, email: school.operationsEmail, schoolName: school.name });
    setAssignSelectedSchoolIds(new Set());
    setShowAssignModal(true);
    setAssignLoading(true);
    try {
      const res = await api.get<OperatorSchoolRow[]>(`/rbac/users/${operatorUserId}/operator-schools`);
      setAssignSelectedSchoolIds(new Set((res.data || []).map((row) => row.schoolId)));
    } catch (err: any) {
      setError(err?.response?.data?.message || err?.message || 'Unable to load assigned schools.');
      setShowAssignModal(false);
      setAssignTarget(null);
    } finally {
      setAssignLoading(false);
    }
  };

  const toggleAssignSchool = (schoolId: number) => {
    setAssignSelectedSchoolIds((prev) => {
      const next = new Set(prev);
      if (next.has(schoolId)) next.delete(schoolId);
      else next.add(schoolId);
      return next;
    });
  };

  const submitAssign = async () => {
    if (!assignTarget) return;
    try {
      setSaving(true);
      setError('');
      await api.post(`/rbac/users/${assignTarget.userId}/operator-schools`, {
        schoolIds: Array.from(assignSelectedSchoolIds),
      });
      setShowAssignModal(false);
      setNotice(`Assigned schools updated for ${assignTarget.email || 'operator'}.`);
      setAssignTarget(null);
    } catch (err: any) {
      setError(err?.response?.data?.message || err?.message || 'Unable to update assigned schools.');
    } finally {
      setSaving(false);
    }
  };

  const submitModules = async () => {
    if (!selectedSchool) return;
    try {
      setSaving(true);
      setError('');
      await Promise.all(
        ALL_MODULES.map((m) =>
          api.put(`/schools/${selectedSchool.id}/modules/${m.code}`, {
            enabled: !!currentEntitlements[m.code],
          })
        )
      );
      setShowModulesModal(false);
      setSelectedSchool(null);
      setNotice(`Modules updated for ${selectedSchool.name}.`);
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
            <p className="page-subtitle">Onboard branches, review school status, and create one admin per school.</p>
          </div>
          <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
            <span className="badge">{schools.length} schools</span>
            <span className="badge">{activeCount} active</span>
            {can('school:create') && (
              <button onClick={() => { setNotice(''); setShowSchoolModal(true); }} className="ck-btn ck-btn-g">Add School</button>
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
                <th>Admin Email</th>
                <th>Operations Email</th>
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
              {schools.map((school) => (
                <tr key={school.id}>
                  <td>{school.name}</td>
                  <td>{school.shortCode}</td>
                  <td>{school.city || '—'}</td>
                  <td>{school.adminEmail || '—'}</td>
                  <td>{school.operationsEmail || '—'}</td>
                  <td><span className="badge">{school.active ? 'Active' : 'Inactive'}</span></td>
                  <td style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                    {can('school:update') && (
                      <button className="ck-btn ck-btn-ghost" onClick={() => openAdminModal(school)}>
                        {school.adminEmail ? 'Reset Admin' : 'Add Admin'}
                      </button>
                    )}
                    {can('school:update') && (
                      <button className="ck-btn ck-btn-ghost" onClick={() => openOpsModal(school)}>
                        {school.operationsEmail ? 'Reset Ops' : 'Add Ops'}
                      </button>
                    )}
                    {can('school:update') && operatorUserIdBySchool[school.id] && (
                      <button className="ck-btn ck-btn-ghost" onClick={() => openAssignModal(school)}>
                        Assign Schools
                      </button>
                    )}
                    {can('school:update') && (
                      <button className="ck-btn ck-btn-ghost" onClick={() => openModulesModal(school)}>
                        Modules
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* ── Add School Modal ─────────────────────────────────────────────── */}
      {showSchoolModal && (
        <div className="ck-modal-bg" onClick={() => !saving && setShowSchoolModal(false)}>
          <div className="ck-modal" onClick={(e) => e.stopPropagation()} style={{ maxWidth: 600 }}>
            <div className="ck-modal-h">
              <div className="ck-modal-title">Add school</div>
              <button type="button" className="ck-modal-x" onClick={() => !saving && setShowSchoolModal(false)}>×</button>
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
                    Select which modules this school can access. Can be changed later.
                  </div>
                  <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: 10 }}>
                    {ALL_MODULES.map((m) => (
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

      {/* ── Add / Reset Admin Modal ──────────────────────────────────────── */}
      {showAdminModal && selectedSchool && (
        <div className="ck-modal-bg" onClick={() => !saving && setShowAdminModal(false)}>
          <div className="ck-modal" onClick={(e) => e.stopPropagation()}>
            <div className="ck-modal-h">
              <div>
                <div className="ck-modal-title">Add / Reset admin</div>
                <div className="section-copy" style={{ marginTop: 6 }}>{selectedSchool.name}</div>
              </div>
              <button type="button" className="ck-modal-x" onClick={() => !saving && setShowAdminModal(false)}>×</button>
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

      {/* ── Add / Reset Ops Modal ────────────────────────────────────────── */}
      {showOpsModal && selectedSchool && (
        <div className="ck-modal-bg" onClick={() => !saving && setShowOpsModal(false)}>
          <div className="ck-modal" onClick={(e) => e.stopPropagation()}>
            <div className="ck-modal-h">
              <div>
                <div className="ck-modal-title">Add / Reset operations user</div>
                <div className="section-copy" style={{ marginTop: 6 }}>{selectedSchool.name}</div>
              </div>
              <button type="button" className="ck-modal-x" onClick={() => !saving && setShowOpsModal(false)}>×</button>
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
                <button type="submit" className="ck-btn ck-btn-g" disabled={saving}>{saving ? 'Saving...' : 'Save Ops User'}</button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* ── Assign Schools to Operator Modal ────────────────────────────── */}
      {showAssignModal && assignTarget && (
        <div className="ck-modal-bg" onClick={() => !saving && setShowAssignModal(false)}>
          <div className="ck-modal" onClick={(e) => e.stopPropagation()} style={{ maxWidth: 560 }}>
            <div className="ck-modal-h">
              <div>
                <div className="ck-modal-title">Assign schools</div>
                <div className="section-copy" style={{ marginTop: 6 }}>
                  Operator: {assignTarget.email || `user #${assignTarget.userId}`}
                </div>
              </div>
              <button type="button" className="ck-modal-x" onClick={() => !saving && setShowAssignModal(false)}>×</button>
            </div>
            <div className="ck-modal-body">
              {assignLoading ? (
                <div style={{ textAlign: 'center', padding: '24px 0', color: '#6b7280' }}>Loading assigned schools…</div>
              ) : (
                <>
                  <div style={{ fontSize: 13, color: '#6b7280', marginBottom: 16 }}>
                    Select which schools this operations user can access. This replaces their
                    current set of assigned schools.
                  </div>
                  <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: 10, maxHeight: 320, overflowY: 'auto' }}>
                    {schools.map((school) => (
                      <label
                        key={school.id}
                        style={{ display: 'flex', alignItems: 'flex-start', gap: 10, cursor: 'pointer', padding: '8px 10px', borderRadius: 8, border: '1px solid var(--border, #e5e7eb)', background: assignSelectedSchoolIds.has(school.id) ? 'var(--g1, #f0fdf4)' : '#fafafa' }}
                      >
                        <input
                          type="checkbox"
                          checked={assignSelectedSchoolIds.has(school.id)}
                          onChange={() => toggleAssignSchool(school.id)}
                          style={{ marginTop: 2, accentColor: 'var(--g, #16a34a)' }}
                        />
                        <div>
                          <div style={{ fontWeight: 600, fontSize: 13 }}>{school.name}</div>
                          <div style={{ fontSize: 11, color: '#6b7280' }}>{school.shortCode}</div>
                        </div>
                      </label>
                    ))}
                  </div>
                </>
              )}
            </div>
            <div className="ck-modal-foot">
              <button type="button" className="ck-btn ck-btn-ghost" onClick={() => !saving && setShowAssignModal(false)}>Cancel</button>
              <button type="button" className="ck-btn ck-btn-g" disabled={saving || assignLoading} onClick={submitAssign}>
                {saving ? 'Saving…' : 'Save Assignment'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ── Manage Modules Modal ─────────────────────────────────────────── */}
      {showModulesModal && selectedSchool && (
        <div className="ck-modal-bg" onClick={() => !saving && setShowModulesModal(false)}>
          <div className="ck-modal" onClick={(e) => e.stopPropagation()} style={{ maxWidth: 560 }}>
            <div className="ck-modal-h">
              <div>
                <div className="ck-modal-title">Manage modules</div>
                <div className="section-copy" style={{ marginTop: 6 }}>{selectedSchool.name}</div>
              </div>
              <button type="button" className="ck-modal-x" onClick={() => !saving && setShowModulesModal(false)}>×</button>
            </div>
            <div className="ck-modal-body">
              {modulesLoading ? (
                <div style={{ textAlign: 'center', padding: '24px 0', color: '#6b7280' }}>Loading modules…</div>
              ) : (
                <>
                  <div style={{ fontSize: 13, color: '#6b7280', marginBottom: 16 }}>
                    Enable or disable modules for this school. Changes take effect immediately.
                  </div>
                  <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: 10 }}>
                    {ALL_MODULES.map((m) => (
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
                {saving ? 'Saving…' : 'Save Modules'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

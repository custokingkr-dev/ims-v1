import { useEffect, useMemo, useState } from 'react';
import { Navigate, Link } from 'react-router-dom';
import api from '../services/api';
import { useAuth } from '../contexts/AuthContext';

type SchoolRow = {
  id: number;
  name: string;
  shortCode: string;
  city: string;
  active: boolean;
  adminEmail: string;
  operationsEmail: string;
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

export default function SchoolManagementPage() {
  const { user } = useAuth();
  const [schools, setSchools] = useState<SchoolRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [showSchoolModal, setShowSchoolModal] = useState(false);
  const [showAdminModal, setShowAdminModal] = useState(false);
  const [showOpsModal, setShowOpsModal] = useState(false);
  const [schoolForm, setSchoolForm] = useState(defaultSchoolForm);
  const [adminForm, setAdminForm] = useState(defaultAdminForm);
  const [opsForm, setOpsForm] = useState(defaultOpsForm);
  const [selectedSchool, setSelectedSchool] = useState<SchoolRow | null>(null);
  const [saving, setSaving] = useState(false);

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

  useEffect(() => {
    if (user?.role === 'SUPERADMIN') {
      loadSchools();
    }
  }, [user?.role]);

  if (!user) return <Navigate to="/login" replace />;
  if (user.role !== 'SUPERADMIN') return <Navigate to="/dashboard" replace />;

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

  const submitSchool = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      setSaving(true);
      setError('');
      await api.post('/schools', { ...schoolForm, classCount: Number(schoolForm.classCount || 12), sectionCount: Number(schoolForm.sectionCount || 2) });
      setShowSchoolModal(false);
      setSchoolForm(defaultSchoolForm);
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
      await api.post(`/schools/${selectedSchool.id}/operations-user`, opsForm);
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
            <button onClick={() => { setNotice(''); setShowSchoolModal(true); }} className="ck-btn ck-btn-g">Add School</button>
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
                    <button className="ck-btn ck-btn-ghost" onClick={() => openAdminModal(school)}>
                      {school.adminEmail ? 'Reset Admin' : 'Add Admin'}
                    </button>
                    <button className="ck-btn ck-btn-ghost" onClick={() => openOpsModal(school)}>
                      {school.operationsEmail ? 'Reset Ops' : 'Add Ops'}
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
          <div className="ck-modal" onClick={(e) => e.stopPropagation()}>
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
    </div>
  );
}

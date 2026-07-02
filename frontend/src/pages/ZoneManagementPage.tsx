import { useEffect, useState } from 'react';
import { Navigate, Link } from 'react-router-dom';
import api from '../services/api';
import { usePermissions } from '../hooks/usePermissions';

type ZoneRow = {
  id: number;
  name: string;
  code: string;
  city: string;
  active: boolean;
  schoolCount: number;
  adminEmail: string;
};

const defaultZoneForm = { name: '', code: '', city: '', state: '', description: '' };
const defaultAdminForm = { fullName: '', email: '', temporaryPassword: 'Welcome@123' };

export default function ZoneManagementPage() {
  const { can } = usePermissions();
  const [zones, setZones] = useState<ZoneRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [showZoneModal, setShowZoneModal] = useState(false);
  const [showAdminModal, setShowAdminModal] = useState(false);
  const [zoneForm, setZoneForm] = useState(defaultZoneForm);
  const [adminForm, setAdminForm] = useState(defaultAdminForm);
  const [selectedZoneId, setSelectedZoneId] = useState<number | null>(null);
  const [saving, setSaving] = useState(false);

  function load() {
    setLoading(true);
    api.get('/zones').then(r => {
      setZones(r.data);
      setLoading(false);
    }).catch(() => {
      setError('Failed to load zones');
      setLoading(false);
    });
  }

  useEffect(() => { load(); }, []);

  if (!can('platform:admin')) return <Navigate to="/dashboard" replace />;

  async function createZone(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    setSaving(true);
    try {
      await api.post('/zones', zoneForm);
      setNotice('Zone created');
      setShowZoneModal(false);
      setZoneForm(defaultZoneForm);
      load();
    } catch (err: unknown) {
      const e = err as { response?: { data?: { message?: string } }; message?: string };
      setError(e?.response?.data?.message || e?.message || 'Failed to create zone');
    } finally {
      setSaving(false);
    }
  }

  async function createAdmin(e: React.FormEvent) {
    e.preventDefault();
    if (!selectedZoneId) return;
    setError('');
    setSaving(true);
    try {
      await api.post(`/zones/${selectedZoneId}/admin`, adminForm);
      setNotice('Zone admin set');
      setShowAdminModal(false);
      setAdminForm(defaultAdminForm);
      load();
    } catch (err: unknown) {
      const e = err as { response?: { data?: { message?: string } }; message?: string };
      setError(e?.response?.data?.message || e?.message || 'Failed to set zone admin');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div style={{ padding: '2rem', maxWidth: 960, margin: '0 auto' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
        <div>
          <Link to="/schools" style={{ fontSize: 13, color: '#666', textDecoration: 'none' }}>&larr; Schools</Link>
          <h1 style={{ margin: '0.25rem 0 0', fontSize: '1.4rem', fontWeight: 700 }}>Zone management</h1>
        </div>
        <button onClick={() => setShowZoneModal(true)}
          style={{ background: '#1a1a1a', color: '#fff', border: 'none', borderRadius: 8, padding: '0.5rem 1rem', cursor: 'pointer', fontWeight: 600 }}>
          + New zone
        </button>
      </div>

      {error && <div style={{ background: '#fef2f2', color: '#dc2626', padding: '0.75rem 1rem', borderRadius: 8, marginBottom: '1rem' }}>{error}</div>}
      {notice && <div style={{ background: '#f0fdf4', color: '#16a34a', padding: '0.75rem 1rem', borderRadius: 8, marginBottom: '1rem' }}>{notice}</div>}

      {loading ? <p>Loading zones…</p> : (
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 14 }}>
          <thead>
            <tr style={{ borderBottom: '2px solid #e5e7eb' }}>
              {['Zone', 'Code', 'City', 'Schools', 'Admin', 'Actions'].map(h => (
                <th key={h} style={{ textAlign: 'left', padding: '0.5rem 0.75rem', fontWeight: 600, color: '#374151' }}>{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {zones.length === 0 && (
              <tr><td colSpan={6} style={{ textAlign: 'center', color: '#6b7280', padding: '1rem' }}>No zones created yet.</td></tr>
            )}
            {zones.map(z => (
              <tr key={z.id} style={{ borderBottom: '1px solid #f3f4f6' }}>
                <td style={{ padding: '0.75rem' }}>{z.name}</td>
                <td style={{ padding: '0.75rem', color: '#6b7280' }}>{z.code}</td>
                <td style={{ padding: '0.75rem' }}>{z.city || '—'}</td>
                <td style={{ padding: '0.75rem' }}>{z.schoolCount}</td>
                <td style={{ padding: '0.75rem', color: z.adminEmail ? '#111' : '#9ca3af' }}>{z.adminEmail || 'Not set'}</td>
                <td style={{ padding: '0.75rem' }}>
                  <button onClick={() => { setSelectedZoneId(z.id); setShowAdminModal(true); }}
                    style={{ fontSize: 12, background: '#f3f4f6', border: 'none', borderRadius: 6, padding: '0.3rem 0.6rem', cursor: 'pointer' }}>
                    Set admin
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {showZoneModal && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.4)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000 }}>
          <form onSubmit={createZone} style={{ background: '#fff', borderRadius: 12, padding: '2rem', width: 420, display: 'flex', flexDirection: 'column', gap: '1rem' }}>
            <h2 style={{ margin: 0, fontSize: '1.1rem', fontWeight: 700 }}>Create zone</h2>
            {(['name', 'code', 'city', 'state', 'description'] as const).map(f => (
              <input key={f} required={f === 'name' || f === 'code'} placeholder={f.charAt(0).toUpperCase() + f.slice(1)}
                value={zoneForm[f]} onChange={e => setZoneForm(p => ({ ...p, [f]: e.target.value }))}
                style={{ padding: '0.5rem 0.75rem', border: '1px solid #e5e7eb', borderRadius: 8, fontSize: 14 }} />
            ))}
            <div style={{ display: 'flex', gap: '0.5rem', justifyContent: 'flex-end' }}>
              <button type="button" onClick={() => setShowZoneModal(false)}
                style={{ padding: '0.5rem 1rem', border: '1px solid #e5e7eb', borderRadius: 8, background: '#fff', cursor: 'pointer' }}>Cancel</button>
              <button type="submit" disabled={saving}
                style={{ padding: '0.5rem 1rem', border: 'none', borderRadius: 8, background: '#1a1a1a', color: '#fff', cursor: 'pointer', fontWeight: 600 }}>
                {saving ? 'Creating…' : 'Create zone'}
              </button>
            </div>
          </form>
        </div>
      )}

      {showAdminModal && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.4)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000 }}>
          <form onSubmit={createAdmin} style={{ background: '#fff', borderRadius: 12, padding: '2rem', width: 420, display: 'flex', flexDirection: 'column', gap: '1rem' }}>
            <h2 style={{ margin: 0, fontSize: '1.1rem', fontWeight: 700 }}>Set zone admin</h2>
            {(['fullName', 'email', 'temporaryPassword'] as const).map(f => (
              <input key={f} required type={f === 'email' ? 'email' : 'text'} placeholder={f === 'fullName' ? 'Full name' : f === 'temporaryPassword' ? 'Temporary password' : 'Email'}
                value={adminForm[f]} onChange={e => setAdminForm(p => ({ ...p, [f]: e.target.value }))}
                style={{ padding: '0.5rem 0.75rem', border: '1px solid #e5e7eb', borderRadius: 8, fontSize: 14 }} />
            ))}
            <div style={{ display: 'flex', gap: '0.5rem', justifyContent: 'flex-end' }}>
              <button type="button" onClick={() => setShowAdminModal(false)}
                style={{ padding: '0.5rem 1rem', border: '1px solid #e5e7eb', borderRadius: 8, background: '#fff', cursor: 'pointer' }}>Cancel</button>
              <button type="submit" disabled={saving}
                style={{ padding: '0.5rem 1rem', border: 'none', borderRadius: 8, background: '#1a1a1a', color: '#fff', cursor: 'pointer', fontWeight: 600 }}>
                {saving ? 'Saving…' : 'Save admin'}
              </button>
            </div>
          </form>
        </div>
      )}
    </div>
  );
}

import { useEffect, useState } from 'react';
import api from '../../../services/api';
import { ModuleShell, Field } from '../ui';
import { formatMoney } from '../utils';

export function SaSchoolsPanel() {
  const [saSchools, setSaSchools] = useState<any[]>([]);
  const [saSchoolsLoading, setSaSchoolsLoading] = useState(false);
  const [saSchoolsError, setSaSchoolsError] = useState('');
  const [saOnboardOpen, setSaOnboardOpen] = useState(false);
  const [saOnboardForm, setSaOnboardForm] = useState({ name: '', shortCode: '', city: '', state: '', contactEmail: '', contactPhone: '', classCount: '12', sectionCount: '2' });
  const [saOnboardErrors, setSaOnboardErrors] = useState<Record<string, string>>({});
  const [saOnboardSaving, setSaOnboardSaving] = useState(false);

  const loadSaSchools = async () => {
    setSaSchoolsLoading(true); setSaSchoolsError('');
    try {
      const res = await api.get('/sa/schools');
      setSaSchools(Array.isArray(res.data) ? res.data : []);
    } catch (e: any) {
      setSaSchoolsError(e?.response?.data?.message || 'Failed to load schools.');
    } finally {
      setSaSchoolsLoading(false);
    }
  };

  useEffect(() => { void loadSaSchools(); }, []);

  const submitSaOnboard = async () => {
    const errors: Record<string, string> = {};
    if (!saOnboardForm.name) errors.name = 'School name is required';
    if (!saOnboardForm.shortCode) errors.shortCode = 'Short code is required';
    if (!saOnboardForm.city) errors.city = 'City is required';
    const classCount = Number(saOnboardForm.classCount || 0);
    const sectionCount = Number(saOnboardForm.sectionCount || 0);
    if (!Number.isInteger(classCount) || classCount < 1 || classCount > 12) errors.classCount = 'Classes must be between 1 and 12';
    if (!Number.isInteger(sectionCount) || sectionCount < 1 || sectionCount > 26) errors.sectionCount = 'Sections must be between 1 and 26';
    if (Object.keys(errors).length) { setSaOnboardErrors(errors); return; }
    setSaOnboardErrors({}); setSaOnboardSaving(true);
    try {
      await api.post('/schools', { ...saOnboardForm, classCount, sectionCount });
      alert(`${saOnboardForm.name} onboarded successfully`);
      setSaOnboardOpen(false);
      setSaOnboardForm({ name: '', shortCode: '', city: '', state: '', contactEmail: '', contactPhone: '', classCount: '12', sectionCount: '2' });
      await loadSaSchools();
    } catch (e: any) {
      setSaOnboardErrors({ _: e?.response?.data?.message || 'Save failed. Please try again.' });
    } finally {
      setSaOnboardSaving(false);
    }
  };

  return (
    <>
      <ModuleShell title="School accounts" subtitle="All schools with admin and GMV stats" actions={<button className="ck-btn ck-btn-g" onClick={() => setSaOnboardOpen(true)}>+ Onboard school</button>}>
        <div className="ck-card">
          {saSchoolsLoading ? <div style={{ padding: 16 }}>Loading schools…</div>
          : saSchoolsError ? <div style={{ padding: 16 }}>{saSchoolsError}</div>
          : <table className="ck-table">
            <thead><tr><th>School</th><th>Short code</th><th>City</th><th>Classes</th><th>Sections / class</th><th>Admin</th><th>Orders YTD</th><th>GMV YTD</th><th>ERP since</th></tr></thead>
            <tbody>
              {saSchools.length === 0
                ? <tr><td colSpan={9}><div className="ts">No schools found.</div></td></tr>
                : saSchools.map((school: any) => (
                  <tr key={school.id}>
                    <td><div className="tb">{school.name}</div><div className="ts">{school.active ? 'Active' : 'Inactive'}</div></td>
                    <td>{school.shortCode || '—'}</td>
                    <td>{school.city || '—'}</td>
                    <td>{school.configuredClassCount ?? '—'}</td>
                    <td>{school.configuredSectionCount ?? '—'}</td>
                    <td>{school.adminEmail || '—'}</td>
                    <td>{school.ordersYTD ?? 0}</td>
                    <td>₹{formatMoney(Math.round(Number(school.gmvYTD || 0) / 100))}</td>
                    <td>{school.erpSince || '—'}</td>
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
                  <input type="number" min={1} max={12} value={saOnboardForm.classCount} onChange={(e) => setSaOnboardForm({ ...saOnboardForm, classCount: e.target.value })} />
                  {saOnboardErrors.classCount ? <div className="ts" style={{ color: 'var(--re)', marginTop: 6 }}>{saOnboardErrors.classCount}</div> : <div className="ts" style={{ marginTop: 6 }}>Creates classes 1 to {saOnboardForm.classCount || 12}</div>}
                </Field>
                <Field label="Sections per class *">
                  <input type="number" min={1} max={26} value={saOnboardForm.sectionCount} onChange={(e) => setSaOnboardForm({ ...saOnboardForm, sectionCount: e.target.value })} />
                  {saOnboardErrors.sectionCount ? <div className="ts" style={{ color: 'var(--re)', marginTop: 6 }}>{saOnboardErrors.sectionCount}</div> : <div className="ts" style={{ marginTop: 6 }}>Creates sections A to {String.fromCharCode(64 + Math.max(1, Math.min(26, Number(saOnboardForm.sectionCount || 2))))}</div>}
                </Field>
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
    </>
  );
}

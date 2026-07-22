import { useEffect, useState } from 'react';
import api from '../../../services/api';
import { ModuleShell, Field } from '../ui';

const MAX_CLASS_COUNT = 15;

export function SchoolStructurePanel({ schoolId, onSaved }: { schoolId?: number; onSaved: () => void }) {
  const [form, setForm] = useState({ classCount: '', sectionCount: '' });
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');

  useEffect(() => {
    if (!schoolId) { setLoading(false); return; }
    let alive = true;
    setLoading(true);
    void api.get<{ configuredClassCount?: number; configuredSectionCount?: number }>(`/schools/${schoolId}`)
      .then((res) => {
        if (!alive) return;
        setForm({
          classCount: String(res.data.configuredClassCount ?? MAX_CLASS_COUNT),
          sectionCount: String(res.data.configuredSectionCount ?? 2),
        });
      })
      .catch(() => { if (alive) setError('Unable to load current structure.'); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [schoolId]);

  const save = async () => {
    if (!schoolId) return;
    const classCount = Number(form.classCount || 0);
    const sectionCount = Number(form.sectionCount || 0);
    if (!Number.isInteger(classCount) || classCount < 1 || classCount > MAX_CLASS_COUNT) { setError(`Classes must be between 1 and ${MAX_CLASS_COUNT}`); return; }
    if (!Number.isInteger(sectionCount) || sectionCount < 1 || sectionCount > 26) { setError('Sections must be between 1 and 26'); return; }
    setError(''); setOk(''); setSaving(true);
    try {
      await api.put(`/schools/${schoolId}/structure`, { classCount, sectionCount });
      setOk('Structure updated.');
      onSaved();
    } catch (e: any) {
      setError(e?.response?.data?.message || 'Update failed. Please try again.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <ModuleShell title="Class & section setup" subtitle="Set how many classes and sections per class your school uses">
      <div className="ck-form-card">
        <div className="ck-form-body">
          {!schoolId ? <div className="ck-alert ck-alert-re"><span>!</span><div>No school is associated with your account.</div></div>
          : loading ? <div style={{ padding: 16 }}>Loading…</div>
          : <>
            {error && <div className="ck-alert ck-alert-re" style={{ marginBottom: 16 }}><span>✕</span><div>{error}</div></div>}
            {ok && <div className="ck-alert ck-alert-g" style={{ marginBottom: 16 }}><span>✓</span><div>{ok}</div></div>}
            <div className="ck-form-grid ck-fg-2">
              <Field label="No. of classes *"><input type="number" min={1} max={MAX_CLASS_COUNT} value={form.classCount} onChange={(e) => setForm({ ...form, classCount: e.target.value })} /></Field>
              <Field label="Sections per class *"><input type="number" min={1} max={26} value={form.sectionCount} onChange={(e) => setForm({ ...form, sectionCount: e.target.value })} /></Field>
            </div>
            <div className="ts" style={{ marginTop: 10 }}>Count order is Nursery/Pre-Nursery/Playgroup, LKG, UKG, then classes 1 to 12. Reducing a count is blocked if a removed class or section still has students.</div>
            <div className="ck-actions-inline" style={{ marginTop: 16 }}>
              <button className="ck-btn ck-btn-g" disabled={saving} onClick={() => void save()}>{saving ? 'Saving…' : 'Save changes'}</button>
            </div>
          </>}
        </div>
      </div>
    </ModuleShell>
  );
}

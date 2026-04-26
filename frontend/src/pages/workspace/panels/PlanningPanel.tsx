import { useState } from 'react';
import api from '../../../services/api';
import { ModuleShell, Field } from '../ui';
import { WorkspaceData } from '../../workspace/config';
import { formatMoney } from '../utils';

interface Props {
  workspace: WorkspaceData;
  onRefresh: () => Promise<void>;
}

export function PlanningPanel({ workspace, onRefresh }: Props) {
  const [form, setForm] = useState({ term: 'Term 1', category: 'Stationery', quantity: '200 units', amount: '38000', status: 'Planned' });
  const [saving, setSaving] = useState(false);

  const submit = async () => {
    try {
      setSaving(true);
      await api.post('/workspace/annual-plan', form);
      await onRefresh();
    } finally {
      setSaving(false);
    }
  };

  const studentCount = workspace.school?.students ?? '';

  return (
    <ModuleShell title="Annual plan" subtitle="Map your full-year supply requirements by term — lock in pricing early" actions={<><button className="ck-btn ck-btn-ghost">Export PDF</button><button className="ck-btn ck-btn-g" disabled={saving} onClick={submit}>{saving ? 'Saving…' : 'Save plan'}</button></>}>
      <div className="ck-alert ck-alert-g"><span>✦</span><div><strong>Custoking insight:</strong> Schools that plan annually save an average of 12% vs ad-hoc ordering. Your student count ({studentCount}) is pre-filled — adjust quantities as needed and lock in Term 1 by March.</div></div>
      <div className="ck-card" style={{ marginBottom: 16 }}>
        <div className="ck-form-grid ck-fg-5">
          <Field label="Term"><select value={form.term} onChange={(e) => setForm({ ...form, term: e.target.value })}><option>Term 1</option><option>Term 2</option><option>Term 3</option></select></Field>
          <Field label="Category"><input value={form.category} onChange={(e) => setForm({ ...form, category: e.target.value })} /></Field>
          <Field label="Quantity"><input value={form.quantity} onChange={(e) => setForm({ ...form, quantity: e.target.value })} /></Field>
          <Field label="Amount"><input value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} /></Field>
          <Field label="Status"><select value={form.status} onChange={(e) => setForm({ ...form, status: e.target.value })}><option>Planned</option><option>Draft</option><option>Locked</option></select></Field>
        </div>
      </div>
      <div className="ck-card"><table className="ck-table"><thead><tr><th>Term</th><th>Category</th><th>Status</th><th>Quantity</th><th>Amount</th></tr></thead><tbody>{(workspace.annualPlan?.terms || []).map((row: { term: string; category: string; status: string; quantity: string; amount: number }, i: number) => <tr key={i}><td>{row.term}</td><td>{row.category}</td><td><span className={`ck-status ${row.status === 'Planned' ? 'sg' : 'sam'}`}>{row.status}</span></td><td>{row.quantity}</td><td>₹{formatMoney(row.amount)}</td></tr>)}</tbody></table></div>
    </ModuleShell>
  );
}

import { useState } from 'react';
import api from '../../../services/api';
import { ModuleShell, Field } from '../ui';
import { WorkspaceData } from '../../workspace/config';
import { formatMoney } from '../utils';

interface Props {
  workspace: WorkspaceData;
  onRefresh: () => Promise<void>;
}

export function StaffPanel({ workspace, onRefresh }: Props) {
  const [form, setForm] = useState({ name: '', designation: '', department: '', monthlySalary: '42000', payrollStatus: 'Pending' });
  const [saving, setSaving] = useState(false);

  const submit = async () => {
    try {
      setSaving(true);
      await api.post('/workspace/staff', form);
      await onRefresh();
    } finally {
      setSaving(false);
    }
  };

  return (
    <ModuleShell title="Staff & HR" subtitle="68 staff · Payroll pending ₹12.4L" actions={<button className="ck-btn ck-btn-g" disabled={saving} onClick={submit}>{saving ? 'Saving…' : '+ Add staff'}</button>}>
      <div className="ck-card" style={{ marginBottom: 16 }}>
        <div className="ck-form-grid ck-fg-5">
          <Field label="Name"><input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></Field>
          <Field label="Designation"><input value={form.designation} onChange={(e) => setForm({ ...form, designation: e.target.value })} /></Field>
          <Field label="Department"><input value={form.department} onChange={(e) => setForm({ ...form, department: e.target.value })} /></Field>
          <Field label="Monthly salary"><input value={form.monthlySalary} onChange={(e) => setForm({ ...form, monthlySalary: e.target.value })} /></Field>
          <Field label="Payroll"><select value={form.payrollStatus} onChange={(e) => setForm({ ...form, payrollStatus: e.target.value })}><option>Pending</option><option>Processed</option></select></Field>
        </div>
      </div>
      <div className="ck-card"><table className="ck-table"><thead><tr><th>Name</th><th>Designation</th><th>Department</th><th>Payroll</th><th className="col-money">Monthly salary</th></tr></thead><tbody>{(workspace.staff || []).length === 0 ? (
        <tr>
          <td colSpan={5}>
            <div style={{ padding: '40px 20px', textAlign: 'center' }}>
              <div style={{ fontSize: 32, marginBottom: 12 }}>👥</div>
              <div style={{ fontWeight: 600, marginBottom: 4 }}>No staff members yet</div>
              <div style={{ fontSize: 13, color: 'var(--ink3)' }}>Add your first staff member using the form above</div>
            </div>
          </td>
        </tr>
      ) : (workspace.staff || []).map((row: { id: string; name: string; designation: string; department: string; payrollStatus: string; monthlySalary: number }) => (
        <tr key={row.id}>
          <td>{row.name}</td>
          <td>{row.designation}</td>
          <td>{row.department}</td>
          <td><span className={`ck-status ${row.payrollStatus === 'Processed' ? 'spaid' : 'spending'}`}>{row.payrollStatus}</span></td>
          <td className="col-money">₹{formatMoney(row.monthlySalary)}</td>
        </tr>
      ))}</tbody></table></div>
    </ModuleShell>
  );
}

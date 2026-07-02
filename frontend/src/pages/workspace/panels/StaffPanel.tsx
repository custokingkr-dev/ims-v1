import { useState } from 'react';
import { UserPlus, Users } from 'lucide-react';
import api from '../../../services/api';
import { ModuleShell, Field } from '../ui';
import { WorkspaceData } from '../../workspace/config';
import { formatMoney } from '../utils';

interface Props {
  workspace: WorkspaceData;
  onRefresh: () => Promise<void>;
}

type StaffRow = {
  id: string;
  name: string;
  designation: string;
  department: string;
  payrollStatus: string;
  monthlySalary: number;
};

const INITIAL_FORM = {
  name: '',
  designation: '',
  department: '',
  monthlySalary: '42000',
  payrollStatus: 'Pending',
};

export function StaffPanel({ workspace, onRefresh }: Props) {
  const [form, setForm] = useState(INITIAL_FORM);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const rows = (workspace.staff || []) as StaffRow[];
  const pendingPayroll = rows
    .filter((row) => row.payrollStatus !== 'Processed')
    .reduce((sum, row) => sum + Number(row.monthlySalary || 0), 0);

  const submit = async () => {
    setError('');
    setNotice('');
    try {
      setSaving(true);
      await api.post('/workspace/staff', form);
      await onRefresh();
      setForm(INITIAL_FORM);
      setNotice('Staff member added successfully.');
    } catch (err: unknown) {
      const e = err as { response?: { data?: { message?: string } }; message?: string };
      setError(e?.response?.data?.message || e?.message || 'Failed to add staff member.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <ModuleShell
      title="Staff & HR"
      subtitle={`${rows.length} staff members - pending payroll Rs ${formatMoney(pendingPayroll)}`}
      actions={
        <button className="ck-btn ck-btn-g ck-icon-btn" disabled={saving} onClick={submit}>
          <UserPlus size={16} aria-hidden="true" />
          <span>{saving ? 'Saving...' : 'Add staff'}</span>
        </button>
      }
    >
      {error && <div className="ck-alert ck-alert-re" style={{ marginBottom: 12 }}>{error}</div>}
      {notice && <div className="ck-alert ck-alert-ok" style={{ marginBottom: 12 }}>{notice}</div>}
      <div className="ck-card ck-compact-form-card" style={{ marginBottom: 16 }}>
        <div className="ck-card-h">
          <div className="ck-card-t">New staff member</div>
        </div>
        <div className="ck-form-body">
          <div className="ck-form-grid ck-staff-grid">
            <Field label="Name">
              <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
            </Field>
            <Field label="Designation">
              <input value={form.designation} onChange={(e) => setForm({ ...form, designation: e.target.value })} />
            </Field>
            <Field label="Department">
              <input value={form.department} onChange={(e) => setForm({ ...form, department: e.target.value })} />
            </Field>
            <Field label="Monthly salary">
              <input
                inputMode="numeric"
                value={form.monthlySalary}
                onChange={(e) => setForm({ ...form, monthlySalary: e.target.value })}
              />
            </Field>
            <Field label="Payroll">
              <select value={form.payrollStatus} onChange={(e) => setForm({ ...form, payrollStatus: e.target.value })}>
                <option>Pending</option>
                <option>Processed</option>
              </select>
            </Field>
          </div>
        </div>
      </div>

      <div className="ck-card">
        <div className="ck-card-h">
          <div className="ck-card-t">Staff directory</div>
        </div>
        {rows.length === 0 ? (
          <div className="ck-empty-state">
            <Users size={28} aria-hidden="true" />
            <div>
              <strong>No staff members yet</strong>
              <span>Add your first staff member using the form above.</span>
            </div>
          </div>
        ) : (
          <div className="ck-table-wrap">
            <table className="ck-table ck-data-table">
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Designation</th>
                  <th>Department</th>
                  <th>Payroll</th>
                  <th className="col-money">Monthly salary</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.id}>
                    <td data-label="Name">{row.name || '-'}</td>
                    <td data-label="Designation">{row.designation || '-'}</td>
                    <td data-label="Department">{row.department || '-'}</td>
                    <td data-label="Payroll">
                      <span className={`ck-status ${row.payrollStatus === 'Processed' ? 'spaid' : 'spending'}`}>
                        {row.payrollStatus || 'Pending'}
                      </span>
                    </td>
                    <td data-label="Monthly salary" className="col-money">
                      Rs {formatMoney(row.monthlySalary)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </ModuleShell>
  );
}

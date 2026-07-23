import { useEffect, useMemo, useState } from 'react';
import {
  BadgeCheck,
  BriefcaseBusiness,
  CalendarDays,
  Mail,
  Pencil,
  Phone,
  RefreshCw,
  Search,
  UserMinus,
  UserPlus,
  Users,
  WalletCards,
} from 'lucide-react';
import api from '../../../services/api';
import { useAuth } from '../../../contexts/AuthContext';
import { usePermissions } from '../../../hooks/usePermissions';
import { ModuleShell, Field } from '../ui';
import { WorkspaceData } from '../../workspace/config';
import { formatMoney } from '../utils';

interface Props {
  workspace: WorkspaceData;
  onRefresh: () => Promise<void>;
}

type StaffRow = {
  id: string | number;
  name: string;
  designation?: string;
  department?: string;
  employeeCode?: string;
  email?: string;
  phone?: string;
  staffType?: string;
  employmentStatus?: string;
  joinDate?: string;
  notes?: string;
  payrollStatus?: string;
  monthlySalary?: number;
};

type StaffForm = {
  name: string;
  employeeCode: string;
  designation: string;
  department: string;
  staffType: string;
  employmentStatus: string;
  email: string;
  phone: string;
  joinDate: string;
  monthlySalary: string;
  payrollStatus: string;
  notes: string;
};

const INITIAL_FORM: StaffForm = {
  name: '',
  employeeCode: '',
  designation: '',
  department: '',
  staffType: 'Teaching',
  employmentStatus: 'Active',
  email: '',
  phone: '',
  joinDate: '',
  monthlySalary: '',
  payrollStatus: 'Pending',
  notes: '',
};

const STAFF_TYPES = ['Teaching', 'Administration', 'Accounts', 'Operations', 'Support', 'Leadership', 'Non-teaching'];
const EMPLOYMENT_STATUSES = ['Active', 'On Leave', 'Inactive'];
const PAYROLL_STATUSES = ['Pending', 'Processed', 'On Hold'];

function normalizeRows(rows: StaffRow[] | undefined): StaffRow[] {
  return (rows || []).map((row) => ({
    ...row,
    id: row.id,
    name: row.name || '',
    designation: row.designation || '',
    department: row.department || '',
    employeeCode: row.employeeCode || '',
    email: row.email || '',
    phone: row.phone || '',
    staffType: row.staffType || (String(row.designation || '').toLowerCase().includes('teacher') ? 'Teaching' : 'Non-teaching'),
    employmentStatus: row.employmentStatus || 'Active',
    joinDate: row.joinDate || '',
    notes: row.notes || '',
    payrollStatus: row.payrollStatus || 'Pending',
    monthlySalary: Number(row.monthlySalary || 0),
  }));
}

function formFromRow(row?: StaffRow): StaffForm {
  if (!row) return INITIAL_FORM;
  return {
    name: row.name || '',
    employeeCode: row.employeeCode || '',
    designation: row.designation || '',
    department: row.department || '',
    staffType: row.staffType || 'Teaching',
    employmentStatus: row.employmentStatus || 'Active',
    email: row.email || '',
    phone: row.phone || '',
    joinDate: row.joinDate || '',
    monthlySalary: row.monthlySalary ? String(row.monthlySalary) : '',
    payrollStatus: row.payrollStatus || 'Pending',
    notes: row.notes || '',
  };
}

function statusClass(status?: string) {
  const normalized = String(status || '').toLowerCase();
  if (normalized === 'processed' || normalized === 'active') return 'sg';
  if (normalized === 'inactive' || normalized === 'on hold') return 'sr';
  return 'sam';
}

function formatDate(value?: string) {
  if (!value) return '-';
  const isoDate = value.match(/^(\d{4})-(\d{2})-(\d{2})$/);
  if (isoDate) {
    const [, year, month, day] = isoDate;
    return new Date(Number(year), Number(month) - 1, Number(day))
      .toLocaleDateString(undefined, { day: '2-digit', month: 'short', year: 'numeric' });
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleDateString(undefined, { day: '2-digit', month: 'short', year: 'numeric' });
}

export function StaffPanel({ workspace, onRefresh }: Props) {
  const { user } = useAuth();
  const { can } = usePermissions();
  const canManageStaff = can('staff:manage');
  const canReadStaff = can('staff:read') || canManageStaff;
  const schoolId = user?.branchId;

  const [rows, setRows] = useState<StaffRow[]>(() => normalizeRows(workspace.staff as StaffRow[]));
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [query, setQuery] = useState('');
  const [typeFilter, setTypeFilter] = useState('All');
  const [departmentFilter, setDepartmentFilter] = useState('All');
  const [statusFilter, setStatusFilter] = useState('Active');
  const [selectedId, setSelectedId] = useState<string | number | null>(null);
  const [dialogMode, setDialogMode] = useState<'create' | 'edit' | null>(null);
  const [form, setForm] = useState<StaffForm>(INITIAL_FORM);

  const loadStaff = async () => {
    if (!canReadStaff || !schoolId) {
      setRows(normalizeRows(workspace.staff as StaffRow[]));
      return;
    }
    setLoading(true);
    setError('');
    try {
      const res = await api.get<StaffRow[]>(`/schools/${schoolId}/staff`);
      const nextRows = normalizeRows(Array.isArray(res.data) ? res.data : []);
      setRows(nextRows);
      setSelectedId((prev) => (prev && nextRows.some((row) => String(row.id) === String(prev)) ? prev : nextRows[0]?.id ?? null));
    } catch (err: unknown) {
      const e = err as { response?: { data?: { message?: string } }; message?: string };
      setError(e?.response?.data?.message || e?.message || 'Failed to load staff records.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadStaff();
  }, [canReadStaff, schoolId]);

  const departments = useMemo(() => {
    const names = Array.from(new Set(rows.map((row) => row.department || 'Unassigned'))).sort();
    return ['All', ...names];
  }, [rows]);

  const filteredRows = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return rows.filter((row) => {
      const status = row.employmentStatus || 'Active';
      const department = row.department || 'Unassigned';
      const haystack = [row.name, row.employeeCode, row.designation, row.department, row.email, row.phone]
        .join(' ')
        .toLowerCase();
      return (!needle || haystack.includes(needle))
        && (typeFilter === 'All' || row.staffType === typeFilter)
        && (departmentFilter === 'All' || department === departmentFilter)
        && (statusFilter === 'All' || status === statusFilter);
    });
  }, [departmentFilter, query, rows, statusFilter, typeFilter]);

  const selected = useMemo(() => {
    if (selectedId == null) return filteredRows[0] || rows[0] || null;
    return rows.find((row) => String(row.id) === String(selectedId)) || filteredRows[0] || rows[0] || null;
  }, [filteredRows, rows, selectedId]);

  const stats = useMemo(() => {
    const active = rows.filter((row) => (row.employmentStatus || 'Active') !== 'Inactive');
    const teaching = active.filter((row) => row.staffType === 'Teaching').length;
    const payrollPending = active.filter((row) => row.payrollStatus !== 'Processed');
    return {
      total: rows.length,
      active: active.length,
      teaching,
      departments: new Set(active.map((row) => row.department || 'Unassigned')).size,
      pendingPayrollCount: payrollPending.length,
      pendingPayrollAmount: payrollPending.reduce((sum, row) => sum + Number(row.monthlySalary || 0), 0),
    };
  }, [rows]);

  const openCreate = () => {
    setForm(INITIAL_FORM);
    setDialogMode('create');
    setError('');
    setNotice('');
  };

  const openEdit = (row: StaffRow) => {
    setForm(formFromRow(row));
    setDialogMode('edit');
    setSelectedId(row.id);
    setError('');
    setNotice('');
  };

  const closeDialog = () => {
    if (saving) return;
    setDialogMode(null);
  };

  const payload = () => ({
    ...form,
    schoolId,
    monthlySalary: form.monthlySalary ? Number(form.monthlySalary) : 0,
  });

  const saveStaff = async () => {
    if (!canManageStaff) {
      setError('You do not have permission to manage staff records.');
      return;
    }
    if (!form.name.trim()) {
      setError('Staff name is required.');
      return;
    }
    if (!schoolId) {
      setError('School context is required to manage staff records.');
      return;
    }
    setSaving(true);
    setError('');
    setNotice('');
    try {
      if (dialogMode === 'edit' && selected) {
        await api.put(`/workspace/staff/${selected.id}`, payload());
        setNotice('Staff profile updated.');
      } else {
        await api.post('/workspace/staff', payload());
        setNotice('Staff member added.');
      }
      setDialogMode(null);
      await loadStaff();
      await onRefresh();
    } catch (err: unknown) {
      const e = err as { response?: { data?: { message?: string } }; message?: string };
      setError(e?.response?.data?.message || e?.message || 'Failed to save staff member.');
    } finally {
      setSaving(false);
    }
  };

  const deactivateStaff = async (row: StaffRow) => {
    if (!canManageStaff || !window.confirm(`Deactivate ${row.name}?`)) return;
    if (!schoolId) {
      setError('School context is required to manage staff records.');
      return;
    }
    setSaving(true);
    setError('');
    setNotice('');
    try {
      await api.delete(`/workspace/staff/${row.id}`, { data: { schoolId } });
      setNotice('Staff member deactivated.');
      setStatusFilter('All');
      await loadStaff();
      await onRefresh();
    } catch (err: unknown) {
      const e = err as { response?: { data?: { message?: string } }; message?: string };
      setError(e?.response?.data?.message || e?.message || 'Failed to deactivate staff member.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <ModuleShell
      title="Staff Management"
      subtitle={`${stats.active} active staff - ${stats.departments} departments - pending payroll Rs ${formatMoney(stats.pendingPayrollAmount)}`}
      actions={
        <>
          <button className="ck-btn ck-btn-ghost ck-icon-btn" onClick={() => void loadStaff()} disabled={loading}>
            <RefreshCw size={16} aria-hidden="true" />
            <span>{loading ? 'Loading...' : 'Refresh'}</span>
          </button>
          {canManageStaff ? (
            <button className="ck-btn ck-btn-g ck-icon-btn" onClick={openCreate}>
              <UserPlus size={16} aria-hidden="true" />
              <span>Add staff</span>
            </button>
          ) : null}
        </>
      }
    >
      {error && <div className="ck-alert ck-alert-re" style={{ marginBottom: 12 }}>{error}</div>}
      {notice && <div className="ck-alert ck-alert-g" style={{ marginBottom: 12 }}>{notice}</div>}

      <div className="ck-staff-stat-grid">
        <div className="ck-staff-stat">
          <Users size={18} aria-hidden />
          <span>Total staff</span>
          <strong>{stats.total}</strong>
        </div>
        <div className="ck-staff-stat">
          <BadgeCheck size={18} aria-hidden />
          <span>Active</span>
          <strong>{stats.active}</strong>
        </div>
        <div className="ck-staff-stat">
          <BriefcaseBusiness size={18} aria-hidden />
          <span>Teaching</span>
          <strong>{stats.teaching}</strong>
        </div>
        <div className="ck-staff-stat">
          <WalletCards size={18} aria-hidden />
          <span>Payroll pending</span>
          <strong>{stats.pendingPayrollCount}</strong>
        </div>
      </div>

      <div className="ck-card ck-staff-toolbar">
        <label className="ck-staff-search">
          <Search size={16} aria-hidden />
          <input
            aria-label="Search staff"
            placeholder="Search name, code, role, contact"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
        </label>
        <select aria-label="Filter staff type" value={typeFilter} onChange={(event) => setTypeFilter(event.target.value)}>
          <option value="All">All types</option>
          {STAFF_TYPES.map((type) => <option key={type} value={type}>{type}</option>)}
        </select>
        <select aria-label="Filter department" value={departmentFilter} onChange={(event) => setDepartmentFilter(event.target.value)}>
          {departments.map((department) => <option key={department} value={department}>{department === 'All' ? 'All departments' : department}</option>)}
        </select>
        <select aria-label="Filter employment status" value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)}>
          <option value="All">All statuses</option>
          {EMPLOYMENT_STATUSES.map((status) => <option key={status} value={status}>{status}</option>)}
        </select>
      </div>

      <div className="ck-staff-layout">
        <div className="ck-card ck-staff-directory">
          <div className="ck-card-h">
            <div>
              <div className="ck-card-t">Staff directory</div>
              <div className="ts">{filteredRows.length} matching records</div>
            </div>
          </div>
          {filteredRows.length === 0 ? (
            <div className="ck-empty-state">
              <Users size={28} aria-hidden="true" />
              <div>
                <strong>No staff records found</strong>
                <span>Adjust the filters or add a staff profile.</span>
              </div>
            </div>
          ) : (
            <div className="ck-staff-list" role="list">
              {filteredRows.map((row) => (
                <button
                  key={row.id}
                  type="button"
                  className={`ck-staff-row${selected && String(selected.id) === String(row.id) ? ' active' : ''}`}
                  onClick={() => setSelectedId(row.id)}
                  aria-label={`Select ${row.name}`}
                >
                  <span className="ck-staff-avatar" aria-hidden>{(row.name || '?').charAt(0).toUpperCase()}</span>
                  <span className="ck-staff-row-main">
                    <span className="ck-staff-row-title">{row.name || '-'}</span>
                    <span className="ck-staff-row-sub">{row.designation || 'Staff'} - {row.department || 'Unassigned'}</span>
                  </span>
                  <span className={`ck-status ${statusClass(row.employmentStatus)}`}>{row.employmentStatus || 'Active'}</span>
                </button>
              ))}
            </div>
          )}
        </div>

        <div className="ck-card ck-staff-detail">
          {selected ? (
            <>
              <div className="ck-staff-detail-head">
                <div className="ck-staff-profile-mark" aria-hidden>{(selected.name || '?').charAt(0).toUpperCase()}</div>
                <div>
                  <div className="ck-staff-detail-title">{selected.name}</div>
                  <div className="ck-staff-detail-sub">
                    {selected.employeeCode || 'No staff code'} - {selected.designation || 'Staff'}
                  </div>
                </div>
                <span className={`ck-status ${statusClass(selected.payrollStatus)}`}>{selected.payrollStatus || 'Pending'}</span>
              </div>

              <div className="ck-staff-detail-grid">
                <div><span>Type</span><strong>{selected.staffType || '-'}</strong></div>
                <div><span>Department</span><strong>{selected.department || '-'}</strong></div>
                <div><span>Monthly salary</span><strong>Rs {formatMoney(selected.monthlySalary || 0)}</strong></div>
                <div><span>Joined</span><strong>{formatDate(selected.joinDate)}</strong></div>
              </div>

              <div className="ck-staff-contact">
                <div><Mail size={15} aria-hidden /> <span>{selected.email || 'No email recorded'}</span></div>
                <div><Phone size={15} aria-hidden /> <span>{selected.phone || 'No phone recorded'}</span></div>
                <div><CalendarDays size={15} aria-hidden /> <span>{selected.employmentStatus || 'Active'}</span></div>
              </div>

              <div className="ck-staff-notes">
                <span>Notes</span>
                <p>{selected.notes || 'No HR notes recorded.'}</p>
              </div>

              {canManageStaff ? (
                <div className="ck-actions-inline ck-staff-detail-actions">
                  <button className="ck-btn ck-btn-g ck-icon-btn" onClick={() => openEdit(selected)}>
                    <Pencil size={16} aria-hidden />
                    <span>Edit profile</span>
                  </button>
                  {(selected.employmentStatus || 'Active') !== 'Inactive' ? (
                    <button className="ck-btn ck-btn-ghost ck-icon-btn" onClick={() => void deactivateStaff(selected)} disabled={saving}>
                      <UserMinus size={16} aria-hidden />
                      <span>Deactivate</span>
                    </button>
                  ) : null}
                </div>
              ) : null}
            </>
          ) : (
            <div className="ck-empty-state">
              <Users size={28} aria-hidden="true" />
              <div>
                <strong>No staff selected</strong>
                <span>Select a record from the directory.</span>
              </div>
            </div>
          )}
        </div>
      </div>

      {dialogMode ? (
        <div className="ck-modal-bg" onClick={closeDialog}>
          <div className="ck-modal" role="dialog" aria-modal="true" aria-label={dialogMode === 'edit' ? 'Edit staff profile' : 'Add staff profile'} onClick={(event) => event.stopPropagation()} style={{ maxWidth: 920 }}>
            <div className="ck-modal-h">
              <div className="ck-modal-title">{dialogMode === 'edit' ? 'Edit staff profile' : 'Add staff profile'}</div>
              <button type="button" className="ck-modal-x" onClick={closeDialog}>x</button>
            </div>
            <div className="ck-modal-body">
              <div className="ck-form-grid ck-fg-3">
                <Field label="Full name">
                  <input aria-label="Full name" value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} />
                </Field>
                <Field label="Staff code">
                  <input aria-label="Staff code" value={form.employeeCode} onChange={(event) => setForm({ ...form, employeeCode: event.target.value })} />
                </Field>
                <Field label="Designation">
                  <input aria-label="Designation" value={form.designation} onChange={(event) => setForm({ ...form, designation: event.target.value })} />
                </Field>
                <Field label="Department">
                  <input aria-label="Department" value={form.department} onChange={(event) => setForm({ ...form, department: event.target.value })} />
                </Field>
                <Field label="Staff type">
                  <select aria-label="Staff type" value={form.staffType} onChange={(event) => setForm({ ...form, staffType: event.target.value })}>
                    {STAFF_TYPES.map((type) => <option key={type}>{type}</option>)}
                  </select>
                </Field>
                <Field label="Employment status">
                  <select aria-label="Employment status" value={form.employmentStatus} onChange={(event) => setForm({ ...form, employmentStatus: event.target.value })}>
                    {EMPLOYMENT_STATUSES.map((status) => <option key={status}>{status}</option>)}
                  </select>
                </Field>
                <Field label="Email">
                  <input aria-label="Email" type="email" value={form.email} onChange={(event) => setForm({ ...form, email: event.target.value })} />
                </Field>
                <Field label="Phone">
                  <input aria-label="Phone" value={form.phone} onChange={(event) => setForm({ ...form, phone: event.target.value })} />
                </Field>
                <Field label="Joining date">
                  <input aria-label="Joining date" type="date" value={form.joinDate} onChange={(event) => setForm({ ...form, joinDate: event.target.value })} />
                </Field>
                <Field label="Monthly salary">
                  <input
                    aria-label="Monthly salary"
                    inputMode="numeric"
                    value={form.monthlySalary}
                    onChange={(event) => setForm({ ...form, monthlySalary: event.target.value })}
                  />
                </Field>
                <Field label="Payroll">
                  <select aria-label="Payroll" value={form.payrollStatus} onChange={(event) => setForm({ ...form, payrollStatus: event.target.value })}>
                    {PAYROLL_STATUSES.map((status) => <option key={status}>{status}</option>)}
                  </select>
                </Field>
                <Field label="Notes">
                  <textarea aria-label="Notes" value={form.notes} onChange={(event) => setForm({ ...form, notes: event.target.value })} rows={3} />
                </Field>
              </div>
            </div>
            <div className="ck-modal-foot">
              <button type="button" className="ck-btn ck-btn-ghost" onClick={closeDialog} disabled={saving}>Cancel</button>
              <button type="button" className="ck-btn ck-btn-g" onClick={() => void saveStaff()} disabled={saving}>
                {saving ? 'Saving...' : dialogMode === 'edit' ? 'Save profile' : 'Create staff'}
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </ModuleShell>
  );
}

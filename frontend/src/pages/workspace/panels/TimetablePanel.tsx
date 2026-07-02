import { useState } from 'react';
import { CalendarPlus } from 'lucide-react';
import api from '../../../services/api';
import { ModuleShell, Field } from '../ui';
import { WorkspaceData } from '../../workspace/config';

interface Props {
  workspace: WorkspaceData;
  onRefresh: () => Promise<void>;
}

type TimetableRow = {
  id?: string;
  day: string;
  period: string;
  classSection: string;
  subject: string;
  teacher: string;
};

export function TimetablePanel({ workspace, onRefresh }: Props) {
  const [form, setForm] = useState({
    day: 'Monday',
    period: 'P6',
    classSection: '9-B',
    subject: '',
    teacher: '',
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const rows = (workspace.timetable || []) as TimetableRow[];

  const submit = async () => {
    if (!form.subject.trim() || !form.teacher.trim()) {
      setError('Subject and teacher are required.');
      return;
    }
    setError('');
    try {
      setSaving(true);
      await api.post('/workspace/timetable', form);
      await onRefresh();
    } catch (err: unknown) {
      const msg =
        err instanceof Error
          ? err.message
          : (err as { response?: { data?: { message?: string } } })?.response?.data?.message ||
            'Failed to save timetable entry';
      setError(msg);
    } finally {
      setSaving(false);
    }
  };

  return (
    <ModuleShell
      title="Timetable"
      subtitle={`${rows.length} scheduled entries`}
      actions={
        <button className="ck-btn ck-btn-g ck-icon-btn" disabled={saving} onClick={submit}>
          <CalendarPlus size={16} aria-hidden="true" />
          <span>{saving ? 'Saving...' : 'Add entry'}</span>
        </button>
      }
    >
      <div className="ck-card ck-compact-form-card" style={{ marginBottom: 16 }}>
        <div className="ck-card-h">
          <div className="ck-card-t">New timetable entry</div>
        </div>
        <div className="ck-form-body">
          <div className="ck-form-grid ck-timetable-grid">
            <Field label="Day">
              <select value={form.day} onChange={(e) => setForm({ ...form, day: e.target.value })}>
                <option>Monday</option>
                <option>Tuesday</option>
                <option>Wednesday</option>
                <option>Thursday</option>
                <option>Friday</option>
                <option>Saturday</option>
              </select>
            </Field>
            <Field label="Period">
              <input value={form.period} onChange={(e) => setForm({ ...form, period: e.target.value })} />
            </Field>
            <Field label="Class">
              <input value={form.classSection} onChange={(e) => setForm({ ...form, classSection: e.target.value })} />
            </Field>
            <Field label="Subject">
              <input value={form.subject} onChange={(e) => setForm({ ...form, subject: e.target.value })} />
            </Field>
            <Field label="Teacher">
              <input value={form.teacher} onChange={(e) => setForm({ ...form, teacher: e.target.value })} />
            </Field>
          </div>
        </div>
      </div>

      {error && (
        <div className="ck-alert ck-alert-re" style={{ marginBottom: 16 }}>
          <span>!</span>
          <div>{error}</div>
        </div>
      )}

      <div className="ck-card">
        <div className="ck-card-h">
          <div className="ck-card-t">Weekly schedule</div>
        </div>
        {rows.length === 0 ? (
          <div className="ck-empty-state">
            <CalendarPlus size={28} aria-hidden="true" />
            <div>
              <strong>No timetable entries yet</strong>
              <span>Add the first class period using the form above.</span>
            </div>
          </div>
        ) : (
          <div className="ck-table-wrap">
            <table className="ck-table ck-data-table">
              <thead>
                <tr>
                  <th>Day</th>
                  <th>Period</th>
                  <th>Class</th>
                  <th>Subject</th>
                  <th>Teacher</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row, i) => (
                  <tr key={row.id || `${row.day}-${row.period}-${i}`}>
                    <td data-label="Day">{row.day}</td>
                    <td data-label="Period">{row.period}</td>
                    <td data-label="Class">{row.classSection}</td>
                    <td data-label="Subject">{row.subject || '-'}</td>
                    <td data-label="Teacher">{row.teacher || '-'}</td>
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

import { useState } from 'react';
import api from '../../../services/api';
import { ModuleShell, Field } from '../ui';
import { WorkspaceData } from '../../workspace/config';

interface Props {
  workspace: WorkspaceData;
  onRefresh: () => Promise<void>;
}

export function TimetablePanel({ workspace, onRefresh }: Props) {
  const [form, setForm] = useState({ day: 'Monday', period: 'P6', classSection: '9-B', subject: '', teacher: '' });
  const [saving, setSaving] = useState(false);

  const submit = async () => {
    try {
      setSaving(true);
      await api.post('/workspace/timetable', form);
      await onRefresh();
    } finally {
      setSaving(false);
    }
  };

  return (
    <ModuleShell title="Timetable" subtitle="Academic year 2024–25" actions={<button className="ck-btn ck-btn-g" disabled={saving} onClick={submit}>{saving ? 'Saving…' : 'Add timetable entry'}</button>}>
      <div className="ck-card" style={{ marginBottom: 16 }}>
        <div className="ck-form-grid ck-fg-5">
          <Field label="Day"><select value={form.day} onChange={(e) => setForm({ ...form, day: e.target.value })}><option>Monday</option><option>Tuesday</option><option>Wednesday</option><option>Thursday</option><option>Friday</option></select></Field>
          <Field label="Period"><input value={form.period} onChange={(e) => setForm({ ...form, period: e.target.value })} /></Field>
          <Field label="Class"><input value={form.classSection} onChange={(e) => setForm({ ...form, classSection: e.target.value })} /></Field>
          <Field label="Subject"><input value={form.subject} onChange={(e) => setForm({ ...form, subject: e.target.value })} /></Field>
          <Field label="Teacher"><input value={form.teacher} onChange={(e) => setForm({ ...form, teacher: e.target.value })} /></Field>
        </div>
      </div>
      <div className="ck-card"><table className="ck-table"><thead><tr><th>Day</th><th>Period</th><th>Class</th><th>Subject</th><th>Teacher</th></tr></thead><tbody>{(workspace.timetable || []).map((row: { day: string; period: string; classSection: string; subject: string; teacher: string }, i: number) => <tr key={i}><td>{row.day}</td><td>{row.period}</td><td>{row.classSection}</td><td>{row.subject}</td><td>{row.teacher}</td></tr>)}</tbody></table></div>
    </ModuleShell>
  );
}

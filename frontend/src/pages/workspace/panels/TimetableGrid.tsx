import { useEffect, useState } from 'react';
import api from '../../../services/api';
import { ModuleShell } from '../ui';
import { usePermissions } from '../../../hooks/usePermissions';
import { useAuth } from '../../../contexts/AuthContext';
import {
  getTimetable, putEntry, deleteEntry, getClassSubjects,
  type TimetableView, type ClassSubjects,
} from '../../../services/timetableApi';

interface AcademicYearOpt { id: string; label: string; active: boolean }

interface Props {
  readOnly?: boolean;
  staff?: Array<{ id: number | string; name: string }>;
  yearId?: string;
  years?: AcademicYearOpt[];
}

interface ClassOpt { id: string; name: string }
interface SectionOpt { id: string; name: string }

function errMsg(err: unknown, fallback: string): string {
  return (err as { response?: { data?: { message?: string } } })?.response?.data?.message
    || (err instanceof Error ? err.message : fallback);
}

export function TimetableGrid({ readOnly, yearId: yearIdProp, years: yearsProp }: Props) {
  const { can } = usePermissions();
  const { user } = useAuth();
  const canRead = can('timetable:read');

  const [classes, setClasses] = useState<ClassOpt[]>([]);
  const [sections, setSections] = useState<SectionOpt[]>([]);
  const [internalYears, setInternalYears] = useState<AcademicYearOpt[]>([]);
  const [classId, setClassId] = useState('');
  const [sectionId, setSectionId] = useState('');
  const [internalYearId, setInternalYearId] = useState('');
  const yearId = yearIdProp ?? internalYearId;
  const years = yearsProp ?? internalYears;
  const [data, setData] = useState<TimetableView | null>(null);
  const [subjects, setSubjects] = useState<ClassSubjects | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [toast, setToast] = useState('');
  const [editingCell, setEditingCell] = useState<{ day: string; periodId: number } | null>(null);
  const [editSubject, setEditSubject] = useState('');
  const [editTeacherId, setEditTeacherId] = useState('');
  const [saving, setSaving] = useState(false);
  const [staffOptions, setStaffOptions] = useState<Array<{ id: number; name: string }>>([]);

  useEffect(() => {
    if (!canRead || !user?.branchId) { setStaffOptions([]); return; }
    void api.get<Array<{ id: number | string; name: string }>>(`/schools/${user.branchId}/staff`)
      .then((r) => {
        const list = Array.isArray(r.data) ? r.data : [];
        setStaffOptions(list.map((row) => ({ id: Number(row.id), name: row.name })));
      })
      .catch(() => setStaffOptions([]));
  }, [canRead, user?.branchId]);

  useEffect(() => {
    if (!canRead) return;
    void api.get<ClassOpt[]>('/classes')
      .then((r) => setClasses(Array.isArray(r.data) ? r.data : []))
      .catch(() => setClasses([]));
    if (!yearIdProp) {
      void api.get<AcademicYearOpt[]>('/academic-years')
        .then((r) => {
          const list = Array.isArray(r.data) ? r.data : [];
          setInternalYears(list);
          const activeYear = list.find((y) => y.active);
          if (activeYear) setInternalYearId(activeYear.id);
        })
        .catch(() => setInternalYears([]));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canRead, yearIdProp]);

  useEffect(() => {
    if (!canRead || !classes.length) return;
    setClassId((prev) => prev || classes[0].id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canRead, classes]);

  useEffect(() => {
    if (!classId) { setSections([]); setSectionId(''); return; }
    void api.get<SectionOpt[]>(`/classes/${encodeURIComponent(classId)}/sections`)
      .then((r) => {
        const list = Array.isArray(r.data) ? r.data : [];
        setSections(list);
        setSectionId((prev) => (list.some((s) => s.id === prev) ? prev : (list[0]?.id || '')));
      })
      .catch(() => { setSections([]); setSectionId(''); });
  }, [classId]);

  const load = async () => {
    if (!sectionId || !yearId) return;
    setLoading(true);
    setError('');
    setEditingCell(null);
    try {
      const res = await getTimetable(sectionId, yearId);
      setData(res.data);
    } catch (err: unknown) {
      setError(errMsg(err, 'Could not load timetable.'));
      setData(null);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (canRead && sectionId && yearId) void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canRead, sectionId, yearId]);

  useEffect(() => {
    if (!classId || !yearId) { setSubjects(null); return; }
    void getClassSubjects(classId, yearId)
      .then((r) => setSubjects(r.data))
      .catch(() => setSubjects(null));
  }, [classId, yearId]);

  const selectedYear = years.find((y) => y.id === yearId) || null;
  const editable = !!(data?.editable && !readOnly);

  const entryFor = (day: string, periodId: number) =>
    data?.entries.find((e) => e.day === day && e.periodId === periodId) || null;

  const openEditor = (day: string, periodId: number) => {
    if (!editable) return;
    const existing = entryFor(day, periodId);
    setEditingCell({ day, periodId });
    setEditSubject(existing?.subjectName || '');
    setEditTeacherId(existing?.teacherId != null ? String(existing.teacherId) : '');
    setError('');
  };

  const closeEditor = () => setEditingCell(null);

  const save = async () => {
    if (!editingCell) return;
    if (!editSubject.trim()) {
      setError('Subject is required.');
      return;
    }
    setSaving(true);
    setError('');
    try {
      const res = await putEntry({
        sectionId,
        day: editingCell.day,
        periodId: editingCell.periodId,
        subjectName: editSubject.trim(),
        teacherId: editTeacherId ? Number(editTeacherId) : null,
      });
      if (res.data?.conflict) {
        setToast(res.data.conflict);
      } else {
        setToast('');
      }
      closeEditor();
      await load();
    } catch (err: unknown) {
      setError(errMsg(err, 'Could not save timetable entry.'));
    } finally {
      setSaving(false);
    }
  };

  const clearEntry = async () => {
    if (!editingCell) return;
    setSaving(true);
    setError('');
    try {
      await deleteEntry({ sectionId, day: editingCell.day, periodId: editingCell.periodId });
      closeEditor();
      await load();
    } catch (err: unknown) {
      setError(errMsg(err, 'Could not clear timetable entry.'));
    } finally {
      setSaving(false);
    }
  };

  if (!canRead) {
    return (
      <ModuleShell title="Timetable" subtitle="Weekly class schedule">
        <div className="ck-alert ck-alert-am"><span>!</span><div>You do not have permission to view the timetable.</div></div>
      </ModuleShell>
    );
  }

  return (
    <ModuleShell
      title="Timetable"
      subtitle={readOnly ? 'View-only weekly schedule' : 'Weekly class schedule'}
    >
      <div className="ck-panel-stack">
        {toast ? (
          <div className="ck-alert ck-alert-am">
            <span>!</span>
            <div>{toast}</div>
          </div>
        ) : null}
        {error ? <div className="ck-alert ck-alert-re"><span>!</span><div>{error}</div></div> : null}

        <div className="ck-actions-inline">
          <select value={classId} onChange={(e) => setClassId(e.target.value)}>
            {classes.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
          <select value={sectionId} onChange={(e) => setSectionId(e.target.value)} disabled={!classId}>
            {sections.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
          {!yearIdProp ? (
            <select value={internalYearId} onChange={(e) => setInternalYearId(e.target.value)}>
              {years.map((y) => <option key={y.id} value={y.id}>{y.label}{y.active ? ' (current)' : ''}</option>)}
            </select>
          ) : null}
        </div>

        {!data?.editable && !readOnly && data ? (
          <div className="ck-alert ck-alert-am">
            <span>!</span>
            <div>Locked — {selectedYear?.label ?? data.yearId} is not the active year. Timetables can only be edited for the current year.</div>
          </div>
        ) : null}

        <div className="ck-card">
          <div className="ck-card-h"><div className="ck-card-t">Weekly schedule</div></div>
          <div style={{ padding: 16 }}>
            {loading ? (
              <div style={{ color: 'var(--ink3)' }}>Loading…</div>
            ) : !data ? (
              <div className="ck-import-zone"><div className="iz-title">Select a class, section and year</div></div>
            ) : data.noSchedule ? (
              <div className="ck-alert ck-alert-am">
                <span>i</span>
                <div>This class has no bell schedule — set one up in Setup → Bell schedules.</div>
              </div>
            ) : data.periods.length === 0 ? (
              <div className="ck-import-zone"><div className="iz-title">No periods defined for this schedule yet</div></div>
            ) : (
              <div className="ck-table-wrap">
                <table className="ck-table ck-data-table">
                  <thead>
                    <tr>
                      <th>Period</th>
                      {data.days.map((d) => <th key={d}>{d}</th>)}
                    </tr>
                  </thead>
                  <tbody>
                    {data.periods.map((p) => (
                      <tr key={p.id}>
                        <td data-label="Period">
                          <strong>{p.label}</strong>
                          <div style={{ fontSize: 12, color: 'var(--ink3)' }}>{p.start}–{p.end}</div>
                        </td>
                        {p.isBreak ? (
                          <td colSpan={data.days.length} style={{ color: 'var(--ink3)', fontStyle: 'italic', textAlign: 'center' }}>
                            Break
                          </td>
                        ) : (
                          data.days.map((day) => {
                            const entry = entryFor(day, p.id);
                            const isEditing = editingCell && editingCell.day === day && editingCell.periodId === p.id;
                            return (
                              <td
                                key={day}
                                data-label={day}
                                onClick={() => (editable && !isEditing ? openEditor(day, p.id) : undefined)}
                                style={editable ? { cursor: 'pointer' } : undefined}
                              >
                                {isEditing ? (
                                  <div style={{ display: 'grid', gap: 6, minWidth: 160 }}>
                                    <select value={editSubject} onChange={(e) => setEditSubject(e.target.value)}>
                                      <option value="">Select subject</option>
                                      {(subjects?.subjects || []).map((s) => (
                                        <option key={s.id} value={s.subjectName}>{s.subjectName}</option>
                                      ))}
                                    </select>
                                    <select value={editTeacherId} onChange={(e) => setEditTeacherId(e.target.value)}>
                                      <option value="">No teacher</option>
                                      {staffOptions.map((s) => (
                                        <option key={s.id} value={String(s.id)}>{s.name}</option>
                                      ))}
                                    </select>
                                    <div style={{ display: 'flex', gap: 6 }}>
                                      <button className="ck-btn ck-btn-g" disabled={saving} onClick={save}>Save</button>
                                      <button className="ck-btn ck-btn-ghost" disabled={saving} onClick={clearEntry}>Clear</button>
                                      <button className="ck-btn ck-btn-ghost" disabled={saving} onClick={closeEditor}>Cancel</button>
                                    </div>
                                  </div>
                                ) : entry ? (
                                  <div>
                                    <div>{entry.subjectName}</div>
                                    {entry.teacherName ? <div style={{ fontSize: 12, color: 'var(--ink3)' }}>{entry.teacherName}</div> : null}
                                  </div>
                                ) : (
                                  <span style={{ color: 'var(--ink3)' }}>—</span>
                                )}
                              </td>
                            );
                          })
                        )}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      </div>
    </ModuleShell>
  );
}

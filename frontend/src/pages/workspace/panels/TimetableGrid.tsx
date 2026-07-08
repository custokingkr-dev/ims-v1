import { useEffect, useRef, useState } from 'react';
import api from '../../../services/api';
import { ModuleShell } from '../ui';
import { usePermissions } from '../../../hooks/usePermissions';
import { useAuth } from '../../../contexts/AuthContext';
import {
  getTimetable, putEntry, deleteEntry, putEntriesBulk, getClassSubjects, addSubject,
  getBellSchedules, getClassSchedules, setClassSchedule,
  type TimetableView, type ClassSubjects, type BellSchedule,
} from '../../../services/timetableApi';

const EVERY_DAY = '__EVERY_DAY__';

/** True when, for every non-break period, the (subjectName, teacherId) assignment is
 * identical across all `view.days`, and at least one such assignment exists. */
function isUniformAcrossDays(view: TimetableView | null): boolean {
  if (!view || !view.days.length) return false;
  const nonBreak = view.periods.filter((p) => !p.isBreak);
  if (!nonBreak.length) return false;
  let anyAssigned = false;
  const keyOf = (day: string, periodId: number) => {
    const e = view.entries.find((x) => x.day === day && x.periodId === periodId) || null;
    if (e) anyAssigned = true;
    return e ? `${e.subjectName}|${e.teacherId ?? ''}` : '';
  };
  for (const p of nonBreak) {
    const first = keyOf(view.days[0], p.id);
    for (const day of view.days) {
      if (keyOf(day, p.id) !== first) return false;
    }
  }
  return anyAssigned;
}

interface AcademicYearOpt { id: string; label: string; active: boolean }

interface Props {
  readOnly?: boolean;
  staff?: Array<{ id: number | string; name: string }>;
  yearId?: string;
  years?: AcademicYearOpt[];
  embedded?: boolean;
  onManagePatterns?: () => void;
  onManageSubjects?: (classId: string) => void;
  refreshSignal?: number;
}

const ADD_SUBJECT_VALUE = '__add_subject__';

interface ClassOpt { id: string; name: string }
interface SectionOpt { id: string; name: string }

function errMsg(err: unknown, fallback: string): string {
  return (err as { response?: { data?: { message?: string } } })?.response?.data?.message
    || (err instanceof Error ? err.message : fallback);
}

export function TimetableGrid({ readOnly, yearId: yearIdProp, years: yearsProp, embedded, onManagePatterns, onManageSubjects, refreshSignal }: Props) {
  const { can } = usePermissions();
  const { user } = useAuth();
  const canRead = can('timetable:read');
  const canManage = can('timetable:manage');

  const [classes, setClasses] = useState<ClassOpt[]>([]);
  const [sections, setSections] = useState<SectionOpt[]>([]);
  const [internalYears, setInternalYears] = useState<AcademicYearOpt[]>([]);
  const [classId, setClassId] = useState('');
  const [sectionId, setSectionId] = useState('');
  const [internalYearId, setInternalYearId] = useState('');
  const controlled = yearIdProp !== undefined;
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
  const [addingSubject, setAddingSubject] = useState(false);
  const [newSubjectName, setNewSubjectName] = useState('');
  const [staffOptions, setStaffOptions] = useState<Array<{ id: number; name: string }>>([]);
  const [schedules, setSchedules] = useState<BellSchedule[]>([]);
  const [classScheduleRows, setClassScheduleRows] = useState<Array<{ classId: string; className: string; scheduleId: number | null }>>([]);
  const [patternSaving, setPatternSaving] = useState(false);
  const [sameEveryDay, setSameEveryDay] = useState(false);
  const autoKeyRef = useRef('');

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
    if (!controlled) {
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
  }, [canRead, controlled]);

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
      const key = `${sectionId}:${yearId}`;
      if (autoKeyRef.current !== key) {
        autoKeyRef.current = key;
        setSameEveryDay(isUniformAcrossDays(res.data));
      }
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
  }, [classId, yearId, refreshSignal]);

  const loadPatterns = async () => {
    try {
      const [schedulesRes, classSchedulesRes] = await Promise.all([getBellSchedules(), getClassSchedules()]);
      setSchedules(Array.isArray(schedulesRes.data) ? schedulesRes.data : []);
      setClassScheduleRows(Array.isArray(classSchedulesRes.data) ? classSchedulesRes.data : []);
    } catch {
      setSchedules([]);
      setClassScheduleRows([]);
    }
  };

  useEffect(() => {
    if (canRead) void loadPatterns();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canRead, refreshSignal]);

  const currentClassScheduleId = classScheduleRows.find((c) => c.classId === classId)?.scheduleId ?? null;
  const selectedSchedule = schedules.find((s) => s.id === currentClassScheduleId) || null;

  const handlePatternChange = async (scheduleId: string) => {
    if (!classId || !scheduleId) return;
    setPatternSaving(true);
    setError('');
    try {
      await setClassSchedule(classId, Number(scheduleId));
      await loadPatterns();
      await load();
    } catch (err: unknown) {
      setError(errMsg(err, 'Could not set period pattern.'));
    } finally {
      setPatternSaving(false);
    }
  };

  const handleSubjectSelectChange = (value: string) => {
    if (value === ADD_SUBJECT_VALUE) {
      // Open an inline styled input inside the cell editor (no window.prompt).
      setNewSubjectName('');
      setAddingSubject(true);
      return;
    }
    setEditSubject(value);
  };

  const submitNewSubject = async () => {
    const name = newSubjectName.trim();
    if (!name) return;
    try {
      await addSubject(classId, name);
      const res = await getClassSubjects(classId, yearId);
      setSubjects(res.data);
      setEditSubject(name);
      setAddingSubject(false);
      setNewSubjectName('');
    } catch (err: unknown) {
      setError(errMsg(err, 'Could not add subject.'));
    }
  };

  const selectedYear = years.find((y) => y.id === yearId) || null;
  const editable = !!(data?.editable && !readOnly);

  const entryFor = (day: string, periodId: number) =>
    data?.entries.find((e) => e.day === day && e.periodId === periodId) || null;

  const openEditor = (day: string, periodId: number) => {
    if (!editable) return;
    const lookupDay = day === EVERY_DAY ? (data?.days[0] || day) : day;
    const existing = entryFor(lookupDay, periodId);
    setEditingCell({ day, periodId });
    setEditSubject(existing?.subjectName || '');
    setEditTeacherId(existing?.teacherId != null ? String(existing.teacherId) : '');
    setAddingSubject(false);
    setNewSubjectName('');
    setError('');
  };

  const closeEditor = () => { setEditingCell(null); setAddingSubject(false); setNewSubjectName(''); };

  const save = async () => {
    if (!editingCell) return;
    if (!editSubject.trim()) {
      setError('Subject is required.');
      return;
    }
    setSaving(true);
    setError('');
    try {
      if (editingCell.day === EVERY_DAY) {
        const res = await putEntriesBulk({
          sectionId,
          entries: (data?.days || []).map((day) => ({
            day,
            periodId: editingCell.periodId,
            subjectName: editSubject.trim(),
            teacherId: editTeacherId ? Number(editTeacherId) : null,
          })),
        });
        setToast(res.data?.conflict || '');
      } else {
        const res = await putEntry({
          sectionId,
          day: editingCell.day,
          periodId: editingCell.periodId,
          subjectName: editSubject.trim(),
          teacherId: editTeacherId ? Number(editTeacherId) : null,
        });
        setToast(res.data?.conflict || '');
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
      if (editingCell.day === EVERY_DAY) {
        for (const day of data?.days || []) {
          await deleteEntry({ sectionId, day, periodId: editingCell.periodId });
        }
      } else {
        await deleteEntry({ sectionId, day: editingCell.day, periodId: editingCell.periodId });
      }
      closeEditor();
      await load();
    } catch (err: unknown) {
      setError(errMsg(err, 'Could not clear timetable entry.'));
    } finally {
      setSaving(false);
    }
  };

  const handleToggleSameEveryDay = async (checked: boolean) => {
    if (!checked) {
      setSameEveryDay(false);
      return;
    }
    if (!data || isUniformAcrossDays(data)) {
      setSameEveryDay(true);
      return;
    }
    const sourceDay = data.days.find((d) =>
      data.periods.some((p) => !p.isBreak && data.entries.some((e) => e.day === d && e.periodId === p.id))
    ) || data.days[0];
    if (!window.confirm(`Set every day to match ${sourceDay}? This overwrites the other days.`)) {
      return;
    }
    const nonBreak = data.periods.filter((p) => !p.isBreak);
    const entries: { day: string; periodId: number; subjectName: string; teacherId: number | null }[] = [];
    const toClear: { day: string; periodId: number }[] = [];
    for (const p of nonBreak) {
      const src = data.entries.find((e) => e.day === sourceDay && e.periodId === p.id);
      if (src) {
        for (const day of data.days) {
          entries.push({ day, periodId: p.id, subjectName: src.subjectName, teacherId: src.teacherId });
        }
      } else {
        // sourceDay has no assignment for this period, but some other day may — clear
        // it everywhere so the collapsed "Every day" view doesn't hide a divergent value.
        for (const day of data.days) {
          if (data.entries.some((e) => e.day === day && e.periodId === p.id)) {
            toClear.push({ day, periodId: p.id });
          }
        }
      }
    }
    if (!entries.length && !toClear.length) {
      setSameEveryDay(true);
      return;
    }
    setSaving(true);
    setError('');
    try {
      let conflict: string | undefined;
      if (entries.length) {
        const res = await putEntriesBulk({ sectionId, entries });
        conflict = res.data?.conflict || undefined;
      }
      for (const { day, periodId } of toClear) {
        await deleteEntry({ sectionId, day, periodId });
      }
      setSameEveryDay(true);
      await load();
      if (conflict) setToast(conflict);
    } catch (err: unknown) {
      setError(errMsg(err, 'Could not sync days.'));
    } finally {
      setSaving(false);
    }
  };

  const handleCopyDay = async (day: string) => {
    if (!data) return;
    const nonBreak = data.periods.filter((p) => !p.isBreak);
    const entries: { day: string; periodId: number; subjectName: string; teacherId: number | null }[] = [];
    const toClear: { day: string; periodId: number }[] = [];
    for (const p of nonBreak) {
      const src = data.entries.find((e) => e.day === day && e.periodId === p.id);
      if (src) {
        for (const otherDay of data.days) {
          if (otherDay === day) continue;
          entries.push({ day: otherDay, periodId: p.id, subjectName: src.subjectName, teacherId: src.teacherId });
        }
      } else {
        // source day is empty for this period — clear it on the other days too so the
        // copy fully mirrors the source (matches the confirm text below).
        for (const otherDay of data.days) {
          if (otherDay === day) continue;
          if (data.entries.some((e) => e.day === otherDay && e.periodId === p.id)) {
            toClear.push({ day: otherDay, periodId: p.id });
          }
        }
      }
    }
    if (!entries.length && !toClear.length) {
      setToast(`${day} has no assignments to copy.`);
      return;
    }
    if (!window.confirm(`Copy ${day}'s schedule to all other weekdays? This will overwrite existing entries.`)) {
      return;
    }
    setSaving(true);
    setError('');
    try {
      let conflict: string | undefined;
      if (entries.length) {
        const res = await putEntriesBulk({ sectionId, entries });
        conflict = res.data?.conflict || undefined;
      }
      for (const { day: clearDay, periodId } of toClear) {
        await deleteEntry({ sectionId, day: clearDay, periodId });
      }
      await load();
      setToast(conflict || '');
    } catch (err: unknown) {
      setError(errMsg(err, 'Could not copy day.'));
    } finally {
      setSaving(false);
    }
  };

  if (!canRead) {
    const noPermission = (
      <div className="ck-alert ck-alert-am"><span>!</span><div>You do not have permission to view the timetable.</div></div>
    );
    return embedded ? noPermission : (
      <ModuleShell title="Timetable" subtitle="Weekly class schedule">
        {noPermission}
      </ModuleShell>
    );
  }

  const body = (
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
          {!controlled ? (
            <select value={internalYearId} onChange={(e) => setInternalYearId(e.target.value)}>
              {years.map((y) => <option key={y.id} value={y.id}>{y.label}{y.active ? ' (current)' : ''}</option>)}
            </select>
          ) : null}
        </div>

        <div className="ck-actions-inline" style={{ flexWrap: 'wrap' }}>
          <label className="ts" style={{ color: 'var(--ink3)' }}>Period pattern</label>
          {canManage && editable ? (
            <select
              value={currentClassScheduleId != null ? String(currentClassScheduleId) : ''}
              onChange={(e) => void handlePatternChange(e.target.value)}
              disabled={!classId || patternSaving}
            >
              <option value="">Select a pattern…</option>
              {schedules.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          ) : (
            <span>{selectedSchedule?.name || '—'}</span>
          )}
          {canManage && onManagePatterns ? (
            <button type="button" className="ck-btn ck-btn-ghost" onClick={onManagePatterns}>Manage patterns</button>
          ) : null}
          {canManage && editable && onManageSubjects ? (
            <button type="button" className="ck-btn ck-btn-ghost" onClick={() => onManageSubjects(classId)}>Manage subjects</button>
          ) : null}
        </div>
        {selectedSchedule ? (
          <div className="ts" style={{ color: 'var(--ink3)' }}>
            {selectedSchedule.periods.length} period{selectedSchedule.periods.length === 1 ? '' : 's'}:{' '}
            {[...selectedSchedule.periods]
              .sort((a, b) => a.sortOrder - b.sortOrder)
              .map((p) => `${p.label} ${p.start}–${p.end}${p.isBreak ? ' (break)' : ''}`)
              .join(', ')}
          </div>
        ) : null}

        {editable && data && !data.noSchedule && data.periods.length > 0 ? (
          <label className="ck-actions-inline ts" style={{ color: 'var(--ink3)', gap: 6 }}>
            <input
              type="checkbox"
              checked={sameEveryDay}
              disabled={saving}
              onChange={(e) => void handleToggleSameEveryDay(e.target.checked)}
            />
            Same every day
          </label>
        ) : null}

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
                <div>
                  Pick a period pattern above to start.
                  {canManage && onManagePatterns ? (
                    <>
                      {' '}
                      No patterns yet?{' '}
                      <button
                        type="button"
                        className="ck-btn ck-btn-ghost"
                        style={{ padding: '2px 6px', textDecoration: 'underline' }}
                        onClick={onManagePatterns}
                      >
                        Create one →
                      </button>
                    </>
                  ) : null}
                </div>
              </div>
            ) : data.periods.length === 0 ? (
              <div className="ck-import-zone"><div className="iz-title">No periods defined for this schedule yet</div></div>
            ) : (
              <div className="ck-table-wrap">
                <table className="ck-table ck-data-table">
                  <thead>
                    <tr>
                      <th>Period</th>
                      {sameEveryDay ? (
                        <th>Every day</th>
                      ) : (
                        data.days.map((d) => (
                          <th key={d}>
                            <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                              <span>{d}</span>
                              {editable ? (
                                <button
                                  type="button"
                                  className="ck-btn ck-btn-ghost"
                                  title={`Copy ${d} to all other weekdays`}
                                  style={{ padding: '0 4px', lineHeight: 1 }}
                                  disabled={saving}
                                  onClick={() => void handleCopyDay(d)}
                                >
                                  ⧉
                                </button>
                              ) : null}
                            </div>
                          </th>
                        ))
                      )}
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
                          <td colSpan={sameEveryDay ? 1 : data.days.length} style={{ color: 'var(--ink3)', fontStyle: 'italic', textAlign: 'center', background: 'var(--g1)' }}>
                            ☕ Break
                          </td>
                        ) : (
                          (sameEveryDay ? [EVERY_DAY] : data.days).map((day) => {
                            const entry = entryFor(day === EVERY_DAY ? data.days[0] : day, p.id);
                            const isEditing = editingCell && editingCell.day === day && editingCell.periodId === p.id;
                            return (
                              <td
                                key={day}
                                data-label={day === EVERY_DAY ? 'Every day' : day}
                                onClick={() => (editable && !isEditing ? openEditor(day, p.id) : undefined)}
                                style={editable ? { cursor: 'pointer' } : undefined}
                              >
                                {isEditing ? (
                                  <div style={{ display: 'grid', gap: 6, minWidth: 160 }}>
                                    <select value={editSubject} onChange={(e) => void handleSubjectSelectChange(e.target.value)}>
                                      <option value="">Select subject</option>
                                      {(subjects?.subjects || []).map((s) => (
                                        <option key={s.id} value={s.subjectName}>{s.subjectName}</option>
                                      ))}
                                      <option value={ADD_SUBJECT_VALUE}>+ Add subject…</option>
                                    </select>
                                    {addingSubject ? (
                                      <div style={{ display: 'flex', gap: 6 }}>
                                        <input
                                          autoFocus
                                          value={newSubjectName}
                                          onChange={(e) => setNewSubjectName(e.target.value)}
                                          onKeyDown={(e) => { if (e.key === 'Enter') void submitNewSubject(); if (e.key === 'Escape') setAddingSubject(false); }}
                                          placeholder="New subject name"
                                          style={{ flex: 1, minWidth: 0 }}
                                        />
                                        <button className="ck-btn ck-btn-g" disabled={!newSubjectName.trim()} onClick={() => void submitNewSubject()}>Add</button>
                                        <button className="ck-btn ck-btn-ghost" onClick={() => setAddingSubject(false)}>×</button>
                                      </div>
                                    ) : null}
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
  );

  return embedded ? body : (
    <ModuleShell
      title="Timetable"
      subtitle={readOnly ? 'View-only weekly schedule' : 'Weekly class schedule'}
    >
      {body}
    </ModuleShell>
  );
}

import { useEffect, useMemo, useState } from 'react';
import {
  AlertTriangle,
  Building2,
  Check,
  ChevronRight,
  Clock3,
  DoorOpen,
  Plus,
  RefreshCw,
  Save,
  Settings2,
  ShieldCheck,
  Sparkles,
  Trash2,
  UserRound,
  UsersRound,
  X,
} from 'lucide-react';
import api from '../../../services/api';
import {
  addSubject,
  createRoom,
  deleteEntry,
  getBellSchedules,
  getClassSchedules,
  getClassSubjects,
  getRooms,
  getTeacherAvailability,
  getTimetable,
  getTimetableHealth,
  getTimetableOverview,
  publishTimetable,
  putEntry,
  saveTeacherAvailability,
  setClassSchedule,
  updateSubjectPolicy,
  type BellPeriod,
  type BellSchedule,
  type ClassScheduleRow,
  type ClassSubject,
  type TimetableEntry,
  type TimetableHealth,
  type TimetableOverviewEntry,
  type TimetableRoom,
  type TimetableView,
  type TeacherAvailability,
} from '../../../services/timetableApi';
import { usePermissions } from '../../../hooks/usePermissions';
import { ModuleShell } from '../ui';
import { BellSchedulesPanel } from './setup/BellSchedulesPanel';

type StudioView = 'class' | 'teacher' | 'room';
type Option = { id: string | number; name?: string; label?: string; active?: boolean };
type StaffOption = { id: string | number; name: string };

interface TimetableStudioPanelProps {
  readOnly?: boolean;
  staff?: StaffOption[];
}

function errorMessage(error: unknown, fallback: string): string {
  return (error as { response?: { data?: { message?: string } } })?.response?.data?.message
    || (error instanceof Error ? error.message : fallback);
}

function timeLabel(value: string): string {
  if (!value) return '';
  const [hourValue, minute = '00'] = value.split(':');
  const hour = Number(hourValue);
  const suffix = hour >= 12 ? 'PM' : 'AM';
  return `${hour % 12 || 12}:${minute} ${suffix}`;
}

function cellKey(day: string, periodId: number): string {
  return `${day}:${periodId}`;
}

function statusTone(issueCount: number): string {
  return issueCount > 0 ? 'danger' : 'good';
}

export function TimetableStudioPanel({ readOnly, staff = [] }: TimetableStudioPanelProps) {
  const { can } = usePermissions();
  const editable = !readOnly && can('timetable:manage');
  const [view, setView] = useState<StudioView>('class');
  const [years, setYears] = useState<Option[]>([]);
  const [classes, setClasses] = useState<Option[]>([]);
  const [sections, setSections] = useState<Option[]>([]);
  const [schedules, setSchedules] = useState<BellSchedule[]>([]);
  const [classSchedules, setClassSchedules] = useState<ClassScheduleRow[]>([]);
  const [subjects, setSubjects] = useState<ClassSubject[]>([]);
  const [rooms, setRooms] = useState<TimetableRoom[]>([]);
  const [overview, setOverview] = useState<TimetableOverviewEntry[]>([]);
  const [timetable, setTimetable] = useState<TimetableView | null>(null);
  const [health, setHealth] = useState<TimetableHealth | null>(null);
  const [yearId, setYearId] = useState('');
  const [classId, setClassId] = useState('');
  const [sectionId, setSectionId] = useState('');
  const [resourceId, setResourceId] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [editor, setEditor] = useState<{ day: string; period: BellPeriod; entry?: TimetableEntry } | null>(null);
  const [entryForm, setEntryForm] = useState({ subjectName: '', teacherId: '', roomId: '' });
  const [showPatterns, setShowPatterns] = useState(false);
  const [showSubjects, setShowSubjects] = useState(false);
  const [showRooms, setShowRooms] = useState(false);
  const [showAvailability, setShowAvailability] = useState(false);
  const [availabilityScheduleId, setAvailabilityScheduleId] = useState('');
  const [availability, setAvailability] = useState<TeacherAvailability[]>([]);
  const [newSubject, setNewSubject] = useState('');
  const [newRoom, setNewRoom] = useState({ name: '', roomType: 'CLASSROOM', capacity: '40' });

  const loadPlanningData = async (nextYearId: string) => {
    const [overviewResult, healthResult] = await Promise.all([
      getTimetableOverview(nextYearId || undefined),
      getTimetableHealth(nextYearId || undefined),
    ]);
    setOverview(Array.isArray(overviewResult.data) ? overviewResult.data : []);
    setHealth(healthResult.data);
  };

  const loadBase = async () => {
    setLoading(true);
    setError('');
    try {
      const [yearsResult, classesResult, schedulesResult, classSchedulesResult, roomsResult] = await Promise.all([
        api.get<Option[]>('/academic-years'),
        api.get<Option[]>('/classes'),
        getBellSchedules(),
        getClassSchedules(),
        getRooms(),
      ]);
      const yearList = Array.isArray(yearsResult.data) ? yearsResult.data : [];
      const classList = Array.isArray(classesResult.data) ? classesResult.data : [];
      const nextYearId = String(yearList.find((row) => row.active)?.id || yearList[0]?.id || '');
      setYears(yearList);
      setClasses(classList);
      setSchedules(Array.isArray(schedulesResult.data) ? schedulesResult.data : []);
      setClassSchedules(Array.isArray(classSchedulesResult.data) ? classSchedulesResult.data : []);
      setRooms(Array.isArray(roomsResult.data) ? roomsResult.data : []);
      setYearId(nextYearId);
      await loadPlanningData(nextYearId);
    } catch (loadError) {
      setError(errorMessage(loadError, 'Timetable planning data could not be loaded.'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadBase();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const changeYear = async (nextYearId: string) => {
    setYearId(nextYearId);
    setTimetable(null);
    setError('');
    setLoading(true);
    try {
      await loadPlanningData(nextYearId);
      if (classId) {
        const subjectResult = await getClassSubjects(classId, nextYearId);
        setSubjects(subjectResult.data?.subjects ?? []);
      }
      if (sectionId) {
        const [gridResult, healthResult] = await Promise.all([
          getTimetable(sectionId, nextYearId),
          getTimetableHealth(nextYearId),
        ]);
        setTimetable(gridResult.data);
        setHealth(healthResult.data);
      }
    } catch (loadError) {
      setError(errorMessage(loadError, 'The selected academic year could not be loaded.'));
    } finally {
      setLoading(false);
    }
  };

  const changeClass = async (nextClassId: string) => {
    setClassId(nextClassId);
    setSectionId('');
    setTimetable(null);
    setSections([]);
    setSubjects([]);
    setError('');
    if (!nextClassId) return;
    setLoading(true);
    try {
      const [sectionsResult, subjectsResult] = await Promise.all([
        api.get<Option[]>(`/classes/${encodeURIComponent(nextClassId)}/sections`),
        getClassSubjects(nextClassId, yearId || undefined),
      ]);
      setSections(Array.isArray(sectionsResult.data) ? sectionsResult.data : []);
      setSubjects(subjectsResult.data?.subjects ?? []);
    } catch (loadError) {
      setError(errorMessage(loadError, 'Class timetable settings could not be loaded.'));
    } finally {
      setLoading(false);
    }
  };

  const changeSection = async (nextSectionId: string) => {
    setSectionId(nextSectionId);
    setTimetable(null);
    setError('');
    if (!nextSectionId) return;
    setLoading(true);
    try {
      const [gridResult, healthResult] = await Promise.all([
        getTimetable(nextSectionId, yearId || undefined),
        getTimetableHealth(yearId || undefined),
      ]);
      setTimetable(gridResult.data);
      setHealth(healthResult.data);
    } catch (loadError) {
      setError(errorMessage(loadError, 'The class timetable could not be loaded.'));
    } finally {
      setLoading(false);
    }
  };

  const refreshGrid = async () => {
    if (!sectionId) return;
    const [gridResult, healthResult, overviewResult] = await Promise.all([
      getTimetable(sectionId, yearId || undefined),
      getTimetableHealth(yearId || undefined),
      getTimetableOverview(yearId || undefined),
    ]);
    setTimetable(gridResult.data);
    setHealth(healthResult.data);
    setOverview(Array.isArray(overviewResult.data) ? overviewResult.data : []);
  };

  const scheduleId = classSchedules.find((row) => String(row.classId) === classId)?.scheduleId ?? null;
  const activeSchedule = schedules.find((row) => row.id === scheduleId);
  const periods = timetable?.periods ?? activeSchedule?.periods ?? [];
  const days = timetable?.days?.length ? timetable.days : ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
  const entryMap = useMemo(() => new Map((timetable?.entries ?? []).map((entry) => [cellKey(entry.day, entry.periodId), entry])), [timetable]);

  const openEditor = (day: string, period: BellPeriod) => {
    if (!editable || period.isBreak || !sectionId) return;
    const entry = entryMap.get(cellKey(day, period.id));
    setEntryForm({
      subjectName: entry?.subjectName || subjects[0]?.subjectName || '',
      teacherId: entry?.teacherId ? String(entry.teacherId) : '',
      roomId: entry?.roomId ? String(entry.roomId) : '',
    });
    setEditor({ day, period, entry });
  };

  const saveEntry = async () => {
    if (!editor || !sectionId) return;
    setSaving('entry');
    setError('');
    try {
      await putEntry({
        sectionId,
        day: editor.day,
        periodId: editor.period.id,
        subjectName: entryForm.subjectName,
        teacherId: entryForm.teacherId ? Number(entryForm.teacherId) : null,
        roomId: entryForm.roomId ? Number(entryForm.roomId) : null,
      });
      setEditor(null);
      setNotice('Period saved. Teacher and room conflicts were checked.');
      await refreshGrid();
    } catch (saveError) {
      setError(errorMessage(saveError, 'The period could not be saved.'));
    } finally {
      setSaving('');
    }
  };

  const clearEntry = async () => {
    if (!editor || !sectionId || !editor.entry) return;
    setSaving('entry');
    setError('');
    try {
      await deleteEntry({ sectionId, day: editor.day, periodId: editor.period.id });
      setEditor(null);
      setNotice('The period was cleared.');
      await refreshGrid();
    } catch (saveError) {
      setError(errorMessage(saveError, 'The period could not be cleared.'));
    } finally {
      setSaving('');
    }
  };

  const changeSchedule = async (nextScheduleId: string) => {
    if (!classId || !nextScheduleId || !editable) return;
    setSaving('schedule');
    setError('');
    try {
      await setClassSchedule(classId, Number(nextScheduleId));
      const result = await getClassSchedules();
      setClassSchedules(Array.isArray(result.data) ? result.data : []);
      setNotice('Period pattern assigned to the selected class.');
      if (sectionId) await changeSection(sectionId);
    } catch (saveError) {
      setError(errorMessage(saveError, 'The period pattern could not be assigned.'));
    } finally {
      setSaving('');
    }
  };

  const createSubject = async () => {
    if (!classId || !newSubject) return;
    setSaving('subject');
    setError('');
    try {
      await addSubject(classId, newSubject, yearId || undefined);
      const result = await getClassSubjects(classId, yearId || undefined);
      setSubjects(result.data?.subjects ?? []);
      setNewSubject('');
      setNotice('Subject added to the class planning rules.');
    } catch (saveError) {
      setError(errorMessage(saveError, 'The subject could not be added.'));
    } finally {
      setSaving('');
    }
  };

  const updateSubject = async (subject: ClassSubject, patch: Partial<ClassSubject>) => {
    setSaving(`subject-${subject.id}`);
    setError('');
    try {
      const next = { ...subject, ...patch };
      await updateSubjectPolicy(subject.id, {
        weeklyPeriods: next.weeklyPeriods,
        preferredPartOfDay: next.preferredPartOfDay,
        requiredRoomType: next.requiredRoomType,
        doublePeriod: next.doublePeriod,
      });
      const result = await getClassSubjects(classId, yearId || undefined);
      setSubjects(result.data?.subjects ?? []);
    } catch (saveError) {
      setError(errorMessage(saveError, 'The subject rule could not be updated.'));
    } finally {
      setSaving('');
    }
  };

  const addRoom = async () => {
    setSaving('room');
    setError('');
    try {
      await createRoom({ name: newRoom.name, roomType: newRoom.roomType, capacity: Number(newRoom.capacity) });
      const result = await getRooms();
      setRooms(Array.isArray(result.data) ? result.data : []);
      setNewRoom({ name: '', roomType: 'CLASSROOM', capacity: '40' });
      setNotice('Room added to timetable conflict checks.');
    } catch (saveError) {
      setError(errorMessage(saveError, 'The room could not be created.'));
    } finally {
      setSaving('');
    }
  };

  const openAvailability = async () => {
    if (!resourceId) return;
    setSaving('availability-load');
    setError('');
    try {
      const result = await getTeacherAvailability(yearId || undefined, Number(resourceId));
      setAvailability(Array.isArray(result.data) ? result.data : []);
      setAvailabilityScheduleId(String(scheduleId || schedules[0]?.id || ''));
      setShowAvailability(true);
    } catch (loadError) {
      setError(errorMessage(loadError, 'Teacher availability could not be loaded.'));
    } finally {
      setSaving('');
    }
  };

  const toggleAvailability = async (day: string, periodId: number) => {
    if (!resourceId || !yearId) return;
    const current = availability.find((row) => row.day === day && row.periodId === periodId);
    const nextAvailable = current ? !current.available : false;
    setSaving(`availability-${day}-${periodId}`);
    setError('');
    try {
      const result = await saveTeacherAvailability({
        yearId,
        teacherId: Number(resourceId),
        day,
        periodId,
        available: nextAvailable,
        note: nextAvailable ? null : 'Unavailable',
      });
      setAvailability((rows) => [
        ...rows.filter((row) => !(row.day === day && row.periodId === periodId)),
        result.data,
      ]);
      setNotice('Teacher availability updated and included in conflict validation.');
      await loadPlanningData(yearId);
    } catch (saveError) {
      setError(errorMessage(saveError, 'Teacher availability could not be updated.'));
    } finally {
      setSaving('');
    }
  };

  const publish = async () => {
    if (!yearId || !health?.readyToPublish) return;
    setSaving('publish');
    setError('');
    try {
      const result = await publishTimetable(yearId, `${years.find((row) => String(row.id) === yearId)?.label || 'Academic year'} timetable`);
      setNotice(`Timetable revision ${result.data.revision ?? ''} published.`);
      await loadPlanningData(yearId);
    } catch (saveError) {
      setError(errorMessage(saveError, 'The timetable could not be published.'));
    } finally {
      setSaving('');
    }
  };

  const resourceOptions = view === 'teacher'
    ? staff.map((row) => ({ id: String(row.id), name: row.name }))
    : rooms.map((row) => ({ id: String(row.id), name: row.name }));
  const resourceEntries = useMemo(() => {
    if (view === 'teacher') return overview.filter((row) => String(row.teacherId || '') === resourceId);
    if (view === 'room') return overview.filter((row) => String(row.roomId || '') === resourceId);
    return [];
  }, [overview, resourceId, view]);

  return (
    <ModuleShell
      title="Timetable studio"
      subtitle="Plan classes, teachers, rooms, and weekly subject requirements in one connected workspace"
      actions={
        <div className="erp-action-group">
          <select aria-label="Academic year" value={yearId} onChange={(event) => void changeYear(event.target.value)}>
            {years.map((year) => <option key={year.id} value={year.id}>{year.label || year.name}{year.active ? ' (current)' : ''}</option>)}
          </select>
          {editable && <button className="erp-btn primary" disabled={!health?.readyToPublish || saving === 'publish'} onClick={() => void publish()}><ShieldCheck size={16} /> Publish</button>}
        </div>
      }
    >
      <div className="erp-module">
        <div className="erp-tabs" role="tablist" aria-label="Timetable views">
          <button className={view === 'class' ? 'active' : ''} onClick={() => setView('class')}><UsersRound size={16} /> Class</button>
          <button className={view === 'teacher' ? 'active' : ''} onClick={() => { setView('teacher'); setResourceId(''); }}><UserRound size={16} /> Teacher</button>
          <button className={view === 'room' ? 'active' : ''} onClick={() => { setView('room'); setResourceId(''); }}><Building2 size={16} /> Room</button>
        </div>

        {notice && <div className="erp-notice good"><Check size={16} /><span>{notice}</span><button aria-label="Dismiss" onClick={() => setNotice('')}><X size={15} /></button></div>}
        {error && <div className="erp-notice danger"><AlertTriangle size={16} /><span>{error}</span><button aria-label="Dismiss" onClick={() => setError('')}><X size={15} /></button></div>}

        <div className="erp-timetable-top">
          <div className="erp-health-score">
            <div><span>Readiness</span><strong>{health?.score ?? 0}</strong><small>/100</small></div>
            <div className="erp-score-track"><span style={{ width: `${health?.score ?? 0}%` }} /></div>
          </div>
          <div className="erp-issue-summary">
            <span className={`erp-status ${statusTone(health?.teacherConflicts ?? 0)}`}>{health?.teacherConflicts ?? 0} teacher conflicts</span>
            <span className={`erp-status ${statusTone(health?.roomConflicts ?? 0)}`}>{health?.roomConflicts ?? 0} room conflicts</span>
            <span className={`erp-status ${statusTone(health?.preferenceIssues ?? health?.quotaIssues ?? 0)}`}>{health?.preferenceIssues ?? health?.quotaIssues ?? 0} planning issues</span>
          </div>
          <div className="erp-action-group">
            <button className="erp-btn secondary" onClick={() => setShowPatterns(true)}><Clock3 size={16} /> Period patterns</button>
            <button className="erp-btn secondary" onClick={() => setShowRooms(true)}><DoorOpen size={16} /> Rooms</button>
          </div>
        </div>

        {view === 'class' ? (
          <>
            <div className="erp-toolbar timetable">
              <div className="erp-filter-group">
                <select aria-label="Class" value={classId} onChange={(event) => void changeClass(event.target.value)}>
                  <option value="">Select class</option>
                  {classes.map((row) => <option key={row.id} value={row.id}>{row.name || row.label}</option>)}
                </select>
                <select aria-label="Section" disabled={!classId} value={sectionId} onChange={(event) => void changeSection(event.target.value)}>
                  <option value="">Select section</option>
                  {sections.map((row) => <option key={row.id} value={row.id}>{row.name || row.label}</option>)}
                </select>
                <select aria-label="Period pattern" disabled={!classId || !editable} value={scheduleId ?? ''} onChange={(event) => void changeSchedule(event.target.value)}>
                  <option value="">Assign period pattern</option>
                  {schedules.map((row) => <option key={row.id} value={row.id}>{row.name}</option>)}
                </select>
              </div>
              <div className="erp-action-group">
                <button className="erp-btn secondary" disabled={!classId} onClick={() => setShowSubjects(true)}><Settings2 size={16} /> Subject rules</button>
                <button className="erp-icon-btn" title="Refresh" aria-label="Refresh timetable" disabled={!sectionId || loading} onClick={() => void refreshGrid()}><RefreshCw size={17} /></button>
              </div>
            </div>

            {timetable?.noSchedule ? (
              <div className="erp-empty prominent"><Clock3 size={24} /><strong>No period pattern assigned</strong><span>Assign a period pattern to this class before scheduling lessons.</span></div>
            ) : sectionId && periods.length ? (
              <div className="erp-timetable-scroll">
                <div className="erp-timetable-grid" style={{ gridTemplateColumns: `112px repeat(${periods.length}, minmax(142px, 1fr))` }}>
                  <div className="erp-timetable-corner">Day</div>
                  {periods.map((period) => <div key={period.id} className={`erp-period-head ${period.isBreak ? 'break' : ''}`}><strong>{period.label}</strong><span>{timeLabel(period.start)}-{timeLabel(period.end)}</span></div>)}
                  {days.map((day) => (
                    <div className="erp-timetable-row" key={day}>
                      <div className="erp-day-head">{day}</div>
                      {periods.map((period) => {
                        const entry = entryMap.get(cellKey(day, period.id));
                        return (
                          <button
                            key={period.id}
                            type="button"
                            className={`erp-schedule-cell ${period.isBreak ? 'break' : ''} ${entry ? 'filled' : ''}`}
                            disabled={period.isBreak || !editable}
                            onClick={() => openEditor(day, period)}
                          >
                            {period.isBreak ? <><Clock3 size={16} /><span>{period.label}</span></> : entry ? <><strong>{entry.subjectName}</strong><span>{entry.teacherName || 'Teacher unassigned'}</span><small>{entry.roomName || 'Room unassigned'}</small></> : <><Plus size={16} /><span>Add lesson</span></>}
                          </button>
                        );
                      })}
                    </div>
                  ))}
                </div>
              </div>
            ) : (
              <div className="erp-empty prominent"><UsersRound size={24} /><strong>Select a class and section</strong><span>The weekly timetable and its connected validation will appear here.</span></div>
            )}
          </>
        ) : (
          <>
            <div className="erp-toolbar">
              <div className="erp-filter-group">
                <select aria-label={view === 'teacher' ? 'Teacher' : 'Room'} value={resourceId} onChange={(event) => setResourceId(event.target.value)}>
                  <option value="">Select {view}</option>
                  {resourceOptions.map((row) => <option key={row.id} value={row.id}>{row.name}</option>)}
                </select>
              </div>
              {view === 'teacher' && (
                <div className="erp-action-group">
                  <button className="erp-btn secondary" disabled={!editable || !resourceId || saving === 'availability-load'} onClick={() => void openAvailability()}><Clock3 size={16} /> Availability</button>
                </div>
              )}
            </div>
            <div className="erp-resource-board">
              {resourceId ? days.map((day) => {
                const dayEntries = resourceEntries.filter((row) => row.day === day).sort((a, b) => a.start.localeCompare(b.start));
                return (
                  <section key={day}>
                    <h3>{day}<span>{dayEntries.length} periods</span></h3>
                    {dayEntries.map((entry) => <div key={entry.id} className="erp-resource-entry"><span>{timeLabel(entry.start)}</span><div><strong>{entry.subjectName}</strong><small>{entry.className} - {entry.sectionName}{view === 'teacher' ? ` - ${entry.roomName || 'No room'}` : ` - ${entry.teacherName || 'No teacher'}`}</small></div><ChevronRight size={16} /></div>)}
                    {!dayEntries.length && <p>No scheduled periods</p>}
                  </section>
                );
              }) : <div className="erp-empty prominent"><Sparkles size={23} /><strong>Select a {view}</strong><span>Its complete weekly workload will appear without changing the class timetable.</span></div>}
            </div>
          </>
        )}

        <section className="erp-validation">
          <div className="erp-section-head"><div><span className="erp-eyebrow">Publish gate</span><h2>Validation results</h2><p>Hard conflicts block publication; preference issues guide timetable quality.</p></div>{health?.publication && <span className="erp-status good">Published revision {health.publication.revision}</span>}</div>
          <div className="erp-validation-grid">
            {(health?.issues ?? []).map((issue) => <div key={issue.code} className={issue.severity === 'HARD' ? 'hard' : 'preference'}><AlertTriangle size={17} /><span><strong>{issue.label}</strong><small>{issue.severity === 'HARD' ? 'Must be resolved' : 'Planning preference'}</small></span><b>{issue.count}</b></div>)}
            {!health?.issues?.length && <div className="clear"><ShieldCheck size={18} /><span><strong>No timetable conflicts</strong><small>Current entries pass all connected checks.</small></span><b><Check size={17} /></b></div>}
          </div>
        </section>
      </div>

      {editor && (
        <div className="erp-dialog-backdrop" onMouseDown={() => setEditor(null)}>
          <div className="erp-dialog narrow" role="dialog" aria-modal="true" aria-labelledby="period-editor-title" onMouseDown={(event) => event.stopPropagation()}>
            <header><div><span className="erp-eyebrow">{editor.day} - {timeLabel(editor.period.start)}</span><h2 id="period-editor-title">{editor.entry ? 'Edit lesson' : 'Add lesson'}</h2></div><button className="erp-icon-btn" aria-label="Close" onClick={() => setEditor(null)}><X size={18} /></button></header>
            <div className="erp-form-grid single">
              <label>Subject<select autoFocus value={entryForm.subjectName} onChange={(event) => setEntryForm({ ...entryForm, subjectName: event.target.value })}><option value="">Select subject</option>{subjects.map((row) => <option key={row.id} value={row.subjectName}>{row.subjectName}</option>)}</select></label>
              <label>Teacher<select value={entryForm.teacherId} onChange={(event) => setEntryForm({ ...entryForm, teacherId: event.target.value })}><option value="">Unassigned</option>{staff.map((row) => <option key={row.id} value={row.id}>{row.name}</option>)}</select></label>
              <label>Room<select value={entryForm.roomId} onChange={(event) => setEntryForm({ ...entryForm, roomId: event.target.value })}><option value="">Unassigned</option>{rooms.map((row) => <option key={row.id} value={row.id}>{row.name} - {row.roomType.toLowerCase()}</option>)}</select></label>
            </div>
            <div className="erp-dialog-hint"><ShieldCheck size={16} /> Saving checks time-overlap conflicts for the teacher and room.</div>
            <footer>{editor.entry ? <button className="erp-btn danger" disabled={saving === 'entry'} onClick={() => void clearEntry()}><Trash2 size={16} /> Clear period</button> : <span />}<div className="erp-action-group"><button className="erp-btn secondary" onClick={() => setEditor(null)}>Cancel</button><button className="erp-btn primary" disabled={!entryForm.subjectName || saving === 'entry'} onClick={() => void saveEntry()}><Save size={16} /> Save lesson</button></div></footer>
          </div>
        </div>
      )}

      {showPatterns && (
        <div className="erp-dialog-backdrop" onMouseDown={() => setShowPatterns(false)}>
          <div className="erp-dialog wide" role="dialog" aria-modal="true" aria-labelledby="patterns-title" onMouseDown={(event) => event.stopPropagation()}>
            <header><div><span className="erp-eyebrow">School setup</span><h2 id="patterns-title">Period patterns</h2></div><button className="erp-icon-btn" aria-label="Close" onClick={() => setShowPatterns(false)}><X size={18} /></button></header>
            <div className="erp-dialog-body"><BellSchedulesPanel embedded /></div>
            <footer><span /><button className="erp-btn primary" onClick={() => { setShowPatterns(false); void loadBase(); }}>Done</button></footer>
          </div>
        </div>
      )}

      {showSubjects && (
        <div className="erp-dialog-backdrop" onMouseDown={() => setShowSubjects(false)}>
          <div className="erp-dialog wide" role="dialog" aria-modal="true" aria-labelledby="subjects-title" onMouseDown={(event) => event.stopPropagation()}>
            <header><div><span className="erp-eyebrow">Class planning</span><h2 id="subjects-title">Subject rules</h2></div><button className="erp-icon-btn" aria-label="Close" onClick={() => setShowSubjects(false)}><X size={18} /></button></header>
            <div className="erp-dialog-body">
              <div className="erp-inline-form subject-add"><input autoFocus placeholder="New subject name" value={newSubject} onChange={(event) => setNewSubject(event.target.value)} /><button className="erp-btn primary" disabled={!editable || !newSubject || saving === 'subject'} onClick={() => void createSubject()}><Plus size={16} /> Add subject</button></div>
              <div className="erp-table-frame flush">
                <table className="erp-table subject-rules"><thead><tr><th>Subject</th><th>Periods/week</th><th>Preferred time</th><th>Room type</th><th>Double period</th></tr></thead>
                  <tbody>{subjects.map((subject) => <tr key={subject.id}>
                    <td><strong>{subject.subjectName}</strong></td>
                    <td><input aria-label={`${subject.subjectName} weekly periods`} disabled={!editable || saving === `subject-${subject.id}`} type="number" min="0" max="20" value={subject.weeklyPeriods} onChange={(event) => setSubjects((current) => current.map((row) => row.id === subject.id ? { ...row, weeklyPeriods: Number(event.target.value) } : row))} onBlur={() => void updateSubject(subject, { weeklyPeriods: subjects.find((row) => row.id === subject.id)?.weeklyPeriods ?? 0 })} /></td>
                    <td><select aria-label={`${subject.subjectName} preferred time`} disabled={!editable || saving === `subject-${subject.id}`} value={subject.preferredPartOfDay} onChange={(event) => void updateSubject(subject, { preferredPartOfDay: event.target.value as ClassSubject['preferredPartOfDay'] })}><option value="ANY">Any</option><option value="MORNING">Morning</option><option value="AFTERNOON">Afternoon</option></select></td>
                    <td><select aria-label={`${subject.subjectName} room type`} disabled={!editable || saving === `subject-${subject.id}`} value={subject.requiredRoomType || ''} onChange={(event) => void updateSubject(subject, { requiredRoomType: event.target.value || null })}><option value="">Any room</option><option value="CLASSROOM">Classroom</option><option value="LAB">Lab</option><option value="COMPUTER_LAB">Computer lab</option><option value="MUSIC">Music</option><option value="SPORTS">Sports</option></select></td>
                    <td><label className="erp-switch"><input type="checkbox" disabled={!editable || saving === `subject-${subject.id}`} checked={subject.doublePeriod} onChange={(event) => void updateSubject(subject, { doublePeriod: event.target.checked })} /><span /></label></td>
                  </tr>)}</tbody>
                </table>
                {!subjects.length && <div className="erp-empty compact">Add subjects before scheduling lessons.</div>}
              </div>
            </div>
            <footer><span /><button className="erp-btn primary" onClick={() => setShowSubjects(false)}>Done</button></footer>
          </div>
        </div>
      )}

      {showRooms && (
        <div className="erp-dialog-backdrop" onMouseDown={() => setShowRooms(false)}>
          <div className="erp-dialog" role="dialog" aria-modal="true" aria-labelledby="rooms-title" onMouseDown={(event) => event.stopPropagation()}>
            <header><div><span className="erp-eyebrow">Shared resources</span><h2 id="rooms-title">Rooms</h2></div><button className="erp-icon-btn" aria-label="Close" onClick={() => setShowRooms(false)}><X size={18} /></button></header>
            <div className="erp-dialog-body">
              <div className="erp-room-list">{rooms.map((room) => <div key={room.id}><DoorOpen size={17} /><span><strong>{room.name}</strong><small>{room.roomType.toLowerCase()} - capacity {room.capacity}</small></span><span className={`erp-status ${room.active ? 'good' : 'neutral'}`}>{room.active ? 'active' : 'inactive'}</span></div>)}</div>
              {editable && <div className="erp-inline-form room-add"><input placeholder="Room name" value={newRoom.name} onChange={(event) => setNewRoom({ ...newRoom, name: event.target.value })} /><select value={newRoom.roomType} onChange={(event) => setNewRoom({ ...newRoom, roomType: event.target.value })}><option value="CLASSROOM">Classroom</option><option value="LAB">Lab</option><option value="COMPUTER_LAB">Computer lab</option><option value="MUSIC">Music</option><option value="SPORTS">Sports</option></select><input aria-label="Capacity" type="number" min="1" value={newRoom.capacity} onChange={(event) => setNewRoom({ ...newRoom, capacity: event.target.value })} /><button className="erp-btn primary" disabled={!newRoom.name || saving === 'room'} onClick={() => void addRoom()}><Plus size={16} /> Add room</button></div>}
            </div>
            <footer><span /><button className="erp-btn primary" onClick={() => setShowRooms(false)}>Done</button></footer>
          </div>
        </div>
      )}

      {showAvailability && (
        <div className="erp-dialog-backdrop" onMouseDown={() => setShowAvailability(false)}>
          <div className="erp-dialog wide" role="dialog" aria-modal="true" aria-labelledby="availability-title" onMouseDown={(event) => event.stopPropagation()}>
            <header>
              <div><span className="erp-eyebrow">Teacher planning</span><h2 id="availability-title">Availability - {staff.find((row) => String(row.id) === resourceId)?.name}</h2></div>
              <button className="erp-icon-btn" aria-label="Close" onClick={() => setShowAvailability(false)}><X size={18} /></button>
            </header>
            <div className="erp-dialog-body">
              <div className="erp-toolbar">
                <div className="erp-filter-group">
                  <select aria-label="Period pattern" value={availabilityScheduleId} onChange={(event) => setAvailabilityScheduleId(event.target.value)}>
                    {schedules.map((row) => <option key={row.id} value={row.id}>{row.name}</option>)}
                  </select>
                </div>
                <span className="erp-availability-key"><i /> Available <i className="blocked" /> Unavailable</span>
              </div>
              {(() => {
                const availabilitySchedule = schedules.find((row) => String(row.id) === availabilityScheduleId);
                const availabilityPeriods = availabilitySchedule?.periods.filter((period) => !period.isBreak) ?? [];
                return availabilityPeriods.length ? (
                  <div className="erp-availability-scroll">
                    <div className="erp-availability-grid" style={{ gridTemplateColumns: `112px repeat(${availabilityPeriods.length}, minmax(112px, 1fr))` }}>
                      <div className="erp-timetable-corner">Day</div>
                      {availabilityPeriods.map((period) => <div key={period.id} className="erp-period-head"><strong>{period.label}</strong><span>{timeLabel(period.start)}</span></div>)}
                      {days.map((day) => (
                        <div className="erp-timetable-row" key={day}>
                          <div className="erp-day-head">{day}</div>
                          {availabilityPeriods.map((period) => {
                            const row = availability.find((item) => item.day === day && item.periodId === period.id);
                            const isAvailable = row?.available !== false;
                            return (
                              <button
                                key={period.id}
                                className={`erp-availability-cell ${isAvailable ? '' : 'blocked'}`}
                                disabled={!editable || saving === `availability-${day}-${period.id}`}
                                onClick={() => void toggleAvailability(day, period.id)}
                              >
                                {isAvailable ? <Check size={16} /> : <X size={16} />}
                                <span>{isAvailable ? 'Available' : 'Unavailable'}</span>
                              </button>
                            );
                          })}
                        </div>
                      ))}
                    </div>
                  </div>
                ) : <div className="erp-empty compact">Add teaching periods to this pattern first.</div>;
              })()}
            </div>
            <footer><span /><button className="erp-btn primary" onClick={() => setShowAvailability(false)}>Done</button></footer>
          </div>
        </div>
      )}
    </ModuleShell>
  );
}

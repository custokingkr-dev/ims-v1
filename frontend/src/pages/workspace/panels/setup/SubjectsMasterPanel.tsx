import { useEffect, useState } from 'react';
import {
  type ClassSubjects,
  getClassSubjects, addSubject, deleteSubject,
} from '../../../../services/timetableApi';
import { ModuleShell } from '../../ui';
import { usePermissions } from '../../../../hooks/usePermissions';
import api from '../../../../services/api';

interface ClassOpt { id: string; name: string }
interface AcademicYearOpt { id: string; label: string; active: boolean }

function errMsg(err: unknown, fallback: string): string {
  return (err as { response?: { data?: { message?: string } } })?.response?.data?.message
    || (err instanceof Error ? err.message : fallback);
}

interface Props { yearId?: string; years?: AcademicYearOpt[]; embedded?: boolean; classId?: string }

export function SubjectsMasterPanel({ yearId: yearIdProp, years: yearsProp, embedded, classId: classIdProp }: Props = {}) {
  const { can } = usePermissions();
  const canManage = can('timetable:manage');

  const [classes, setClasses] = useState<ClassOpt[]>([]);
  const [internalYears, setInternalYears] = useState<AcademicYearOpt[]>([]);
  const [classId, setClassId] = useState('');
  const [internalYearId, setInternalYearId] = useState('');
  const [data, setData] = useState<ClassSubjects | null>(null);
  const [newSubjectName, setNewSubjectName] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const controlled = yearIdProp !== undefined;
  const yearId = yearIdProp ?? internalYearId;
  const years = yearsProp ?? internalYears;

  useEffect(() => {
    if (!canManage) return;
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
  }, [canManage, controlled]);

  useEffect(() => {
    if (!canManage || !classes.length) return;
    setClassId((prev) => prev || classIdProp || classes[0].id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canManage, classes]);

  // When opened for a specific class (e.g. the Manage-subjects modal on the timetable screen),
  // preselect that class instead of defaulting to the first one.
  useEffect(() => {
    if (classIdProp) setClassId(classIdProp);
  }, [classIdProp]);

  const load = async () => {
    if (!classId || !yearId) return;
    setLoading(true);
    setError('');
    try {
      const res = await getClassSubjects(classId, yearId);
      setData(res.data);
    } catch (err: unknown) {
      setError(errMsg(err, 'Could not load subjects.'));
      setData(null);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (canManage && classId && yearId) load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canManage, classId, yearId]);

  const handleAddSubject = async () => {
    if (!classId || !newSubjectName.trim()) return;
    try {
      setError('');
      await addSubject(classId, newSubjectName.trim());
      setNewSubjectName('');
      await load();
    } catch (err: unknown) {
      setError(errMsg(err, 'Could not add subject.'));
    }
  };

  const handleDeleteSubject = async (id: number) => {
    try {
      setError('');
      await deleteSubject(id);
      await load();
    } catch (err: unknown) {
      setError(errMsg(err, 'Could not delete subject.'));
    }
  };

  if (!canManage) {
    const noPermission = (
      <div className="ck-alert ck-alert-am"><span>!</span><div>You do not have permission to manage subjects.</div></div>
    );
    return embedded ? noPermission : (
      <ModuleShell title="Subjects" subtitle="Configure per-class subjects for the academic year">
        {noPermission}
      </ModuleShell>
    );
  }

  const selectedYear = years.find((y) => y.id === yearId) || null;

  const body = (
      <div className="ck-panel-stack">
        {error ? <div className="ck-alert ck-alert-re"><span>!</span><div>{error}</div></div> : null}

        <div className="ck-actions-inline">
          <select value={classId} onChange={(e) => setClassId(e.target.value)}>
            {classes.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
          {!controlled ? (
            <select value={internalYearId} onChange={(e) => setInternalYearId(e.target.value)}>
              {years.map((y) => <option key={y.id} value={y.id}>{y.label}{y.active ? ' (current)' : ''}</option>)}
            </select>
          ) : null}
        </div>

        <div className="ck-card">
          <div className="ck-card-h"><div className="ck-card-t">Subjects</div></div>
          <div style={{ padding: 16 }}>
            {loading ? (
              <div style={{ color: 'var(--ink3)' }}>Loading…</div>
            ) : !data ? (
              <div className="ck-import-zone"><div className="iz-title">Select a class and year</div></div>
            ) : (
              <>
                {!data.editable ? (
                  <div className="ck-alert ck-alert-am">
                    <span>!</span>
                    <div>
                      Locked — {selectedYear?.label ?? data.yearId} has ended. Subjects can only be edited for the current year.
                    </div>
                  </div>
                ) : null}

                {!data.subjects.length ? (
                  <div className="ck-import-zone"><div className="iz-title">No subjects added yet</div></div>
                ) : (
                  <table className="ck-table">
                    <thead><tr><th>Subject</th>{data.editable ? <th></th> : null}</tr></thead>
                    <tbody>
                      {data.subjects.map((s) => (
                        <tr key={s.id}>
                          <td>{s.subjectName}</td>
                          {data.editable ? (
                            <td>
                              <button className="ck-btn ck-btn-ghost" onClick={() => handleDeleteSubject(s.id)}>Delete</button>
                            </td>
                          ) : null}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}

                {data.editable ? (
                  <div className="ck-actions-inline" style={{ marginTop: 16 }}>
                    <input
                      placeholder="New subject name"
                      value={newSubjectName}
                      onChange={(e) => setNewSubjectName(e.target.value)}
                      style={{ flex: 1 }}
                    />
                    <button className="ck-btn ck-btn-g" disabled={!newSubjectName.trim()} onClick={handleAddSubject}>
                      + Add subject
                    </button>
                  </div>
                ) : null}
              </>
            )}
          </div>
        </div>
      </div>
  );

  return embedded ? body : (
    <ModuleShell title="Subjects" subtitle="Configure per-class subjects for the academic year">
      {body}
    </ModuleShell>
  );
}

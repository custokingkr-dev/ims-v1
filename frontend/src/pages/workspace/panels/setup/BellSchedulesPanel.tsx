import React, { useEffect, useState } from 'react';
import {
  type BellSchedule, type BellPeriod, type ClassScheduleRow,
  getBellSchedules, createSchedule, renameSchedule, deleteSchedule,
  addPeriod, updatePeriod, deletePeriod,
  getClassSchedules, setClassSchedule,
} from '../../../../services/timetableApi';
import { ModuleShell } from '../../ui';
import { usePermissions } from '../../../../hooks/usePermissions';

function errMsg(err: unknown, fallback: string): string {
  return (err as { response?: { data?: { message?: string } } })?.response?.data?.message
    || (err instanceof Error ? err.message : fallback);
}

export function BellSchedulesPanel() {
  const { can } = usePermissions();
  const canManage = can('timetable:manage');

  const [schedules, setSchedules] = useState<BellSchedule[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [classSchedules, setClassSchedules] = useState<ClassScheduleRow[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const [newScheduleName, setNewScheduleName] = useState('');
  const [renamingId, setRenamingId] = useState<number | null>(null);
  const [renameValue, setRenameValue] = useState('');

  const [newPeriod, setNewPeriod] = useState<{ label: string; start: string; end: string; isBreak: boolean }>({
    label: '', start: '', end: '', isBreak: false,
  });

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      const [schedulesRes, classSchedulesRes] = await Promise.all([
        getBellSchedules(),
        getClassSchedules(),
      ]);
      const nextSchedules = Array.isArray(schedulesRes.data) ? schedulesRes.data : [];
      setSchedules(nextSchedules);
      setClassSchedules(Array.isArray(classSchedulesRes.data) ? classSchedulesRes.data : []);
      setSelectedId((prev) => {
        if (prev && nextSchedules.some((s) => s.id === prev)) return prev;
        return nextSchedules[0]?.id ?? null;
      });
    } catch (err: unknown) {
      setError(errMsg(err, 'Could not load bell schedules.'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (canManage) load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canManage]);

  const selectedSchedule = schedules.find((s) => s.id === selectedId) || null;

  const handleCreateSchedule = async () => {
    if (!newScheduleName.trim()) return;
    try {
      setError('');
      await createSchedule(newScheduleName.trim());
      setNewScheduleName('');
      await load();
    } catch (err: unknown) {
      setError(errMsg(err, 'Could not create schedule.'));
    }
  };

  const handleRenameSchedule = async (id: number) => {
    if (!renameValue.trim()) { setRenamingId(null); return; }
    try {
      setError('');
      await renameSchedule(id, renameValue.trim());
      setRenamingId(null);
      setRenameValue('');
      await load();
    } catch (err: unknown) {
      setError(errMsg(err, 'Could not rename schedule.'));
    }
  };

  const handleDeleteSchedule = async (id: number) => {
    try {
      setError('');
      await deleteSchedule(id);
      await load();
    } catch (err: unknown) {
      setError(errMsg(err, 'Could not delete schedule.'));
    }
  };

  const handleAddPeriod = async () => {
    if (!selectedSchedule || !newPeriod.label.trim() || !newPeriod.start || !newPeriod.end) return;
    try {
      setError('');
      const sortOrder = selectedSchedule.periods.length
        ? Math.max(...selectedSchedule.periods.map((p) => p.sortOrder)) + 1
        : 0;
      await addPeriod(selectedSchedule.id, {
        label: newPeriod.label.trim(),
        start: newPeriod.start,
        end: newPeriod.end,
        isBreak: newPeriod.isBreak,
        sortOrder,
      });
      setNewPeriod({ label: '', start: '', end: '', isBreak: false });
      await load();
    } catch (err: unknown) {
      setError(errMsg(err, 'Could not add period.'));
    }
  };

  const handleUpdatePeriod = async (period: BellPeriod) => {
    if (!selectedSchedule) return;
    try {
      setError('');
      await updatePeriod(selectedSchedule.id, period.id, {
        label: period.label,
        start: period.start,
        end: period.end,
        isBreak: period.isBreak,
        sortOrder: period.sortOrder,
      });
      await load();
    } catch (err: unknown) {
      setError(errMsg(err, 'Could not save period.'));
    }
  };

  const handleDeletePeriod = async (periodId: number) => {
    if (!selectedSchedule) return;
    try {
      setError('');
      await deletePeriod(selectedSchedule.id, periodId);
      await load();
    } catch (err: unknown) {
      setError(errMsg(err, 'Could not delete period.'));
    }
  };

  const handleMovePeriod = async (period: BellPeriod, direction: 'up' | 'down') => {
    if (!selectedSchedule) return;
    const sorted = [...selectedSchedule.periods].sort((a, b) => a.sortOrder - b.sortOrder);
    const idx = sorted.findIndex((p) => p.id === period.id);
    const swapIdx = direction === 'up' ? idx - 1 : idx + 1;
    if (idx < 0 || swapIdx < 0 || swapIdx >= sorted.length) return;
    const other = sorted[swapIdx];
    try {
      setError('');
      await Promise.all([
        updatePeriod(selectedSchedule.id, period.id, {
          label: period.label, start: period.start, end: period.end, isBreak: period.isBreak, sortOrder: other.sortOrder,
        }),
        updatePeriod(selectedSchedule.id, other.id, {
          label: other.label, start: other.start, end: other.end, isBreak: other.isBreak, sortOrder: period.sortOrder,
        }),
      ]);
      await load();
    } catch (err: unknown) {
      setError(errMsg(err, 'Could not reorder periods.'));
    }
  };

  const handleClassScheduleChange = async (classId: string, scheduleId: string) => {
    if (!scheduleId) return;
    try {
      setError('');
      await setClassSchedule(classId, Number(scheduleId));
      await load();
    } catch (err: unknown) {
      setError(errMsg(err, 'Could not assign schedule to class.'));
    }
  };

  if (!canManage) {
    return (
      <ModuleShell title="Bell schedules" subtitle="Configure period timings and class assignments">
        <div className="ck-alert ck-alert-am"><span>!</span><div>You do not have permission to manage bell schedules.</div></div>
      </ModuleShell>
    );
  }

  const sortedPeriods = selectedSchedule
    ? [...selectedSchedule.periods].sort((a, b) => a.sortOrder - b.sortOrder)
    : [];

  return (
    <ModuleShell title="Bell schedules" subtitle="Configure period timings and assign schedules to classes">
      <div className="ck-panel-stack">
        {error ? <div className="ck-alert ck-alert-re"><span>!</span><div>{error}</div></div> : null}

        <div className="ck-form-grid ck-fg-2" style={{ gap: 16 }}>
          {/* Left: schedule list */}
          <div className="ck-card">
            <div className="ck-card-h"><div className="ck-card-t">Schedules</div></div>
            <div style={{ padding: 16 }}>
              {loading ? (
                <div style={{ color: 'var(--ink3)' }}>Loading…</div>
              ) : !schedules.length ? (
                <div className="ck-import-zone"><div className="iz-title">No bell schedules yet</div></div>
              ) : (
                <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'grid', gap: 8 }}>
                  {schedules.map((s) => (
                    <li key={s.id} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      {renamingId === s.id ? (
                        <>
                          <input
                            value={renameValue}
                            onChange={(e) => setRenameValue(e.target.value)}
                            style={{ flex: 1 }}
                            autoFocus
                          />
                          <button className="ck-btn ck-btn-g" onClick={() => handleRenameSchedule(s.id)}>Save</button>
                          <button className="ck-btn ck-btn-ghost" onClick={() => { setRenamingId(null); setRenameValue(''); }}>Cancel</button>
                        </>
                      ) : (
                        <>
                          <button
                            className={`ck-btn ${selectedId === s.id ? 'ck-btn-g' : 'ck-btn-ghost'}`}
                            style={{ flex: 1, textAlign: 'left' }}
                            onClick={() => setSelectedId(s.id)}
                          >
                            {s.name}
                          </button>
                          <button className="ck-btn ck-btn-ghost" onClick={() => { setRenamingId(s.id); setRenameValue(s.name); }}>Rename</button>
                          <button className="ck-btn ck-btn-ghost" onClick={() => handleDeleteSchedule(s.id)}>Delete</button>
                        </>
                      )}
                    </li>
                  ))}
                </ul>
              )}
              <div className="ck-actions-inline" style={{ marginTop: 16 }}>
                <input
                  placeholder="New schedule name"
                  value={newScheduleName}
                  onChange={(e) => setNewScheduleName(e.target.value)}
                  style={{ flex: 1 }}
                />
                <button className="ck-btn ck-btn-g" disabled={!newScheduleName.trim()} onClick={handleCreateSchedule}>
                  + New schedule
                </button>
              </div>
            </div>
          </div>

          {/* Right: periods for selected schedule */}
          <div className="ck-card">
            <div className="ck-card-h"><div className="ck-card-t">Periods {selectedSchedule ? `— ${selectedSchedule.name}` : ''}</div></div>
            {!selectedSchedule ? (
              <div style={{ padding: 16 }} className="ck-import-zone"><div className="iz-title">Select a schedule to edit its periods</div></div>
            ) : (
              <div style={{ padding: 16 }}>
                <table className="ck-table">
                  <thead>
                    <tr>
                      <th>Label</th>
                      <th>Start</th>
                      <th>End</th>
                      <th>Break</th>
                      <th>Order</th>
                      <th></th>
                    </tr>
                  </thead>
                  <tbody>
                    {sortedPeriods.map((period, idx) => (
                      <tr key={period.id}>
                        <td>
                          <input
                            value={period.label}
                            onChange={(e) => {
                              const label = e.target.value;
                              setSchedules((prev) => prev.map((s) => s.id === selectedSchedule.id
                                ? { ...s, periods: s.periods.map((p) => p.id === period.id ? { ...p, label } : p) }
                                : s));
                            }}
                          />
                        </td>
                        <td>
                          <input
                            type="time"
                            value={period.start}
                            onChange={(e) => {
                              const start = e.target.value;
                              setSchedules((prev) => prev.map((s) => s.id === selectedSchedule.id
                                ? { ...s, periods: s.periods.map((p) => p.id === period.id ? { ...p, start } : p) }
                                : s));
                            }}
                          />
                        </td>
                        <td>
                          <input
                            type="time"
                            value={period.end}
                            onChange={(e) => {
                              const end = e.target.value;
                              setSchedules((prev) => prev.map((s) => s.id === selectedSchedule.id
                                ? { ...s, periods: s.periods.map((p) => p.id === period.id ? { ...p, end } : p) }
                                : s));
                            }}
                          />
                        </td>
                        <td>
                          <input
                            type="checkbox"
                            checked={period.isBreak}
                            onChange={(e) => {
                              const isBreak = e.target.checked;
                              setSchedules((prev) => prev.map((s) => s.id === selectedSchedule.id
                                ? { ...s, periods: s.periods.map((p) => p.id === period.id ? { ...p, isBreak } : p) }
                                : s));
                            }}
                          />
                        </td>
                        <td>
                          <button className="ck-btn ck-btn-ghost" disabled={idx === 0} onClick={() => handleMovePeriod(period, 'up')}>↑</button>
                          <button className="ck-btn ck-btn-ghost" disabled={idx === sortedPeriods.length - 1} onClick={() => handleMovePeriod(period, 'down')}>↓</button>
                        </td>
                        <td>
                          <button className="ck-btn ck-btn-g" onClick={() => handleUpdatePeriod(period)}>Save</button>
                          <button className="ck-btn ck-btn-ghost" onClick={() => handleDeletePeriod(period.id)}>Delete</button>
                        </td>
                      </tr>
                    ))}
                    <tr>
                      <td><input placeholder="Label" value={newPeriod.label} onChange={(e) => setNewPeriod({ ...newPeriod, label: e.target.value })} /></td>
                      <td><input type="time" value={newPeriod.start} onChange={(e) => setNewPeriod({ ...newPeriod, start: e.target.value })} /></td>
                      <td><input type="time" value={newPeriod.end} onChange={(e) => setNewPeriod({ ...newPeriod, end: e.target.value })} /></td>
                      <td><input type="checkbox" checked={newPeriod.isBreak} onChange={(e) => setNewPeriod({ ...newPeriod, isBreak: e.target.checked })} /></td>
                      <td></td>
                      <td>
                        <button
                          className="ck-btn ck-btn-g"
                          disabled={!newPeriod.label.trim() || !newPeriod.start || !newPeriod.end}
                          onClick={handleAddPeriod}
                        >
                          + Add row
                        </button>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>

        {/* Class schedules mapping */}
        <div className="ck-card">
          <div className="ck-card-h"><div className="ck-card-t">Class schedules</div></div>
          <div style={{ padding: 16 }}>
            {!classSchedules.length ? (
              <div className="ck-import-zone"><div className="iz-title">No classes found</div></div>
            ) : (
              <table className="ck-table">
                <thead><tr><th>Class</th><th>Bell schedule</th></tr></thead>
                <tbody>
                  {classSchedules.map((row) => (
                    <tr key={row.classId}>
                      <td>
                        {row.className}
                        {row.scheduleId == null ? <div className="ts" style={{ color: 'var(--am)' }}>Unmapped — no schedule assigned</div> : null}
                      </td>
                      <td>
                        <select
                          value={row.scheduleId ?? ''}
                          onChange={(e) => handleClassScheduleChange(row.classId, e.target.value)}
                        >
                          <option value="">Select schedule</option>
                          {schedules.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
                        </select>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </div>
      </div>
    </ModuleShell>
  );
}

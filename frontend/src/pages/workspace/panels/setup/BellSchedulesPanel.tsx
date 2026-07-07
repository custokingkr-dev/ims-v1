import React, { useEffect, useState } from 'react';
import {
  type BellSchedule, type BellPeriod, type ClassScheduleRow,
  getBellSchedules, createSchedule, renameSchedule, deleteSchedule,
  addPeriod, updatePeriod, deletePeriod, swapPeriods,
  getClassSchedules, setClassSchedule,
} from '../../../../services/timetableApi';
import { ModuleShell } from '../../ui';
import { usePermissions } from '../../../../hooks/usePermissions';

function errMsg(err: unknown, fallback: string): string {
  return (err as { response?: { data?: { message?: string } } })?.response?.data?.message
    || (err instanceof Error ? err.message : fallback);
}

interface Props {
  embedded?: boolean;
  initialClassId?: string;
}

export function BellSchedulesPanel({ embedded, initialClassId }: Props = {}) {
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
  const [showAddPeriod, setShowAddPeriod] = useState(false);
  const [editingPeriodId, setEditingPeriodId] = useState<number | null>(null);
  const [assignClassId, setAssignClassId] = useState('');

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

  // Best-effort cross-tab handoff: pre-select the class that triggered the
  // "no bell schedule" prompt on the Grid tab, if it's still unassigned.
  useEffect(() => {
    if (!initialClassId || !classSchedules.length) return;
    const target = classSchedules.find((c) => c.classId === initialClassId);
    if (target && target.scheduleId == null) {
      setAssignClassId(initialClassId);
    }
  }, [initialClassId, classSchedules]);

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
      await swapPeriods(selectedSchedule.id, period.id, other.id);
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
    const noPermission = (
      <div className="ck-alert ck-alert-am"><span>!</span><div>You do not have permission to manage bell schedules.</div></div>
    );
    return embedded ? noPermission : (
      <ModuleShell title="Bell schedules" subtitle="Configure period timings and class assignments">
        {noPermission}
      </ModuleShell>
    );
  }

  const sortedPeriods = selectedSchedule
    ? [...selectedSchedule.periods].sort((a, b) => a.sortOrder - b.sortOrder)
    : [];

  const assigned = selectedSchedule
    ? classSchedules.filter((c) => c.scheduleId === selectedSchedule.id)
    : [];
  const unassignedToThis = selectedSchedule
    ? classSchedules.filter((c) => c.scheduleId !== selectedSchedule.id)
    : [];
  const unmapped = classSchedules.filter((c) => c.scheduleId == null);

  const updatePeriodField = (periodId: number, patch: Partial<BellPeriod>) => {
    if (!selectedSchedule) return;
    setSchedules((prev) => prev.map((s) => s.id === selectedSchedule.id
      ? { ...s, periods: s.periods.map((p) => p.id === periodId ? { ...p, ...patch } : p) }
      : s));
  };

  const handleAssignClass = async () => {
    if (!selectedSchedule || !assignClassId) return;
    await handleClassScheduleChange(assignClassId, String(selectedSchedule.id));
    setAssignClassId('');
  };

  const body = (
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

          {/* Right: timeline for selected schedule */}
          <div className="ck-card">
            <div className="ck-card-h"><div className="ck-card-t">Periods {selectedSchedule ? `— ${selectedSchedule.name}` : ''}</div></div>
            {!selectedSchedule ? (
              <div style={{ padding: 16 }} className="ck-import-zone"><div className="iz-title">Select a schedule to edit its periods</div></div>
            ) : (
              <div style={{ padding: 16 }}>
                {/* Applies-to chip row */}
                <div style={{ marginBottom: 16 }}>
                  <div className="ts" style={{ color: 'var(--ink3)', marginBottom: 6 }}>Applies to</div>
                  <div className="ck-badge-row">
                    {assigned.length ? assigned.map((c) => (
                      <span
                        key={c.classId}
                        className="ck-btn ck-btn-ghost"
                        style={{ borderRadius: 999, cursor: 'default' }}
                      >
                        {c.className}
                      </span>
                    )) : (
                      <span className="ts" style={{ color: 'var(--ink3)' }}>No classes assigned yet</span>
                    )}
                    {unassignedToThis.length ? (
                      <>
                        <select
                          value={assignClassId}
                          onChange={(e) => setAssignClassId(e.target.value)}
                          style={{ borderRadius: 999 }}
                        >
                          <option value="">+ Assign class…</option>
                          {unassignedToThis.map((c) => (
                            <option key={c.classId} value={c.classId}>{c.className}</option>
                          ))}
                        </select>
                        <button className="ck-btn ck-btn-g" disabled={!assignClassId} onClick={handleAssignClass}>
                          + Assign class
                        </button>
                      </>
                    ) : null}
                  </div>
                  {unmapped.length ? (
                    <div className="ck-alert ck-alert-am" style={{ marginTop: 8 }}>
                      <span>!</span>
                      <div>{unmapped.length} class{unmapped.length === 1 ? '' : 'es'} have no bell schedule: {unmapped.map((c) => c.className).join(', ')}</div>
                    </div>
                  ) : null}
                </div>

                {/* Day timeline */}
                {!sortedPeriods.length ? (
                  <div className="ck-import-zone"><div className="iz-title">No periods yet — add the first one below</div></div>
                ) : (
                  <div style={{ display: 'grid', gap: 8 }}>
                    {sortedPeriods.map((period, idx) => (
                      <div
                        key={period.id}
                        className="ck-card"
                        style={{
                          display: 'flex',
                          gap: 12,
                          padding: 12,
                          alignItems: editingPeriodId === period.id ? 'flex-start' : 'center',
                          background: period.isBreak ? 'var(--g1)' : undefined,
                          border: '1px solid var(--border)',
                        }}
                      >
                        <div style={{ minWidth: 108, color: 'var(--ink3)' }} className="ts">
                          {period.start}–{period.end}
                        </div>

                        {editingPeriodId === period.id ? (
                          <div style={{ flex: 1, display: 'grid', gap: 8 }}>
                            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                              <input
                                value={period.label}
                                onChange={(e) => updatePeriodField(period.id, { label: e.target.value })}
                                style={{ flex: 1, minWidth: 140 }}
                              />
                              <input
                                type="time"
                                value={period.start}
                                onChange={(e) => updatePeriodField(period.id, { start: e.target.value })}
                              />
                              <input
                                type="time"
                                value={period.end}
                                onChange={(e) => updatePeriodField(period.id, { end: e.target.value })}
                              />
                              <label style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                                <input
                                  type="checkbox"
                                  checked={period.isBreak}
                                  onChange={(e) => updatePeriodField(period.id, { isBreak: e.target.checked })}
                                />
                                Break
                              </label>
                            </div>
                            <div className="ck-actions-inline">
                              <button
                                className="ck-btn ck-btn-g"
                                onClick={async () => { await handleUpdatePeriod(period); setEditingPeriodId(null); }}
                              >
                                Save
                              </button>
                              <button
                                className="ck-btn ck-btn-ghost"
                                onClick={async () => { setEditingPeriodId(null); await load(); }}
                              >
                                Cancel
                              </button>
                            </div>
                          </div>
                        ) : (
                          <>
                            <div style={{ flex: 1 }}>
                              <div>{period.label}</div>
                              {period.isBreak ? (
                                <div className="ts" style={{ color: 'var(--am)' }}>☕ Break</div>
                              ) : null}
                            </div>
                            <div className="ck-actions-inline">
                              <button className="ck-btn ck-btn-ghost" onClick={() => setEditingPeriodId(period.id)}>✎ Edit</button>
                              <button className="ck-btn ck-btn-ghost" disabled={idx === 0} onClick={() => handleMovePeriod(period, 'up')}>↑</button>
                              <button className="ck-btn ck-btn-ghost" disabled={idx === sortedPeriods.length - 1} onClick={() => handleMovePeriod(period, 'down')}>↓</button>
                              <button className="ck-btn ck-btn-ghost" onClick={() => handleDeletePeriod(period.id)}>Delete</button>
                            </div>
                          </>
                        )}
                      </div>
                    ))}
                  </div>
                )}

                {/* Add period */}
                <div style={{ marginTop: 16 }}>
                  {showAddPeriod ? (
                    <div className="ck-card" style={{ padding: 12, border: '1px solid var(--border)' }}>
                      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                        <input placeholder="Label" value={newPeriod.label} onChange={(e) => setNewPeriod({ ...newPeriod, label: e.target.value })} style={{ flex: 1, minWidth: 140 }} />
                        <input type="time" value={newPeriod.start} onChange={(e) => setNewPeriod({ ...newPeriod, start: e.target.value })} />
                        <input type="time" value={newPeriod.end} onChange={(e) => setNewPeriod({ ...newPeriod, end: e.target.value })} />
                        <label style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                          <input type="checkbox" checked={newPeriod.isBreak} onChange={(e) => setNewPeriod({ ...newPeriod, isBreak: e.target.checked })} />
                          Break
                        </label>
                      </div>
                      <div className="ck-actions-inline" style={{ marginTop: 8 }}>
                        <button
                          className="ck-btn ck-btn-g"
                          disabled={!newPeriod.label.trim() || !newPeriod.start || !newPeriod.end}
                          onClick={async () => { await handleAddPeriod(); setShowAddPeriod(false); }}
                        >
                          Save period
                        </button>
                        <button
                          className="ck-btn ck-btn-ghost"
                          onClick={() => { setShowAddPeriod(false); setNewPeriod({ label: '', start: '', end: '', isBreak: false }); }}
                        >
                          Cancel
                        </button>
                      </div>
                    </div>
                  ) : (
                    <button className="ck-btn ck-btn-ghost" onClick={() => setShowAddPeriod(true)}>+ Add period</button>
                  )}
                </div>
              </div>
            )}
          </div>
        </div>
      </div>
  );

  return embedded ? body : (
    <ModuleShell title="Bell schedules" subtitle="Configure period timings and assign schedules to classes">
      {body}
    </ModuleShell>
  );
}

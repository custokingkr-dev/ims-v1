import React, { useEffect, useRef, useState } from 'react';
import api from '../../../services/api';
import { useAuth } from '../../../contexts/AuthContext';
import { usePermissions } from '../../../hooks/usePermissions';
import { ModuleShell, Field } from '../ui';
import { formatMoney } from '../utils';

interface Props {
  onRefresh: () => void;
}

export function FeeStructurePanel({ onRefresh }: Props) {
  const { user } = useAuth();
  const { can } = usePermissions();
  const schoolScopedParams = !can('platform:admin') && user?.branchId ? { schoolId: user.branchId } : undefined;

  const [saving, setSaving] = useState('');
  const [feeClasses, setFeeClasses] = useState<any[]>([]);
  const [feeStructureData, setFeeStructureData] = useState<any>({ academicYear: '2025–26', academicYearId: 'ay_2024_25', bands: [] });
  const [feeStructureLoading, setFeeStructureLoading] = useState(false);
  const [feeStructureError, setFeeStructureError] = useState('');
  const [feeStructureToast, setFeeStructureToast] = useState('');
  const [showFeeItemForm, setShowFeeItemForm] = useState(false);
  const [showBandForm, setShowBandForm] = useState(false);
  const [feeItemForm, setFeeItemForm] = useState({ bandId: '', itemName: '', frequency: 'Annual', amount: '' });
  const [bandForm, setBandForm] = useState<any>({ name: '', classFrom: '1', classTo: '5', discount: '0', schedules: ['Annual'] });
  const [editingBandId, setEditingBandId] = useState('');
  const [confirmDeleteBandId, setConfirmDeleteBandId] = useState('');
  const [expandedBandIds, setExpandedBandIds] = useState<string[]>([]);
  const [editingFeeItem, setEditingFeeItem] = useState<any | null>(null);
  const [confirmRemoveFeeItemId, setConfirmRemoveFeeItemId] = useState('');

  // Assign fee plan state
  const [assignSelection, setAssignSelection] = useState<any>({ classId: '', sectionId: '', studentId: '' });
  const [assignOptions, setAssignOptions] = useState<any>({ sections: [], students: [] });
  const [feeAssignForm, setFeeAssignForm] = useState({ studentId: '', bandId: '', paymentSchedule: '', bandDiscount: '0', manualDiscount: '0', surcharge: '2' });
  const [feeAssignHint, setFeeAssignHint] = useState('');
  const [feeAssignError, setFeeAssignError] = useState('');

  const discountTimers = useRef<Record<string, number>>({});
  const feeToastTimerRef = useRef<number | null>(null);

  const showFeeToast = (message: string) => {
    setFeeStructureToast(message);
    if (feeToastTimerRef.current) window.clearTimeout(feeToastTimerRef.current);
    feeToastTimerRef.current = window.setTimeout(() => setFeeStructureToast(''), 4000);
  };

  const loadFeeClasses = async () => {
    try {
      const res = await api.get('/classes', { params: schoolScopedParams });
      setFeeClasses(res.data || []);
    } catch (err: unknown) {
      setFeeClasses([]);
      setFeeStructureError((err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Could not load classes.'));
    }
  };

  const loadFeeStructure = async () => {
    try {
      setFeeStructureLoading(true);
      setFeeStructureError('');
      const res = await api.get('/fee-structure', { params: { academicYearId: 'ay_2024_25' } });
      setFeeStructureData(res.data || { academicYear: '2025–26', academicYearId: 'ay_2024_25', bands: [] });
      setExpandedBandIds([]);
      const firstBandId = res.data?.bands?.[0]?.id || '';
      setFeeItemForm((prev) => ({ ...prev, bandId: prev.bandId || firstBandId }));
    } catch (err: unknown) {
      setFeeStructureError((err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Could not load fee structure.'));
    } finally {
      setFeeStructureLoading(false);
    }
  };

  useEffect(() => {
    loadFeeClasses();
    loadFeeStructure();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const patchFeeBand = async (bandId: string, payload: any) => {
    try {
      await api.patch(`/fee-structure/band/${encodeURIComponent(bandId)}`, payload);
      await loadFeeStructure();
      await onRefresh();
    } catch (err: unknown) {
      setFeeStructureError((err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Could not update fee band.'));
    }
  };

  const handleDiscountChange = (bandId: string, value: string) => {
    const nextBands = (feeStructureData.bands || []).map((band: any) => band.id === bandId ? { ...band, discount: value } : band);
    setFeeStructureData((prev: any) => ({ ...prev, bands: nextBands }));
    const existing = discountTimers.current[bandId];
    if (existing) window.clearTimeout(existing);
    discountTimers.current[bandId] = window.setTimeout(() => {
      patchFeeBand(bandId, { discount: Number(value || 0) });
    }, 400);
  };

  const toggleBandSchedule = async (band: any, schedule: string) => {
    const active = Array.isArray(band.activeSchedules) ? band.activeSchedules : [];
    const next = active.includes(schedule) ? active.filter((item: string) => item !== schedule) : [...active, schedule];
    const nextBands = (feeStructureData.bands || []).map((row: any) => row.id === band.id ? { ...row, activeSchedules: next } : row);
    setFeeStructureData((prev: any) => ({ ...prev, bands: nextBands }));
    await patchFeeBand(band.id, { schedules: next });
  };

  const toggleBandFormSchedule = (schedule: string, target: 'create' | 'edit', bandId?: string) => {
    if (target === 'create') {
      setBandForm((prev: any) => ({ ...prev, schedules: prev.schedules.includes(schedule) ? prev.schedules.filter((item: string) => item !== schedule) : [...prev.schedules, schedule] }));
      return;
    }
    setFeeStructureData((prev: any) => ({
      ...prev,
      bands: prev.bands.map((band: any) => band.id === bandId ? {
        ...band,
        editSchedules: (band.editSchedules || band.activeSchedules || []).includes(schedule)
          ? (band.editSchedules || band.activeSchedules || []).filter((item: string) => item !== schedule)
          : [...(band.editSchedules || band.activeSchedules || []), schedule],
      } : band),
    }));
  };

  const toggleBandAccordion = (bandId: string) => {
    setExpandedBandIds((prev) => prev.includes(bandId) ? prev.filter((id) => id !== bandId) : [...prev, bandId]);
  };

  const exportFeeStructurePdf = async () => {
    try {
      setFeeStructureError('');
      const res = await api.get('/fee-structure/export', { params: { academicYearId: feeStructureData.academicYearId || 'ay_2024_25', format: 'pdf' }, responseType: 'blob' });
      const url = URL.createObjectURL(new Blob([res.data], { type: 'application/pdf' }));
      const link = document.createElement('a');
      link.href = url;
      link.download = 'fee-structure.pdf';
      link.click();
      URL.revokeObjectURL(url);
    } catch (err: unknown) {
      setFeeStructureError((err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Could not export fee structure PDF.'));
    }
  };

  const addFeeStructureItem = async () => {
    if (!feeItemForm.bandId || !feeItemForm.itemName.trim() || feeItemForm.amount === '') {
      setFeeStructureError('Item name and amount are required.');
      return;
    }
    try {
      setSaving('fee-structure-add');
      setFeeStructureError('');
      await api.post('/fee-structure/item', { bandId: feeItemForm.bandId, itemName: feeItemForm.itemName, frequency: feeItemForm.frequency, amount: Math.round(Number(feeItemForm.amount) * 100) });
      const bandName = feeStructureData.bands.find((band: any) => band.id === feeItemForm.bandId)?.name || 'band';
      showFeeToast(`Item added to ${bandName}.`);
      setShowFeeItemForm(false);
      setFeeItemForm((prev) => ({ ...prev, itemName: '', amount: '' }));
      await loadFeeStructure();
      setExpandedBandIds([feeItemForm.bandId]);
      await onRefresh();
    } catch (err: unknown) {
      setFeeStructureError((err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Could not add item.'));
    } finally {
      setSaving('');
    }
  };

  const saveFeeStructureItem = async () => {
    if (!editingFeeItem?.id) return;
    try {
      setSaving('fee-structure-edit');
      await api.put(`/fee-structure/item/${encodeURIComponent(editingFeeItem.id)}`, {
        itemName: editingFeeItem.name,
        frequency: editingFeeItem.frequency,
        amount: Math.round(Number(editingFeeItem.amount || 0) * 100),
      });
      showFeeToast(`Updated ${editingFeeItem.name}.`);
      setEditingFeeItem(null);
      await loadFeeStructure();
      await onRefresh();
    } catch (err: unknown) {
      setFeeStructureError((err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Could not update item.'));
    } finally {
      setSaving('');
    }
  };

  const removeFeeStructureItem = async (itemId: string) => {
    try {
      setSaving(`fee-structure-remove-${itemId}`);
      await api.delete(`/fee-structure/item/${encodeURIComponent(itemId)}`);
      showFeeToast('Item removed.');
      setConfirmRemoveFeeItemId('');
      await loadFeeStructure();
      await onRefresh();
    } catch (err: unknown) {
      setFeeStructureError((err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Could not remove item.'));
    } finally {
      setSaving('');
    }
  };

  const addFeeBand = async () => {
    if (!bandForm.name.trim()) { setFeeStructureError('Band name is required.'); return; }
    if (Number(bandForm.classTo) < Number(bandForm.classFrom)) { setFeeStructureError('Class to must be greater than or equal to class from.'); return; }
    if (!(bandForm.schedules || []).length) { setFeeStructureError('Select at least one payment schedule.'); return; }
    try {
      setSaving('fee-band-add');
      setFeeStructureError('');
      await api.post('/fee-structure/band', { name: bandForm.name, classFrom: Number(bandForm.classFrom), classTo: Number(bandForm.classTo), discount: Number(bandForm.discount || 0), schedules: bandForm.schedules });
      showFeeToast(`Band '${bandForm.name}' created.`);
      setShowBandForm(false);
      setBandForm({ name: '', classFrom: '1', classTo: '5', discount: '0', schedules: ['Annual'] });
      await loadFeeStructure();
    } catch (err: unknown) {
      setFeeStructureError((err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Could not create band.'));
    } finally {
      setSaving('');
    }
  };

  const saveFeeBandEdit = async (band: any) => {
    const schedules = band.editSchedules || band.activeSchedules || [];
    const nextName = String(band.editName ?? band.name ?? '').trim();
    const nextClassFrom = Number(band.editClassFrom ?? band.classFrom ?? 0);
    const nextClassTo = Number(band.editClassTo ?? band.classTo ?? 0);
    const nextDiscount = Number(band.editDiscount ?? band.discount ?? 0);
    if (!nextName) { setFeeStructureError('Band name is required.'); return; }
    if (nextClassTo < nextClassFrom) { setFeeStructureError('Class to must be >= class from.'); return; }
    if (!schedules.length) { setFeeStructureError('Select at least one payment schedule.'); return; }
    try {
      setSaving(`fee-band-edit-${band.id}`);
      setFeeStructureError('');
      await api.put(`/fee-structure/band/${encodeURIComponent(band.id)}`, { name: nextName, classFrom: nextClassFrom, classTo: nextClassTo, discount: nextDiscount, schedules });
      showFeeToast(`Updated ${nextName}.`);
      setEditingBandId('');
      await loadFeeStructure();
    } catch (err: unknown) {
      setFeeStructureError((err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Could not update band.'));
    } finally {
      setSaving('');
    }
  };

  const deleteFeeBand = async (bandId: string, bandName: string) => {
    try {
      setSaving(`fee-band-remove-${bandId}`);
      await api.delete(`/fee-structure/band/${encodeURIComponent(bandId)}`);
      showFeeToast('Band deleted.');
      setConfirmDeleteBandId('');
      await loadFeeStructure();
    } catch (err: unknown) {
      setFeeStructureError((err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : `Could not delete band ${bandName}.`));
    } finally {
      setSaving('');
    }
  };

  const handleAssignClassChange = async (classId: string) => {
    setAssignSelection({ classId, sectionId: '', studentId: '' });
    setAssignOptions({ sections: [], students: [] });
    setFeeAssignHint('');
    setFeeAssignError('');
    setFeeAssignForm({ studentId: '', bandId: '', paymentSchedule: '', bandDiscount: '0', manualDiscount: feeAssignForm.manualDiscount, surcharge: feeAssignForm.surcharge });
    if (!classId) return;
    try {
      const res = await api.get(`/classes/${encodeURIComponent(classId)}/sections`, { params: schoolScopedParams });
      setAssignOptions((prev: any) => ({ ...prev, sections: res.data || [], students: [] }));
    } catch (err: unknown) {
      setAssignOptions({ sections: [], students: [] });
      setFeeAssignError((err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Could not load sections.'));
    }
  };

  const handleAssignSectionChange = async (sectionId: string) => {
    setAssignSelection((prev: any) => ({ ...prev, sectionId, studentId: '' }));
    setAssignOptions((prev: any) => ({ ...prev, students: [] }));
    setFeeAssignHint('');
    setFeeAssignError('');
    setFeeAssignForm((prev) => ({ ...prev, studentId: '', bandId: '', paymentSchedule: '', bandDiscount: '0' }));
    if (!sectionId) return;
    try {
      const res = await api.get(`/classes/${encodeURIComponent(assignSelection.classId)}/sections/${encodeURIComponent(sectionId)}/students`, { params: schoolScopedParams });
      setAssignOptions((prev: any) => ({ ...prev, students: res.data || [] }));
    } catch (err: unknown) {
      setAssignOptions((prev: any) => ({ ...prev, students: [] }));
      setFeeAssignError((err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Could not load students.'));
    }
  };

  const handleAssignStudentChange = async (studentId: string) => {
    setAssignSelection((prev: any) => ({ ...prev, studentId }));
    setFeeAssignError('');
    setFeeAssignForm((prev) => ({ ...prev, studentId }));
    if (!studentId || !assignSelection.classId) { setFeeAssignHint(''); return; }
    try {
      const res = await api.get('/fee-structure/match', { params: { classId: assignSelection.classId } });
      const band = res.data || {};
      const schedules = Array.isArray(band.activeSchedules) ? band.activeSchedules : [];
      setFeeAssignForm((prev) => ({
        ...prev,
        studentId,
        bandId: band.id || '',
        paymentSchedule: schedules[0] || '',
        bandDiscount: String(band.discount ?? 0),
        surcharge: prev.paymentSchedule === 'Annual' ? '0' : prev.surcharge,
      }));
      setFeeAssignHint('Auto-matched fee plan based on class. You can override below.');
    } catch (err: unknown) {
      setFeeAssignError((err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Could not auto-match fee plan.'));
    }
  };

  const handleFeePlanChange = (bandId: string) => {
    const band = (feeStructureData.bands || []).find((row: any) => row.id === bandId);
    const schedules = Array.isArray(band?.activeSchedules) ? band.activeSchedules : [];
    setFeeAssignForm((prev) => ({ ...prev, bandId, paymentSchedule: schedules.includes(prev.paymentSchedule) ? prev.paymentSchedule : (schedules[0] || ''), bandDiscount: String(band?.discount ?? 0) }));
  };

  const submitFeeAssignment = async () => {
    try {
      setSaving('assign-fee');
      setFeeAssignError('');
      const selectedBand = (feeStructureData.bands || []).find((band: any) => band.id === feeAssignForm.bandId);
      const total = Number(selectedBand?.annualTotal || 0);
      const bandDiscountPct = Number(feeAssignForm.bandDiscount || 0);
      const manualDiscountPct = Number(feeAssignForm.manualDiscount || 0);
      const surchargePct = feeAssignForm.paymentSchedule === 'Annual' ? 0 : Number(feeAssignForm.surcharge || 0);
      const netPayable = Math.round(total - Math.round(total * bandDiscountPct / 100) - Math.round(total * manualDiscountPct / 100) + (feeAssignForm.paymentSchedule === 'Annual' ? 0 : Math.round(total * surchargePct / 100)));
      await api.post('/fee-assignments', {
        studentId: feeAssignForm.studentId,
        bandId: feeAssignForm.bandId,
        schedule: feeAssignForm.paymentSchedule,
        bandDiscount: bandDiscountPct,
        manualDiscount: manualDiscountPct,
        surcharge: surchargePct,
        netPayable,
        academicYearId: feeStructureData.academicYearId,
        assignedBy: user?.userId || user?.email || 'current-user',
      });
      showFeeToast('Fee plan assigned successfully');
      setAssignSelection({ classId: '', sectionId: '', studentId: '' });
      setAssignOptions({ sections: [], students: [] });
      setFeeAssignForm({ studentId: '', bandId: '', paymentSchedule: '', bandDiscount: '0', manualDiscount: '0', surcharge: '2' });
      setFeeAssignHint('');
      await onRefresh();
    } catch (err: unknown) {
      setFeeAssignError((err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Could not assign fee plan.'));
    } finally {
      setSaving('');
    }
  };

  return (
    <ModuleShell
      title="Fee Configuration"
      subtitle={`Define class bands, fee items and payment schedules · Academic year ${feeStructureData.academicYear || '2025–26'}`}
      actions={
        <>
          <button className="ck-btn ck-btn-ghost" onClick={exportFeeStructurePdf}>Export PDF</button>
          <button className="ck-btn ck-btn-ghost" onClick={() => { setShowBandForm((prev) => !prev); setShowFeeItemForm(false); }}>+ Add band</button>
          <button className="ck-btn ck-btn-g" onClick={() => { setShowFeeItemForm((prev) => !prev); setShowBandForm(false); }}>+ Add item</button>
        </>
      }
    >
      {feeStructureToast ? <div className="ck-alert ck-alert-g" style={{ marginBottom: 16 }}><span>✓</span><div>{feeStructureToast}</div></div> : null}
      {feeStructureError ? <div className="ck-alert ck-alert-re" style={{ marginBottom: 16 }}><span>!</span><div>{feeStructureError}</div></div> : null}

      {showBandForm ? (
        <div className="ck-form-card" style={{ marginBottom: 16 }}>
          <div className="ck-form-head">Add band</div>
          <div className="ck-form-body">
            <div className="ck-form-grid ck-fg-6">
              <Field label="Band name"><input value={bandForm.name} onChange={(e) => setBandForm((prev: any) => ({ ...prev, name: e.target.value }))} placeholder="Class 1–5" /></Field>
              <Field label="Class from"><select value={bandForm.classFrom} onChange={(e) => setBandForm((prev: any) => ({ ...prev, classFrom: e.target.value }))}>{Array.from({ length: 12 }, (_, i) => i + 1).map((n) => <option key={n} value={n}>{n}</option>)}</select></Field>
              <Field label="Class to"><select value={bandForm.classTo} onChange={(e) => setBandForm((prev: any) => ({ ...prev, classTo: e.target.value }))}>{Array.from({ length: 12 }, (_, i) => i + 1).map((n) => <option key={n} value={n}>{n}</option>)}</select></Field>
              <Field label="Discount %"><input type="number" min={0} max={100} value={bandForm.discount} onChange={(e) => setBandForm((prev: any) => ({ ...prev, discount: e.target.value }))} /></Field>
              <div className="ck-field">
                <label>Payment schedules</label>
                <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', minHeight: 44, alignItems: 'center' }}>
                  {['Monthly', 'Quarterly', 'Half-yearly', 'Annual'].map((schedule) => (
                    <button type="button" key={schedule} className={`ck-pill ${bandForm.schedules.includes(schedule) ? 'pg' : ''}`} style={{ border: bandForm.schedules.includes(schedule) ? '1px solid var(--g2)' : '1px solid var(--border)', background: bandForm.schedules.includes(schedule) ? 'var(--g1)' : '#fff', cursor: 'pointer' }} onClick={() => toggleBandFormSchedule(schedule, 'create')}>{schedule}</button>
                  ))}
                </div>
              </div>
              <div className="ck-field">
                <label>&nbsp;</label>
                <div style={{ display: 'flex', gap: 10 }}>
                  <button className="ck-btn ck-btn-g" disabled={saving === 'fee-band-add'} onClick={addFeeBand}>Create band</button>
                  <button className="ck-btn ck-btn-ghost" onClick={() => setShowBandForm(false)}>Cancel</button>
                </div>
              </div>
            </div>
          </div>
        </div>
      ) : null}

      {showFeeItemForm ? (
        <div className="ck-form-card" style={{ marginBottom: 16 }}>
          <div className="ck-form-head">Add fee item</div>
          <div className="ck-form-body">
            <div className="ck-form-grid ck-fg-6">
              <Field label="Class band">
                <select value={feeItemForm.bandId} onChange={(e) => setFeeItemForm({ ...feeItemForm, bandId: e.target.value })} disabled={feeStructureLoading || !(feeStructureData.bands || []).length}>
                  <option value="">{feeStructureLoading ? 'Loading class bands…' : (feeStructureData.bands || []).length ? 'Select class band' : 'No class bands found'}</option>
                  {(feeStructureData.bands || []).map((band: any) => <option key={band.id} value={band.id}>{band.name}</option>)}
                </select>
              </Field>
              <Field label="Item name"><input value={feeItemForm.itemName} onChange={(e) => setFeeItemForm({ ...feeItemForm, itemName: e.target.value })} placeholder="Tuition fee" /></Field>
              <Field label="Frequency">
                <select value={feeItemForm.frequency} onChange={(e) => setFeeItemForm({ ...feeItemForm, frequency: e.target.value })}>
                  <option>Monthly</option><option>Quarterly</option><option>Half-yearly</option><option>Annual</option>
                </select>
              </Field>
              <Field label="Amount (₹)"><input type="number" min={0} value={feeItemForm.amount} onChange={(e) => setFeeItemForm({ ...feeItemForm, amount: e.target.value })} /></Field>
              <div className="ck-field"><label>&nbsp;</label><button className="ck-btn ck-btn-g" disabled={saving === 'fee-structure-add'} onClick={addFeeStructureItem}>Add</button></div>
              <div className="ck-field"><label>&nbsp;</label><button className="ck-btn ck-btn-ghost" onClick={() => setShowFeeItemForm(false)}>Cancel</button></div>
            </div>
          </div>
        </div>
      ) : null}

      {feeStructureLoading ? <div className="ck-card" style={{ padding: 16 }}>Loading fee structure…</div> : null}

      {(feeStructureData.bands || []).length === 0 && !feeStructureLoading ? (
        <div className="ck-import-zone" style={{ margin: '24px 0' }}>
          <div className="iz-title">No fee bands yet</div>
          <div className="ts" style={{ marginTop: 8 }}>Create a band (e.g. "Class 1–5") to start adding fee items and payment schedules.</div>
          <button className="ck-btn ck-btn-g" style={{ marginTop: 16 }} onClick={() => setShowBandForm(true)}>+ Create first band</button>
        </div>
      ) : null}

      {(feeStructureData.bands || []).map((band: any) => {
        const totalPaise = Number(band.annualTotal || 0);
        const totalRupees = Math.round(totalPaise / 100);
        const discount = Number(band.discount || 0);
        const savings = Math.round(totalRupees * discount / 100);
        const activeSchedules = Array.isArray(band.activeSchedules) ? band.activeSchedules : [];
        const isExpanded = expandedBandIds.includes(band.id);
        const isEditingBand = editingBandId === band.id;
        const editSchedules = band.editSchedules || activeSchedules;
        return (
          <div className="ck-fee-group" key={band.id} style={{ marginBottom: 16, overflow: 'hidden' }}>
            <div className="ck-fee-head" style={{ cursor: 'pointer', gap: 16 }} onClick={() => toggleBandAccordion(band.id)}>
              <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12, flex: 1 }}>
                <div style={{ fontSize: 18, transform: isExpanded ? 'rotate(0deg)' : 'rotate(-90deg)', transition: 'transform 0.2s' }}>▾</div>
                <div style={{ flex: 1 }}>
                  {isEditingBand ? (
                    <div className="ck-form-grid ck-fg-6" onClick={(e) => e.stopPropagation()}>
                      <Field label="Band name"><input value={band.editName ?? band.name} onChange={(e) => setFeeStructureData((prev: any) => ({ ...prev, bands: prev.bands.map((row: any) => row.id === band.id ? { ...row, editName: e.target.value } : row) }))} /></Field>
                      <Field label="Class from"><select value={band.editClassFrom ?? band.classFrom} onChange={(e) => setFeeStructureData((prev: any) => ({ ...prev, bands: prev.bands.map((row: any) => row.id === band.id ? { ...row, editClassFrom: e.target.value } : row) }))}>{Array.from({ length: 12 }, (_, i) => i + 1).map((n) => <option key={n} value={n}>{n}</option>)}</select></Field>
                      <Field label="Class to"><select value={band.editClassTo ?? band.classTo} onChange={(e) => setFeeStructureData((prev: any) => ({ ...prev, bands: prev.bands.map((row: any) => row.id === band.id ? { ...row, editClassTo: e.target.value } : row) }))}>{Array.from({ length: 12 }, (_, i) => i + 1).map((n) => <option key={n} value={n}>{n}</option>)}</select></Field>
                      <Field label="Discount %"><input type="number" min={0} max={100} value={band.editDiscount ?? band.discount ?? 0} onChange={(e) => setFeeStructureData((prev: any) => ({ ...prev, bands: prev.bands.map((row: any) => row.id === band.id ? { ...row, editDiscount: e.target.value } : row) }))} /></Field>
                      <div className="ck-field">
                        <label>Payment schedules</label>
                        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', minHeight: 44, alignItems: 'center' }}>
                          {['Monthly', 'Quarterly', 'Half-yearly', 'Annual'].map((schedule) => (
                            <button type="button" key={schedule} className={`ck-pill ${editSchedules.includes(schedule) ? 'pg' : ''}`} style={{ border: editSchedules.includes(schedule) ? '1px solid var(--g2)' : '1px solid var(--border)', background: editSchedules.includes(schedule) ? 'var(--g1)' : '#fff', cursor: 'pointer' }} onClick={(e) => { e.stopPropagation(); toggleBandFormSchedule(schedule, 'edit', band.id); }}>{schedule}</button>
                          ))}
                        </div>
                      </div>
                      <div className="ck-field">
                        <label>&nbsp;</label>
                        <div style={{ display: 'flex', gap: 8 }}>
                          <button className="ck-btn ck-btn-g" disabled={saving === `fee-band-edit-${band.id}`} onClick={(e) => { e.stopPropagation(); saveFeeBandEdit(band); }}>Save</button>
                          <button className="ck-btn ck-btn-ghost" onClick={(e) => { e.stopPropagation(); setEditingBandId(''); }}>Cancel</button>
                        </div>
                      </div>
                    </div>
                  ) : (
                    <>
                      <div className="ck-fee-name" style={{ fontSize: 15, fontWeight: 500 }}>{band.name} <span className="ts">· Classes {band.classFrom}–{band.classTo}</span></div>
                      <div style={{ display: 'flex', gap: 8, marginTop: 10, flexWrap: 'wrap' }}>
                        {['Monthly', 'Quarterly', 'Half-yearly', 'Annual'].map((schedule) => (
                          <button type="button" key={schedule} className={`ck-pill ${activeSchedules.includes(schedule) ? 'pg' : ''}`} style={{ border: activeSchedules.includes(schedule) ? '1px solid var(--g2)' : '1px solid var(--border)', background: activeSchedules.includes(schedule) ? 'var(--g1)' : '#fff', cursor: 'pointer' }} onClick={(e) => { e.stopPropagation(); toggleBandSchedule(band, schedule); }}>{schedule}</button>
                        ))}
                      </div>
                    </>
                  )}
                </div>
              </div>
              {!isEditingBand ? (
                <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                  <div className="ck-fee-amt">₹{formatMoney(totalRupees)}</div>
                  <button className="ck-btn ck-btn-ghost" onClick={(e) => { e.stopPropagation(); setEditingBandId(band.id); setExpandedBandIds((prev) => prev.includes(band.id) ? prev : [...prev, band.id]); setFeeStructureData((prev: any) => ({ ...prev, bands: prev.bands.map((row: any) => row.id === band.id ? { ...row, editName: row.name, editClassFrom: String(row.classFrom), editClassTo: String(row.classTo), editDiscount: String(row.discount ?? 0), editSchedules: [...(row.activeSchedules || [])] } : row) })); }}>Edit</button>
                  <button className="ck-btn ck-btn-ghost" style={{ color: '#A32D2D', borderColor: '#f5c0bc' }} onClick={(e) => { e.stopPropagation(); setConfirmDeleteBandId(confirmDeleteBandId === band.id ? '' : band.id); }}>Delete band</button>
                </div>
              ) : null}
            </div>
            {confirmDeleteBandId === band.id ? (
              <div style={{ padding: '0 16px 12px', textAlign: 'right' }}>
                <span className="ts">Delete band '{band.name}' and all its items? </span>
                <button className="ck-inline-action" onClick={() => deleteFeeBand(band.id, band.name)}>Yes</button> / <button className="ck-inline-action" onClick={() => setConfirmDeleteBandId('')}>No</button>
              </div>
            ) : null}
            {isExpanded ? (
              <div onClick={(e) => e.stopPropagation()}>
                <div className="ck-card" style={{ border: 'none', borderTop: '1px solid var(--border)', borderRadius: 0, boxShadow: 'none' }}>
                  {(band.items || []).length ? (
                    <table className="ck-table">
                      <thead><tr><th>Item name</th><th>Frequency</th><th>Amount</th><th>% of total</th><th>Actions</th></tr></thead>
                      <tbody>
                        {(band.items || []).map((item: any) => {
                          const isEditing = editingFeeItem?.id === item.id;
                          const pct = totalPaise > 0 ? Math.round(Number(item.amount || 0) / totalPaise * 100) : 0;
                          return (
                            <tr key={item.id}>
                              <td>{isEditing ? <input value={editingFeeItem.name} onChange={(e) => setEditingFeeItem({ ...editingFeeItem, name: e.target.value })} /> : <div className="tb">{item.name}</div>}</td>
                              <td>{isEditing ? <select value={editingFeeItem.frequency} onChange={(e) => setEditingFeeItem({ ...editingFeeItem, frequency: e.target.value })}><option>Monthly</option><option>Quarterly</option><option>Half-yearly</option><option>Annual</option></select> : <span className="ck-pill pb">{item.frequency}</span>}</td>
                              <td style={{ textAlign: 'right' }}>{isEditing ? <input type="number" min={0} value={editingFeeItem.amount} onChange={(e) => setEditingFeeItem({ ...editingFeeItem, amount: e.target.value })} /> : `₹${formatMoney(Math.round(Number(item.amount || 0) / 100))}`}</td>
                              <td><span className="ts">{pct}%</span></td>
                              <td>
                                <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
                                  {isEditing ? (
                                    <><button className="ck-btn ck-btn-g" disabled={saving === 'fee-structure-edit'} onClick={saveFeeStructureItem}>Save</button><button className="ck-btn ck-btn-ghost" onClick={() => setEditingFeeItem(null)}>Cancel</button></>
                                  ) : (
                                    <><button className="ck-btn ck-btn-ghost" onClick={() => setEditingFeeItem({ id: item.id, name: item.name, frequency: item.frequency, amount: Math.round(Number(item.amount || 0) / 100) })}>Edit</button><button className="ck-btn ck-btn-ghost" onClick={() => setConfirmRemoveFeeItemId(confirmRemoveFeeItemId === item.id ? '' : item.id)}>Remove</button></>
                                  )}
                                </div>
                                {confirmRemoveFeeItemId === item.id && !isEditing ? (
                                  <div className="ts" style={{ marginTop: 6, textAlign: 'right' }}>Remove this item? <button className="ck-inline-action" onClick={() => removeFeeStructureItem(item.id)}>Yes</button> / <button className="ck-inline-action" onClick={() => setConfirmRemoveFeeItemId('')}>No</button></div>
                                ) : null}
                              </td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  ) : (
                    <div style={{ padding: 20, textAlign: 'center' }} className="ts">No fee items yet. Use '+ Add item' to add one.</div>
                  )}
                </div>
                <div className="ck-fee-footer">
                  <div className="ck-fee-footer-left">
                    <label className="ts" style={{ display: 'block', marginBottom: 6 }}>Discount</label>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
                      <input className="ck-fee-discount-input" type="number" min={0} max={100} value={band.discount} onChange={(e) => handleDiscountChange(band.id, e.target.value)} />
                      <span>%</span>
                      <span className="ts">saves <strong style={{ color: '#085041' }}>₹{formatMoney(savings)}</strong></span>
                    </div>
                  </div>
                  <div className="ck-fee-footer-right">Total annual: ₹{formatMoney(totalRupees)}</div>
                </div>
              </div>
            ) : null}
          </div>
        );
      })}

      {/* Assign fee plan */}
      <div className="ck-card" style={{ marginTop: 24 }}>
        <div className="ck-card-h"><div className="ck-card-t">Assign fee plan to student</div></div>
        <div className="ck-form-grid ck-fg-6" style={{ padding: 16 }}>
          <Field label="Class"><select value={assignSelection.classId} onChange={(e) => handleAssignClassChange(e.target.value)}><option value="">Select class</option>{feeClasses.map((row: any) => <option key={row.id} value={row.id}>{row.name}</option>)}</select></Field>
          <Field label="Section"><select disabled={!assignSelection.classId} value={assignSelection.sectionId} onChange={(e) => handleAssignSectionChange(e.target.value)}><option value="">Select section</option>{assignOptions.sections.map((row: any) => <option key={row.id} value={row.id}>{row.name}</option>)}</select>{!assignSelection.classId ? <div className="ts">Select a class first</div> : null}</Field>
          <Field label="Student"><select disabled={!assignSelection.sectionId} value={assignSelection.studentId} onChange={(e) => handleAssignStudentChange(e.target.value)}><option value="">Select student</option>{assignOptions.students.map((student: any) => <option key={student.id} value={student.id}>{student.name} · {student.admissionNo}</option>)}</select>{!assignSelection.sectionId ? <div className="ts">Select a section first</div> : null}</Field>
          <Field label="Fee plan"><select value={feeAssignForm.bandId} onChange={(e) => handleFeePlanChange(e.target.value)}><option value="">Select fee plan</option>{(feeStructureData.bands || []).map((band: any) => <option key={band.id} value={band.id}>{band.name} · ₹{formatMoney(Math.round(Number(band.annualTotal || 0) / 100))}</option>)}</select>{feeAssignHint ? <div className="ts">{feeAssignHint}</div> : null}</Field>
          <Field label="Payment schedule"><select disabled={!feeAssignForm.bandId} value={feeAssignForm.paymentSchedule} onChange={(e) => setFeeAssignForm({ ...feeAssignForm, paymentSchedule: e.target.value, surcharge: e.target.value === 'Annual' ? '0' : feeAssignForm.surcharge })}><option value="">Select schedule</option>{(((feeStructureData.bands || []).find((band: any) => band.id === feeAssignForm.bandId)?.activeSchedules) || []).map((schedule: string) => <option key={schedule} value={schedule}>{schedule}</option>)}</select></Field>
          <Field label="Discount % (from band)"><input readOnly value={feeAssignForm.bandDiscount} /><div className="ts">Set in Fee structure</div></Field>
          <Field label="Manual student discount"><input type="number" min={0} max={100} value={feeAssignForm.manualDiscount} onChange={(e) => setFeeAssignForm({ ...feeAssignForm, manualDiscount: e.target.value })} /></Field>
          <Field label="Installment surcharge %"><input type="number" min={0} max={100} value={feeAssignForm.surcharge} onChange={(e) => setFeeAssignForm({ ...feeAssignForm, surcharge: e.target.value })} /></Field>
        </div>
        {feeAssignError ? <div style={{ padding: '0 16px 16px' }}><div className="ck-alert ck-alert-re"><span>!</span><div>{feeAssignError}</div></div></div> : null}
        {feeAssignForm.bandId ? (
          <div style={{ padding: '0 16px 16px' }}>
            <div className="ck-card" style={{ boxShadow: 'none' }}>
              <div className="ck-card-h"><div className="ck-card-t">Fee items in this band</div></div>
              <table className="ck-table">
                <thead><tr><th>Item name</th><th>Frequency</th><th>Amount</th></tr></thead>
                <tbody>
                  {(((feeStructureData.bands || []).find((band: any) => band.id === feeAssignForm.bandId)?.items) || []).map((item: any) => (
                    <tr key={item.id}><td>{item.name}</td><td>{item.frequency}</td><td>₹{formatMoney(Math.round(Number(item.amount || 0) / 100))}</td></tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        ) : null}
        {feeAssignForm.bandId && feeAssignForm.paymentSchedule ? (() => {
          const band = (feeStructureData.bands || []).find((row: any) => row.id === feeAssignForm.bandId);
          const total = Math.round(Number(band?.annualTotal || 0) / 100);
          const bandDiscountAmt = Math.round(total * Number(feeAssignForm.bandDiscount || 0) / 100);
          const manualDiscountAmt = Math.round(total * Number(feeAssignForm.manualDiscount || 0) / 100);
          const surchargeAmt = feeAssignForm.paymentSchedule === 'Annual' ? 0 : Math.round(total * Number(feeAssignForm.surcharge || 0) / 100);
          const netPayable = total - bandDiscountAmt - manualDiscountAmt + surchargeAmt;
          return (
            <div style={{ padding: '0 16px 16px' }}>
              <div className="ck-alert ck-alert-g">
                <span>₹</span>
                <div>
                  <strong>Live net payable preview</strong>
                  <div>Total annual fee ₹{formatMoney(total)} · Band discount −₹{formatMoney(bandDiscountAmt)} · Manual discount −₹{formatMoney(manualDiscountAmt)} · Surcharge +₹{formatMoney(surchargeAmt)} · <span style={{ color: '#085041', fontWeight: 700 }}>Net payable ₹{formatMoney(netPayable)}</span></div>
                </div>
              </div>
            </div>
          );
        })() : null}
        <div className="ck-actions-inline" style={{ padding: '0 16px 16px' }}>
          <button disabled={!(feeAssignForm.studentId && feeAssignForm.bandId && feeAssignForm.paymentSchedule) || saving === 'assign-fee'} className="ck-btn ck-btn-g" onClick={submitFeeAssignment}>
            {saving === 'assign-fee' ? 'Saving…' : 'Assign / update plan'}
          </button>
        </div>
      </div>
    </ModuleShell>
  );
}

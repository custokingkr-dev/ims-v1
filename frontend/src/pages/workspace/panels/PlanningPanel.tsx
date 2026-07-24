import { useMemo, useState } from 'react';
import { ModuleShell } from '../ui';
import { Modal } from '../../../components/Modal';
import type { WorkspaceData, PanelKey } from '../../workspace/config';
import { currentFinancialYearLabel, financialYearHistoryOptions, formatMoney } from '../utils';
import api from '../../../services/api';

interface Props {
  workspace: WorkspaceData;
  onRefresh: () => Promise<void>;
  setPanel?: (k: PanelKey) => void;
}

interface PlanTerm {
  id?: string | number;
  term?: string;
  category?: string;
  status?: string;
  quantity?: string | number;
  amount?: number;
  estimatedAmount?: number;
  description?: string;
}

interface PlanForm {
  category: string;
  description: string;
  estimatedAmount: string;
}

const EMPTY_FORM: PlanForm = {
  category: '',
  description: '',
  estimatedAmount: '',
};

function planAmount(item: PlanTerm): number {
  return Number(item.amount ?? item.estimatedAmount ?? 0);
}

function normalizeStatus(status?: string): string {
  const value = String(status || 'Draft').trim();
  return value || 'Draft';
}

function statusTone(status?: string): string {
  const value = normalizeStatus(status).toLowerCase();
  if (value.includes('confirm') || value.includes('lock') || value.includes('ordered')) return 'sg';
  if (value.includes('pending') || value.includes('draft')) return 'sam';
  if (value.includes('cancel') || value.includes('reject')) return 'sr';
  return 'sb2';
}

export function PlanningPanel({ workspace, onRefresh, setPanel }: Props) {
  const [selectedYear, setSelectedYear] = useState<string | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [form, setForm] = useState<PlanForm>(EMPTY_FORM);
  const [error, setError] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [confirming, setConfirming] = useState(false);

  const financialYearStartMonth = Number(workspace.school?.financialYearStartMonth || 4);
  const currentYearLabel = currentFinancialYearLabel(new Date(), financialYearStartMonth);
  const planningYearTabs = financialYearHistoryOptions(3, new Date(), financialYearStartMonth);
  const activeYear = selectedYear ?? currentYearLabel;

  const planTerms = useMemo(
    () => ((workspace.annualPlan?.terms ?? []) as PlanTerm[]),
    [workspace.annualPlan?.terms],
  );

  const totalPlanned = useMemo(
    () => planTerms.reduce((sum, item) => sum + planAmount(item), 0),
    [planTerms],
  );

  const confirmedCount = useMemo(
    () => planTerms.filter(item => {
      const status = normalizeStatus(item.status).toLowerCase();
      return status.includes('confirm') || status.includes('lock') || status.includes('ordered');
    }).length,
    [planTerms],
  );

  const categories = useMemo(
    () => new Set(planTerms.map(item => String(item.category || 'Uncategorized'))).size,
    [planTerms],
  );

  function showToast(message: string) {
    setToast(message);
    window.setTimeout(() => setToast(null), 3200);
  }

  function updateForm<K extends keyof PlanForm>(key: K, value: PlanForm[K]) {
    setForm(current => ({ ...current, [key]: value }));
    setError(null);
  }

  async function createPlanItem(): Promise<void> {
    const category = form.category.trim();
    const description = form.description.trim();
    const estimatedAmount = Number(form.estimatedAmount);

    if (!category) {
      setError('Category is required.');
      return;
    }
    if (!Number.isFinite(estimatedAmount) || estimatedAmount < 0) {
      setError('Enter a valid estimated amount.');
      return;
    }

    setSaving(true);
    setError(null);
    try {
      await api.post('/supply/annual-plan/items', {
        category,
        description,
        estimatedAmount: Math.round(estimatedAmount),
      });
      setForm(EMPTY_FORM);
      setFormOpen(false);
      showToast('Annual plan item saved.');
      await onRefresh();
    } catch (err: unknown) {
      setError((err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Could not save annual plan item.'));
    } finally {
      setSaving(false);
    }
  }

  async function confirmPlan(): Promise<void> {
    setConfirming(true);
    try {
      await api.post('/supply/annual-plan/confirm');
      showToast('Annual plan confirmed.');
      await onRefresh();
    } catch (err: unknown) {
      showToast((err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Could not confirm annual plan.'));
    } finally {
      setConfirming(false);
    }
  }

  return (
    <ModuleShell
      title="Annual plan"
      subtitle={`Plan and confirm real supply requirements for ${activeYear}`}
      actions={
        <>
          {setPanel ? (
            <button className="ck-btn ck-btn-ghost" onClick={() => setPanel('catalog' as PanelKey)}>
              Catalog
            </button>
          ) : null}
          <button className="ck-btn ck-btn-ghost" onClick={() => setFormOpen(true)}>
            Add item
          </button>
          <button className="ck-btn ck-btn-g" disabled={confirming || planTerms.length === 0} onClick={() => void confirmPlan()}>
            {confirming ? 'Confirming...' : 'Confirm plan'}
          </button>
        </>
      }
    >
      <div className="ap-subheader">
        <div className="ap-year-tabs">
          {planningYearTabs.map(year => (
            <button
              key={year}
              className={`ap-ytab${year === activeYear ? ' on' : ''}`}
              onClick={() => {
                setSelectedYear(year);
                if (year !== currentYearLabel) showToast(`Showing saved workspace data for ${year}.`);
              }}
            >
              {year}
            </button>
          ))}
        </div>
        <div className="ap-hstats">
          <div className="ap-hstat">
            <div className="ap-hstat-v" style={{ color: 'var(--b)' }}>{planTerms.length}</div>
            <div className="ap-hstat-l">Items</div>
          </div>
          <div className="ap-hstat">
            <div className="ap-hstat-v" style={{ color: 'var(--g)' }}>Rs {formatMoney(totalPlanned)}</div>
            <div className="ap-hstat-l">Planned</div>
          </div>
          <div className="ap-hstat">
            <div className="ap-hstat-v" style={{ color: 'var(--am)' }}>{categories}</div>
            <div className="ap-hstat-l">Categories</div>
          </div>
          <div className="ap-hstat">
            <div className="ap-hstat-v" style={{ color: 'var(--pu)' }}>{confirmedCount}</div>
            <div className="ap-hstat-l">Confirmed</div>
          </div>
        </div>
      </div>

      <div className="ck-card" style={{ marginBottom: 18 }}>
        <div className="ck-card-head">
          <div>
            <div className="ck-card-title">Saved plan items</div>
            <div className="ts">Only records returned by the workspace annual-plan API are shown.</div>
          </div>
          <button className="ck-btn ck-btn-g" onClick={() => setFormOpen(true)}>Add item</button>
        </div>

        {planTerms.length === 0 ? (
          <div className="ck-empty-state" style={{ marginTop: 16 }}>
            <div>
              <strong>No annual plan items saved</strong>
              <span>Create the first item when the school has a confirmed supply requirement.</span>
              <button className="ck-btn ck-btn-g" style={{ marginTop: 12 }} onClick={() => setFormOpen(true)}>Create item</button>
            </div>
          </div>
        ) : (
          <div className="ck-table-wrap" style={{ marginTop: 14 }}>
            <table className="ck-table">
              <thead>
                <tr>
                  <th>Term</th>
                  <th>Category</th>
                  <th>Quantity</th>
                  <th>Status</th>
                  <th style={{ textAlign: 'right' }}>Amount</th>
                </tr>
              </thead>
              <tbody>
                {planTerms.map((item, index) => (
                  <tr key={item.id ?? `${item.category}-${item.term}-${index}`}>
                    <td>{item.term || activeYear}</td>
                    <td>
                      <strong>{item.category || 'Uncategorized'}</strong>
                      {item.description ? <div className="ts">{item.description}</div> : null}
                    </td>
                    <td>{item.quantity || '-'}</td>
                    <td><span className={`status ${statusTone(item.status)}`}>{normalizeStatus(item.status)}</span></td>
                    <td className="mono" style={{ textAlign: 'right' }}>Rs {formatMoney(planAmount(item))}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="ck-card">
        <div className="ck-card-head">
          <div>
            <div className="ck-card-title">Procurement handoff</div>
            <div className="ts">Use the catalog to convert approved planning items into actual supply orders.</div>
          </div>
          {setPanel ? (
            <button className="ck-btn ck-btn-ghost" onClick={() => setPanel('orders' as PanelKey)}>
              View orders
            </button>
          ) : null}
        </div>
      </div>

      {toast ? <div className="toast">{toast}</div> : null}

      {formOpen ? (
        <Modal
          title="Add annual plan item"
          subtitle="Save a real requirement to the school's annual plan"
          onClose={() => { if (!saving) { setFormOpen(false); setError(null); } }}
          footer={
            <>
              <button className="ck-btn ck-btn-ghost" disabled={saving} onClick={() => setFormOpen(false)}>Cancel</button>
              <button className="ck-btn ck-btn-g" disabled={saving} onClick={() => void createPlanItem()}>
                {saving ? 'Saving...' : 'Save item'}
              </button>
            </>
          }
        >
          {error ? (
            <div className="ck-alert ck-alert-r" style={{ marginBottom: 14 }}>
              <span>!</span>
              <div>{error}</div>
            </div>
          ) : null}
          <div className="ck-form-grid ck-fg-1">
            <div className="ck-field">
              <label htmlFor="plan-category">Category</label>
              <input
                id="plan-category"
                value={form.category}
                onChange={event => updateForm('category', event.target.value)}
                placeholder="Uniforms, notebooks, exam stationery"
              />
            </div>
            <div className="ck-field">
              <label htmlFor="plan-description">Description</label>
              <textarea
                id="plan-description"
                rows={3}
                value={form.description}
                onChange={event => updateForm('description', event.target.value)}
                placeholder="Requirement, class coverage, deadline, or vendor note"
              />
            </div>
            <div className="ck-field">
              <label htmlFor="plan-amount">Estimated amount</label>
              <input
                id="plan-amount"
                type="number"
                min={0}
                step={1}
                value={form.estimatedAmount}
                onChange={event => updateForm('estimatedAmount', event.target.value)}
                placeholder="0"
              />
            </div>
          </div>
        </Modal>
      ) : null}
    </ModuleShell>
  );
}

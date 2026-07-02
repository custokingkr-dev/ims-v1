import { FormEvent, useEffect, useState } from 'react';
import api from '../services/api';
import { Customer } from '../types/invoice';
import { useAuth } from '../contexts/AuthContext';
import { PageHero } from '../components/PageHero';

export default function CustomersPage() {
  const { user } = useAuth();
  const [customers, setCustomers] = useState<Customer[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [formError, setFormError] = useState('');
  const [saving, setSaving] = useState(false);
  const [form, setForm] = useState({ code: '', name: '', email: '', phone: '', gstin: '', addressLine: '' });

  const load = async () => {
    try {
      setLoading(true);
      setError('');
      const res = await api.get<Customer[]>('/customers');
      setCustomers(res.data);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : (err as { response?: { data?: { message?: string } } })?.response?.data?.message || 'Failed to load customers.';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setFormError('');
    // Client-side required-field validation
    if (!form.code.trim()) { setFormError('Customer code is required.'); return; }
    if (!form.name.trim()) { setFormError('Customer name is required.'); return; }
    // branchId guard: superadmins have no implicit branchId — block rather than silently default to school 1
    if (!user?.branchId) {
      setFormError('A school (branch) must be selected before creating a customer. Contact your administrator if this option is unavailable.');
      return;
    }
    try {
      setSaving(true);
      await api.post('/customers', { ...form, branchId: user.branchId, active: true });
      setForm({ code: '', name: '', email: '', phone: '', gstin: '', addressLine: '' });
      void load();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : (err as { response?: { data?: { message?: string } } })?.response?.data?.message || 'Failed to save customer.';
      setError(msg);
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="page-stack">
      <PageHero
        label="Customer master"
        title={<>Manage <em>customers</em></>}
        subtitle="Capture schools, parents, and B2B records with branch-aligned billing details."
        actions={<div className="badge">{customers.length} records</div>}
      />
      {error && <p className="ck-alert ck-alert-re">{error}</p>}
      <div className="two-col">
        <form className="card" onSubmit={submit}>
          <div className="section-head"><div><h3>Create customer</h3><p className="section-copy">Add a new customer profile for invoicing and collections.</p></div></div>
          {formError && <p className="ck-alert ck-alert-re">{formError}</p>}
          <input placeholder="Customer code" value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value })} />
          <input placeholder="Customer name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          <input placeholder="Email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} />
          <input placeholder="Phone" value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} />
          <input placeholder="GSTIN" value={form.gstin} onChange={(e) => setForm({ ...form, gstin: e.target.value })} />
          <textarea placeholder="Address" value={form.addressLine} onChange={(e) => setForm({ ...form, addressLine: e.target.value })} />
          <button type="submit" disabled={saving}>{saving ? 'Saving…' : 'Save customer'}</button>
        </form>
        <div className="card">
          <div className="section-head"><div><h3>Customer list</h3><p className="section-copy">Recent customer records in the current workspace.</p></div></div>
          {loading ? <p>Loading…</p> : (
            <div className="table-wrap">
              <table className="table">
                <thead><tr><th>Code</th><th>Name</th><th>Branch</th><th>Email</th></tr></thead>
                <tbody>
                  {customers.length === 0
                    ? <tr><td colSpan={4} style={{ textAlign: 'center', color: 'var(--ink3)', padding: '16px 0' }}>No customers found.</td></tr>
                    : customers.map(c => <tr key={c.id}><td>{c.code}</td><td>{c.name}</td><td>{c.branchName}</td><td>{c.email || '-'}</td></tr>)}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

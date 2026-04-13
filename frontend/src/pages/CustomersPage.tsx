import { FormEvent, useEffect, useState } from 'react';
import api from '../services/api';
import { Customer } from '../types/invoice';
import { useAuth } from '../contexts/AuthContext';

export default function CustomersPage() {
  const { user } = useAuth();
  const [customers, setCustomers] = useState<Customer[]>([]);
  const [form, setForm] = useState({ code: '', name: '', email: '', phone: '', gstin: '', addressLine: '' });

  function load() { api.get('/customers').then((res) => setCustomers(res.data)); }
  useEffect(() => { void load(); }, []);

  async function submit(e: FormEvent) {
    e.preventDefault();
    await api.post('/customers', { ...form, branchId: user?.branchId || 1, active: true });
    setForm({ code: '', name: '', email: '', phone: '', gstin: '', addressLine: '' });
    void load();
  }

  return (
    <div className="page-stack">
      <section className="hero">
        <div className="hero-row">
          <div>
            <div className="section-label">Customer master</div>
            <h1 className="page-title">Manage <em>customers</em></h1>
            <p className="page-subtitle">Capture schools, parents, and B2B records with branch-aligned billing details.</p>
          </div>
          <div className="badge">{customers.length} records</div>
        </div>
      </section>

      <div className="two-col">
        <form className="card" onSubmit={submit}>
          <div className="section-head"><div><h3>Create customer</h3><p className="section-copy">Add a new customer profile for invoicing and collections.</p></div></div>
          <input placeholder="Customer code" value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value })} />
          <input placeholder="Customer name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          <input placeholder="Email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} />
          <input placeholder="Phone" value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} />
          <input placeholder="GSTIN" value={form.gstin} onChange={(e) => setForm({ ...form, gstin: e.target.value })} />
          <textarea placeholder="Address" value={form.addressLine} onChange={(e) => setForm({ ...form, addressLine: e.target.value })} />
          <button type="submit">Save customer</button>
        </form>
        <div className="card">
          <div className="section-head"><div><h3>Customer list</h3><p className="section-copy">Recent customer records in the current workspace.</p></div></div>
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Code</th><th>Name</th><th>Branch</th><th>Email</th></tr></thead>
              <tbody>{customers.map(c => <tr key={c.id}><td>{c.code}</td><td>{c.name}</td><td>{c.branchName}</td><td>{c.email || '-'}</td></tr>)}</tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  );
}

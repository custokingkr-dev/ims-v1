import { FormEvent, useEffect, useState } from 'react';
import api from '../services/api';
import { Customer, Invoice } from '../types/invoice';
import { useAuth } from '../contexts/AuthContext';
import { PageHero } from '../components/PageHero';

const initialItem = { description: '', quantity: 1, unitPrice: 0, taxRate: 18 };

export default function InvoicesPage() {
  const { user } = useAuth();
  const [customers, setCustomers] = useState<Customer[]>([]);
  const [invoices, setInvoices] = useState<Invoice[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const [form, setForm] = useState({
    customerId: 1,
    invoiceDate: new Date().toISOString().slice(0, 10),
    dueDate: new Date().toISOString().slice(0, 10),
    discountPercent: 0,
    notes: '',
    items: [{ ...initialItem }],
  });

  const load = async () => {
    try {
      setLoading(true);
      setError('');
      const [custRes, invRes] = await Promise.all([
        api.get<Customer[]>('/customers'),
        api.get<Invoice[]>('/invoices'),
      ]);
      setCustomers(custRes.data);
      setInvoices(invRes.data);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : (err as { response?: { data?: { message?: string } } })?.response?.data?.message || 'Failed to load data.';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    try {
      setSaving(true);
      setError('');
      await api.post('/invoices', {
        ...form,
        branchId: user?.branchId || 1,
        discountPercent: Number(form.discountPercent),
        customerId: Number(form.customerId),
      });
      setForm({
        customerId: 1,
        invoiceDate: new Date().toISOString().slice(0, 10),
        dueDate: new Date().toISOString().slice(0, 10),
        discountPercent: 0,
        notes: '',
        items: [{ ...initialItem }],
      });
      void load();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : (err as { response?: { data?: { message?: string } } })?.response?.data?.message || 'Failed to create invoice.';
      setError(msg);
    } finally {
      setSaving(false);
    }
  };

  const downloadPdf = async (id: number, invoiceNo: string) => {
    try {
      const response = await api.get(`/invoices/${id}/pdf`, { responseType: 'blob' });
      const blobUrl = window.URL.createObjectURL(new Blob([response.data as BlobPart], { type: 'application/pdf' }));
      const link = document.createElement('a');
      link.href = blobUrl;
      link.download = `${invoiceNo}.pdf`;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      window.URL.revokeObjectURL(blobUrl);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to download PDF.';
      setError(msg);
    }
  };

  return (
    <div className="page-stack">
      <PageHero
        label="Billing operations"
        title={<>Create and track <em>invoices</em></>}
        subtitle="Issue polished invoices, manage discounting, and download PDFs for customer communication."
        actions={<div className="badge">{invoices.length} invoices</div>}
      />
      {error && <p className="ck-alert ck-alert-re">{error}</p>}
      <div className="two-col">
        <form className="card" onSubmit={submit}>
          <div className="section-head"><div><h3>Create invoice</h3><p className="section-copy">Build a customer invoice with line items and due dates.</p></div></div>
          <select value={form.customerId} onChange={(e) => setForm({ ...form, customerId: Number(e.target.value) })}>
            {customers.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
          <input type="date" value={form.invoiceDate} onChange={(e) => setForm({ ...form, invoiceDate: e.target.value })} />
          <input type="date" value={form.dueDate} onChange={(e) => setForm({ ...form, dueDate: e.target.value })} />
          <input type="number" step="0.01" value={form.discountPercent} onChange={(e) => setForm({ ...form, discountPercent: Number(e.target.value) })} placeholder="Discount %" />
          {form.items.map((item, index) => (
            <div className="row" key={index}>
              <input placeholder="Description" value={item.description} onChange={(e) => {
                const items = [...form.items]; items[index] = { ...items[index], description: e.target.value }; setForm({ ...form, items });
              }} />
              <input type="number" value={item.quantity} onChange={(e) => { const items = [...form.items]; items[index] = { ...items[index], quantity: Number(e.target.value) }; setForm({ ...form, items }); }} />
              <input type="number" value={item.unitPrice} onChange={(e) => { const items = [...form.items]; items[index] = { ...items[index], unitPrice: Number(e.target.value) }; setForm({ ...form, items }); }} />
              <input type="number" value={item.taxRate} onChange={(e) => { const items = [...form.items]; items[index] = { ...items[index], taxRate: Number(e.target.value) }; setForm({ ...form, items }); }} />
              <button type="button" className="secondary" disabled={form.items.length <= 1} onClick={() => { const items = form.items.filter((_, i) => i !== index); setForm({ ...form, items }); }}>Remove</button>
            </div>
          ))}
          <div className="actions-inline">
            <button type="button" className="secondary" onClick={() => setForm({ ...form, items: [...form.items, { ...initialItem }] })}>Add line</button>
            <button type="submit" disabled={saving}>{saving ? 'Saving…' : 'Save invoice'}</button>
          </div>
          <textarea placeholder="Notes" value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} />
        </form>
        <div className="card">
          <div className="section-head"><div><h3>Invoice list</h3><p className="section-copy">Generated invoices with downloadable PDFs.</p></div></div>
          {loading ? <p>Loading…</p> : (
            <div className="table-wrap">
              <table className="table">
                <thead><tr><th>No</th><th>Customer</th><th>Total</th><th>Status</th><th>PDF</th></tr></thead>
                <tbody>{invoices.length ? invoices.map(inv => (
                  <tr key={inv.id}>
                    <td>{inv.invoiceNo}</td>
                    <td>{inv.customerName}</td>
                    <td>₹ {inv.grandTotal}</td>
                    <td><span className={`status-pill ${String(inv.status).toLowerCase()}`}>{inv.status}</span></td>
                    <td><button type="button" className="secondary" onClick={() => void downloadPdf(inv.id, inv.invoiceNo)}>Download</button></td>
                  </tr>
                )) : <tr><td colSpan={5} style={{ textAlign: 'center', color: 'var(--ink3)' }}>No invoices yet</td></tr>}</tbody>
              </table>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

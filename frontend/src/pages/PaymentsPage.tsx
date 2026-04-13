import { FormEvent, useEffect, useState } from 'react';
import api from '../services/api';
import { Invoice, Payment } from '../types/invoice';
import { useAuth } from '../contexts/AuthContext';

export default function PaymentsPage() {
  const { user } = useAuth();
  const [payments, setPayments] = useState<Payment[]>([]);
  const [invoices, setInvoices] = useState<Invoice[]>([]);
  const [form, setForm] = useState({ invoiceId: 1, paymentDate: new Date().toISOString().slice(0,10), amount: 0, paymentMode: 'UPI', referenceNo: '', notes: '' });

  function load() {
    api.get('/payments').then((res) => setPayments(res.data));
    api.get('/invoices').then((res) => setInvoices(res.data));
  }
  useEffect(() => { void load(); }, []);

  async function submit(e: FormEvent) {
    e.preventDefault();
    await api.post('/payments', { ...form, invoiceId: Number(form.invoiceId), amount: Number(form.amount), branchId: user?.branchId || 1 });
    setForm({ invoiceId: 1, paymentDate: new Date().toISOString().slice(0,10), amount: 0, paymentMode: 'UPI', referenceNo: '', notes: '' });
    void load();
  }

  return (
    <div className="page-stack">
      <section className="hero">
        <div className="hero-row">
          <div>
            <div className="section-label">Collections</div>
            <h1 className="page-title">Record incoming <em>payments</em></h1>
            <p className="page-subtitle">Capture settlement details with mode, reference numbers, and linked invoices.</p>
          </div>
          <div className="badge">{payments.length} payments</div>
        </div>
      </section>

      <div className="two-col">
        <form className="card" onSubmit={submit}>
          <div className="section-head"><div><h3>Record payment</h3><p className="section-copy">Link collections to the correct invoice and payment mode.</p></div></div>
          <select value={form.invoiceId} onChange={(e) => setForm({ ...form, invoiceId: Number(e.target.value) })}>
            {invoices.map((i) => <option key={i.id} value={i.id}>{i.invoiceNo} - {i.customerName}</option>)}
          </select>
          <input type="date" value={form.paymentDate} onChange={(e) => setForm({ ...form, paymentDate: e.target.value })} />
          <input type="number" step="0.01" value={form.amount} onChange={(e) => setForm({ ...form, amount: Number(e.target.value) })} placeholder="Amount" />
          <select value={form.paymentMode} onChange={(e) => setForm({ ...form, paymentMode: e.target.value })}>
            <option>CASH</option><option>UPI</option><option>CARD</option><option>BANK_TRANSFER</option><option>CHEQUE</option>
          </select>
          <input value={form.referenceNo} onChange={(e) => setForm({ ...form, referenceNo: e.target.value })} placeholder="Reference no" />
          <textarea value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} placeholder="Notes" />
          <button type="submit">Save payment</button>
        </form>
        <div className="card">
          <div className="section-head"><div><h3>Payment list</h3><p className="section-copy">Recent collections logged by your team.</p></div></div>
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Invoice</th><th>Amount</th><th>Mode</th><th>Date</th><th>By</th></tr></thead>
              <tbody>{payments.map(p => <tr key={p.id}><td>{p.invoiceNo}</td><td>₹ {p.amount}</td><td>{p.paymentMode}</td><td>{p.paymentDate}</td><td>{p.receivedBy}</td></tr>)}</tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  );
}

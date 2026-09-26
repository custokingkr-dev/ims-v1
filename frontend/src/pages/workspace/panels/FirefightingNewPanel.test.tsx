import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import api from '../../../services/api';
import { FirefightingNewPanel } from './FirefightingNewPanel';
import type { DocumentCapabilities } from '../../../generated/quotationDocumentClient';

vi.mock('../../../services/api');
vi.mock('../../../contexts/AuthContext', () => ({ useAuth: () => ({ user: { userId: 7, branchId: 10 } }) }));
const recoveryKey = 'ck:procurement-save:v1:7:10:new';
const savedDraft = { code: 'FF-42', status: 'DRAFT', title: 'Lab benches', category: 'Lab equipment', urgency: 'MEDIUM', description: 'Replace two benches', quotations: [] };
const enabledFiles: DocumentCapabilities = { available: true, canUpload: true, maxBytes: 5242880, allowedContentTypes: ['application/pdf', 'image/png', 'image/jpeg'], unavailableReason: null };
const disabledFiles = { ...enabledFiles, available: false, canUpload: false, unavailableReason: 'Private quotation storage is not configured' };
function loadDraft(draft: unknown = savedDraft, capabilities = enabledFiles) {
  vi.mocked(api.get).mockImplementation(url => Promise.resolve({ data: url.endsWith('/capabilities') ? capabilities : draft }));
}

function fillQuotation() {
  fireEvent.change(screen.getAllByLabelText('Vendor name *')[0], { target: { value: 'Custoking Supplies' } });
  fireEvent.change(screen.getAllByLabelText(/Quoted amount/)[0], { target: { value: '1200' } });
  fireEvent.change(screen.getByLabelText('Document reference for quotation 1 (optional)'), { target: { value: 'School drive / Quotes / Lab-42.pdf' } });
}

describe('Urgent procurement document references and recovery', () => {
  beforeEach(() => {
    vi.resetAllMocks(); localStorage.clear();
    vi.stubGlobal('navigator', Object.assign(Object.create(navigator), { locks: { request: vi.fn(async (_name: string, _options: unknown, callback: (lock: unknown) => unknown) => callback({})) } }));
    vi.mocked(api.patch).mockResolvedValue({ data: {} }); loadDraft(savedDraft, disabledFiles);
  });
  afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

  it('saves an honest document reference on an existing draft and does not claim an upload or vendor recommendation', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: savedDraft });
    vi.mocked(api.post).mockResolvedValue({ data: { id: 'quote-1', requestId: 'FF-42' } });
    const onRefresh = vi.fn().mockResolvedValue(undefined);
    const setPanel = vi.fn();
    const { container } = render(<FirefightingNewPanel editingCode="FF-42" onRefresh={onRefresh} setPanel={setPanel} />);
    await screen.findByLabelText('Document reference for quotation 1 (optional)');
    fillQuotation();
    expect(container.querySelector('input[type="file"]')).toBeNull();
    expect(screen.getAllByText(/Only this reference is saved/)).toHaveLength(3);
    fireEvent.click(screen.getByRole('button', { name: /Next.*review/ }));
    expect(screen.getByText('School drive / Quotes / Lab-42.pdf')).toBeInTheDocument();
    expect(screen.queryByText(/auto-recommended|Our quote|uploaded/)).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Back to quotations/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Save as draft' }));
    await waitFor(() => expect(setPanel).toHaveBeenCalledWith('ff-dashboard'));
    expect(api.post).toHaveBeenCalledWith('/ff/requests/FF-42/quotations', expect.objectContaining({ documentUrl: 'School drive / Quotes / Lab-42.pdf', vendorName: 'Custoking Supplies', amount: 1200 }));
    expect(vi.mocked(api.post).mock.calls.some(([url]) => url.endsWith('/submit'))).toBe(false);
  });

  it('keeps the created request and confirmed quote on a failed submission retry', async () => {
    let submissionCount = 0;
    vi.mocked(api.post).mockImplementation(url => {
      if (url === '/ff/requests') return Promise.resolve({ data: { code: 'FF-42' } });
      if (url.endsWith('/quotations')) return Promise.resolve({ data: { id: 'quote-1', requestId: 'FF-42' } });
      if (url.endsWith('/submit') && submissionCount++ === 0) return Promise.reject(new Error('offline'));
      return Promise.resolve({ data: { code: 'FF-42', status: 'AWAITING_BURSAR' } });
    });
    const setPanel = vi.fn();
    render(<FirefightingNewPanel editingCode={null} onRefresh={vi.fn().mockResolvedValue(undefined)} setPanel={setPanel} />);
    fireEvent.change(screen.getByLabelText('Request title *'), { target: { value: 'Lab benches' } });
    fireEvent.click(screen.getByRole('button', { name: /Next.*add quotations/ }));
    fillQuotation();
    fireEvent.click(screen.getByRole('button', { name: /Next.*review/ }));
    fireEvent.click(screen.getByRole('button', { name: /Submit for approval/ }));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Confirmed quotations are kept'));
    expect(setPanel).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Recover saved result' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Recover saved result' })).not.toBeInTheDocument());
    expect(screen.getByText('School drive / Quotes / Lab-42.pdf')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Submit for approval/ })).toBeDisabled();
    const calls = vi.mocked(api.post).mock.calls.map(([url]) => url);
    expect(calls.filter(url => url === '/ff/requests')).toHaveLength(1);
    expect(calls.filter(url => url.endsWith('/quotations'))).toHaveLength(1);
    expect(calls.filter(url => url.endsWith('/submit'))).toHaveLength(2);
  });

  it('blocks editing after a load failure and provides a successful retry', async () => {
    let attempts = 0;
    vi.mocked(api.get).mockImplementation(url => url.endsWith('/capabilities') ? Promise.resolve({ data: disabledFiles }) : attempts++ === 0 ? Promise.reject(new Error('offline')) : Promise.resolve({ data: savedDraft }));
    render(<FirefightingNewPanel editingCode="FF-42" onRefresh={vi.fn()} setPanel={vi.fn()} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('before editing');
    expect(screen.queryByRole('button', { name: 'Save as draft' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Retry opening draft' }));
    expect(await screen.findByLabelText('Document reference for quotation 1 (optional)')).toBeInTheDocument();
    expect(api.patch).not.toHaveBeenCalled();
  });

  it('replays a lost request creation response with its exact original key and payload after remount', async () => {
    vi.mocked(api.post).mockRejectedValueOnce(new Error('response lost')).mockResolvedValue({ data: { code: 'FF-42' } });
    const props = { editingCode: null, onRefresh: vi.fn(), setPanel: vi.fn() };
    const first = render(<FirefightingNewPanel {...props} />);
    fireEvent.change(screen.getByLabelText('Request title *'), { target: { value: 'Lab benches' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save as draft' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Recover saved result' })).toBeEnabled());
    expect(screen.queryByLabelText('Request title *')).not.toBeInTheDocument();
    const original = vi.mocked(api.post).mock.calls[0][1];
    expect(original).toMatchObject({ title: 'Lab benches', idempotencyKey: expect.any(String) });
    expect(JSON.parse(localStorage.getItem(recoveryKey)!).pending.payload).toEqual(original);
    first.unmount();
    render(<FirefightingNewPanel {...props} />);
    fireEvent.click(screen.getByRole('button', { name: 'Recover saved result' }));
    await screen.findByLabelText('Document reference for quotation 1 (optional)');
    expect(vi.mocked(api.post).mock.calls).toEqual([['/ff/requests', original], ['/ff/requests', original]]);
    expect(JSON.parse(localStorage.getItem(recoveryKey)!)).toMatchObject({ code: 'FF-42', pending: null });
    expect(api.patch).not.toHaveBeenCalled();
  });

  it('recovers a lost quotation response once and retains its original payload instead of adding a duplicate on save', async () => {
    let creates = 0;
    vi.mocked(api.post).mockImplementation(() => ++creates === 1 ? Promise.reject(new Error('lost response')) : Promise.resolve({ data: { id: 'quote-1', requestId: 'FF-42' } }));
    const setPanel = vi.fn();
    render(<FirefightingNewPanel editingCode="FF-42" onRefresh={vi.fn().mockResolvedValue(undefined)} setPanel={setPanel} />);
    await screen.findByLabelText('Document reference for quotation 1 (optional)');
    fillQuotation();
    fireEvent.click(screen.getByRole('button', { name: 'Save as draft' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Recover saved result' })).toBeEnabled());
    const original = vi.mocked(api.post).mock.calls[0][1];
    fireEvent.click(screen.getByRole('button', { name: 'Recover saved result' }));
    await screen.findByText('Custoking Supplies');
    expect(vi.mocked(api.post).mock.calls[1][1]).toEqual(original);
    expect(screen.getAllByLabelText('Vendor name *')[0]).toHaveValue('');
    fireEvent.click(screen.getByRole('button', { name: 'Save as draft' }));
    await waitFor(() => expect(setPanel).toHaveBeenCalledWith('ff-dashboard'));
    expect(api.post).toHaveBeenCalledTimes(2);
  });

  it('reconciles a lost submission response against current status without submitting twice', async () => {
    let committed = false;
    vi.mocked(api.get).mockImplementation(url => Promise.resolve({ data: url.endsWith('/capabilities') ? disabledFiles : { ...savedDraft, status: committed ? 'AWAITING_PRINCIPAL' : 'DRAFT' } }));
    vi.mocked(api.post).mockImplementation(() => { committed = true; return Promise.reject(new Error('lost committed response')); });
    render(<FirefightingNewPanel editingCode="FF-42" onRefresh={vi.fn()} setPanel={vi.fn()} />);
    await screen.findByLabelText('Document reference for quotation 1 (optional)');
    fireEvent.click(screen.getByRole('button', { name: /Next.*review/ }));
    fireEvent.click(screen.getByRole('button', { name: /Submit for approval/ }));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Submission is confirmed. Current status: AWAITING_PRINCIPAL'));
    expect(api.post).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: /Submit for approval/ })).toBeDisabled();
    expect(localStorage.getItem('ck:procurement-save:v1:7:10:FF-42')).toBeNull();
  });

  it('does not send a creation when its recovery record cannot be persisted', async () => {
    render(<FirefightingNewPanel editingCode={null} onRefresh={vi.fn()} setPanel={vi.fn()} />);
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota exceeded'); });
    fireEvent.change(screen.getByLabelText('Request title *'), { target: { value: 'Lab benches' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save as draft' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('This save was not sent');
    expect(api.post).not.toHaveBeenCalled();
  });

  it('preserves another tab recovery record and sends no mutation from a stale form', async () => {
    render(<FirefightingNewPanel editingCode="FF-42" onRefresh={vi.fn()} setPanel={vi.fn()} />);
    await screen.findByLabelText('Document reference for quotation 1 (optional)');
    const otherTab = JSON.stringify({ version: 1, code: 'FF-42', pending: { kind: 'quotation', code: 'FF-42', payload: { vendorName: 'Other tab vendor', amount: 100, idempotencyKey: 'other-tab-key' } } });
    const key = 'ck:procurement-save:v1:7:10:FF-42';
    localStorage.setItem(key, otherTab);
    fireEvent.click(screen.getByRole('button', { name: 'Save as draft' }));
    await screen.findByRole('alert');
    expect(api.patch).not.toHaveBeenCalled();
    expect(api.post).not.toHaveBeenCalled();
    expect(localStorage.getItem(key)).toBe(otherTab);
  });

  it('keeps a malformed submit success unconfirmed until the server returns a matching submitted status', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: {} });
    render(<FirefightingNewPanel editingCode="FF-42" onRefresh={vi.fn()} setPanel={vi.fn()} />);
    await screen.findByLabelText('Document reference for quotation 1 (optional)');
    fireEvent.click(screen.getByRole('button', { name: /Next.*review/ }));
    fireEvent.click(screen.getByRole('button', { name: /Submit for approval/ }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Recover saved result' })).toBeEnabled());
    expect(screen.getByRole('alert')).toHaveTextContent('unconfirmed');
    expect(JSON.parse(localStorage.getItem('ck:procurement-save:v1:7:10:FF-42')!).pending.kind).toBe('submit');
    loadDraft({ ...savedDraft, status: 'AWAITING_BURSAR' }, disabledFiles);
    fireEvent.click(screen.getByRole('button', { name: 'Recover saved result' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Recover saved result' })).not.toBeInTheDocument());
    expect(api.post).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: /Submit for approval/ })).toBeDisabled();
  });

  it('blocks corrupted recovery and never consumes another school or user recovery entry', async () => {
    localStorage.setItem('ck:procurement-save:v1:88:20:new', JSON.stringify({ version: 1, code: null, pending: { kind: 'request', code: null, payload: { title: 'Private vendor details', idempotencyKey: 'other-user-key' } } }));
    const first = render(<FirefightingNewPanel editingCode={null} onRefresh={vi.fn()} setPanel={vi.fn()} />);
    expect(screen.getByLabelText('Request title *')).toHaveValue('');
    expect(screen.queryByText(/Private vendor details/)).not.toBeInTheDocument();
    first.unmount();
    localStorage.setItem(recoveryKey, '{damaged');
    render(<FirefightingNewPanel editingCode={null} onRefresh={vi.fn()} setPanel={vi.fn()} />);
    expect(screen.getByRole('alert')).toHaveTextContent('could not be read safely');
    expect(screen.queryByLabelText('Request title *')).not.toBeInTheDocument();
    expect(api.post).not.toHaveBeenCalled();
  });

  it('keeps an incomplete quotation visible and explains the missing amount', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: savedDraft });
    render(<FirefightingNewPanel editingCode="FF-42" onRefresh={vi.fn()} setPanel={vi.fn()} />);
    await screen.findByLabelText('Document reference for quotation 1 (optional)');
    fireEvent.change(screen.getAllByLabelText('Vendor name *')[0], { target: { value: 'Vendor' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save as draft' }));
    expect(screen.getByRole('alert')).toHaveTextContent('amount greater than zero');
    expect(screen.getAllByLabelText('Vendor name *')[0]).toHaveValue('Vendor');
    expect(api.post).not.toHaveBeenCalled();
  });

  it('keeps invalid amounts editable before persisting a replay record or sending a request', async () => {
    render(<FirefightingNewPanel editingCode={null} onRefresh={vi.fn()} setPanel={vi.fn()} />);
    fireEvent.change(screen.getByLabelText('Request title *'), { target: { value: 'Lab benches' } });
    fireEvent.change(screen.getByLabelText(/Estimated budget/), { target: { value: '1.25' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save as draft' }));
    expect(screen.getByRole('alert')).toHaveTextContent('whole number');
    expect(localStorage.getItem(recoveryKey)).toBeNull();
    expect(api.post).not.toHaveBeenCalled();
    expect(screen.getByLabelText(/Estimated budget/)).toBeEnabled();
  });

  it('uploads a selected file privately after creating its quotation, retaining the quote on upload retry', async () => {
    loadDraft();
    const setPanel = vi.fn();
    let uploads = 0;
    vi.mocked(api.post).mockImplementation(url => {
      if (url.endsWith('/quotations')) return Promise.resolve({ data: { id: 'quote-1', requestId: 'FF-42' } });
      if (url.endsWith('/document') && uploads++ === 0) return Promise.reject(new Error('upload timeout'));
      return Promise.resolve({ data: { id: 'document-1', filename: 'vendor.pdf', contentType: 'application/pdf', sizeBytes: 9, uploadedAt: '2026-09-26T10:00:00Z' } });
    });
    render(<FirefightingNewPanel editingCode="FF-42" onRefresh={vi.fn().mockResolvedValue(undefined)} setPanel={setPanel} />);
    await screen.findByLabelText('Quotation file 1 (optional)');
    fillQuotation();
    const file = new File(['%PDF-test'], 'vendor.pdf', { type: 'application/pdf' });
    fireEvent.change(screen.getByLabelText('Quotation file 1 (optional)'), { target: { files: [file] } });
    expect(screen.getByText(/vendor.pdf selected · not uploaded yet/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Save as draft' }));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('A file upload may also have completed'));
    expect(screen.getByText(/selected, not yet confirmed uploaded/)).toBeInTheDocument();
    expect(setPanel).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Save as draft' }));
    await waitFor(() => expect(setPanel).toHaveBeenCalledWith('ff-dashboard'));
    const calls = vi.mocked(api.post).mock.calls;
    expect(calls.filter(([url]) => url.endsWith('/quotations'))).toHaveLength(1);
    expect(calls.filter(([url]) => url === '/ff/requests/FF-42/quotations/quote-1/document')).toHaveLength(2);
    expect((calls.find(([url]) => url.endsWith('/document'))![1] as FormData).get('file')).toBe(file);
    expect(screen.getByText('Attached:')).toBeInTheDocument();
  });

  it('shows unavailable storage honestly and rejects unsupported file selections', async () => {
    loadDraft(savedDraft, disabledFiles);
    const { rerender } = render(<FirefightingNewPanel editingCode="FF-42" onRefresh={vi.fn()} setPanel={vi.fn()} />);
    expect(await screen.findByText(/Private quotation storage is not configured/)).toBeInTheDocument();
    expect(screen.queryByLabelText('Quotation file 1 (optional)')).not.toBeInTheDocument();
    loadDraft();
    fireEvent.click(screen.getByRole('button', { name: 'Retry file storage' }));
    const input = await screen.findByLabelText('Quotation file 1 (optional)');
    fireEvent.change(input, { target: { files: [new File(['<script/>'], 'quote.svg', { type: 'image/svg+xml' })] } });
    expect(screen.getByRole('alert')).toHaveTextContent('PDF, PNG, or JPEG');
    expect(api.post).not.toHaveBeenCalled();
    rerender(<FirefightingNewPanel editingCode="FF-42" onRefresh={vi.fn()} setPanel={vi.fn()} />);
  });

  it('downloads an existing file through the authenticated API and removes only after server confirmation', async () => {
    const metadata = { id: 'document-1', filename: 'vendor.pdf', contentType: 'application/pdf', sizeBytes: 12, uploadedAt: '2026-09-26T10:00:00Z' };
    const draft = { ...savedDraft, quotations: [{ id: 'quote-1', vendorName: 'Vendor', amount: 1200, document: metadata }] };
    loadDraft(draft);
    const createObjectURL = vi.fn().mockReturnValue('blob:private-file');
    const revokeObjectURL = vi.fn();
    vi.stubGlobal('URL', class extends URL { static createObjectURL = createObjectURL; static revokeObjectURL = revokeObjectURL; });
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    render(<FirefightingNewPanel editingCode="FF-42" onRefresh={vi.fn()} setPanel={vi.fn()} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Download file' }));
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/ff/requests/FF-42/quotations/quote-1/document', { responseType: 'blob' }));
    await waitFor(() => expect(click).toHaveBeenCalledOnce());
    vi.mocked(api.delete).mockResolvedValue({ data: undefined });
    fireEvent.click(screen.getByRole('button', { name: 'Remove file' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Download file' })).not.toBeInTheDocument());
    expect(api.delete).toHaveBeenCalledWith('/ff/requests/FF-42/quotations/quote-1/document');
    click.mockRestore();
  });
});

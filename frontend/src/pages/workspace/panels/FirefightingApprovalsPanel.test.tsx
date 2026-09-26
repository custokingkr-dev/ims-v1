import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import api from '../../../services/api';
import { FirefightingApprovalsPanel } from './FirefightingApprovalsPanel';

vi.mock('../../../services/api');
vi.mock('../../../hooks/usePermissions', () => ({ usePermissions: () => ({ can: () => true }) }));

const quotation = { id: 'q-1', vendorName: 'Vendor', amount: 100, documentUrl: '',
  document: { id: 'doc-1', filename: 'vendor.pdf', contentType: 'application/pdf', sizeBytes: 9, uploadedAt: '2026-09-26T10:00:00Z' } };
const request = { code: 'FF-42', title: 'Lab benches', status: 'AWAITING_BURSAR', category: 'Equipment', urgency: 'MEDIUM', quotations: [quotation] };

describe('Approval quotation attachments', () => {
  beforeEach(() => { vi.resetAllMocks(); });
  afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

  it('downloads private files using the authenticated API, then releases the local object URL', async () => {
    const blob = new Blob(['quotation'], { type: 'application/pdf' });
    vi.mocked(api.get).mockImplementation(url => Promise.resolve({ data: url.endsWith('/document') ? blob : url.endsWith('/pending-approvals') ? [request] : request }));
    const createObjectURL = vi.fn().mockReturnValue('blob:private-quotation');
    const revokeObjectURL = vi.fn();
    vi.stubGlobal('URL', class extends URL { static createObjectURL = createObjectURL; static revokeObjectURL = revokeObjectURL; });
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      expect(this.download).toBe('vendor.pdf');
      expect(this.href).toBe('blob:private-quotation');
    });
    render(<FirefightingApprovalsPanel isSuperAdmin={false} onRefresh={vi.fn()} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Download vendor.pdf' }));
    await waitFor(() => expect(click).toHaveBeenCalledOnce());
    expect(api.get).toHaveBeenCalledWith('/ff/requests/FF-42/quotations/q-1/document', { responseType: 'blob' });
    expect(createObjectURL).toHaveBeenCalledWith(blob);
    expect(document.querySelector('a[href="blob:private-quotation"]')).toBeNull();
    await waitFor(() => expect(revokeObjectURL).toHaveBeenCalledWith('blob:private-quotation'), { timeout: 2000 });
  });

  it('keeps a failed download actionable and distinguishes unavailable storage from an attached reference', async () => {
    vi.mocked(api.get).mockImplementation(url => url.endsWith('/document')
      ? Promise.reject({ response: { status: 503 } })
      : Promise.resolve({ data: url.endsWith('/pending-approvals') ? [request] : request }));
    render(<FirefightingApprovalsPanel isSuperAdmin={false} onRefresh={vi.fn()} />);
    const button = await screen.findByRole('button', { name: 'Download vendor.pdf' });
    fireEvent.click(button);
    expect(await screen.findByRole('alert')).toHaveTextContent('Private file storage is temporarily unavailable');
    expect(button).toHaveTextContent('Retry download');
    expect(button).not.toBeDisabled();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });

  it('shows legacy references as references without inventing an attachment link', async () => {
    const legacy = { ...request, quotations: [{ ...quotation, document: null, documentUrl: 'School drive / vendor.pdf' }] };
    vi.mocked(api.get).mockImplementation(url => Promise.resolve({ data: url.endsWith('/pending-approvals') ? [legacy] : legacy }));
    render(<FirefightingApprovalsPanel isSuperAdmin={false} onRefresh={vi.fn()} />);
    expect(await screen.findByText('Reference: School drive / vendor.pdf')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Download/ })).not.toBeInTheDocument();
  });
});

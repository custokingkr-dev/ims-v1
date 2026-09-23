import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import api from '../../services/api';
import { ProductFormBuilder } from './ProductFormBuilder';
import { flexDefinition, notebookDefinition, savedNotebook } from './catalogTestFixtures';
vi.mock('../../services/api', () => ({ default: { post: vi.fn(), patch: vi.fn(), get: vi.fn() } }));
vi.mock('../../hooks/usePermissions', () => ({ usePermissions: () => ({ can: () => true }) }));
beforeEach(() => { vi.clearAllMocks(); URL.createObjectURL = vi.fn(() => 'blob:artwork'); URL.revokeObjectURL = vi.fn(); });
afterEach(cleanup);
describe('ProductFormBuilder', () => {
  it('renders typed inputs and hides the page count for a non-paged category', () => {
    render(<ProductFormBuilder categoryCode="FLEX" definition={flexDefinition} preview />);
    // A typed group is a free entry with its unit shown, not a dropdown of options.
    const length = screen.getByLabelText('Length for line 1');
    expect(length).toHaveAttribute('type', 'number');
    expect(screen.getAllByText('ft').length).toBeGreaterThan(0);
    // Selections still render as a dropdown.
    expect(within(screen.getByLabelText('Type of flex for line 1')).getAllByRole('option').length).toBeGreaterThan(1);
    // Flex has no page concept, so the notebook page field must not appear.
    expect(screen.queryByLabelText('Printed pages for line 1')).not.toBeInTheDocument();
    fireEvent.change(length, { target: { value: '6.5' } });
    expect(length).toHaveValue(6.5);
  });

  it('shows every ruling and disabled incomplete sizes, aggregates all lines, and preserves requested pages', () => {
    render(<ProductFormBuilder definition={notebookDefinition} preview />);
    expect(within(screen.getByLabelText('Ruling for line 1')).getAllByRole('option')).toHaveLength(16);
    expect(screen.getByRole('option', { name: 'King - Specification pending from Custoking' })).toBeDisabled();
    expect(screen.getByRole('option', { name: 'Drawing book - Specification pending from Custoking' })).toBeDisabled();
    expect(screen.getAllByRole('option', { name: 'FA / A4 notebook' })).toHaveLength(1);
    fireEvent.change(screen.getByLabelText('Books for line 1'), { target: { value: '400' } });
    fireEvent.change(screen.getByLabelText('Printed pages for line 1'), { target: { value: '198' } });
    fireEvent.blur(screen.getByLabelText('Printed pages for line 1'));
    expect(screen.getByText('Rounded from 198 to 196')).toBeInTheDocument();
    expect(screen.getByLabelText('Printed pages for line 1')).toHaveValue(198);
    fireEvent.click(screen.getByRole('button', { name: 'Add line' }));
    fireEvent.change(screen.getByLabelText('Size for line 2'), { target: { value: 'JUMBO_LONG' } });
    fireEvent.change(screen.getByLabelText('Books for line 2'), { target: { value: '600' } });
    expect(screen.getAllByText('All sizes combined')).toHaveLength(1);
    expect(screen.getByText('Required total met')).toBeInTheDocument();
    expect(screen.getByText('Pending pricing')).toBeInTheDocument();
    expect(screen.queryByLabelText(/price|GST/i)).not.toBeInTheDocument();
  });
  it('saves an incomplete aggregate as draft and retries placement using that same id', async () => {
    let detail = savedNotebook('DRAFT', false);
    let places = 0;
    vi.mocked(api.get).mockImplementation(async () => ({ data: detail }));
    vi.mocked(api.patch).mockImplementation(async () => ({ data: detail }));
    vi.mocked(api.post).mockImplementation(async (url) => {
      if (url === '/supply/orders') return { data: { id: 'ORD-42', formVersion: 2, version: 0 } };
      if (String(url).endsWith('/place')) { places++; if (places === 1) throw new Error('Temporary failure'); detail = savedNotebook('PROCESSING', false); }
      return { data: {} };
    });
    render(<ProductFormBuilder definition={notebookDefinition} schoolId={7} />);
    fireEvent.change(screen.getByLabelText('Customization'), { target: { value: 'NON_CUSTOMIZED' } });
    fireEvent.change(screen.getByLabelText('Books for line 1'), { target: { value: '37' } });
    fireEvent.click(screen.getByRole('button', { name: 'Place order' }));
    expect(await screen.findByText('Temporary failure')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Place order' })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: 'Place order' }));
    expect(await screen.findByText(/Order placed. Processing and pricing are pending/)).toBeInTheDocument();
    expect(vi.mocked(api.post).mock.calls.filter(([url]) => url === '/supply/orders')).toHaveLength(1);
    expect(api.patch).toHaveBeenCalledWith('/supply/orders/ORD-42', expect.objectContaining({ version: 0 }));
    expect(vi.mocked(api.post).mock.calls[0][1]).toEqual(expect.objectContaining({ schoolId: 7, status: 'DRAFT', orderData: expect.objectContaining({ orderSelections: { CUSTOMIZATION: 'NON_CUSTOMIZED' } }) }));
    expect(vi.mocked(api.post).mock.calls[0][1]).not.toHaveProperty('totalAmount');
  });
  it('allows a customized draft without artwork or a complete total, and reopens requested selections', async () => {
    const detail = savedNotebook();
    vi.mocked(api.post).mockResolvedValue({ data: { id: detail.order.id, version: 0 } });
    vi.mocked(api.get).mockResolvedValue({ data: detail });
    const view = render(<ProductFormBuilder definition={notebookDefinition} />);
    fireEvent.change(screen.getByLabelText('Books for line 1'), { target: { value: '400' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    expect(await screen.findByText('Draft ORD-42 saved.')).toBeInTheDocument();
    expect(api.post).toHaveBeenCalledTimes(1);
    view.unmount(); render(<ProductFormBuilder definition={notebookDefinition} initialOrder={detail} />);
    expect(screen.getByLabelText('Printed pages for line 1')).toHaveValue(198);
    expect(screen.getByLabelText('Books for line 1')).toHaveValue(1000);
    fireEvent.click(screen.getByRole('button', { name: 'Place order' }));
    expect(await screen.findByText('Attach design artwork before placing this order.')).toBeInTheDocument();
  });
});

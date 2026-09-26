import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import api from '../../services/api';
import { ProductFormBuilder } from './ProductFormBuilder';
import { flexDefinition, notebookDefinition, reportCardDefinition, savedNotebook, tieDefinition } from './catalogTestFixtures';
vi.mock('../../services/api', () => ({ default: { post: vi.fn(), patch: vi.fn(), get: vi.fn() } }));
vi.mock('../../hooks/usePermissions', () => ({ usePermissions: () => ({ can: () => true }) }));
beforeEach(() => { vi.clearAllMocks(); URL.createObjectURL = vi.fn(() => 'blob:artwork'); URL.revokeObjectURL = vi.fn(); });
afterEach(cleanup);
describe('ProductFormBuilder in the prototype format', () => {
  // The notebook prototype picks Category and Size once, then shows every ruling as a row with its
  // own quantity, and "Add to order" moves the non-zero rows into the order.
  it('picks context once and adds the non-zero matrix rows as lines', () => {
    render(<ProductFormBuilder definition={notebookDefinition} schoolId={7} preview />);
    // A SEGMENTED group is a row of buttons, not a dropdown.
    expect(screen.getByRole('button', { name: 'Custom' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Wholesale' })).toBeInTheDocument();
    expect(screen.queryByRole('combobox', { name: 'Category' })).not.toBeInTheDocument();
    // A SELECT group stays a dropdown.
    fireEvent.change(screen.getByLabelText('Size'), { target: { value: 'LONG' } });
    // The MATRIX group is one row per option, all fifteen of them.
    expect(screen.getAllByRole('spinbutton', { name: /Quantity for / })).toHaveLength(15);

    fireEvent.change(screen.getByLabelText('Quantity for Single rule'), { target: { value: '1000' } });
    fireEvent.change(screen.getByLabelText('Quantity for Ruling 1'), { target: { value: '400' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add to order' }));

    // Two rows had a quantity; the other thirteen are skipped.
    const order = screen.getByRole('region', { name: 'Order' });
    expect(within(order).getAllByRole('listitem')).toHaveLength(2);
    expect(within(order).getByText('Single rule')).toBeInTheDocument();
    expect(within(order).queryByText('Ruling 2')).not.toBeInTheDocument();
  });

  it('steps a line quantity up and down and removes it', () => {
    render(<ProductFormBuilder definition={notebookDefinition} schoolId={7} preview />);
    fireEvent.change(screen.getByLabelText('Size'), { target: { value: 'LONG' } });
    fireEvent.change(screen.getByLabelText('Quantity for Single rule'), { target: { value: '10' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add to order' }));
    const order = screen.getByRole('region', { name: 'Order' });
    fireEvent.click(within(order).getByRole('button', { name: 'Increase Single rule' }));
    expect(within(order).getByLabelText('Ordered quantity for Single rule')).toHaveValue(11);
    fireEvent.click(within(order).getByRole('button', { name: 'Decrease Single rule' }));
    expect(within(order).getByLabelText('Ordered quantity for Single rule')).toHaveValue(10);
    fireEvent.click(within(order).getByRole('button', { name: 'Remove Single rule' }));
    expect(within(order).queryByRole('listitem')).not.toBeInTheDocument();
  });

  // Flex has no matrix: the prototype builds one line at a time from typed measurements.
  it('adds a single line from typed fields when there is no matrix group', () => {
    render(<ProductFormBuilder definition={flexDefinition} schoolId={7} preview />);
    expect(screen.getByRole('button', { name: 'Star flex' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Add to order' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Star flex' }));
    fireEvent.change(screen.getByLabelText('Length'), { target: { value: '6' } });
    fireEvent.change(screen.getByLabelText('Breadth'), { target: { value: '3' } });
    fireEvent.change(screen.getByLabelText('Count'), { target: { value: '4' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    const order = screen.getByRole('region', { name: 'Order' });
    expect(within(order).getAllByRole('listitem')).toHaveLength(1);
    expect(within(order).getByRole('listitem')).toHaveTextContent('Star flex');
  });
});

describe('ProductFormBuilder, 2026-09-25 product decisions', () => {
  // A wholesale notebook run is printed from stock, so there is no artwork to attach. The upload
  // belongs to the customised choice only.
  it('offers the sample design for a customised notebook and not for a wholesale one', () => {
    render(<ProductFormBuilder definition={notebookDefinition} preview />);
    // Custom is the first confirmed option, so the upload is there on arrival.
    expect(screen.getByRole('heading', { name: 'Sample design' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Wholesale' }));
    expect(screen.queryByRole('heading', { name: 'Sample design' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Custom' }));
    expect(screen.getByRole('heading', { name: 'Sample design' })).toBeInTheDocument();
  });

  // Report cards are the only category that shows a school a price, and it is computed, not typed.
  it('shows a computed estimate and offers no way to type a price', () => {
    render(<ProductFormBuilder definition={reportCardDefinition} preview />);
    expect(screen.queryByText(/Estimated total/)).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'A4' }));
    fireEvent.click(screen.getByRole('button', { name: '8' }));
    fireEvent.click(screen.getByRole('button', { name: 'Yes' }));
    fireEvent.change(screen.getByLabelText('Count'), { target: { value: '100' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));

    // (16*100) + (14*100*2) + 900 = 5300, with D9 excluded.
    expect(screen.getByText(/Estimated total/)).toBeInTheDocument();
    expect(screen.getByText(/5,300/)).toBeInTheDocument();
    expect(screen.getByText(/the Custoking quote is the price of record/i)).toBeInTheDocument();
    // No price entry anywhere on the school's form.
    expect(screen.queryByLabelText(/price|cost/i)).not.toBeInTheDocument();
  });

  // Ties have no order-scope group: the segmented choice is per line, above the length matrix.
  it('drives a line-scoped segmented choice into the matrix rows', () => {
    render(<ProductFormBuilder definition={tieDefinition} preview />);
    expect(screen.getByRole('group', { name: 'Tie type' })).toBeInTheDocument();
    expect(screen.getAllByRole('spinbutton', { name: /Count for / })).toHaveLength(6);
    // No page column on a category that counts units.
    expect(screen.queryByLabelText(/Pages for/)).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Cloth Tie' }));
    fireEvent.change(screen.getByLabelText('Count for Long Tie'), { target: { value: '12' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add to order' }));
    const order = screen.getByRole('region', { name: 'Order' });
    expect(within(order).getAllByRole('listitem')).toHaveLength(1);
    expect(within(order).getByText('Long Tie')).toBeInTheDocument();
    expect(within(order).getByText('Cloth Tie')).toBeInTheDocument();
  });

  it('shows the note on a category that collects one and nowhere else', () => {
    const { unmount } = render(<ProductFormBuilder definition={notebookDefinition} preview />);
    expect(screen.queryByLabelText('Notes')).not.toBeInTheDocument();
    unmount();
    render(<ProductFormBuilder definition={{ ...flexDefinition, category: { ...flexDefinition.category, notesEnabled: true } }} preview />);
    expect(screen.getByLabelText('Notes')).toBeInTheDocument();
  });

  it('places a customised order with no artwork attached', async () => {
    // The design upload stays, but it no longer blocks placement.
    const withoutArtworkRule = {
      ...notebookDefinition,
      rules: notebookDefinition.rules.filter((rule) => !(rule.ruleType === 'REQUIRE_ASSET' && rule.params.assetKind === 'DESIGN')),
    };
    const detail = savedNotebook('PROCESSING');
    vi.mocked(api.post).mockImplementation(async (url) => {
      if (url === '/catalog/orders') return { data: { id: 'ORD-42', formVersion: 2, version: 0 } };
      return { data: {} };
    });
    vi.mocked(api.get).mockResolvedValue({ data: detail });
    vi.mocked(api.patch).mockResolvedValue({ data: detail });
    render(<ProductFormBuilder definition={withoutArtworkRule} schoolId={7} />);
    fireEvent.change(screen.getByLabelText('Size'), { target: { value: 'LONG' } });
    fireEvent.change(screen.getByLabelText('Quantity for Single rule'), { target: { value: '1000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add to order' }));
    fireEvent.click(screen.getByRole('button', { name: 'Place order' }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/catalog/orders/ORD-42/place'));
    expect(screen.queryByText('Attach design artwork before placing this order.')).not.toBeInTheDocument();
  });
});

describe('ProductFormBuilder', () => {
  it('renders typed entries with their unit and no page column for a non-paged category', () => {
    render(<ProductFormBuilder categoryCode="FLEX" definition={flexDefinition} preview />);
    const length = screen.getByLabelText('Length');
    expect(length).toHaveAttribute('type', 'number');
    expect(screen.getAllByText('ft').length).toBeGreaterThan(0);
    // A short choice is a row of buttons, as the flex prototype shows.
    expect(screen.getByRole('group', { name: 'Type of flex' })).toBeInTheDocument();
    // Flex has no page concept, so no page entry appears anywhere.
    expect(screen.queryByLabelText(/pages/i)).not.toBeInTheDocument();
    fireEvent.change(length, { target: { value: '6.5' } });
    expect(length).toHaveValue(6.5);
  });

  it('disables sizes with no agreed spec, snaps pages and aggregates every added line', () => {
    render(<ProductFormBuilder definition={notebookDefinition} preview />);
    expect(screen.getByRole('option', { name: 'King - Specification pending from Custoking' })).toBeDisabled();
    expect(screen.getByRole('option', { name: 'Drawing book - Specification pending from Custoking' })).toBeDisabled();
    expect(screen.getAllByRole('option', { name: 'FA / A4 notebook' })).toHaveLength(1);

    fireEvent.change(screen.getByLabelText('Size'), { target: { value: 'LONG' } });
    fireEvent.change(screen.getByLabelText('Quantity for Single rule'), { target: { value: '400' } });
    fireEvent.change(screen.getByLabelText('Pages for Single rule'), { target: { value: '198' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add to order' }));
    // The requested page count is kept and the snapped one is shown beside it.
    expect(screen.getByText('Rounded from 198 to 196')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Size'), { target: { value: 'JUMBO_LONG' } });
    fireEvent.change(screen.getByLabelText('Quantity for Single rule'), { target: { value: '600' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add to order' }));
    expect(screen.getAllByText('All sizes combined')).toHaveLength(1);
    expect(screen.getByText('Required total met')).toBeInTheDocument();
    expect(screen.getByText('Pending pricing')).toBeInTheDocument();
    expect(screen.queryByLabelText(/price|GST/i)).not.toBeInTheDocument();
  });

  it('files the order under its own category rather than defaulting to notebooks', async () => {
    vi.mocked(api.post).mockImplementation(async (url) => {
      if (url === '/catalog/orders') return { data: { id: 'ORD-9', formVersion: 1, version: 0 } };
      return { data: {} };
    });
    vi.mocked(api.get).mockImplementation(async () => ({ data: savedNotebook('DRAFT', false) }));
    vi.mocked(api.patch).mockImplementation(async () => ({ data: savedNotebook('DRAFT', false) }));
    // No categoryCode prop: the definition already names the category, and defaulting to
    // NOTEBOOKS filed every flex, flier, bill book and belt order as a notebook order.
    render(<ProductFormBuilder definition={flexDefinition} schoolId={7} />);
    fireEvent.click(screen.getByRole('button', { name: 'Star flex' }));
    fireEvent.change(screen.getByLabelText('Length'), { target: { value: '6' } });
    fireEvent.change(screen.getByLabelText('Breadth'), { target: { value: '3' } });
    fireEvent.change(screen.getByLabelText('Count'), { target: { value: '4' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/catalog/orders', expect.objectContaining({ category: 'FLEX' })));
  });

  it('saves an incomplete aggregate as draft and retries placement using that same id', async () => {
    let detail = savedNotebook('DRAFT', false);
    let places = 0;
    vi.mocked(api.get).mockImplementation(async () => ({ data: detail }));
    vi.mocked(api.patch).mockImplementation(async () => ({ data: detail }));
    vi.mocked(api.post).mockImplementation(async (url) => {
      if (url === '/catalog/orders') return { data: { id: 'ORD-42', formVersion: 2, version: 0 } };
      if (String(url).endsWith('/place')) { places++; if (places === 1) throw new Error('Temporary failure'); detail = savedNotebook('PROCESSING', false); }
      return { data: {} };
    });
    render(<ProductFormBuilder definition={notebookDefinition} schoolId={7} />);
    fireEvent.click(screen.getByRole('button', { name: 'Wholesale' }));
    fireEvent.change(screen.getByLabelText('Size'), { target: { value: 'LONG' } });
    fireEvent.change(screen.getByLabelText('Quantity for Single rule'), { target: { value: '37' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add to order' }));
    fireEvent.click(screen.getByRole('button', { name: 'Place order' }));
    expect(await screen.findByText('Temporary failure')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Place order' })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: 'Place order' }));
    expect(await screen.findByText(/Order placed. Processing and pricing are pending/)).toBeInTheDocument();
    expect(vi.mocked(api.post).mock.calls.filter(([url]) => url === '/catalog/orders')).toHaveLength(1);
    expect(api.patch).toHaveBeenCalledWith('/supply/orders/ORD-42', expect.objectContaining({ version: 0 }));
    expect(vi.mocked(api.post).mock.calls[0][1]).toEqual(expect.objectContaining({ schoolId: 7, status: 'DRAFT', orderData: expect.objectContaining({ orderSelections: { CUSTOMIZATION: 'NON_CUSTOMIZED' } }) }));
    expect(vi.mocked(api.post).mock.calls[0][1]).not.toHaveProperty('totalAmount');
  });

  it('allows a customized draft without artwork or a complete total, and reopens requested selections', async () => {
    const detail = savedNotebook();
    vi.mocked(api.post).mockResolvedValue({ data: { id: detail.order.id, version: 0 } });
    vi.mocked(api.get).mockResolvedValue({ data: detail });
    const view = render(<ProductFormBuilder definition={notebookDefinition} />);
    fireEvent.change(screen.getByLabelText('Size'), { target: { value: 'LONG' } });
    fireEvent.change(screen.getByLabelText('Quantity for Single rule'), { target: { value: '400' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add to order' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    expect(await screen.findByText('Draft ORD-42 saved.')).toBeInTheDocument();
    expect(api.post).toHaveBeenCalledTimes(1);
    // Reopening a saved draft shows what was requested, not what the rules adjusted it to.
    view.unmount(); render(<ProductFormBuilder definition={notebookDefinition} initialOrder={detail} />);
    expect(screen.getByText('Rounded from 198 to 196')).toBeInTheDocument();
    expect(screen.getByLabelText('Ordered quantity for Single rule')).toHaveValue(1000);
    fireEvent.click(screen.getByRole('button', { name: 'Place order' }));
    expect(await screen.findByText('Attach design artwork before placing this order.')).toBeInTheDocument();
  });
});

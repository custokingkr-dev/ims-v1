import { cleanup, fireEvent, render, screen, within, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TrophyCatalogue } from './TrophyCatalogue';
import { filterTrophies, trophyProducts, trophyReferencePrice, trophyVariantCode } from './catalogue';
import { trophyDefinition } from '../catalogTestFixtures';
import { ProductFormBuilder } from '../ProductFormBuilder';
import type { FormOrderDetail } from '../types';
import api from '../../../services/api';

vi.mock('../../../services/api', () => ({ default: { post: vi.fn(), patch: vi.fn(), get: vi.fn() } }));
vi.mock('../../../hooks/usePermissions', () => ({ usePermissions: () => ({ can: () => true }) }));
beforeEach(() => vi.clearAllMocks());
afterEach(cleanup);
const options = trophyDefinition.groups[0].options;

describe('Trophy catalogue', () => {
  it('keeps every unique model/size, including AA/AAA, and treats zero prices as unquoted', () => {
    expect(trophyProducts).toHaveLength(814);
    const codes = trophyProducts.flatMap((product) => Object.keys(product.sizes).map((size) => trophyVariantCode(product.model, size)));
    expect(new Set(codes).size).toBe(2420);
    expect(codes).toContain('T_9001_AA');
    expect(trophyReferencePrice('T_WM001_B')).toBe(2801);
    expect(trophyReferencePrice('T_9800_A')).toBeNull();
  });
  it('sorts and filters on starting price, independently of the selected size', () => {
    const filtered = filterTrophies(trophyProducts, { search: 'a-', type: 'Economic Trophy', min: '40', max: '50', sort: 'ASC' });
    expect(filtered.length).toBeGreaterThan(0);
    expect(filtered.every((product) => product.startingPrice >= 40 && product.startingPrice <= 50)).toBe(true);
    expect(filterTrophies(trophyProducts, { search: 'wm001', type: '', min: '', max: '', sort: 'DESC' })[0].model).toBe('WM001');
  });
  it('changes size prices and adds the exact variant and positive integer quantity', () => {
    const add = vi.fn(() => true);
    render(<TrophyCatalogue options={options} onAdd={add} />);
    const card = within(screen.getByRole('article', { name: 'Trophy WM001' }));
    fireEvent.change(card.getByLabelText('Size for WM001'), { target: { value: 'B' } });
    expect(card.getByText('₹2,801')).toBeInTheDocument();
    fireEvent.change(card.getByLabelText('Quantity for WM001'), { target: { value: '3' } });
    fireEvent.click(card.getByRole('button', { name: 'Add WM001 size B to order' }));
    expect(add).toHaveBeenCalledWith('T_WM001_B', 3);
    fireEvent.change(card.getByLabelText('Quantity for WM001'), { target: { value: '1.5' } });
    expect(card.getByRole('button', { name: 'Add WM001 size B to order' })).toBeDisabled();
  });
  it('honors backend deactivation and handles empty searches and missing images', () => {
    render(<TrophyCatalogue options={options.filter((option) => option.code !== 'T_WM001_B')} onAdd={() => true} />);
    expect(screen.queryByRole('option', { name: /Size B.*2,801/ })).not.toBeInTheDocument();
    const image = screen.queryByAltText('Trophy model WM001');
    if (image) fireEvent.error(image);
    expect(within(screen.getByRole('article', { name: 'Trophy WM001' })).getByText('Image unavailable')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Search trophy model'), { target: { value: 'no-such-model' } });
    expect(screen.getByText('No matching trophies')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Reset filters' }));
    expect(screen.getByRole('article', { name: 'Trophy WM001' })).toBeInTheDocument();
  });
  it('merges matching cart lines and saves a TROPHIES draft without submitting catalogue prices', async () => {
    let detail: FormOrderDetail;
    vi.mocked(api.post).mockImplementation(async (_url, payload) => {
      const body = payload as { orderData: { lines: { selections: Record<string, string>; bookCount: number }[] } };
      detail = { order: { id: 'CK-55', category: 'TROPHIES', status: 'DRAFT', subtotal: 0, gst: 0, totalAmount: 0 },
        formDefinition: trophyDefinition, orderSelections: {}, assets: [], formVersion: 2, pricingStatus: 'PENDING_PRICING', version: 0, quantityRuleResults: [],
        lines: body.orderData.lines.map((line, index) => ({ id: index + 1, lineNo: index + 1, optionSelections: line.selections, requestedBookCount: line.bookCount, bookCount: line.bookCount, requestedPageCount: 1, pageCount: 1, unitPricePaise: null, lineTotalPaise: null })) };
      return { data: { id: 'CK-55' } };
    });
    vi.mocked(api.get).mockImplementation(async () => ({ data: detail }));
    render(<ProductFormBuilder definition={trophyDefinition} schoolId={7} />);
    fireEvent.change(screen.getByLabelText('Search trophy model'), { target: { value: 'wm001' } });
    fireEvent.change(screen.getByLabelText('Size for WM001'), { target: { value: 'B' } });
    fireEvent.change(screen.getByLabelText('Quantity for WM001'), { target: { value: '3' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add WM001 size B to order' }));
    fireEvent.click(screen.getByRole('button', { name: 'Add WM001 size B to order' }));
    expect(screen.getByLabelText('Ordered count for WM001 / Size B')).toHaveValue(6);
    expect(screen.getByText('₹16,806.00')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/catalog/orders', {
      category: 'TROPHIES', status: 'DRAFT', schoolId: 7, requiredByDate: null, notes: '',
      orderData: { orderSelections: {}, lines: [{ selections: { VARIANT: 'T_WM001_B' }, bookCount: 6, pageCount: 1 }] },
    }));
    expect(await screen.findByText('Draft CK-55 saved.')).toBeInTheDocument();
  });
});

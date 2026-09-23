import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { SaNewOrderPanel } from './SaNewOrderPanel';
import api from '../../../services/api';
import { notebookDefinition as definition } from '../../../features/catalog/catalogTestFixtures';

vi.mock('../../../services/api');

// Every category whose form is enabled must be orderable. The tile list used to be a hardcoded
// array, so the four categories added on 2026-09-23 (flex, flier, bill book, belt) were seeded in
// the database and reachable over the API but had no tile to click.
const categories = [
  { code: 'NOTEBOOKS', label: 'Notebooks', emoji: '📓', description: 'Ruled, plain, graph', orderType: 'Recurring', formEnabled: true, sortOrder: 1, active: true },
  { code: 'BILLBOOKS', label: 'Bill books', emoji: '🧾', description: 'A3/A4/A5 carbonless', orderType: 'One-time', formEnabled: true, sortOrder: 2, active: true },
  { code: 'BELTS', label: 'Belts', emoji: '🎗', description: 'Cloth and satin belts', orderType: 'Recurring', formEnabled: true, sortOrder: 3, active: true },
  { code: 'UNIFORMS', label: 'Uniforms & apparel', emoji: '👕', description: 'Sets, PE kits', orderType: 'Recurring', formEnabled: false, sortOrder: 4, active: true },
];

function mockApi(forms: Record<string, unknown> = {}) {
  vi.mocked(api.get).mockImplementation((url: string) => {
    if (url === '/supply/product-catalog/categories') return Promise.resolve({ data: categories });
    if (url === '/sa/schools') return Promise.resolve({ data: [{ id: 7, name: 'Demo School', financialYearStartMonth: 4 }] });
    const match = /^\/supply\/product-catalog\/forms\/(.+)$/.exec(url);
    if (match) {
      const form = forms[match[1]];
      return form ? Promise.resolve({ data: form }) : Promise.reject(new Error(`no form for ${match[1]}`));
    }
    return Promise.resolve({ data: [] });
  });
}

describe('SaNewOrderPanel category tiles', () => {
  beforeEach(() => vi.mocked(api.get).mockReset());
  afterEach(() => cleanup());

  it('offers a tile for every form-enabled category the API returns', async () => {
    mockApi();
    render(<SaNewOrderPanel onOrderCreated={() => {}} />);
    await waitFor(() => expect(screen.getByRole('button', { name: /notebooks/i })).toBeInTheDocument());
    expect(screen.getByRole('button', { name: /bill books/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /belts/i })).toBeInTheDocument();
  });

  it('renders the product form for a non-notebook category', async () => {
    const billbooks = {
      ...definition,
      category: { ...definition.category, code: 'BILLBOOKS', label: 'Bill books' },
      groups: definition.groups.map((group) => ({ ...group, categoryCode: 'BILLBOOKS' })),
      rules: [],
    };
    mockApi({ BILLBOOKS: billbooks });
    render(<SaNewOrderPanel onOrderCreated={() => {}} />);
    await waitFor(() => expect(screen.getByRole('button', { name: /bill books/i })).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: /bill books/i }));
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/supply/product-catalog/forms/BILLBOOKS'));
    // The school selector belongs to the product form branch, not the legacy free-text branch,
    // which would instead show a "Notes" field.
    await waitFor(() => expect(screen.getByText('Select a school to begin this order.')).toBeInTheDocument());
  });
});

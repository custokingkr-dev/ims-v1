import { render, screen, fireEvent, waitFor, within, cleanup } from '@testing-library/react';
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

  it('reports a failed category load instead of silently showing a short list', async () => {
    // A 401 mid-session empties the catalogue. The panel used to keep rendering the two generic
    // fallback tiles with only an unstyled line of text, which reads as "the categories are gone".
    vi.mocked(api.get).mockImplementation((url: string) => {
      if (url === '/supply/product-catalog/categories') return Promise.reject(new Error('Request failed with status code 401'));
      if (url === '/sa/schools') return Promise.resolve({ data: [] });
      return Promise.resolve({ data: [] });
    });
    render(<SaNewOrderPanel onOrderCreated={() => {}} />);
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/catalog|categor/i);
    expect(within(alert).getByRole('button', { name: /retry/i })).toBeInTheDocument();
    // The stale fallback tiles must not be offered as if they were the catalogue.
    expect(screen.queryByRole('button', { name: /custom \/ other/i })).not.toBeInTheDocument();
  });

  it('draws category icons rather than emoji, and offers one route for anything else', async () => {
    mockApi();
    const { container } = render(<SaNewOrderPanel onOrderCreated={() => {}} />);
    await waitFor(() => expect(screen.getByRole('button', { name: /notebooks/i })).toBeInTheDocument());

    // Icons come from the shared library at one stroke weight, not from the platform's emoji font.
    const notebooks = screen.getByRole('button', { name: /notebooks/i });
    expect(notebooks.querySelector('svg')).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/\p{Extended_Pictographic}/u);

    // The order type is on the card, so the grid can be scanned without opening a form.
    expect(within(notebooks).getByText('Recurring')).toBeInTheDocument();

    // Two tiles used to carry the CUSTOM key and led to the same form.
    expect(screen.getAllByRole('button', { name: /custom \/ other/i })).toHaveLength(1);
    expect(screen.queryByRole('button', { name: /food & canteen/i })).not.toBeInTheDocument();
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

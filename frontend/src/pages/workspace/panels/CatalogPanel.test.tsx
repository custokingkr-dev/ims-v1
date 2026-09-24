import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { CatalogPanel } from './CatalogPanel';
import api from '../../../services/api';
import { notebookDefinition } from '../../../features/catalog/catalogTestFixtures';

vi.mock('../../../services/api');
vi.mock('../../../contexts/AuthContext', () => ({ useAuth: () => ({ user: { branchId: 7 } }) }));
vi.mock('../../../hooks/usePermissions', () => ({ usePermissions: () => ({ can: () => false }) }));

// A form-enabled category must be orderable even when it has no entry in the hardcoded tile list.
const categories = [
  { code: 'NOTEBOOKS', label: 'Notebooks', emoji: '📓', description: 'Ruled, plain, graph', orderType: 'Recurring', formEnabled: true, sortOrder: 1, active: true },
  { code: 'FLIERS', label: 'Fliers', emoji: '📄', description: 'Single-page fliers', orderType: 'One-time', formEnabled: true, sortOrder: 2, active: true },
];

function mockApi(forms: Record<string, unknown> = {}) {
  vi.mocked(api.get).mockImplementation((url: string) => {
    if (url === '/supply/product-catalog/categories') return Promise.resolve({ data: categories });
    const match = /^\/supply\/product-catalog\/forms\/(.+)$/.exec(url);
    if (match) {
      const form = forms[match[1]];
      return form ? Promise.resolve({ data: form }) : Promise.reject(new Error(`no form for ${match[1]}`));
    }
    return Promise.resolve({ data: [] });
  });
}

describe('CatalogPanel tiles', () => {
  beforeEach(() => vi.mocked(api.get).mockReset());
  afterEach(() => cleanup());

  it('makes a form-enabled category orderable even without a hardcoded tile', async () => {
    mockApi();
    render(<CatalogPanel setPanel={() => {}} />);
    const tile = await screen.findByRole('button', { name: /fliers/i });
    expect(tile).not.toBeDisabled();
  });

  it('opens the product form for that category', async () => {
    const fliers = {
      ...notebookDefinition,
      category: { ...notebookDefinition.category, code: 'FLIERS', label: 'Fliers' },
      groups: notebookDefinition.groups.map((group) => ({ ...group, categoryCode: 'FLIERS' })),
      rules: [],
    };
    mockApi({ FLIERS: fliers });
    render(<CatalogPanel setPanel={() => {}} />);
    fireEvent.click(await screen.findByRole('button', { name: /fliers/i }));
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/supply/product-catalog/forms/FLIERS'));
  });
});

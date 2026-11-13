import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import api from '../../services/api';
import { ProductCatalogManager } from './ProductCatalogManager';
import { notebookDefinition } from './catalogTestFixtures';
vi.mock('../../services/api', () => ({ default: { get: vi.fn(), post: vi.fn(), patch: vi.fn(), delete: vi.fn() } }));
vi.mock('../../hooks/usePermissions', () => ({ usePermissions: () => ({ can: () => true }) }));
vi.mock('../../contexts/AuthContext', () => ({ useAuth: () => ({ user: { role: 'SUPERADMIN' } }) }));
beforeEach(() => { vi.clearAllMocks(); vi.mocked(api.get).mockImplementation(async (url) => ({ data: String(url).endsWith('/categories') ? [notebookDefinition.category] : notebookDefinition })); });
afterEach(cleanup);
it('creates products with valid order types and edits guided quantity with an unsaved preview', async () => {
  vi.mocked(api.post).mockResolvedValue({ data: {} }); vi.mocked(api.patch).mockResolvedValue({ data: {} });
  render(<ProductCatalogManager />);
  fireEvent.click(await screen.findByRole('button', { name: 'Add product' }));
  fireEvent.change(screen.getByLabelText('Code'), { target: { value: 'WORKBOOKS' } });
  fireEvent.change(screen.getByLabelText('Label'), { target: { value: 'Workbooks' } });
  fireEvent.change(screen.getByLabelText('Order type'), { target: { value: 'One-time' } });
  fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
  await waitFor(() => expect(api.post).toHaveBeenCalledWith('/supply/product-catalog/categories', expect.objectContaining({ code: 'WORKBOOKS', orderType: 'One-time' })));
  await waitFor(() => expect(screen.queryByRole('region', { name: 'Catalog editor' })).not.toBeInTheDocument());
  fireEvent.click(screen.getByRole('tab', { name: 'Conditions' }));
  fireEvent.click(screen.getByRole('button', { name: 'Edit Require exactly 1000 books across all sizes' }));
  fireEvent.change(screen.getByLabelText('Required books across the whole order'), { target: { value: '500' } });
  expect(screen.queryByLabelText('When size is')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Preview changes' }));
  expect(screen.getByText('0 / 500 books')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
  await waitFor(() => expect(api.patch).toHaveBeenCalledWith('/supply/product-catalog/rules/1', expect.objectContaining({ params: expect.objectContaining({ value: 500 }) })));
});
it('distinguishes deactivation from confirmed deletion and surfaces referencing order conflicts', async () => {
  vi.mocked(api.delete).mockResolvedValueOnce({ data: {} }).mockRejectedValueOnce({ response: { status: 409, data: { message: 'Cannot delete: referenced by 3 orders.', referencingOrderCount: 3 } } });
  vi.spyOn(window, 'confirm').mockReturnValue(true);
  render(<ProductCatalogManager />);
  fireEvent.click(await screen.findByRole('button', { name: 'Deactivate' }));
  await waitFor(() => expect(api.delete).toHaveBeenCalledWith('/supply/product-catalog/categories/NOTEBOOKS', { params: { hard: false } }));
  await waitFor(() => expect(screen.getByRole('button', { name: 'Permanently delete Notebooks' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: 'Permanently delete Notebooks' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('referenced by 3 orders');
  expect(window.confirm).toHaveBeenCalled();
  expect(api.delete).toHaveBeenLastCalledWith('/supply/product-catalog/categories/NOTEBOOKS', { params: { hard: true } });
});
it('survives malformed categories and definitions and reloads them through Retry', async () => {
  vi.mocked(api.get).mockResolvedValue({ data: {} });
  render(<ProductCatalogManager />);
  expect(await screen.findByRole('alert')).toHaveTextContent(/could not be read/);
  expect(screen.getByRole('tab', { name: 'Form' })).toBeInTheDocument();
  vi.mocked(api.get).mockImplementation(async (url) => ({ data: String(url).endsWith('/categories') ? [notebookDefinition.category] : notebookDefinition }));
  fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
  expect(await screen.findByRole('button', { name: 'Edit Notebooks' })).toBeInTheDocument();
  fireEvent.click(screen.getByRole('tab', { name: 'Conditions' }));
  expect(screen.getByRole('button', { name: 'Edit Require exactly 1000 books across all sizes' })).toBeInTheDocument();
});
// Design D8: superadmin sets each category's item code, its noun and its workflow template.
it('sets a category order code, noun and workflow, and sends blanks as none', async () => {
  vi.mocked(api.patch).mockResolvedValue({ data: {} });
  render(<ProductCatalogManager />);
  expect(await screen.findByText(/No order numbers \| No workflow/)).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Edit Notebooks' }));
  const editor = screen.getByRole('region', { name: 'Catalog editor' });
  expect(within(editor).getByRole('combobox', { name: 'Workflow' })).toHaveValue('');
  expect(within(editor).getAllByRole('option').map((option) => option.textContent)).toEqual(expect.arrayContaining([
    'None (approve, quote and deliver)', 'Notebook order workflow (12 steps)', 'Flier order workflow (9 steps)', 'Flex order workflow (9 steps)',
    'Bill book order workflow (9 steps)', 'General item order workflow (9 steps)',
  ]));
  fireEvent.change(within(editor).getByRole('textbox', { name: 'Order code' }), { target: { value: 'nb' } });
  expect(within(editor).getByRole('textbox', { name: 'Order code' })).toHaveValue('NB');
  fireEvent.change(within(editor).getByRole('textbox', { name: 'Item noun' }), { target: { value: ' notebook ' } });
  fireEvent.change(within(editor).getByRole('combobox', { name: 'Workflow' }), { target: { value: 'NOTEBOOK_ORDER_TRACKER' } });
  fireEvent.click(within(editor).getByRole('button', { name: 'Save changes' }));
  await waitFor(() => expect(api.patch).toHaveBeenCalledWith('/supply/product-catalog/categories/NOTEBOOKS', expect.objectContaining({
    orderCode: 'NB', itemNoun: 'notebook', workflowDefinition: 'NOTEBOOK_ORDER_TRACKER',
  })));
});

it('refuses a bad order code, or a workflow without one, beside the field and sends nothing', async () => {
  render(<ProductCatalogManager />);
  fireEvent.click(await screen.findByRole('button', { name: 'Edit Notebooks' }));
  const editor = screen.getByRole('region', { name: 'Catalog editor' });
  const code = within(editor).getByRole('textbox', { name: 'Order code' });
  fireEvent.change(code, { target: { value: 'NOTE1' } });
  fireEvent.click(within(editor).getByRole('button', { name: 'Save changes' }));
  expect(await within(editor).findByRole('alert')).toHaveTextContent('Use 2 to 4 capital letters, like NB or BBK.');
  expect(code).toHaveAttribute('aria-invalid', 'true');
  fireEvent.change(code, { target: { value: '' } });
  fireEvent.change(within(editor).getByRole('combobox', { name: 'Workflow' }), { target: { value: 'GENERAL_ITEM_ORDER_WORKFLOW' } });
  fireEvent.click(within(editor).getByRole('button', { name: 'Save changes' }));
  expect(await within(editor).findByRole('alert')).toHaveTextContent('A category with a workflow needs an order code');
  expect(api.patch).not.toHaveBeenCalled();
  fireEvent.change(within(editor).getByRole('combobox', { name: 'Workflow' }), { target: { value: '' } });
  vi.mocked(api.patch).mockResolvedValue({ data: {} });
  fireEvent.click(within(editor).getByRole('button', { name: 'Save changes' }));
  await waitFor(() => expect(api.patch).toHaveBeenCalledWith('/supply/product-catalog/categories/NOTEBOOKS', expect.objectContaining({ orderCode: null, itemNoun: null, workflowDefinition: null })));
});

it('lists each category with its order code and workflow, and activates one without resending them', async () => {
  const coded = { ...notebookDefinition.category, active: false, orderCode: 'NB', itemNoun: 'notebook', workflowDefinition: 'NOTEBOOK_ORDER_TRACKER' as const };
  vi.mocked(api.get).mockImplementation(async (url) => ({ data: String(url).endsWith('/categories') ? [coded] : { ...notebookDefinition, category: coded } }));
  vi.mocked(api.patch).mockResolvedValue({ data: {} });
  render(<ProductCatalogManager />);
  expect(await screen.findByText(/Order code NB \(notebook\) \| Notebook order workflow \(12 steps\)/)).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Activate' }));
  // The server merges a PATCH into the stored category, so the code, noun and workflow it holds are kept; sending this
  // screen's copy of them back could overwrite another superadmin's newer change.
  await waitFor(() => expect(api.patch).toHaveBeenCalledWith('/supply/product-catalog/categories/NOTEBOOKS', { active: true }));
});

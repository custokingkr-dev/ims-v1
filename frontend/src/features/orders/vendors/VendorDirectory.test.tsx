import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setupApi, type VendorCategoryView, type VendorView } from './api';
import { VendorDirectory } from './VendorDirectory';

vi.mock('./api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./api')>();
  return {
    ...actual,
    setupApi: {
      ...actual.setupApi,
      vendorCategories: vi.fn(), vendors: vi.fn(), createVendor: vi.fn(), updateVendor: vi.fn(), updateVendorCategory: vi.fn(),
    },
  };
});

const INVITE = 'https://chat.whatsapp.com/AbCdEf12345';

function category(code: string, name: string, overrides: Partial<VendorCategoryView> = {}): VendorCategoryView {
  return { code, name, sortOrder: 10, active: true, vendorCount: 0, updatedAt: null, updatedBy: null, ...overrides };
}

function vendor(id: number, name: string, overrides: Partial<VendorView> = {}): VendorView {
  return {
    id, name, categories: ['OFFSET_PRINTERS'], contactPerson: null, phone: '9810012345', altPhone: null, email: null, city: 'Delhi',
    whatsappGroupUrl: null, notes: null, sample: false, active: true, updatedAt: '2026-11-13T05:00:00Z', updatedBy: 1, ...overrides,
  };
}

const CATEGORIES = [
  category('OFFSET_PRINTERS', 'Offset Printers', { sortOrder: 4, vendorCount: 2 }),
  category('BOARD_SUPPLIERS', 'Board Suppliers', { sortOrder: 1, vendorCount: 1 }),
  category('FLEX_PRINTERS', 'Flex Printers', { sortOrder: 6 }),
  category('BATCH_MAKERS', 'Batch Makers', { sortOrder: 9, active: false }),
];
const VENDORS = [
  vendor(1, 'Metro Offset Printers', { contactPerson: 'Ravi Kumar', whatsappGroupUrl: INVITE }),
  vendor(2, 'Shree Board Traders', { categories: ['BOARD_SUPPLIERS'], phone: null, sample: true, city: null }),
  vendor(3, 'Old Press', { active: false }),
];

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(setupApi.vendorCategories).mockResolvedValue(CATEGORIES);
  vi.mocked(setupApi.vendors).mockResolvedValue(VENDORS);
});
afterEach(cleanup);

describe('vendor directory (section 4.5)', () => {
  it('lists the categories in order with their counts, and every vendor with its status in words', async () => {
    render(<VendorDirectory />);
    const nav = await screen.findByRole('navigation', { name: 'Vendor categories' });
    // The navigation renders before the categories load; wait for all of them, not just "All vendors".
    await waitFor(() => expect(within(nav).getAllByRole('button', { name: /vendors$/ })).toHaveLength(5));
    const picks = within(nav).getAllByRole('button', { name: /vendors$/ });
    expect(picks.map((button) => button.textContent)).toEqual([
      'All vendors3 vendors', 'Board Suppliers1 vendors', 'Offset Printers2 vendors', 'Flex Printers0 vendors', 'Batch MakersInactive0 vendors',
    ]);
    const table = await screen.findByRole('table', { name: 'All vendors' });
    const metro = within(table).getByRole('row', { name: /Metro Offset Printers/ });
    expect(within(metro).getByText('Active')).toBeInTheDocument();
    expect(within(metro).getByRole('link', { name: 'Open Metro Offset Printers WhatsApp group' })).toHaveAttribute('href', INVITE);
    expect(within(metro).getByRole('link', { name: '9810012345' })).toHaveAttribute('href', 'tel:9810012345');
    expect(within(table).getByRole('row', { name: /Shree Board Traders/ })).toHaveTextContent('Sample');
    expect(within(table).getByRole('row', { name: /Old Press/ })).toHaveTextContent('Inactive');
  });

  it('filters by category and search, and offers to add a vendor to an empty category', async () => {
    vi.mocked(setupApi.createVendor).mockResolvedValue(vendor(9, 'Flexi Prints', { categories: ['FLEX_PRINTERS'] }));
    render(<VendorDirectory />);
    await screen.findByRole('table', { name: 'All vendors' });
    fireEvent.click(screen.getByRole('button', { name: /^Board Suppliers/ }));
    const table = screen.getByRole('table', { name: 'Vendors in Board Suppliers' });
    expect(within(table).getAllByRole('row')).toHaveLength(2);
    fireEvent.click(screen.getByRole('button', { name: /^All vendors/ }));
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search vendors' }), { target: { value: 'ravi' } });
    expect(within(screen.getByRole('table', { name: 'All vendors' })).getAllByRole('row')).toHaveLength(2);
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search vendors' }), { target: { value: '' } });

    fireEvent.click(screen.getByRole('button', { name: /^Flex Printers/ }));
    expect(screen.getByText('No vendors in Flex Printers yet.')).toBeInTheDocument();
    fireEvent.click(screen.getAllByRole('button', { name: 'Add vendor' })[1]);
    const dialog = screen.getByRole('dialog', { name: 'Add vendor' });
    expect(within(dialog).getByRole('checkbox', { name: 'Flex Printers' })).toBeChecked();
    // An inactive category is not offered to a new vendor.
    expect(within(dialog).queryByRole('checkbox', { name: /Batch Makers/ })).toBeNull();
  });

  it('shows every mistake beside its field and sends nothing until the vendor is valid', async () => {
    vi.mocked(setupApi.createVendor).mockResolvedValue(vendor(9, 'Digital Hub', { categories: ['FLEX_PRINTERS'] }));
    render(<VendorDirectory />);
    await screen.findByRole('table', { name: 'All vendors' });
    fireEvent.click(screen.getAllByRole('button', { name: 'Add vendor' })[0]);
    const dialog = screen.getByRole('dialog', { name: 'Add vendor' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add vendor' }));
    expect(within(dialog).getByText('Enter the vendor name.')).toBeInTheDocument();
    expect(within(dialog).getByText('Choose at least one category.')).toBeInTheDocument();
    expect(within(dialog).getByText('Enter a phone number.')).toBeInTheDocument();
    expect(within(dialog).getByRole('textbox', { name: 'Name *' })).toHaveAttribute('aria-invalid', 'true');
    expect(setupApi.createVendor).not.toHaveBeenCalled();

    fireEvent.change(within(dialog).getByRole('textbox', { name: 'Name *' }), { target: { value: ' Digital Hub ' } });
    fireEvent.click(within(dialog).getByRole('checkbox', { name: 'Flex Printers' }));
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'Phone *' }), { target: { value: '+91 98100 12345' } });
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'WhatsApp group invite' }), { target: { value: 'https://wa.me/12345' } });
    expect(within(dialog).getByText('Paste a WhatsApp group invite link (https://chat.whatsapp.com/…)')).toBeInTheDocument();
    expect(within(dialog).queryByText('Enter the vendor name.')).toBeNull();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add vendor' }));
    expect(setupApi.createVendor).not.toHaveBeenCalled();

    fireEvent.change(within(dialog).getByRole('textbox', { name: 'WhatsApp group invite' }), { target: { value: `  ${INVITE}` } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add vendor' }));
    await waitFor(() => expect(setupApi.createVendor).toHaveBeenCalledWith({
      name: 'Digital Hub', categories: ['FLEX_PRINTERS'], contactPerson: null, phone: '+91 98100 12345', altPhone: null, email: null,
      city: null, whatsappGroupUrl: INVITE, notes: null, active: true,
    }));
    expect(await screen.findByRole('status')).toHaveTextContent('Digital Hub added.');
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(setupApi.vendors).toHaveBeenCalledTimes(2);
    expect(setupApi.vendorCategories).toHaveBeenCalledTimes(2);
  });

  it('lets a sample vendor be saved without a phone, and keeps a server refusal in the form', async () => {
    vi.mocked(setupApi.updateVendor).mockRejectedValueOnce({ response: { status: 409, data: { message: 'A vendor with this name already exists.' } } });
    render(<VendorDirectory />);
    await screen.findByRole('table', { name: 'All vendors' });
    fireEvent.click(screen.getByRole('button', { name: 'Edit Shree Board Traders' }));
    const dialog = screen.getByRole('dialog', { name: 'Edit Shree Board Traders' });
    expect(within(dialog).getByRole('textbox', { name: 'Phone' })).toHaveValue('');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save vendor' }));
    await waitFor(() => expect(setupApi.updateVendor).toHaveBeenCalledWith(2, expect.objectContaining({ phone: null, categories: ['BOARD_SUPPLIERS'] })));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('A vendor with this name already exists.');
    expect(screen.getByRole('dialog', { name: 'Edit Shree Board Traders' })).toBeInTheDocument();
  });

  it('asks before deactivating a vendor and says what happens to orders that chose it', async () => {
    vi.mocked(setupApi.updateVendor).mockResolvedValue(vendor(1, 'Metro Offset Printers', { active: false }));
    render(<VendorDirectory />);
    await screen.findByRole('table', { name: 'All vendors' });
    fireEvent.click(screen.getByRole('button', { name: 'Edit Metro Offset Printers' }));
    const dialog = screen.getByRole('dialog', { name: 'Edit Metro Offset Printers' });
    fireEvent.click(within(dialog).getByRole('checkbox', { name: 'Active' }));
    expect(within(dialog).getByRole('status')).toHaveTextContent('Orders that already chose it keep it, marked “Inactive”.');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Deactivate vendor' }));
    await waitFor(() => expect(setupApi.updateVendor).toHaveBeenCalledWith(1, expect.objectContaining({ active: false })));
    expect(await screen.findByText('Metro Offset Printers deactivated.')).toBeInTheDocument();
  });

  it('renames a category and deactivates one after confirming, from its row menu', async () => {
    vi.mocked(setupApi.updateVendorCategory).mockResolvedValue(CATEGORIES[0]);
    render(<VendorDirectory />);
    await screen.findByRole('table', { name: 'All vendors' });
    fireEvent.click(screen.getByRole('button', { name: 'Actions for Board Suppliers' }));
    fireEvent.click(screen.getByRole('menuitem', { name: 'Rename' }));
    const rename = screen.getByRole('dialog', { name: 'Rename Board Suppliers' });
    fireEvent.change(within(rename).getByRole('textbox', { name: 'Category name' }), { target: { value: '  ' } });
    fireEvent.click(within(rename).getByRole('button', { name: 'Save name' }));
    expect(within(rename).getByText('Enter a category name.')).toBeInTheDocument();
    fireEvent.change(within(rename).getByRole('textbox', { name: 'Category name' }), { target: { value: 'Board Traders' } });
    fireEvent.click(within(rename).getByRole('button', { name: 'Save name' }));
    await waitFor(() => expect(setupApi.updateVendorCategory).toHaveBeenCalledWith('BOARD_SUPPLIERS', { name: 'Board Traders' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());

    fireEvent.click(screen.getByRole('button', { name: 'Actions for Flex Printers' }));
    fireEvent.click(screen.getByRole('menuitem', { name: 'Deactivate' }));
    const confirm = screen.getByRole('dialog', { name: 'Deactivate Flex Printers?' });
    expect(setupApi.updateVendorCategory).toHaveBeenCalledTimes(1);
    fireEvent.click(within(confirm).getByRole('button', { name: 'Deactivate category' }));
    await waitFor(() => expect(setupApi.updateVendorCategory).toHaveBeenCalledWith('FLEX_PRINTERS', { active: false }));

    fireEvent.click(screen.getByRole('button', { name: 'Actions for Batch Makers' }));
    fireEvent.click(screen.getByRole('menuitem', { name: 'Activate' }));
    await waitFor(() => expect(setupApi.updateVendorCategory).toHaveBeenCalledWith('BATCH_MAKERS', { active: true }));
  });

  it('closes a row menu with Escape and returns focus to its button', async () => {
    render(<VendorDirectory />);
    await screen.findByRole('table', { name: 'All vendors' });
    const trigger = screen.getByRole('button', { name: 'Actions for Board Suppliers' });
    fireEvent.click(trigger);
    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('menuitem', { name: 'Rename' })).toHaveFocus();
    fireEvent.keyDown(screen.getByRole('menu'), { key: 'Escape' });
    expect(screen.queryByRole('menu')).toBeNull();
    expect(trigger).toHaveFocus();
  });

  it('reports lists that cannot load, plainly when the service is down, and retries them', async () => {
    vi.mocked(setupApi.vendors).mockRejectedValueOnce({ response: { status: 503, data: { message: 'upstream connect error' } } });
    render(<VendorDirectory />);
    expect(await screen.findByText('The order workflow service is not available right now. Try again in a moment.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('table', { name: 'All vendors' })).toBeInTheDocument();
  });
});

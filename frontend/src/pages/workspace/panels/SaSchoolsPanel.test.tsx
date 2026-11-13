import { render, screen, fireEvent, waitFor, within, cleanup } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { SaSchoolsPanel } from './SaSchoolsPanel';
import api from '../../../services/api';

vi.mock('../../../services/api');

describe('SaSchoolsPanel structure edit', () => {
  afterEach(() => {
    cleanup();
  });

  beforeEach(() => {
    vi.mocked(api.get).mockResolvedValue({ data: [
      { id: 7, name: 'Demo School', shortCode: 'DEMO', city: 'Hyd', active: true,
        configuredClassCount: 12, configuredSectionCount: 3, academicYearStartMonth: 4, financialYearStartMonth: 4, timeZone: 'Asia/Kolkata', adminEmail: 'a@x.com', ordersYTD: 0, gmvYTD: 0 },
    ] });
    vi.mocked(api.put).mockReset();
    vi.mocked(api.post).mockReset();
    vi.mocked(api.patch).mockReset();
  });

  it('surfaces a 409 in-use message and does not close on failure', async () => {
    vi.mocked(api.put).mockRejectedValue({ response: { data: { message: "class '8' has 3 student(s)" } } });
    render(<SaSchoolsPanel />);
    await waitFor(() => expect(screen.getByText('Demo School')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: /edit structure/i }));
    const dialog = screen.getByRole('dialog');
    // Field's <label> is a sibling of the control, not a wrapping/associated label
    // (see AddStudentPanel.test.tsx), so getByLabelText cannot resolve it here either.
    // Target the number inputs by their (stable) DOM order instead: classes, then sections.
    const [classesInput] = within(dialog).getAllByRole('spinbutton');
    fireEvent.change(classesInput, { target: { value: '2' } });
    fireEvent.click(within(dialog).getByRole('button', { name: /save/i }));
    await waitFor(() =>
      expect(within(dialog).getByText(/has 3 student\(s\)/i)).toBeInTheDocument());
    expect(api.put).toHaveBeenCalledWith('/schools/7/structure', { classCount: 2, sectionCount: 3 });
  });

  it('shows the current per-school actions without old edit or schools buttons', async () => {
    render(<SaSchoolsPanel />);
    await waitFor(() => expect(screen.getByText('Demo School')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: /edit structure/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /archived students/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^edit$/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^schools$/i })).not.toBeInTheDocument();
  });

  it('saves valid counts and reloads', async () => {
    vi.mocked(api.put).mockResolvedValue({ data: { id: 7 } });
    render(<SaSchoolsPanel />);
    await waitFor(() => expect(screen.getByText('Demo School')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: /edit structure/i }));
    const dialog = screen.getByRole('dialog');
    const [, sectionsInput] = within(dialog).getAllByRole('spinbutton');
    fireEvent.change(sectionsInput, { target: { value: '4' } });
    fireEvent.click(within(dialog).getByRole('button', { name: /save/i }));
    await waitFor(() =>
      expect(api.put).toHaveBeenCalledWith('/schools/7/structure', { classCount: 12, sectionCount: 4 }));
  });

  it('updates an onboarded school timezone with an IANA identifier', async () => {
    vi.mocked(api.put).mockResolvedValue({ data: { id: 7 } });
    vi.mocked(api.patch).mockResolvedValue({ data: { id: 7, timeZone: 'Europe/London' } });
    render(<SaSchoolsPanel />);
    await waitFor(() => expect(screen.getByText('Demo School')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: /edit structure/i }));
    const dialog = screen.getByRole('dialog');

    fireEvent.change(within(dialog).getAllByRole('combobox')[0], { target: { value: 'Europe/London' } });
    fireEvent.click(within(dialog).getByRole('button', { name: /save/i }));

    await waitFor(() => expect(api.patch).toHaveBeenCalledWith('/schools/7', expect.objectContaining({
      timeZone: 'Europe/London', countryCode: 'IN', locale: 'en-IN', currencyCode: 'INR', phoneRegion: 'IN',
    })));
  });

});

describe('SaSchoolsPanel load failure', () => {
  afterEach(cleanup);

  it('offers a way out of a failed load instead of a dead end', async () => {
    // A bare sentence in a card gave the reader no way back: every other panel in the portal
    // raises a real alert with a Retry beside it.
    vi.mocked(api.get).mockRejectedValue({ response: { data: { message: 'Upstream unavailable.' } } });
    render(<SaSchoolsPanel />);
    const alert = await screen.findByRole('alert');
    expect(within(alert).getByText('Upstream unavailable.')).toBeInTheDocument();

    vi.mocked(api.get).mockResolvedValue({ data: [
      { id: 7, name: 'Demo School', shortCode: 'DEMO', city: 'Hyd', active: true,
        configuredClassCount: 12, configuredSectionCount: 3, academicYearStartMonth: 4,
        financialYearStartMonth: 4, timeZone: 'Asia/Kolkata', adminEmail: 'a@x.com',
        ordersYTD: 0, gmvYTD: 0 },
    ] });
    fireEvent.click(within(alert).getByRole('button', { name: /retry/i }));
    await waitFor(() => expect(screen.getByText('Demo School')).toBeInTheDocument());
  });
});

// Design D12 and section 4.8: a school's order code is part of every order number.
describe('SaSchoolsPanel school order code', () => {
  afterEach(cleanup);
  beforeEach(() => {
    vi.mocked(api.get).mockResolvedValue({ data: [
      { id: 7, name: 'Demo School', shortCode: 'DEMO', orderCode: 'DS_HYD', city: 'Hyd', active: true, configuredClassCount: 12, configuredSectionCount: 3,
        academicYearStartMonth: 4, financialYearStartMonth: 4, timeZone: 'Asia/Kolkata', adminEmail: 'a@x.com', ordersYTD: 0, gmvYTD: 0 },
    ] });
    vi.mocked(api.post).mockReset();
    vi.mocked(api.put).mockReset();
    vi.mocked(api.patch).mockReset();
  });

  const onboard = async () => {
    render(<SaSchoolsPanel />);
    await waitFor(() => expect(screen.getByText('Demo School')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: /onboard school/i }));
    return screen.getByRole('dialog', { name: 'Onboard school' });
  };

  it('lists each school\'s order code', async () => {
    render(<SaSchoolsPanel />);
    const row = await screen.findByRole('row', { name: /Demo School/ });
    expect(within(row).getByText('DS_HYD')).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Order code' })).toBeInTheDocument();
  });

  it('suggests a code from the name and city until one is typed, and sends what is shown', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: { id: 8, photoImportFolder: { status: 'READY' } } });
    const dialog = await onboard();
    const code = within(dialog).getByRole('textbox', { name: 'Order code' });
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'School name *' }), { target: { value: 'Delhi Public School' } });
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'City *' }), { target: { value: 'Gurugram' } });
    expect(code).toHaveValue('DPS_GUR');
    expect(code).toHaveAccessibleDescription(/Used in order numbers like NB-DPS_GUR-26-11-13-0001/);
    fireEvent.change(code, { target: { value: 'dps2_ggn' } });
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'City *' }), { target: { value: 'Gurgaon' } });
    expect(code).toHaveValue('DPS2_GGN');
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'Short code *' }), { target: { value: 'DPSG' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create school' }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/schools', expect.objectContaining({ name: 'Delhi Public School', orderCode: 'DPS2_GGN' })));
  });

  it('suggests the next free code when another school has it, and lets the server derive an untouched suggestion (D12)', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: [
      { id: 7, name: 'Demo School', shortCode: 'DEMO', orderCode: 'DS_HYD', city: 'Hyd', active: true, adminEmail: 'a@x.com', ordersYTD: 0, gmvYTD: 0 },
      { id: 9, name: 'Delhi Public School', shortCode: 'DPSG', orderCode: 'DPS_GUR', city: 'Gurugram', active: true, adminEmail: 'b@x.com', ordersYTD: 0, gmvYTD: 0 },
    ] });
    vi.mocked(api.post).mockResolvedValue({ data: { id: 10, orderCode: 'DPS2_GUR', photoImportFolder: { status: 'READY' } } });
    const dialog = await onboard();
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'School name *' }), { target: { value: 'Delhi Public School' } });
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'City *' }), { target: { value: 'Gurugram' } });
    expect(within(dialog).getByRole('textbox', { name: 'Order code' })).toHaveValue('DPS2_GUR');
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'Short code *' }), { target: { value: 'DPSG2' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create school' }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/schools', expect.objectContaining({ name: 'Delhi Public School', orderCode: null })));
    expect(await screen.findByText(/Delhi Public School created with order code DPS2_GUR\./)).toBeInTheDocument();
  });

  it('refuses a code that is not initials, an underscore and a location, and lets a blank code be derived', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: { id: 8, photoImportFolder: { status: 'READY' } } });
    const dialog = await onboard();
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'School name *' }), { target: { value: 'Delhi Public School' } });
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'Short code *' }), { target: { value: 'DPSG' } });
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'City *' }), { target: { value: 'Gurugram' } });
    const code = within(dialog).getByRole('textbox', { name: 'Order code' });
    fireEvent.change(code, { target: { value: 'DPS-GUR' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create school' }));
    expect(await within(dialog).findByText(/Use initials, an underscore and a location, like DPS_GUR/)).toBeInTheDocument();
    expect(code).toHaveAttribute('aria-invalid', 'true');
    expect(api.post).not.toHaveBeenCalled();
    fireEvent.change(code, { target: { value: '' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create school' }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/schools', expect.objectContaining({ orderCode: null })));
  });

  it('changes an existing school\'s code for new orders only, and sends it only when changed', async () => {
    vi.mocked(api.put).mockResolvedValue({ data: { id: 7 } });
    vi.mocked(api.patch).mockResolvedValue({ data: { id: 7 } });
    render(<SaSchoolsPanel />);
    await waitFor(() => expect(screen.getByText('Demo School')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: /edit structure/i }));
    let dialog = screen.getByRole('dialog');
    const code = within(dialog).getByRole('textbox', { name: 'Order code' });
    expect(code).toHaveValue('DS_HYD');
    expect(code).toHaveAccessibleDescription(/Changing it affects only new orders/);
    fireEvent.click(within(dialog).getByRole('button', { name: /save/i }));
    await waitFor(() => expect(api.patch).toHaveBeenCalled());
    expect(vi.mocked(api.patch).mock.calls[0][1]).not.toHaveProperty('orderCode');

    fireEvent.click(await screen.findByRole('button', { name: /edit structure/i }));
    dialog = screen.getByRole('dialog');
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'Order code' }), { target: { value: 'D' } });
    fireEvent.click(within(dialog).getByRole('button', { name: /save/i }));
    expect(await within(dialog).findByText(/Order code: Use initials/)).toBeInTheDocument();
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'Order code' }), { target: { value: 'dsch_hyd' } });
    fireEvent.click(within(dialog).getByRole('button', { name: /save/i }));
    await waitFor(() => expect(api.patch).toHaveBeenLastCalledWith('/schools/7', expect.objectContaining({ orderCode: 'DSCH_HYD' })));
  });
});

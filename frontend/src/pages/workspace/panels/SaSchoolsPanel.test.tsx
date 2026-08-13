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

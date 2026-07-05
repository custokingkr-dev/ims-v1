import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { SchoolStructurePanel } from './SchoolStructurePanel';
import api from '../../../services/api';

vi.mock('../../../services/api');

afterEach(cleanup);

describe('SchoolStructurePanel', () => {
  beforeEach(() => {
    vi.mocked(api.get).mockResolvedValue({ data: { id: 10, configuredClassCount: 8, configuredSectionCount: 3 } });
    vi.mocked(api.put).mockReset();
  });

  it('loads current counts and submits an update for the admin school', async () => {
    vi.mocked(api.put).mockResolvedValue({ data: { id: 10 } });
    render(<SchoolStructurePanel schoolId={10} onSaved={vi.fn()} />);
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/schools/10'));
    await waitFor(() => expect((screen.getAllByRole('spinbutton')[0] as HTMLInputElement).value).toBe('8'));
    fireEvent.change(screen.getAllByRole('spinbutton')[0], { target: { value: '9' } });
    fireEvent.click(screen.getByRole('button', { name: /save/i }));
    await waitFor(() =>
      expect(api.put).toHaveBeenCalledWith('/schools/10/structure', { classCount: 9, sectionCount: 3 }));
  });

  it('shows a 409 in-use message without clearing the form', async () => {
    vi.mocked(api.put).mockRejectedValue({ response: { data: { message: "section 'C' has 2 student(s)" } } });
    render(<SchoolStructurePanel schoolId={10} onSaved={vi.fn()} />);
    await waitFor(() => expect((screen.getAllByRole('spinbutton')[1] as HTMLInputElement).value).toBe('3'));
    fireEvent.change(screen.getAllByRole('spinbutton')[1], { target: { value: '2' } });
    fireEvent.click(screen.getByRole('button', { name: /save/i }));
    await waitFor(() => expect(screen.getByText(/has 2 student\(s\)/i)).toBeInTheDocument());
  });
});

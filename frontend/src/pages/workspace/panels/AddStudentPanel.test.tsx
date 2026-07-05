import { render, screen, waitFor, fireEvent, cleanup } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { AddStudentPanel } from './AddStudentPanel';
import api from '../../../services/api';

vi.mock('../../../services/api');

describe('AddStudentPanel class/section dropdowns', () => {
  afterEach(() => {
    cleanup();
  });

  beforeEach(() => {
    vi.mocked(api.get).mockReset();
    vi.mocked(api.get).mockImplementation((url: string) => {
      if (url === '/classes') {
        return Promise.resolve({ data: [
          { id: 'c1', name: 'Class 1', sortOrder: 1 },
          { id: 'c2', name: 'Class 2', sortOrder: 2 },
        ] });
      }
      if (url === '/classes/c1/sections') {
        return Promise.resolve({ data: [{ id: 's-a', name: 'A' }, { id: 's-b', name: 'B' }] });
      }
      return Promise.resolve({ data: [] });
    });
  });

  it('renders class options fetched from the API, not a hardcoded 1-12 list', async () => {
    render(<AddStudentPanel setPanel={vi.fn()} onRefresh={vi.fn()} />);
    await waitFor(() => expect(screen.getByRole('option', { name: 'Class 1' })).toBeInTheDocument());
    expect(screen.getByRole('option', { name: 'Class 2' })).toBeInTheDocument();
    // The old hardcoded list went up to Class 12 — it must be gone.
    expect(screen.queryByRole('option', { name: 'Class 12' })).not.toBeInTheDocument();
  });

  it('loads sections for the selected class with active=true', async () => {
    render(<AddStudentPanel setPanel={vi.fn()} onRefresh={vi.fn()} />);
    await waitFor(() => expect(screen.getByRole('option', { name: 'Class 1' })).toBeInTheDocument());
    // Field's <label> is a sibling of the control, not a wrapping/associated label,
    // so getByLabelText cannot resolve it. Per the brief's fallback, target the
    // class select via combobox ordering instead. The Gender select (Student
    // Details section) renders before the Class select (Academic Details
    // section), so Class is the second combobox on the page, not the first.
    const classSelect = screen.getAllByRole('combobox')[1];
    fireEvent.change(classSelect, { target: { value: 'Class 1' } });
    await waitFor(() =>
      expect(api.get).toHaveBeenCalledWith('/classes/c1/sections', { params: { active: true } }));
  });

  it('repairs the initial hardcoded class selection to a real fetched class', async () => {
    render(<AddStudentPanel setPanel={vi.fn()} onRefresh={vi.fn()} />);
    await waitFor(() => expect(screen.getByRole('option', { name: 'Class 1' })).toBeInTheDocument());
    const classSelect = screen.getAllByRole('combobox')[1] as HTMLSelectElement;
    await waitFor(() => expect(classSelect.value).toBe('Class 1'));
    expect(classSelect.value).not.toBe('Class 9');
    await waitFor(() =>
      expect(api.get).toHaveBeenCalledWith('/classes/c1/sections', { params: { active: true } }));
  });
});

import { fireEvent, render, screen, waitFor, within, cleanup } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FeeStructurePanel } from './FeeStructurePanel';
import api from '../../../services/api';

vi.mock('../../../services/api');
vi.mock('../../../contexts/AuthContext', () => ({ useAuth: () => ({ user: { branchId: 7 } }) }));
vi.mock('../../../hooks/usePermissions', () => ({
  usePermissions: () => ({ can: () => false }),
}));

describe('FeeStructurePanel student assignment', () => {
  afterEach(cleanup);

  beforeEach(() => {
    vi.mocked(api.get).mockReset();
    vi.mocked(api.get).mockImplementation((url: string) => {
      if (url === '/classes') {
        return Promise.resolve({ data: [{ id: 'class-1', name: 'Class 1', sortOrder: 1 }] });
      }
      if (url === '/fee-structure') {
        return Promise.resolve({ data: { academicYear: '2026-27', academicYearId: 'ay-1', bands: [] } });
      }
      if (url === '/classes/class-1/sections') {
        return Promise.resolve({ data: [{ id: 'section-a', name: 'A' }] });
      }
      if (url === '/classes/class-1/sections/section-a/students') {
        return Promise.resolve({ data: [{ id: 101, admissionNo: 'ADM-101', fullName: 'Aarav Sharma' }] });
      }
      return Promise.resolve({ data: [] });
    });
  });

  it('shows admission number and full name in the student dropdown', async () => {
    render(<FeeStructurePanel onRefresh={vi.fn()} />);

    await waitFor(() => expect(screen.getByRole('option', { name: 'Class 1' })).toBeInTheDocument());
    fireEvent.change(screen.getAllByRole('combobox')[0], { target: { value: 'class-1' } });

    await waitFor(() => expect(screen.getByRole('option', { name: 'A' })).toBeInTheDocument());
    fireEvent.change(screen.getAllByRole('combobox')[1], { target: { value: 'section-a' } });

    await waitFor(() =>
      expect(api.get).toHaveBeenCalledWith('/classes/class-1/sections/section-a/students', { params: { schoolId: 7 } }),
    );
    const studentSelect = screen.getAllByRole('combobox')[2];
    expect(within(studentSelect).getByRole('option', { name: 'ADM-101 - Aarav Sharma' })).toBeInTheDocument();
  });
});

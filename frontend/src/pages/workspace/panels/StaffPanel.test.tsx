import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { StaffPanel } from './StaffPanel';
import api from '../../../services/api';

vi.mock('../../../services/api');

const mockUseAuth = vi.fn();

vi.mock('../../../contexts/AuthContext', () => ({
  useAuth: () => mockUseAuth(),
}));

const workspace = {
  staff: [
    {
      id: '1',
      name: 'Asha',
      designation: 'Teacher',
      department: 'Primary',
      payrollStatus: 'Pending',
      monthlySalary: 42000,
    },
  ],
};

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('StaffPanel permissions', () => {
  it('shows staff directory without the write form when staff:manage is missing', () => {
    mockUseAuth.mockReturnValue({ user: { permissions: ['staff:read'] } });

    render(<StaffPanel workspace={workspace as any} onRefresh={vi.fn()} />);

    expect(screen.getByText('Staff directory')).toBeInTheDocument();
    expect(screen.getByText('Asha')).toBeInTheDocument();
    expect(screen.queryByText('New staff member')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /add staff/i })).not.toBeInTheDocument();
  });

  it('allows staff creation only when staff:manage is present', async () => {
    mockUseAuth.mockReturnValue({ user: { permissions: ['staff:read', 'staff:manage'] } });
    vi.mocked(api.post).mockResolvedValue({ data: { id: 2 } });
    const onRefresh = vi.fn().mockResolvedValue(undefined);

    render(<StaffPanel workspace={workspace as any} onRefresh={onRefresh} />);

    fireEvent.click(screen.getByRole('button', { name: /add staff/i }));

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/workspace/staff', {
      name: '',
      designation: '',
      department: '',
      monthlySalary: '42000',
      payrollStatus: 'Pending',
    }));
    expect(onRefresh).toHaveBeenCalled();
  });
});

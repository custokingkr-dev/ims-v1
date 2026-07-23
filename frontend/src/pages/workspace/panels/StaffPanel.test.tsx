import { render, screen, fireEvent, waitFor, cleanup, within } from '@testing-library/react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { StaffPanel } from './StaffPanel';
import api from '../../../services/api';

vi.mock('../../../services/api');

const mockUseAuth = vi.fn();

vi.mock('../../../contexts/AuthContext', () => ({
  useAuth: () => mockUseAuth(),
}));

const staffRows = [
  {
    id: 1,
    name: 'Asha Rao',
    employeeCode: 'T-001',
    designation: 'Teacher',
    department: 'Primary',
    staffType: 'Teaching',
    employmentStatus: 'Active',
    email: 'asha@school.test',
    phone: '9999999999',
    joinDate: '2026-04-01',
    payrollStatus: 'Pending',
    monthlySalary: 42000,
    notes: 'Class teacher',
  },
];

const workspace = { staff: staffRows };

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  vi.unstubAllGlobals();
});

describe('StaffPanel', () => {
  it('shows staff directory without management actions when staff:manage is missing', async () => {
    mockUseAuth.mockReturnValue({ user: { branchId: 1, permissions: ['staff:read'] } });
    vi.mocked(api.get).mockResolvedValue({ data: staffRows });

    render(<StaffPanel workspace={workspace as any} onRefresh={vi.fn()} />);

    expect(await screen.findByText('Staff directory')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /select asha rao/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /add staff/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /edit profile/i })).not.toBeInTheDocument();
  });

  it('creates a staff profile when staff:manage is present', async () => {
    mockUseAuth.mockReturnValue({ user: { branchId: 1, permissions: ['staff:read', 'staff:manage'] } });
    vi.mocked(api.get).mockResolvedValue({ data: staffRows });
    vi.mocked(api.post).mockResolvedValue({ data: { id: 2 } });
    const onRefresh = vi.fn().mockResolvedValue(undefined);

    render(<StaffPanel workspace={workspace as any} onRefresh={onRefresh} />);

    fireEvent.click(screen.getByRole('button', { name: /add staff/i }));
    const dialog = screen.getByRole('dialog', { name: /add staff profile/i });
    fireEvent.change(within(dialog).getByRole('textbox', { name: /full name/i }), { target: { value: 'Priya Shah' } });
    fireEvent.change(within(dialog).getByRole('textbox', { name: /staff code/i }), { target: { value: 'T-002' } });
    fireEvent.change(within(dialog).getByRole('textbox', { name: /designation/i }), { target: { value: 'Math Teacher' } });
    fireEvent.change(within(dialog).getByRole('textbox', { name: /department/i }), { target: { value: 'Middle School' } });
    fireEvent.click(within(dialog).getByRole('button', { name: /create staff/i }));

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/workspace/staff', expect.objectContaining({
      schoolId: 1,
      name: 'Priya Shah',
      employeeCode: 'T-002',
      designation: 'Math Teacher',
      department: 'Middle School',
      staffType: 'Teaching',
      employmentStatus: 'Active',
      payrollStatus: 'Pending',
    })));
    expect(onRefresh).toHaveBeenCalled();
  });

  it('updates a selected staff profile', async () => {
    mockUseAuth.mockReturnValue({ user: { branchId: 1, permissions: ['staff:read', 'staff:manage'] } });
    vi.mocked(api.get).mockResolvedValue({ data: staffRows });
    vi.mocked(api.put).mockResolvedValue({ data: { id: 1 } });
    const onRefresh = vi.fn().mockResolvedValue(undefined);

    render(<StaffPanel workspace={workspace as any} onRefresh={onRefresh} />);

    expect(await screen.findByRole('button', { name: /select asha rao/i })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /edit profile/i }));
    const dialog = screen.getByRole('dialog', { name: /edit staff profile/i });
    fireEvent.change(within(dialog).getByRole('textbox', { name: /full name/i }), { target: { value: 'Asha Rao Updated' } });
    fireEvent.click(within(dialog).getByRole('button', { name: /save profile/i }));

    await waitFor(() => expect(api.put).toHaveBeenCalledWith('/workspace/staff/1', expect.objectContaining({
      schoolId: 1,
      name: 'Asha Rao Updated',
    })));
    expect(onRefresh).toHaveBeenCalled();
  });

  it('deactivates staff without deleting the profile', async () => {
    mockUseAuth.mockReturnValue({ user: { branchId: 1, permissions: ['staff:read', 'staff:manage'] } });
    vi.mocked(api.get).mockResolvedValue({ data: staffRows });
    vi.mocked(api.delete).mockResolvedValue({ data: { id: 1, employmentStatus: 'Inactive' } });
    vi.stubGlobal('confirm', vi.fn(() => true));
    const onRefresh = vi.fn().mockResolvedValue(undefined);

    render(<StaffPanel workspace={workspace as any} onRefresh={onRefresh} />);

    expect(await screen.findByRole('button', { name: /select asha rao/i })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /deactivate/i }));

    await waitFor(() => expect(api.delete).toHaveBeenCalledWith('/workspace/staff/1', { data: { schoolId: 1 } }));
    expect(onRefresh).toHaveBeenCalled();
  });
});

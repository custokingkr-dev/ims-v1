import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import SchoolManagementPage from './SchoolManagementPage';
import api from '../services/api';

vi.mock('../services/api', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn(),
  },
}));

vi.mock('../contexts/AuthContext', () => ({
  useAuth: () => ({
    user: {
      accessToken: 'test-token',
      userId: 1,
      fullName: 'Local Superadmin',
      email: 'local-superadmin@custoking.local',
      role: 'SUPERADMIN',
      permissions: [
        'platform:admin',
        'school:read',
        'school:create',
        'school:update',
        'user:update',
        'user:reset_password',
        'role:assign',
      ],
    },
  }),
}));

const schools = [
  {
    id: 10,
    name: 'Green Valley School',
    shortCode: 'GVS',
    city: 'Pune',
    state: 'MH',
    active: true,
    academicYearStartMonth: 4,
    financialYearStartMonth: 4,
  },
  {
    id: 20,
    name: 'North Star Academy',
    shortCode: 'NSA',
    city: 'Mumbai',
    state: 'MH',
    active: false,
    academicYearStartMonth: 6,
    financialYearStartMonth: 4,
  },
];

const assignments = [
  {
    userId: 101,
    userEmail: 'admin@gvs.edu',
    userFullName: 'Asha Admin',
    schoolId: 10,
    roleName: 'ADMIN',
    active: true,
  },
  {
    userId: 201,
    userEmail: 'ops@gvs.edu',
    userFullName: 'Omar Ops',
    schoolId: 10,
    roleName: 'OPERATIONS',
    active: true,
  },
  {
    userId: 102,
    userEmail: 'retired@nsa.edu',
    userFullName: 'Retired Admin',
    schoolId: 20,
    roleName: 'ADMIN',
    active: false,
  },
];

const operatorUsers = [
  { id: 201, fullName: 'Omar Ops', email: 'ops@gvs.edu', role: 'OPERATIONS' },
  { id: 202, fullName: 'Una Assigned', email: 'unassigned@ops.edu', role: 'OPERATIONS' },
];

function mockApi() {
  vi.mocked(api.get).mockImplementation((async (url: string) => {
    if (url === '/schools') return { data: schools };
    if (url === '/rbac/user-role-assignments') return { data: assignments };
    if (url === '/users') return { data: operatorUsers };
    if (url === '/rbac/users/201/operator-schools') return { data: [{ schoolId: 10 }] };
    if (url === '/schools/10/modules') {
      return {
        data: [
          { moduleCode: 'ORDERS', enabled: true },
          { moduleCode: 'FIREFIGHTING', enabled: true },
          { moduleCode: 'STUDENTS', enabled: false },
        ],
      };
    }
    throw new Error(`Unexpected GET ${url}`);
  }) as any);

  vi.mocked(api.post).mockImplementation((async (url: string) => {
    if (url === '/schools') return { data: { id: 77 } };
    if (url === '/schools/10/operations-user') return { data: { userId: 303 } };
    return { data: {} };
  }) as any);

  vi.mocked(api.put).mockResolvedValue({ data: {} } as any);
  vi.mocked(api.patch).mockResolvedValue({ data: {} } as any);
  vi.mocked(api.delete).mockResolvedValue({ data: {} } as any);
}

function renderPage() {
  return render(
    <MemoryRouter>
      <SchoolManagementPage />
    </MemoryRouter>
  );
}

async function loadedPage() {
  renderPage();
  await screen.findByRole('button', { name: /select green valley school/i });
  await screen.findByText('unassigned@ops.edu');
}

describe('SchoolManagementPage superadmin workflows', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockApi();
  });

  afterEach(() => {
    cleanup();
  });

  it('renders school health, operator scope, search, and setup filtering', async () => {
    const user = userEvent.setup();
    await loadedPage();

    const summary = screen.getByLabelText(/school management summary/i);
    expect(within(summary).getByText('Total schools').closest('.sms-stat')).toHaveTextContent('2');
    expect(within(summary).getByText('Setup gaps').closest('.sms-stat')).toHaveTextContent('1');
    expect(within(summary).getByText('Operators').closest('.sms-stat')).toHaveTextContent('2');
    expect(within(summary).getByText('School scopes').closest('.sms-stat')).toHaveTextContent('1');

    await user.type(screen.getByRole('textbox', { name: /search schools/i }), 'Mumbai');
    expect(screen.getByRole('button', { name: /select north star academy/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /select green valley school/i })).not.toBeInTheDocument();

    await user.clear(screen.getByRole('textbox', { name: /search schools/i }));
    await user.click(screen.getByRole('button', { name: /needs setup/i }));
    expect(screen.getByRole('button', { name: /select north star academy/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /select green valley school/i })).not.toBeInTheDocument();
  });

  it('creates a school and writes module entitlements from the modal', async () => {
    const user = userEvent.setup();
    await loadedPage();

    await user.click(screen.getByRole('button', { name: /add school/i }));
    const dialog = screen.getByRole('dialog', { name: /add school/i });

    await user.type(within(dialog).getByRole('textbox', { name: /school name/i }), 'Riverdale School');
    await user.type(within(dialog).getByRole('textbox', { name: /short code/i }), 'riv');
    await user.clear(within(dialog).getByRole('spinbutton', { name: /no\. of classes/i }));
    await user.type(within(dialog).getByRole('spinbutton', { name: /no\. of classes/i }), '13');
    await user.clear(within(dialog).getByRole('spinbutton', { name: /sections per class/i }));
    await user.type(within(dialog).getByRole('spinbutton', { name: /sections per class/i }), '3');
    await user.selectOptions(within(dialog).getByRole('combobox', { name: /academic year starts/i }), '6');
    await user.click(within(dialog).getByRole('checkbox', { name: /^erp$/i }));
    await user.click(within(dialog).getByRole('button', { name: /create school/i }));

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/schools', expect.objectContaining({
      name: 'Riverdale School',
      shortCode: 'RIV',
      classCount: 13,
      sectionCount: 3,
      academicYearStartMonth: 6,
      financialYearStartMonth: 4,
    })));
    await waitFor(() => expect(api.put).toHaveBeenCalledWith('/schools/77/modules/ORDERS', { enabled: true }));
    expect(api.delete).toHaveBeenCalledWith('/schools/77/modules/STUDENTS');
  });

  it('adds an admin and edits the existing admin account', async () => {
    const user = userEvent.setup();
    await loadedPage();

    const northRow = screen.getByRole('button', { name: /select north star academy/i });
    expect(within(northRow).getByText('No admin')).toBeInTheDocument();
    await user.click(within(northRow).getByRole('button', { name: /add admin for north star academy/i }));

    const addDialog = screen.getByRole('dialog', { name: /add admin account/i });
    await user.type(within(addDialog).getByRole('textbox', { name: /full name/i }), 'Priya Admin');
    await user.type(within(addDialog).getByRole('textbox', { name: /^email$/i }), 'priya.admin@nsa.edu');
    await user.type(within(addDialog).getByLabelText(/temporary password/i), 'Temporary!2026');
    await user.click(within(addDialog).getByRole('button', { name: /save admin/i }));

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/schools/20/admin', {
      fullName: 'Priya Admin',
      email: 'priya.admin@nsa.edu',
      temporaryPassword: 'Temporary!2026',
    }));

    const greenRow = screen.getByRole('button', { name: /select green valley school/i });
    expect(within(greenRow).getByText('Asha Admin')).toBeInTheDocument();
    expect(within(greenRow).getByText('admin@gvs.edu')).toBeInTheDocument();

    await user.click(within(greenRow).getByRole('button', { name: /manage admins for green valley school/i }));
    const adminMenu = screen.getByRole('menu', { name: /admin users for green valley school/i });
    expect(within(adminMenu).getByText('Asha Admin')).toBeInTheDocument();
    expect(within(adminMenu).getByText('admin@gvs.edu')).toBeInTheDocument();
    await user.click(within(adminMenu).getByRole('menuitem', { name: /asha admin admin@gvs\.edu/i }));

    const editDialog = screen.getByRole('dialog', { name: /edit admin account/i });
    await user.clear(within(editDialog).getByRole('textbox', { name: /full name/i }));
    await user.type(within(editDialog).getByRole('textbox', { name: /full name/i }), 'Asha Admin Updated');
    await user.clear(within(editDialog).getByRole('textbox', { name: /^email$/i }));
    await user.type(within(editDialog).getByRole('textbox', { name: /^email$/i }), 'asha.updated@gvs.edu');
    await user.type(within(editDialog).getByLabelText(/new password/i), 'Updated!2026');
    await user.type(within(editDialog).getByLabelText(/confirm password/i), 'Updated!2026');
    await user.click(within(editDialog).getByRole('button', { name: /save account/i }));

    await waitFor(() => expect(api.patch).toHaveBeenCalledWith('/users/101', {
      fullName: 'Asha Admin Updated',
      email: 'asha.updated@gvs.edu',
    }));
    expect(api.post).toHaveBeenCalledWith('/users/101/password-reset', { password: 'Updated!2026' });
  });

  it('creates, edits, and scopes operator accounts from the operator panel', async () => {
    const user = userEvent.setup();
    await loadedPage();

    await user.click(screen.getByRole('button', { name: /^create$/i }));
    const createDialog = screen.getByRole('dialog', { name: /create operator account/i });
    await user.type(within(createDialog).getByRole('textbox', { name: /full name/i }), 'Ravi Operator');
    await user.type(within(createDialog).getByRole('textbox', { name: /^email$/i }), 'ravi.operator@ops.edu');
    await user.type(within(createDialog).getByLabelText(/temporary password/i), 'Temporary!2026');
    await user.click(within(createDialog).getByRole('checkbox', { name: /green valley school/i }));
    await user.click(within(createDialog).getByRole('checkbox', { name: /north star academy/i }));
    await user.click(within(createDialog).getByRole('button', { name: /^create operator$/i }));

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/schools/10/operations-user', {
      fullName: 'Ravi Operator',
      email: 'ravi.operator@ops.edu',
      temporaryPassword: 'Temporary!2026',
    }));
    expect(api.post).toHaveBeenCalledWith('/rbac/users/303/operator-schools', { schoolIds: [10, 20] });

    const operatorPanel = screen.getByRole('complementary', { name: /operator accounts/i });
    const operatorRow = within(operatorPanel).getByText('Omar Ops').closest('.sms-operator-row') as HTMLElement;
    await user.click(within(operatorRow).getByRole('button', { name: /edit/i }));

    const editDialog = screen.getByRole('dialog', { name: /edit operator account/i });
    await user.clear(within(editDialog).getByRole('textbox', { name: /full name/i }));
    await user.type(within(editDialog).getByRole('textbox', { name: /full name/i }), 'Omar Ops Updated');
    await user.click(within(editDialog).getByRole('button', { name: /save account/i }));

    await waitFor(() => expect(api.patch).toHaveBeenCalledWith('/users/201', {
      fullName: 'Omar Ops Updated',
      email: 'ops@gvs.edu',
    }));

    const refreshedOperatorPanel = screen.getByRole('complementary', { name: /operator accounts/i });
    const refreshedOperatorRow = within(refreshedOperatorPanel).getByText('Omar Ops').closest('.sms-operator-row') as HTMLElement;
    await user.click(within(refreshedOperatorRow).getByRole('button', { name: /schools/i }));
    const schoolsDialog = await screen.findByRole('dialog', { name: /operator schools/i });
    expect(within(schoolsDialog).getByRole('checkbox', { name: /green valley school/i })).toBeChecked();
    await user.click(within(schoolsDialog).getByRole('checkbox', { name: /green valley school/i }));
    await user.click(within(schoolsDialog).getByRole('checkbox', { name: /north star academy/i }));
    await user.click(within(schoolsDialog).getByRole('button', { name: /save schools/i }));

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/rbac/users/201/operator-schools', { schoolIds: [20] }));
  });

  it('loads and saves module groups for a school', async () => {
    const user = userEvent.setup();
    await loadedPage();

    const greenRow = screen.getByRole('button', { name: /select green valley school/i });
    await user.click(within(greenRow).getByRole('button', { name: /modules/i }));

    const dialog = await screen.findByRole('dialog', { name: /manage modules/i });
    await waitFor(() => expect(within(dialog).getByRole('checkbox', { name: /supply os/i })).toBeChecked());
    expect(within(dialog).getByRole('checkbox', { name: /^erp$/i })).not.toBeChecked();

    await user.click(within(dialog).getByRole('checkbox', { name: /^erp$/i }));
    await user.click(within(dialog).getByRole('button', { name: /save modules/i }));

    await waitFor(() => expect(api.put).toHaveBeenCalledWith('/schools/10/modules/STUDENTS', { enabled: true }));
    expect(api.put).toHaveBeenCalledWith('/schools/10/modules/ORDERS', { enabled: true });
  });
});

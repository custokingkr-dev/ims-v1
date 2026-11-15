import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import api from '../../../services/api';
import { AttendanceAbsenteePanel } from './AttendanceAbsenteePanel';

const access = vi.hoisted(() => ({ canManage: true }));

vi.mock('../../../services/api');
vi.mock('../../../contexts/AuthContext', () => ({
  useAuth: () => ({ user: { role: 'ADMIN' } }),
}));
vi.mock('../../../hooks/usePermissions', () => ({
  usePermissions: () => ({ can: (code: string) => access.canManage && code === 'attendance:manage' }),
}));

const NOTICE = /Absence alerts go to parents on WhatsApp, but WhatsApp sending isn't switched on yet/;

const student = (overrides: Record<string, unknown>) => ({
  studentId: 1,
  fullName: 'Asha Rao',
  admissionNo: 'ADM1',
  rollNo: '1',
  classSection: 'Class 1-A',
  parentContact: '919999999999',
  hasContact: true,
  alreadyQueued: false,
  notificationStatus: null,
  status: 'ABSENT',
  remarks: '',
  ...overrides,
});

/** The exceptions list as school-core returns it; `delivery` is what this backend says about sending. */
function mockExceptions(delivery: { channel?: 'WHATSAPP'; deliveryMode?: 'DRY_RUN' | 'LIVE' }) {
  vi.mocked(api.get).mockImplementation((url: string) => {
    if (url === '/classes') return Promise.resolve({ data: [] });
    if (url === '/attendance/exceptions') {
      return Promise.resolve({
        data: {
          students: [
            student({ studentId: 1, fullName: 'Asha Rao', alreadyQueued: false, notificationStatus: null }),
            student({ studentId: 2, fullName: 'Bala Iyer', alreadyQueued: true, notificationStatus: 'QUEUED' }),
            student({ studentId: 3, fullName: 'Charu Nair', alreadyQueued: true, notificationStatus: 'SENT' }),
            student({ studentId: 4, fullName: 'Dev Menon', alreadyQueued: true, notificationStatus: 'SENT_DRY_RUN' }),
            student({ studentId: 5, fullName: 'Esha Pillai', alreadyQueued: true, notificationStatus: 'FAILED' }),
            student({ studentId: 6, fullName: 'Farah Khan', alreadyQueued: true, notificationStatus: 'DEAD_LETTER' }),
            student({ studentId: 7, fullName: 'Gita Das', alreadyQueued: true, notificationStatus: 'SUPPRESSED' }),
          ],
          totalExceptions: 7,
          absentCount: 7,
          lateCount: 0,
          leaveCount: 0,
          queuedCount: 6,
          ...delivery,
        },
      });
    }
    return Promise.resolve({ data: [] });
  });
}

async function renderLoaded() {
  render(<AttendanceAbsenteePanel />);
  await waitFor(() => expect(screen.getByText('Asha Rao')).toBeTruthy());
}

async function queueAlerts() {
  fireEvent.click(screen.getByRole('button', { name: /Queue WhatsApp alerts/ }));
  await waitFor(() => expect(vi.mocked(api.post)).toHaveBeenCalledWith('/attendance/absentees/notify', expect.anything()));
}

describe('AttendanceAbsenteePanel', () => {
  afterEach(cleanup);

  beforeEach(() => {
    access.canManage = true;
    vi.mocked(api.get).mockReset();
    vi.mocked(api.post).mockReset();
    mockExceptions({ channel: 'WHATSAPP', deliveryMode: 'DRY_RUN' });
  });

  it('shows the per-row WhatsApp delivery status so staff can see what actually happened', async () => {
    await renderLoaded();

    expect(screen.getByRole('columnheader', { name: 'WhatsApp alert' })).toBeTruthy();
    expect(screen.queryByRole('columnheader', { name: 'Notification' })).toBeNull();
    expect(screen.getByText('Ready')).toBeTruthy();
    expect(screen.getByText('Queued')).toBeTruthy();
    expect(screen.getByText('Sent').getAttribute('title')).toMatch(/WhatsApp/);
    const testMode = screen.getByText('Not sent (test mode)');
    expect(testMode.getAttribute('title')).toBe('WhatsApp sending is in test mode: every step ran except sending the message.');
    expect(screen.queryByText('Sent (dry run)')).toBeNull();
    expect(screen.getByText('Retrying')).toBeTruthy();
    expect(screen.getByText('Failed')).toBeTruthy();
    expect(screen.getByText('Suppressed')).toBeTruthy();
  });

  it('names WhatsApp on the button, the KPIs and the permission badge', async () => {
    await renderLoaded();

    const button = screen.getByRole('button', { name: 'Queue WhatsApp alerts (1)' });
    expect(button.hasAttribute('disabled')).toBe(false);
    expect(screen.queryByRole('button', { name: /Notify eligible/ })).toBeNull();
    expect(screen.getByText('1 can get a WhatsApp alert')).toBeTruthy();
    expect(screen.getByText('WhatsApp alerts queued')).toBeTruthy();
    expect(screen.getByText('One per student per day')).toBeTruthy();
    expect(screen.queryByText('Parents queued')).toBeNull();
    expect(screen.getByText('Can queue alerts')).toBeTruthy();
  });

  it('says WhatsApp sending is not switched on while the backend runs in test mode', async () => {
    await renderLoaded();

    const notice = screen.getByRole('note');
    expect(notice.textContent).toMatch(NOTICE);
    expect(notice.textContent).toMatch(/no parent receives them/);
    // The button points at the notice so assistive technology reads it with the action.
    const button = screen.getByRole('button', { name: /Queue WhatsApp alerts/ });
    expect(button.getAttribute('aria-describedby')).toBe(notice.id);
  });

  it('treats a backend that does not report the mode as not sending', async () => {
    mockExceptions({});
    await renderLoaded();

    expect(screen.getByRole('note').textContent).toMatch(NOTICE);
  });

  it('hides the notice once WhatsApp sending is live', async () => {
    mockExceptions({ channel: 'WHATSAPP', deliveryMode: 'LIVE' });
    await renderLoaded();

    expect(screen.queryByRole('note')).toBeNull();
    expect(screen.queryByText(NOTICE)).toBeNull();
    expect(screen.getByRole('button', { name: /Queue WhatsApp alerts/ }).hasAttribute('aria-describedby')).toBe(false);
  });

  it('says the queued alerts reach no parent after queuing in test mode', async () => {
    vi.mocked(api.post).mockResolvedValue({
      data: { date: '2026-09-10', queued: 1, skippedNoContact: 0, skippedAlreadyQueued: 6, suppressedCount: 0, channel: 'WHATSAPP', deliveryMode: 'DRY_RUN' },
    });
    await renderLoaded();

    await queueAlerts();

    const toast = await screen.findByText(/^Queued 1 WhatsApp alert in test mode/);
    expect(toast.textContent).toBe(
      "Queued 1 WhatsApp alert in test mode; skipped 6. WhatsApp sending isn't switched on, so no parent will receive it.",
    );
    expect(toast.textContent).not.toMatch(/\bsent\b/i);
  });

  it('points at the per-row status after queuing while sending is live', async () => {
    mockExceptions({ channel: 'WHATSAPP', deliveryMode: 'LIVE' });
    vi.mocked(api.post).mockResolvedValue({
      data: { date: '2026-09-10', queued: 2, skippedNoContact: 1, skippedAlreadyQueued: 6, suppressedCount: 1, channel: 'WHATSAPP', deliveryMode: 'LIVE' },
    });
    await renderLoaded();

    await queueAlerts();

    const toast = await screen.findByText(/^Queued 2 WhatsApp alerts;/);
    expect(toast.textContent).toBe('Queued 2 WhatsApp alerts; skipped 7. Each row shows whether its message was sent.');
    expect(toast.textContent).not.toMatch(/test mode/);
  });

  it('uses the mode from the queue response over the list it was loaded with', async () => {
    mockExceptions({ channel: 'WHATSAPP', deliveryMode: 'LIVE' });
    vi.mocked(api.post).mockResolvedValue({
      data: { date: '2026-09-10', queued: 2, skippedNoContact: 0, skippedAlreadyQueued: 0, deliveryMode: 'DRY_RUN' },
    });
    await renderLoaded();

    await queueAlerts();

    expect((await screen.findByText(/^Queued 2 WhatsApp alerts in test mode/)).textContent)
      .toMatch(/no parent will receive them\.$/);
  });

  it('falls back to the list mode when the queue response does not report one', async () => {
    mockExceptions({ channel: 'WHATSAPP', deliveryMode: 'LIVE' });
    vi.mocked(api.post).mockResolvedValue({
      data: { date: '2026-09-10', queued: 1, skippedNoContact: 0, skippedAlreadyQueued: 0 },
    });
    await renderLoaded();

    await queueAlerts();

    expect((await screen.findByText(/^Queued 1 WhatsApp alert;/)).textContent)
      .toBe('Queued 1 WhatsApp alert; skipped 0. Each row shows whether its message was sent.');
  });

  it('counts every policy refusal as skipped, not only the missing numbers', async () => {
    vi.mocked(api.post).mockResolvedValue({
      data: { date: '2026-09-10', queued: 0, skippedNoContact: 1, skippedAlreadyQueued: 2, suppressedCount: 3, deliveryMode: 'DRY_RUN' },
    });
    await renderLoaded();

    await queueAlerts();

    expect((await screen.findByText(/^Queued 0 WhatsApp alerts in test mode/)).textContent).toMatch(/skipped 5\./);
  });

  it('tells read-only staff that queuing WhatsApp alerts needs attendance:manage', async () => {
    access.canManage = false;
    await renderLoaded();

    expect(screen.getByText('You can review absentees. Queuing WhatsApp alerts requires attendance:manage.')).toBeTruthy();
    expect(screen.getByText('Read-only')).toBeTruthy();
    expect(screen.getByRole('button', { name: /Queue WhatsApp alerts/ }).hasAttribute('disabled')).toBe(true);
  });
});

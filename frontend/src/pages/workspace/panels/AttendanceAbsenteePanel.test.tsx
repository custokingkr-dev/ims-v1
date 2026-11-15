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

type Row = Record<string, unknown>;

const student = (overrides: Row): Row => ({
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

/** One row per delivery status, in the older-backend shape (no alertAllowed). Dev's row ran in test mode. */
const ALL_STATUSES: Row[] = [
  student({ studentId: 1, fullName: 'Asha Rao', alreadyQueued: false, notificationStatus: null }),
  student({ studentId: 2, fullName: 'Bala Iyer', alreadyQueued: true, notificationStatus: 'QUEUED' }),
  student({ studentId: 3, fullName: 'Charu Nair', alreadyQueued: true, notificationStatus: 'SENT' }),
  student({ studentId: 4, fullName: 'Dev Menon', alreadyQueued: true, notificationStatus: 'SENT_DRY_RUN' }),
  student({ studentId: 5, fullName: 'Esha Pillai', alreadyQueued: true, notificationStatus: 'FAILED' }),
  student({ studentId: 6, fullName: 'Farah Khan', alreadyQueued: true, notificationStatus: 'DEAD_LETTER' }),
  student({ studentId: 7, fullName: 'Gita Das', alreadyQueued: true, notificationStatus: 'SUPPRESSED' }),
];

/** What a page looks like once sending really is live: no row finished in test mode. */
const LIVE_ROWS: Row[] = [
  student({ studentId: 1, fullName: 'Asha Rao', alertAllowed: true, alertBlockedReason: null }),
  student({ studentId: 2, fullName: 'Bala Iyer', alreadyQueued: true, notificationStatus: 'QUEUED' }),
  student({ studentId: 3, fullName: 'Charu Nair', alreadyQueued: true, notificationStatus: 'SENT' }),
];

/** The exceptions list as school-core returns it; `delivery` is what this backend says about sending. */
function mockExceptions(delivery: { channel?: 'WHATSAPP'; deliveryMode?: 'DRY_RUN' | 'LIVE' }, students: Row[] = ALL_STATUSES) {
  vi.mocked(api.get).mockImplementation((url: string) => {
    if (url === '/classes') return Promise.resolve({ data: [] });
    if (url === '/attendance/exceptions') {
      return Promise.resolve({
        data: {
          students,
          totalExceptions: students.length,
          absentCount: students.filter((row) => row.status === 'ABSENT').length,
          lateCount: students.filter((row) => row.status === 'LATE').length,
          leaveCount: 0,
          queuedCount: students.filter((row) => row.alreadyQueued).length,
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

/** The alert cell of a student's row. */
function alertCell(name: string) {
  const row = screen.getByText(name).closest('tr');
  if (!row) throw new Error(`no row for ${name}`);
  return row.querySelectorAll('td')[5] as HTMLElement;
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
    expect(screen.getByText('1 ready for a WhatsApp alert')).toBeTruthy();
    expect(screen.getByText('WhatsApp alerts queued')).toBeTruthy();
    expect(screen.getByText('One per student per day')).toBeTruthy();
    expect(screen.queryByText('Parents queued')).toBeNull();
    expect(screen.getByText('Can queue alerts')).toBeTruthy();
  });

  describe('whether an absentee can get an alert', () => {
    it("labels each unqueued absentee by the guardian policy and says why it can't", async () => {
      mockExceptions({ channel: 'WHATSAPP', deliveryMode: 'DRY_RUN' }, [
        student({ studentId: 1, fullName: 'Asha Rao', alertAllowed: true, alertBlockedReason: null }),
        student({ studentId: 2, fullName: 'Bala Iyer', alertAllowed: false, alertBlockedReason: 'CONTACT_NOT_VERIFIED' }),
        student({ studentId: 3, fullName: 'Charu Nair', alertAllowed: false, alertBlockedReason: 'SCHOOL_COMMUNICATIONS_DECISION_MISSING' }),
        student({ studentId: 4, fullName: 'Dev Menon', alertAllowed: false, alertBlockedReason: 'SOMETHING_NEW' }),
        // The policy wins over the student record's own phone field.
        student({ studentId: 5, fullName: 'Esha Pillai', hasContact: false, parentContact: '', alertAllowed: true }),
        student({ studentId: 6, fullName: 'Farah Khan', status: 'LATE', alertAllowed: null }),
      ]);
      await renderLoaded();

      expect(alertCell('Asha Rao').textContent).toBe('Ready');
      expect(alertCell('Esha Pillai').textContent).toBe('Ready');
      const bala = alertCell('Bala Iyer');
      expect(bala.querySelector('.ck-status')?.textContent).toBe("Can't alert");
      // The reason is visible text, not only a tooltip.
      expect(bala.querySelector('.ck-att-cell-sub')?.textContent).toBe("The guardian's phone number is missing or not verified.");
      expect(alertCell('Charu Nair').textContent).toContain('No consent to school messages is recorded.');
      expect(alertCell('Dev Menon').textContent).toContain("The guardian's settings don't allow school messages.");
      expect(alertCell('Farah Khan').textContent).toBe('Not required');

      // Only the rows the policy allows are counted.
      expect(screen.getByRole('button', { name: 'Queue WhatsApp alerts (2)' }).hasAttribute('disabled')).toBe(false);
      expect(screen.getByText('2 ready for a WhatsApp alert')).toBeTruthy();
    });

    it('counts nothing and disables the button when the policy refuses every absentee', async () => {
      // A school with phone numbers on file but no consent records.
      mockExceptions({ channel: 'WHATSAPP', deliveryMode: 'DRY_RUN' }, [
        student({ studentId: 1, fullName: 'Asha Rao', alertAllowed: false, alertBlockedReason: 'SCHOOL_COMMUNICATIONS_DECISION_MISSING' }),
        student({ studentId: 2, fullName: 'Bala Iyer', alertAllowed: false, alertBlockedReason: 'SCHOOL_COMMUNICATIONS_DECISION_MISSING' }),
      ]);
      await renderLoaded();

      expect(screen.queryByText('Ready')).toBeNull();
      expect(screen.getAllByText("Can't alert")).toHaveLength(2);
      expect(screen.getByRole('button', { name: 'Queue WhatsApp alerts (0)' }).hasAttribute('disabled')).toBe(true);
      expect(screen.getByText('0 ready for a WhatsApp alert')).toBeTruthy();
    });

    it('falls back to the phone number on file when an older backend sends no preview', async () => {
      mockExceptions({ channel: 'WHATSAPP', deliveryMode: 'DRY_RUN' }, [
        student({ studentId: 1, fullName: 'Asha Rao' }),
        student({ studentId: 2, fullName: 'Bala Iyer', hasContact: false, parentContact: '' }),
      ]);
      await renderLoaded();

      expect(alertCell('Asha Rao').textContent).toBe('Ready');
      expect(alertCell('Bala Iyer').textContent).toBe('Unavailable');
      expect(screen.queryByText("Can't alert")).toBeNull();
      expect(screen.getByRole('button', { name: 'Queue WhatsApp alerts (1)' })).toBeTruthy();
    });

    it('explains every reason the guardian policy can give', async () => {
      const reasons: Array<[string, string]> = [
        ['NO_PRIMARY_GUARDIAN', 'No primary guardian is recorded.'],
        ['GUARDIAN_INACTIVE', 'The primary guardian is inactive.'],
        ['NOTIFICATION_PREFERENCE_DISABLED', 'School notifications are turned off for this guardian.'],
        ['CONTACT_NOT_VERIFIED', "The guardian's phone number is missing or not verified."],
        ['DESTINATION_MISSING', 'The guardian has no phone number.'],
        ['SCHOOL_COMMUNICATIONS_DECISION_MISSING', 'No consent to school messages is recorded.'],
        ['CONSENT_GUARDIAN_UNSPECIFIED', "Consent wasn't recorded for the primary guardian."],
        ['CONSENT_GUARDIAN_MISMATCH', "Consent wasn't recorded for the primary guardian."],
        ['SCHOOL_COMMUNICATIONS_NOT_GRANTED', "The guardian hasn't agreed to school messages."],
        ['LAWFUL_BASIS_NOT_CONSENT', 'The consent record uses a legal basis other than consent.'],
        ['CONSENT_NOTICE_VERSION_MISSING', 'The consent record is incomplete.'],
        ['SCHOOL_COMMUNICATIONS_EXPIRED', 'Consent to school messages has expired.'],
        ['STUDENT_NOT_FOUND', "The guardian's settings don't allow school messages."],
        ['UNSUPPORTED_CHANNEL', "The guardian's settings don't allow school messages."],
      ];
      mockExceptions({ channel: 'WHATSAPP', deliveryMode: 'DRY_RUN' }, [
        student({ studentId: 100, fullName: 'Asha Rao', alertAllowed: true }),
        ...reasons.map(([reason], index) => student({
          studentId: index + 1, fullName: `Student ${reason}`, alertAllowed: false, alertBlockedReason: reason,
        })),
      ]);
      render(<AttendanceAbsenteePanel />);
      await waitFor(() => expect(screen.getByText('Asha Rao')).toBeTruthy());
      // All rows on one page.
      fireEvent.change(screen.getByRole('combobox', { name: 'Rows per page' }), { target: { value: '50' } });

      for (const [reason, text] of reasons) {
        expect(alertCell(`Student ${reason}`).querySelector('.ck-att-cell-sub')?.textContent, reason).toBe(text);
      }
    });
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
    mockExceptions({ channel: 'WHATSAPP', deliveryMode: 'LIVE' }, LIVE_ROWS);
    await renderLoaded();

    expect(screen.queryByRole('note')).toBeNull();
    expect(screen.queryByText(NOTICE)).toBeNull();
    expect(screen.getByRole('button', { name: /Queue WhatsApp alerts/ }).hasAttribute('aria-describedby')).toBe(false);
  });

  it('trusts a LIVE answer even on a date with rows from before the go-live, which keep their own test-mode chip', async () => {
    // ALL_STATUSES includes a SENT_DRY_RUN row, as every date queued before a go-live does for good.
    mockExceptions({ channel: 'WHATSAPP', deliveryMode: 'LIVE' });
    vi.mocked(api.post).mockResolvedValue({
      data: { date: '2026-09-10', queued: 1, skippedNoContact: 0, skippedAlreadyQueued: 6, suppressedCount: 0, deliveryMode: 'LIVE' },
    });
    await renderLoaded();

    expect(screen.queryByRole('note')).toBeNull();
    expect(screen.getByText('Not sent (test mode)')).toBeTruthy();

    await queueAlerts();

    expect((await screen.findByText(/^Queued 1 WhatsApp alert/)).textContent).toBe(
      'Queued 1 WhatsApp alert; skipped 6. Each row shows whether its message was sent.',
    );
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
    mockExceptions({ channel: 'WHATSAPP', deliveryMode: 'LIVE' }, LIVE_ROWS);
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
    mockExceptions({ channel: 'WHATSAPP', deliveryMode: 'LIVE' }, LIVE_ROWS);
    vi.mocked(api.post).mockResolvedValue({
      data: { date: '2026-09-10', queued: 2, skippedNoContact: 0, skippedAlreadyQueued: 0, deliveryMode: 'DRY_RUN' },
    });
    await renderLoaded();

    await queueAlerts();

    expect((await screen.findByText(/^Queued 2 WhatsApp alerts in test mode/)).textContent)
      .toMatch(/no parent will receive them\.$/);
  });

  it('falls back to the list mode when the queue response does not report one', async () => {
    mockExceptions({ channel: 'WHATSAPP', deliveryMode: 'LIVE' }, LIVE_ROWS);
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

import type { AxiosInstance } from 'axios';
import { describe, expect, it, vi } from 'vitest';
import { createReportingClient } from './reportingClient';

describe('canonical reporting drilldown contracts', () => {
  it('retains every fee filter, page and explicit school scope', async () => {
    const data = { items: [], totalElements: 0 };
    const get = vi.fn().mockResolvedValue({ data });
    const client = createReportingClient({ get } as unknown as AxiosInstance);
    const params = { schoolId: 7, classId: 'c-1', sectionId: 's-2', daysOverdue: 30, reminderStatus: 'PENDING', page: 0, size: 25 };
    await expect(client.getFeeDefaulters(params)).resolves.toBe(data);
    expect(get).toHaveBeenCalledWith('/reporting/fee-defaulters', { params });
  });

  it('encodes section identifiers once and passes optional date and school filters', async () => {
    const get = vi.fn().mockResolvedValue({ data: [] });
    const client = createReportingClient({ get } as unknown as AxiosInstance);
    await client.getLowAttendanceStudents({ sectionId: 'section /?#%', schoolId: 7 });
    expect(get).toHaveBeenLastCalledWith('/reporting/low-attendance/sections/section%20%2F%3F%23%25/students', { params: { schoolId: 7 } });
    await client.getLowAttendanceSections({ date: '2026-10-12', schoolId: 7 });
    expect(get).toHaveBeenLastCalledWith('/reporting/low-attendance/sections', { params: { date: '2026-10-12', schoolId: 7 } });
  });

  it('preserves denial and outage responses without retries or a cached success', async () => {
    const error = { response: { status: 403, data: { message: 'School mismatch' } } };
    const get = vi.fn().mockRejectedValue(error);
    const client = createReportingClient({ get } as unknown as AxiosInstance);
    await expect(client.getVendorDues({ schoolId: 99 })).rejects.toBe(error);
    expect(get).toHaveBeenCalledTimes(1);
    get.mockResolvedValueOnce({ data: { alertCount: 0, items: [] } });
    await expect(client.getReorderSignals()).resolves.toEqual({ alertCount: 0, items: [] });
  });
});

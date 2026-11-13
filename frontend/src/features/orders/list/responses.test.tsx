import { cleanup, render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import api from '../../../services/api';
import { NotebookWorklistPanel } from '../../../pages/workspace/panels/NotebookWorklistPanel';
import { clearWorkflowCaches } from '../workflow/api';

// These run the real clients against a transport that answers whatever the case sets, as a misbehaving gateway or a
// route mock can: the panel must say a response was not understood, or pass on a refusal's own words, and never show an
// empty list or crash instead.
vi.mock('../../../services/api', () => ({ default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() } }));
vi.mock('../../../contexts/AuthContext', () => ({ useAuth: () => ({ user: { role: 'SUPERADMIN', userId: 1, fullName: 'Platform Superadmin' } }) }));

const NOT_UNDERSTOOD = 'The response was not understood.';
const NOW = '2026-11-13T06:30:00Z';
const http = vi.mocked(api);

/** Answers each GET by its path; a path the case does not name answers `fallback`. */
function answer(byPath: Record<string, unknown>, fallback: unknown = []) {
  http.get.mockImplementation(async (url: string) => {
    if (url in byPath) {
      const value = byPath[url];
      if (value instanceof Error) throw value;
      return { data: value } as never;
    }
    return { data: fallback } as never;
  });
}

function refusal(status: number, message: string): Error {
  return Object.assign(new Error(`Request failed with status code ${status}`), { isAxiosError: true, response: { status, data: { message } } });
}

const listItem = {
  orderId: 'CK-1015', orderNumber: 'NB-DPS_GUR-26-11-13-0042', definitionId: 'NOTEBOOK_ORDER_TRACKER', categoryCode: 'NOTEBOOKS', itemCode: 'NB',
  schoolId: 7, schoolName: 'Delhi Public School', customised: true, orderedCount: 15600, placedAt: '2026-11-12T04:00:00Z', timeZone: 'Asia/Kolkata',
  status: 'PRODUCTION_PLANNING', phase: 'PLANNING', currentStep: 5, currentTitle: 'Production Planning', assigneeUserId: 51, assigneeName: 'Ravi Operator',
  dueAt: '2026-11-14T20:00:00Z', overdue: false, slaState: 'ON_TRACK', completed: 4, total: 12,
};

function renderPanel(entry = '/dashboard?panel=nb-work') {
  render(
    <MemoryRouter initialEntries={[entry]}>
      <Routes><Route path="/dashboard" element={<NotebookWorklistPanel />} /></Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  clearWorkflowCaches();
});
afterEach(() => {
  cleanup();
  vi.unstubAllEnvs();
});

describe('responses the order workflows panel cannot use', () => {
  it('says the list was not understood instead of showing an empty list, and stays standing', async () => {
    answer({}, {});
    renderPanel();
    expect(await screen.findByRole('alert')).toHaveTextContent(NOT_UNDERSTOOD);
    expect(screen.queryByText('No orders are in production.')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Order workflows' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Retry/ })).toBeInTheDocument();
  });

  it("passes on the server's own reason when it refuses the list", async () => {
    answer({ '/workflows/item-orders': refusal(403, 'You do not have access to these orders.') });
    renderPanel();
    expect(await screen.findByRole('alert')).toHaveTextContent('You do not have access to these orders.');
  });

  it('says a deep-linked order was not understood, with Retry, instead of a blank page', async () => {
    answer({ '/workflows/item-orders/CK-1015': {} });
    renderPanel('/dashboard?panel=nb-work&order=CK-1015');
    expect(await screen.findByRole('alert')).toHaveTextContent(NOT_UNDERSTOOD);
    expect(screen.getByRole('button', { name: /Retry/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Order workflows/ })).toBeInTheDocument();
  });

  it("shows each deadline in the school's time zone, whatever the browser's zone is", async () => {
    vi.stubEnv('TZ', 'America/New_York');
    answer({ '/workflows/item-orders': { items: [listItem], total: 1, counts: { open: 1, overdue: 0, dueToday: 0, unassigned: 0, mine: 0 }, now: NOW } });
    renderPanel();
    const row = (await screen.findByText('NB-DPS_GUR-26-11-13-0042')).closest('tr') as HTMLElement;
    // 20:00 UTC on 14 Nov is 1:30 am on 15 Nov in India, and 3:00 pm on 14 Nov in New York.
    expect(within(row).getByText('15 Nov, 1:30 am')).toBeInTheDocument();
    expect(within(row).getByText('Ref CK-1015')).toBeInTheDocument();
  });
});

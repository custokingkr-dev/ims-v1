import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import api from '../../../services/api';
import { trackerApi } from '../../../features/tracker/api';
import { NotebookAdminPanel } from './NotebookAdminPanel';
import { NotebookWorklistPanel } from './NotebookWorklistPanel';

// These run the real client against a transport that answers `{}` where a list belongs, as a misbehaving gateway or a
// route mock can: the panels must say the response was not understood instead of crashing the application.
vi.mock('../../../services/api', () => ({ default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() } }));
vi.mock('../../../contexts/AuthContext', () => ({ useAuth: () => ({ user: { role: 'SUPERADMIN', userId: 1 } }) }));
vi.mock('../../../features/tracker/NotebookTracker', () => ({ NotebookTracker: () => <div>tracker</div> }));

const NOT_UNDERSTOOD = 'The response was not understood.';
const answer = (overrides: Record<string, unknown> = {}) =>
  vi.mocked(api.get).mockImplementation(async (url: string) => ({ data: url in overrides ? overrides[url] : {} }) as never);
beforeEach(() => { vi.clearAllMocks(); answer(); });
afterEach(cleanup);

it('shows the not-understood state with Retry for every list on the Operators tab, and stays standing', async () => {
  render(<NotebookAdminPanel />);
  await waitFor(() => expect(screen.getAllByRole('alert').length).toBeGreaterThanOrEqual(4));
  const messages = screen.getAllByRole('alert').map((a) => a.textContent);
  expect(messages.filter((m) => m === NOT_UNDERSTOOD).length).toBeGreaterThanOrEqual(3);
  expect(screen.getAllByRole('button', { name: /Retry/ }).length).toBeGreaterThanOrEqual(4);
  expect(screen.getByRole('heading', { name: 'Notebook workflow set-up' })).toBeInTheDocument();
});

it('shows the not-understood state for the Designers list', async () => {
  render(<NotebookAdminPanel />);
  fireEvent.click(screen.getByRole('tab', { name: 'Designers' }));
  // The designers list and the schools list both fail, so two messages with a Retry each.
  await waitFor(() => expect(screen.getAllByText(NOT_UNDERSTOOD)).toHaveLength(2));
  expect(screen.getAllByRole('button', { name: /Retry/ })).toHaveLength(2);
  expect(screen.queryByText('No designers yet.')).not.toBeInTheDocument();
});

it('shows the not-understood state for the time slots of a school', async () => {
  answer({ '/sa/schools': [{ id: 7, name: 'School Seven' }] });
  render(<NotebookAdminPanel />);
  fireEvent.click(screen.getByRole('tab', { name: 'Time slots' }));
  fireEvent.change(await screen.findByLabelText('School'), { target: { value: '7' } });
  expect(await screen.findByText(NOT_UNDERSTOOD)).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /Retry/ })).toBeInTheDocument();
});

it('shows the not-understood state for the worklist instead of an empty list', async () => {
  render(<NotebookWorklistPanel />);
  expect(await screen.findByRole('alert')).toHaveTextContent(NOT_UNDERSTOOD);
  expect(screen.queryByText(/No notebook orders/)).not.toBeInTheDocument();
});

it('rejects a non-list from every guarded wrapper', async () => {
  await expect(trackerApi.designers()).rejects.toThrow(NOT_UNDERSTOOD);
  await expect(trackerApi.timeSlots(7)).rejects.toThrow(NOT_UNDERSTOOD);
  await expect(trackerApi.operators()).rejects.toThrow(NOT_UNDERSTOOD);
  await expect(trackerApi.operatorUsers()).rejects.toThrow(NOT_UNDERSTOOD);
  await expect(trackerApi.schools()).rejects.toThrow(NOT_UNDERSTOOD);
  await expect(trackerApi.worklist({ scope: 'all' })).rejects.toThrow(NOT_UNDERSTOOD);
  await expect(trackerApi.draftImages('CK-1')).rejects.toThrow(NOT_UNDERSTOOD);
  await expect(trackerApi.categories()).rejects.toThrow();
  await expect(trackerApi.summary('CK-1')).rejects.toThrow(NOT_UNDERSTOOD);
});

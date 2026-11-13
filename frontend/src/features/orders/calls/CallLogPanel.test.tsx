import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { CallLogEntry, VendorSummary } from '../../../generated/orderWorkflowClient';
import { callsApi } from './api';
import { CallLogPanel, type CallLogPanelProps } from './CallLogPanel';

vi.mock('./api', () => ({ callsApi: { list: vi.fn(), log: vi.fn() } }));

const VENDORS: VendorSummary[] = [
  { id: 1, name: 'Metro Offset Printers', categories: ['OFFSET_PRINTERS'], contactPerson: 'Ravi', phone: '98100 12345', city: null, whatsappGroupUrl: null, sample: false, active: true },
  { id: 2, name: 'Perfect Bindery', categories: ['BINDERS'], contactPerson: null, phone: null, city: null, whatsappGroupUrl: null, sample: false, active: true },
];
const CONTACTS = { school: { name: 'Rajesh Kumar', phone: '98111 22233' }, designer: { id: 7, name: 'Priya Singh' } };

function entry(id: number, overrides: Partial<CallLogEntry> = {}): CallLogEntry {
  return {
    id, stepOrder: 6, contacted: 'SCHOOL', outcome: 'CONNECTED', contactLabel: 'Rajesh Kumar', vendorId: null, designerId: null, notes: null,
    nextFollowUpAt: null, createdBy: 3, createdByName: 'Anita Sharma', createdAt: `2026-11-1${id % 10}T06:30:00Z`, ...overrides,
  };
}

function renderPanel(props: Partial<CallLogPanelProps> = {}) {
  const onLogged = vi.fn();
  const view = render(<CallLogPanel orderId="CK-1015" currentStep={6} contacts={CONTACTS} vendors={VENDORS} canLog onLogged={onLogged} {...props} />);
  return { ...view, onLogged };
}

const form = () => screen.getByRole('form', { name: 'Log a call or discussion' });

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(callsApi.list).mockResolvedValue([]);
});
afterEach(cleanup);

describe('call log form (section 4.6)', () => {
  it('offers the SOP entities and outcomes, with who to pick built from the entity', async () => {
    renderPanel();
    const f = form();
    expect(within(f).getByRole('combobox', { name: 'Contacted' })).toHaveValue('SCHOOL');
    expect(within(f).getByRole('combobox', { name: 'Outcome' })).toHaveValue('CONNECTED');
    expect(within(f).getByRole('option', { name: 'Rajesh Kumar · School contact · 98111 22233' })).toBeInTheDocument();
    fireEvent.change(within(f).getByRole('combobox', { name: 'Contacted' }), { target: { value: 'PRINTING_PRESS' } });
    expect(within(f).getAllByRole('option').map((option) => option.textContent)).toEqual(expect.arrayContaining(['Metro Offset Printers · Ravi · 98100 12345', 'Someone else']));
    expect(within(f).queryByRole('option', { name: 'Perfect Bindery' })).toBeNull();
    fireEvent.change(within(f).getByRole('combobox', { name: 'Contacted' }), { target: { value: 'INTERNAL_APPROVER' } });
    expect(within(f).getByRole('textbox', { name: 'Name of the person *' })).toBeInTheDocument();
    expect(await screen.findByText('No calls logged yet.')).toBeInTheDocument();
  });

  it('counts words as they are typed and refuses notes over 100 words', async () => {
    renderPanel();
    const notes = within(form()).getByRole('textbox', { name: 'Call notes' });
    fireEvent.change(notes, { target: { value: 'Spoke   to the\nprincipal' } });
    expect(screen.getByText('4/100 words')).toBeInTheDocument();
    expect(notes).toHaveAccessibleDescription('4/100 words');
    fireEvent.change(notes, { target: { value: Array.from({ length: 101 }, () => 'w').join(' ') } });
    expect(screen.getByText('101/100 words')).toHaveClass('is-over');
    fireEvent.click(screen.getByRole('button', { name: 'Log call' }));
    expect(await screen.findByText('Keep call notes to 100 words (101/100 words).')).toBeInTheDocument();
    expect(callsApi.log).not.toHaveBeenCalled();
  });

  it('needs notes for a delay and a follow-up time for a callback before anything is sent', async () => {
    renderPanel();
    const f = form();
    fireEvent.change(within(f).getByRole('combobox', { name: 'Outcome' }), { target: { value: 'DELAY_REPORTED' } });
    expect(within(f).queryByLabelText('Next follow-up *')).toBeNull();
    fireEvent.click(within(f).getByRole('button', { name: 'Log call' }));
    expect(within(f).getByText('Add call notes for Delay Reported.')).toBeInTheDocument();
    expect(within(f).getByRole('textbox', { name: 'Call notes *' })).toHaveAttribute('aria-invalid', 'true');

    fireEvent.change(within(f).getByRole('combobox', { name: 'Outcome' }), { target: { value: 'CALLBACK_REQUESTED' } });
    fireEvent.change(within(f).getByRole('textbox', { name: 'Call notes *' }), { target: { value: 'Principal asks us to call after 4' } });
    expect(within(f).getByText('Choose when to follow up.')).toBeInTheDocument();
    expect(callsApi.log).not.toHaveBeenCalled();
  });

  it('logs a callback against the current stage, says so, refreshes the history and tells the order', async () => {
    vi.mocked(callsApi.log).mockResolvedValue(entry(1, { outcome: 'CALLBACK_REQUESTED' }));
    vi.mocked(callsApi.list).mockResolvedValueOnce([]).mockResolvedValue([entry(1, { outcome: 'CALLBACK_REQUESTED', notes: 'Call after 4', nextFollowUpAt: '2099-01-02T10:30:00Z' })]);
    const { onLogged } = renderPanel();
    const f = form();
    fireEvent.change(within(f).getByRole('combobox', { name: 'Outcome' }), { target: { value: 'CALLBACK_REQUESTED' } });
    fireEvent.change(within(f).getByRole('textbox', { name: 'Call notes *' }), { target: { value: ' Call after 4 ' } });
    fireEvent.change(within(f).getByLabelText('Next follow-up *'), { target: { value: '2099-01-02T16:00' } });
    fireEvent.click(within(f).getByRole('button', { name: 'Log call' }));
    await waitFor(() => expect(callsApi.log).toHaveBeenCalledWith('CK-1015', {
      contacted: 'SCHOOL', outcome: 'CALLBACK_REQUESTED', contactLabel: 'Rajesh Kumar', vendorId: null, designerId: null, stepOrder: 6,
      notes: 'Call after 4', nextFollowUpAt: new Date('2099-01-02T16:00').toISOString(),
    }));
    expect(await screen.findByText('Call logged')).toBeInTheDocument();
    expect(onLogged).toHaveBeenCalledTimes(1);
    expect(await screen.findByText('Call after 4')).toBeInTheDocument();
    expect(within(form()).getByRole('combobox', { name: 'Outcome' })).toHaveValue('CONNECTED');
    expect(within(form()).getByRole('textbox', { name: 'Call notes' })).toHaveValue('');
  });

  it('keeps its status region mounted before a call is logged, so the confirmation is announced', async () => {
    vi.mocked(callsApi.log).mockResolvedValue(entry(1));
    renderPanel();
    const region = screen.getAllByRole('status').find((node) => node.getAttribute('aria-live') === 'polite');
    expect(region).toBeDefined();
    expect(region).toBeEmptyDOMElement();
    fireEvent.click(within(form()).getByRole('button', { name: 'Log call' }));
    await waitFor(() => expect(region).toHaveTextContent('Call logged'));
  });

  it("raises the confirmation in the host's toast when the host offers one", async () => {
    vi.mocked(callsApi.log).mockResolvedValue(entry(1));
    const onToast = vi.fn();
    renderPanel({ onToast });
    fireEvent.click(within(form()).getByRole('button', { name: 'Log call' }));
    await waitFor(() => expect(onToast).toHaveBeenCalledWith('Call logged'));
    expect(screen.queryByText('Call logged')).not.toBeInTheDocument();
  });

  it('logs a call to the designer by its directory id, and keeps a server refusal on screen', async () => {
    vi.mocked(callsApi.log).mockRejectedValueOnce({ response: { status: 400, data: { code: 'NOTE_TOO_LONG', message: 'Call notes are limited to 100 words.' } } });
    const { onLogged } = renderPanel();
    fireEvent.change(within(form()).getByRole('combobox', { name: 'Contacted' }), { target: { value: 'DESIGNER' } });
    fireEvent.click(within(form()).getByRole('button', { name: 'Log call' }));
    await waitFor(() => expect(callsApi.log).toHaveBeenCalledWith('CK-1015', expect.objectContaining({ contacted: 'DESIGNER', designerId: 7, contactLabel: 'Priya Singh' })));
    expect(await within(form()).findByRole('alert')).toHaveTextContent('Call notes are limited to 100 words.');
    expect(onLogged).not.toHaveBeenCalled();
  });

  it('applies a preset such as School / Changes Requested and puts the cursor in the notes', async () => {
    const { rerender, onLogged } = renderPanel();
    rerender(<CallLogPanel orderId="CK-1015" currentStep={6} contacts={CONTACTS} vendors={VENDORS} canLog onLogged={onLogged}
      preset={{ contacted: 'SCHOOL', outcome: 'CHANGES_REQUESTED' }} />);
    expect(within(form()).getByRole('combobox', { name: 'Outcome' })).toHaveValue('CHANGES_REQUESTED');
    expect(within(form()).getByRole('textbox', { name: 'Call notes *' })).toHaveFocus();
    expect(await screen.findByText('No calls logged yet.')).toBeInTheDocument();
  });

  it('starts clean after a preset call is logged, and applies the same preset again when the host offers it again', async () => {
    vi.mocked(callsApi.log).mockResolvedValue(entry(1, { outcome: 'CHANGES_REQUESTED' }));
    const preset = { contacted: 'SCHOOL', outcome: 'CHANGES_REQUESTED' } as const;
    const onLogged = vi.fn();
    const panel = (next?: typeof preset) => <CallLogPanel orderId="CK-1015" currentStep={6} contacts={CONTACTS} vendors={VENDORS} canLog onLogged={onLogged} preset={next} />;
    const { rerender } = render(panel(preset));
    fireEvent.change(within(form()).getByRole('textbox', { name: 'Call notes *' }), { target: { value: 'Bigger logo' } });
    fireEvent.click(within(form()).getByRole('button', { name: 'Log call' }));
    await waitFor(() => expect(onLogged).toHaveBeenCalledTimes(1));
    // The order screen clears its preset once the call is logged.
    rerender(panel(undefined));
    expect(within(form()).getByRole('combobox', { name: 'Outcome' })).toHaveValue('CONNECTED');
    fireEvent.change(within(form()).getByRole('combobox', { name: 'Outcome' }), { target: { value: 'BUSY' } });
    rerender(panel(preset));
    expect(within(form()).getByRole('combobox', { name: 'Outcome' })).toHaveValue('CHANGES_REQUESTED');
    expect(within(form()).getByRole('textbox', { name: 'Call notes *' })).toHaveFocus();
  });

  it('shows only the history to someone who cannot log calls', async () => {
    vi.mocked(callsApi.list).mockResolvedValue([entry(1)]);
    renderPanel({ canLog: false });
    expect(screen.queryByRole('form')).toBeNull();
    expect(await screen.findByRole('list', { name: 'Logged calls' })).toBeInTheDocument();
  });
});

describe('call history', () => {
  it('lists the newest ten calls with their outcome, who, stage, author and notes, then shows all on request', async () => {
    const eleven = Array.from({ length: 11 }, (_, index) => entry(index + 1, { createdAt: new Date(Date.UTC(2026, 10, 1 + index, 6, 30)).toISOString() }));
    eleven[10] = { ...eleven[10], contacted: 'PRINTING_PRESS', contactLabel: null, vendorId: 1, outcome: 'DELAY_REPORTED', notes: 'Plates late by a day' };
    vi.mocked(callsApi.list).mockResolvedValueOnce([...eleven].reverse()).mockResolvedValue([...eleven, entry(12, { createdAt: '2026-10-01T06:30:00Z' })]);
    renderPanel();
    const list = await screen.findByRole('list', { name: 'Logged calls' });
    expect(callsApi.list).toHaveBeenCalledWith('CK-1015', 11);
    const items = within(list).getAllByRole('listitem');
    expect(items).toHaveLength(10);
    expect(items[0]).toHaveTextContent('Delay Reported');
    expect(items[0]).toHaveTextContent('Printing Press · Metro Offset Printers');
    expect(items[0]).toHaveTextContent(/Step 6 · 11 Nov, 12:00 pm · Anita Sharma|Step 6 · 11 Nov, .* · Anita Sharma/);
    expect(items[0]).toHaveTextContent('Plates late by a day');
    expect(within(items[0]).getByText('Delay Reported')).toHaveClass('is-danger');
    fireEvent.click(screen.getByRole('button', { name: 'Show all' }));
    await waitFor(() => expect(within(screen.getByRole('list', { name: 'Logged calls' })).getAllByRole('listitem')).toHaveLength(12));
    expect(callsApi.list).toHaveBeenLastCalledWith('CK-1015', 200);
    expect(screen.queryByRole('button', { name: 'Show all' })).toBeNull();
  });

  it('hands "Show all" to the host when it offers the full history elsewhere (Activity history on the order screen)', async () => {
    const eleven = Array.from({ length: 11 }, (_, index) => entry(index + 1, { createdAt: new Date(Date.UTC(2026, 10, 1 + index, 6, 30)).toISOString() }));
    vi.mocked(callsApi.list).mockResolvedValue(eleven);
    const onShowAll = vi.fn();
    renderPanel({ onShowAll });
    await screen.findByRole('list', { name: 'Logged calls' });
    fireEvent.click(screen.getByRole('button', { name: 'Show all' }));
    expect(onShowAll).toHaveBeenCalledTimes(1);
    expect(callsApi.list).toHaveBeenCalledTimes(1);
    expect(within(screen.getByRole('list', { name: 'Logged calls' })).getAllByRole('listitem')).toHaveLength(10);
  });

  it('reports a history that cannot load and retries it', async () => {
    vi.mocked(callsApi.list).mockRejectedValueOnce({ response: { status: 502 } }).mockResolvedValue([entry(1)]);
    renderPanel();
    expect(await screen.findByText('The order workflow service is not available right now. Try again in a moment.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('list', { name: 'Logged calls' })).toBeInTheDocument();
  });
});

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ZoneAdminPanel } from './ZoneAdminPanel';
import api from '../../../services/api';

vi.mock('../../../services/api');
afterEach(cleanup);
beforeEach(() => vi.mocked(api.get).mockReset());
describe('zone school scope', () => {
  it('loads only the assigned zone endpoint and filters its schools', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: [
      { id: 1, schoolId: 11, schoolName: 'North School', schoolCity: 'Delhi', schoolShortCode: 'NOR', active: true },
      { id: 2, schoolId: 12, schoolName: 'South School', schoolCity: 'Pune', schoolShortCode: 'SOU', active: true },
    ] });
    render(<ZoneAdminPanel zoneId={9} zoneName="Central zone" view="schools" setPanel={vi.fn()} />);
    expect(await screen.findByText('North School')).toBeInTheDocument();
    expect(api.get).toHaveBeenCalledWith('/zones/9/schools', { params: { active: true } });
    fireEvent.change(screen.getByLabelText('Find an assigned school'), { target: { value: 'Pune' } });
    expect(screen.queryByText('North School')).not.toBeInTheDocument();
    expect(screen.getByText('South School')).toBeInTheDocument();
  });
  it('exposes failed reads with retry and never turns a failure into an empty assignment', async () => {
    vi.mocked(api.get).mockRejectedValueOnce(new Error('Offline')).mockResolvedValueOnce({ data: [] });
    render(<ZoneAdminPanel zoneId={9} view="schools" setPanel={vi.fn()} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Unable to load');
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(screen.getByText('No active schools are assigned to this zone.')).toBeInTheDocument());
  });
  it('does not request arbitrary schools for an account without a zone', () => {
    render(<ZoneAdminPanel view="overview" setPanel={vi.fn()} />);
    expect(screen.getByRole('status')).toHaveTextContent('does not have a zone assignment');
    expect(api.get).not.toHaveBeenCalled();
  });
});

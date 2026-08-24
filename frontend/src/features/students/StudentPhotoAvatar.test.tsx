import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import api from '../../services/api';
import { StudentPhotoAvatar, __studentPhotoAvatarTestHooks } from './StudentPhotoAvatar';

vi.mock('../../services/api', () => ({
  default: {
    get: vi.fn(),
  },
}));

afterEach(cleanup);

describe('StudentPhotoAvatar', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    __studentPhotoAvatarTestHooks.objectUrlCache.clear();
    __studentPhotoAvatarTestHooks.pendingLoads.clear();
    vi.stubGlobal('URL', {
      createObjectURL: vi.fn(() => 'blob:student-photo'),
      revokeObjectURL: vi.fn(),
    });
  });

  it('loads protected student photo references through the API client', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: new Blob(['jpeg'], { type: 'image/jpeg' }) });

    render(<StudentPhotoAvatar photoUrl="/students/42/photo/content?v=abc123" name="Aman Verma" />);

    await waitFor(() => expect(screen.getByRole('img', { name: 'Aman Verma' })).toHaveAttribute('src', 'blob:student-photo'));
    expect(screen.getByRole('img', { name: 'Aman Verma' })).toHaveClass('ck-student-photo-full-frame');
    expect(api.get).toHaveBeenCalledWith('/students/42/photo/content?v=abc123', expect.objectContaining({
      responseType: 'blob',
      timeout: 15000,
    }));
  });

  it('uses the same full-frame contract for signed and external photo URLs', async () => {
    render(<StudentPhotoAvatar photoUrl="https://photos.example/aman.jpg" name="Aman Verma" className="ck-att-avatar" />);

    const image = await screen.findByRole('img', { name: 'Aman Verma' });
    expect(image).toHaveAttribute('src', 'https://photos.example/aman.jpg');
    expect(image).toHaveClass('ck-att-avatar', 'ck-student-photo-full-frame');
    expect(api.get).not.toHaveBeenCalled();
  });

  it('shows initials when no photo is available', () => {
    render(<StudentPhotoAvatar photoUrl={null} name="Aman Verma" />);

    expect(screen.getByText('AV')).toBeInTheDocument();
    expect(api.get).not.toHaveBeenCalled();
  });
});

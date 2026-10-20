import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import api from '../../services/api';
import { StudentPhotoAvatar, __studentPhotoAvatarTestHooks } from './StudentPhotoAvatar';

vi.mock('../../services/api', () => ({
  getAuthSessionVersion: vi.fn(() => 1),
  default: {
    get: vi.fn(),
  },
}));
const NativeURL = URL;

afterEach(cleanup);

describe('StudentPhotoAvatar', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    __studentPhotoAvatarTestHooks.objectUrlCache.clear();
    __studentPhotoAvatarTestHooks.pendingLoads.clear();
    vi.stubGlobal('URL', class extends NativeURL {
      static createObjectURL = vi.fn(() => 'blob:student-photo');
      static revokeObjectURL = vi.fn();
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

  it('uses the full-frame contract for HTTPS storage photo URLs without referrers', async () => {
    render(<StudentPhotoAvatar photoUrl="https://storage.googleapis.com/custoking-dev-student-photos/aman.jpg" name="Aman Verma" className="ck-att-avatar" />);

    const image = await screen.findByRole('img', { name: 'Aman Verma' });
    expect(image).toHaveAttribute('src', 'https://storage.googleapis.com/custoking-dev-student-photos/aman.jpg');
    expect(image).toHaveAttribute('referrerpolicy', 'no-referrer');
    expect(image).toHaveClass('ck-att-avatar', 'ck-student-photo-full-frame');
    expect(api.get).not.toHaveBeenCalled();
  });
  it.each(['http://storage.googleapis.com/photo.jpg', 'https://photos.example/aman.jpg', 'javascript:alert(1)', '//evil.example/photo'])('blocks unapproved photo reference %s', async photoUrl => {
    render(<StudentPhotoAvatar photoUrl={photoUrl} name="Aman Verma" />);
    await waitFor(() => expect(screen.queryByRole('img')).not.toBeInTheDocument());
    expect(screen.getByText('AV')).toBeInTheDocument();
    expect(api.get).not.toHaveBeenCalled();
  });

  it('shows initials when no photo is available', () => {
    render(<StudentPhotoAvatar photoUrl={null} name="Aman Verma" />);

    expect(screen.getByText('AV')).toBeInTheDocument();
    expect(api.get).not.toHaveBeenCalled();
  });
});

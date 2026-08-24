import { useEffect, useState } from 'react';
import api from '../../services/api';
import { initials } from '../../pages/workspace/utils';

interface StudentPhotoAvatarProps {
  photoUrl?: string | null;
  name?: string | null;
  className?: string;
  fallbackClassName?: string;
}

/**
 * Shared student-photo display contract.
 *
 * The permanent photo is always shown full-frame: consumers may choose the frame size through
 * their existing surface class, but they must not crop the image to fill that frame. This keeps
 * directory, attendance, detail, and verification views consistent with the bytes used by export.
 */
const FULL_FRAME_PHOTO_CLASS = 'ck-student-photo-full-frame';

const objectUrlCache = new Map<string, string>();
const pendingLoads = new Map<string, Promise<string | null>>();
const MAX_CACHED_PHOTOS = 300;

function isApiPhotoReference(value: string): boolean {
  return value.startsWith('/students/') && value.includes('/photo/content');
}

function rememberObjectUrl(key: string, value: string) {
  if (!objectUrlCache.has(key) && objectUrlCache.size >= MAX_CACHED_PHOTOS) {
    const oldest = objectUrlCache.keys().next().value;
    if (oldest) {
      const oldUrl = objectUrlCache.get(oldest);
      if (oldUrl) URL.revokeObjectURL(oldUrl);
      objectUrlCache.delete(oldest);
    }
  }
  objectUrlCache.set(key, value);
}

async function resolvePhotoUrl(photoUrl: string): Promise<string | null> {
  if (!isApiPhotoReference(photoUrl)) {
    return photoUrl;
  }
  const cached = objectUrlCache.get(photoUrl);
  if (cached) {
    return cached;
  }
  let pending = pendingLoads.get(photoUrl);
  if (!pending) {
    pending = api.get<Blob>(photoUrl, {
      responseType: 'blob',
      timeout: 15000,
    }).then((response) => {
      const objectUrl = URL.createObjectURL(response.data);
      rememberObjectUrl(photoUrl, objectUrl);
      return objectUrl;
    }).catch(() => null).finally(() => {
      pendingLoads.delete(photoUrl);
    });
    pendingLoads.set(photoUrl, pending);
  }
  return pending;
}

export function StudentPhotoAvatar({
  photoUrl,
  name,
  className = 'ck-student-avatar',
  fallbackClassName = 'ck-student-avatar ck-student-avatar-fallback',
}: StudentPhotoAvatarProps) {
  const [resolvedUrl, setResolvedUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const displayName = name || 'Student';

  useEffect(() => {
    let active = true;
    setFailed(false);
    setResolvedUrl(null);
    const source = photoUrl?.trim();
    if (!source) {
      return () => {
        active = false;
      };
    }
    void resolvePhotoUrl(source).then((url) => {
      if (active) {
        setResolvedUrl(url);
        setFailed(!url);
      }
    });
    return () => {
      active = false;
    };
  }, [photoUrl]);

  if (!resolvedUrl || failed) {
    return <div className={fallbackClassName}>{initials(displayName)}</div>;
  }
  return (
    <img
      src={resolvedUrl}
      alt={displayName}
      className={`${className} ${FULL_FRAME_PHOTO_CLASS}`.trim()}
      loading="lazy"
      decoding="async"
      onError={() => setFailed(true)}
    />
  );
}

export const __studentPhotoAvatarTestHooks = {
  objectUrlCache,
  pendingLoads,
};

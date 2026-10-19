import { useCallback, useEffect, useRef, useState } from 'react';
import { FileText, Trash2, Upload } from 'lucide-react';
import { errorMessage } from '../catalog/api';
import { trackerApi } from './api';
import type { DraftImage } from './types';

export const MAX_DRAFTS = 6;
const ACCEPT = 'image/png,image/jpeg,image/webp,application/pdf';

// Previews use a fixed raster type so a file's own MIME claim can never make the browser render it as a document.
function rasterPreview(data: Blob): Blob | undefined {
  switch (data.type) {
    case 'image/png': return new Blob([data], { type: 'image/png' });
    case 'image/jpeg': return new Blob([data], { type: 'image/jpeg' });
    case 'image/webp': return new Blob([data], { type: 'image/webp' });
    default: return undefined;
  }
}

function Thumb({ orderId, image, selected, onSelect }: { orderId: string; image: DraftImage; selected: boolean; onSelect?: () => void }) {
  const [url, setUrl] = useState('');
  useEffect(() => {
    let active = true; let owned = '';
    trackerApi.draftBlob(orderId, image.id).then((blob) => {
      const preview = rasterPreview(blob);
      if (active && preview) { owned = URL.createObjectURL(preview); setUrl(owned); }
    }).catch(() => { /* A thumbnail that cannot load shows the file name instead. */ });
    return () => { active = false; if (owned) URL.revokeObjectURL(owned); };
  }, [orderId, image.id]);
  return (
    <button type="button" className={`ck-trk-thumb${selected ? ' is-selected' : ''}`} onClick={onSelect} aria-pressed={selected} aria-label={`Draft ${image.originalFilename}`}>
      {url ? <img src={url} alt="" /> : <span className="ck-trk-thumb-file"><FileText size={20} aria-hidden="true" /></span>}
      <span className="ck-trk-thumb-name">{image.originalFilename}</span>
    </button>
  );
}

/**
 * Up to six current design drafts. The newest image is the preview (PDFs have none); a seventh is refused by the server
 * with its own message, which is shown as written.
 */
export function DraftImages({ orderId, canManage, selectedId, onSelect, onChanged }: {
  orderId: string; canManage: boolean; selectedId?: number | null; onSelect?: (id: number) => void; onChanged?: (images: DraftImage[]) => void;
}) {
  const [images, setImages] = useState<DraftImage[] | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const input = useRef<HTMLInputElement>(null);

  const load = useCallback(async () => {
    const next = await trackerApi.draftImages(orderId);
    next.sort((a, b) => a.uploadedAt.localeCompare(b.uploadedAt) || a.id - b.id);
    setImages(next); onChanged?.(next);
  }, [orderId, onChanged]);
  useEffect(() => { load().catch((e: unknown) => setError(errorMessage(e, 'Unable to load the design drafts.'))); }, [load]);

  const run = async (operation: () => Promise<void>) => {
    setBusy(true); setError('');
    try { await operation(); await load(); } catch (e) { setError(errorMessage(e)); } finally { setBusy(false); }
  };
  const upload = (file?: File) => { if (file) void run(() => trackerApi.uploadDraft(orderId, file)); if (input.current) input.current.value = ''; };

  const latest = images?.length ? images[images.length - 1] : undefined;
  return (
    <section className="ck-trk-drafts" aria-label="Design drafts">
      <div className="ck-trk-row">
        <h4>Design drafts <span className="ck-product-muted">{images ? `${images.length} of ${MAX_DRAFTS}` : ''}</span></h4>
        {canManage && (
          <>
            <input ref={input} type="file" accept={ACCEPT} hidden aria-label="Upload design draft" onChange={(e) => upload(e.target.files?.[0])} />
            <button type="button" className="ck-btn ck-btn-ghost" disabled={busy} onClick={() => input.current?.click()}><Upload size={15} aria-hidden="true" /> Upload draft</button>
          </>
        )}
      </div>
      {error && <p className="ck-product-error" role="alert">{error}</p>}
      {images === null && !error && <p className="ck-product-muted">Loading drafts…</p>}
      {images?.length === 0 && <p className="ck-product-muted">No drafts uploaded yet.</p>}
      <ul className="ck-trk-thumbs">
        {images?.map((image) => (
          <li key={image.id}>
            <Thumb orderId={orderId} image={image} selected={selectedId === image.id} onSelect={onSelect ? () => onSelect(image.id) : undefined} />
            {canManage && (
              <button type="button" className="ck-btn ck-btn-ghost ck-product-icon" disabled={busy} title="Delete draft" aria-label={`Delete ${image.originalFilename}`}
                onClick={() => void run(() => trackerApi.deleteDraft(orderId, image.id))}><Trash2 size={15} aria-hidden="true" /></button>
            )}
          </li>
        ))}
      </ul>
      {latest && <p className="ck-product-muted">Latest: {latest.originalFilename}</p>}
    </section>
  );
}

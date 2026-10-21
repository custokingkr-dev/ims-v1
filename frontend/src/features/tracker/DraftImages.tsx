import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { FileText, Trash2, Upload } from 'lucide-react';
import { trackerApi, trackerError } from './api';
import type { DraftImage } from './types';
import '../catalog/product-form.css';

export const MAX_DRAFTS = 6;
const ACCEPT = 'image/png,image/jpeg,image/webp,application/pdf';
const RASTER_TYPES = new Set(['image/png', 'image/jpeg', 'image/webp']);

/** Only PNG, JPEG and WEBP drafts are drawn; a PDF is shown as a file and its bytes are never fetched for a picture. */
const isRaster = (draft: DraftImage) => RASTER_TYPES.has(draft.contentType);
const draftName = (draft: DraftImage) => draft.originalFilename || `Draft ${draft.id}`;

// Previews use a fixed raster type so a file's own MIME claim can never make the browser render it as a document.
function rasterPreview(data: Blob): Blob | undefined {
  switch (data.type) {
    case 'image/png': return new Blob([data], { type: 'image/png' });
    case 'image/jpeg': return new Blob([data], { type: 'image/jpeg' });
    case 'image/webp': return new Blob([data], { type: 'image/webp' });
    default: return undefined;
  }
}

/**
 * Picture URLs for the listed raster drafts: a URL, null when the picture could not be loaded, absent while loading.
 * Each draft is downloaded once while it stays listed, and its URL is released when it leaves the list or the list unmounts.
 */
function useDraftPictures(orderId: string, drafts: DraftImage[] | null): Record<number, string | null> {
  const [pictures, setPictures] = useState<Record<number, string | null>>({});
  const owned = useRef(new Map<number, string | null>());
  const requested = useRef(new Set<number>());
  const mounted = useRef(true);
  const publish = () => setPictures(Object.fromEntries(owned.current));

  useEffect(() => {
    mounted.current = true;
    const urls = owned.current; const asked = requested.current;
    return () => {
      mounted.current = false;
      for (const url of urls.values()) if (url) URL.revokeObjectURL(url);
      urls.clear(); asked.clear();
    };
  }, []);

  useEffect(() => {
    const wanted = new Set((drafts ?? []).filter(isRaster).map((draft) => draft.id));
    let released = false;
    for (const id of [...requested.current]) {
      if (wanted.has(id)) continue;
      const url = owned.current.get(id);
      if (url) URL.revokeObjectURL(url);
      released = owned.current.delete(id) || released;
      requested.current.delete(id);
    }
    if (released) publish();
    for (const id of wanted) {
      if (requested.current.has(id)) continue;
      requested.current.add(id);
      const settle = (url: string | null) => {
        if (!mounted.current || !requested.current.has(id) || owned.current.has(id)) { if (url) URL.revokeObjectURL(url); return; }
        owned.current.set(id, url); publish();
      };
      trackerApi.draftBlob(orderId, id).then((blob) => {
        const picture = rasterPreview(blob);
        settle(picture ? URL.createObjectURL(picture) : null);
      }, () => settle(null));
    }
  }, [orderId, drafts]);
  return pictures;
}

function Picture({ draft, url, alt }: { draft: DraftImage; url: string | null | undefined; alt: string }) {
  if (url) return <img src={url} alt={alt} />;
  return (
    <span className="ck-trk-thumb-file">
      <FileText size={20} aria-hidden="true" />{!isRaster(draft) && <span>PDF</span>}
    </span>
  );
}

/**
 * Up to six current design drafts. With `preview`, the newest image is shown large (PDFs are never the preview); with
 * `onSelect`, the drafts become one choice of the version to approve. A seventh upload is refused by the server with its
 * own message, which is shown as written.
 */
export function DraftImages({ orderId, canManage, preview = false, selectedId, onSelect }: {
  orderId: string; canManage: boolean; preview?: boolean; selectedId?: number | null; onSelect?: (id: number) => void;
}) {
  const [images, setImages] = useState<DraftImage[] | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const group = useId();
  const pictures = useDraftPictures(orderId, images);

  const load = useCallback(async () => {
    const next = await trackerApi.draftImages(orderId);
    next.sort((a, b) => a.uploadedAt.localeCompare(b.uploadedAt) || a.id - b.id);
    setImages(next);
  }, [orderId]);
  useEffect(() => { load().catch((e: unknown) => setError(trackerError(e, 'Unable to load the design drafts.'))); }, [load]);

  const run = async (operation: () => Promise<void>) => {
    setBusy(true); setError('');
    try { await operation(); await load(); } catch (e) { setError(trackerError(e)); } finally { setBusy(false); }
  };
  const upload = (file?: File) => { if (file) void run(() => trackerApi.uploadDraft(orderId, file)); if (input.current) input.current.value = ''; };

  const latest = images?.length ? images[images.length - 1] : undefined;
  const rasters = images?.filter(isRaster) ?? [];
  const latestImage = rasters[rasters.length - 1];
  const list = (
    <ul className="ck-trk-thumbs">
      {images?.map((image) => (
        <li key={image.id}>
          {onSelect ? (
            <label className={`ck-trk-thumb is-choice${selectedId === image.id ? ' is-selected' : ''}`}>
              <input type="radio" className="ck-trk-thumb-radio" name={group} checked={selectedId === image.id} onChange={() => onSelect(image.id)} />
              <Picture draft={image} url={pictures[image.id]} alt="" />
              <span className="ck-trk-thumb-name">{draftName(image)}</span>
            </label>
          ) : (
            <figure className="ck-trk-thumb">
              <Picture draft={image} url={pictures[image.id]} alt={draftName(image)} />
              <figcaption className="ck-trk-thumb-name">{draftName(image)}</figcaption>
            </figure>
          )}
          {canManage && (
            <button type="button" className="ck-btn ck-btn-ghost ck-product-icon" disabled={busy} title="Delete draft" aria-label={`Delete ${draftName(image)}`}
              onClick={() => void run(() => trackerApi.deleteDraft(orderId, image.id))}><Trash2 size={15} aria-hidden="true" /></button>
          )}
        </li>
      ))}
    </ul>
  );

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
      {preview && images && images.length > 0 && (
        <figure className="ck-trk-preview">
          <figcaption>Current design preview</figcaption>
          {!latestImage ? <p className="ck-product-muted">No image uploaded yet; PDFs are not previewed.</p>
            : pictures[latestImage.id] ? <img src={pictures[latestImage.id] as string} alt={`Current design preview: ${draftName(latestImage)}`} />
              : <p className="ck-product-muted">{pictures[latestImage.id] === null ? `The preview of ${draftName(latestImage)} could not be loaded.` : 'Loading preview…'}</p>}
        </figure>
      )}
      {onSelect && images && images.length > 0
        ? <fieldset className="ck-product-fieldset"><legend className="ck-trk-legend">Design version to approve</legend>{list}</fieldset>
        : list}
      {latest && <p className="ck-product-muted">Latest: {draftName(latest)}</p>}
    </section>
  );
}

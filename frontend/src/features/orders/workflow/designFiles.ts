import { useEffect, useState } from 'react';
import { workflowApi } from './api';
import { formatBytes } from './model';
import type { DesignFile } from './types';

/**
 * Design file limits (D26): at most 4 current files per order, 25 MB in total across them, in PDF, CDR, PNG or JPEG. The
 * server checks again under a lock on the order and its refusal is shown word for word; these checks only stop an upload
 * the server would refuse before any bytes are sent.
 */
export const MAX_DESIGN_FILES = 4;
export const MAX_DESIGN_TOTAL_BYTES = 25 * 1024 * 1024;
export const DESIGN_FILE_ACCEPT = '.pdf,.cdr,.png,.jpg,.jpeg,application/pdf,image/png,image/jpeg';
export const DESIGN_FILE_CAPTION = 'Up to 4 files · 25 MB in total · PDF, CDR, PNG, JPEG';

const EXTENSIONS = ['pdf', 'cdr', 'png', 'jpg', 'jpeg'];
/** Pictures shown as previews: the upload types, and WEBP, which is no longer accepted but whose earlier files stay (D26). */
const RASTER_TYPES = new Set(['image/png', 'image/jpeg', 'image/webp']);
/** How school-core stores and serves a CorelDRAW file (D27). */
const CORELDRAW = 'application/vnd.corel-draw';

export function extensionOf(name: string): string {
  const dot = name.lastIndexOf('.');
  return dot < 0 ? '' : name.slice(dot + 1).toLowerCase();
}

export function isCdr(file: Pick<DesignFile, 'contentType' | 'originalFilename'>): boolean {
  return file.contentType === CORELDRAW || extensionOf(file.originalFilename ?? '') === 'cdr';
}

export function isRasterFile(file: Pick<DesignFile, 'contentType'>): boolean {
  return RASTER_TYPES.has(file.contentType);
}

/** The file's kind for a tile: CDR, PDF, PNG, JPEG or WEBP, else its extension, else "File". */
export function designFileKind(file: Pick<DesignFile, 'contentType' | 'originalFilename'>): string {
  if (isCdr(file)) return 'CDR';
  const byType: Record<string, string> = { 'application/pdf': 'PDF', 'image/png': 'PNG', 'image/jpeg': 'JPEG', 'image/webp': 'WEBP' };
  const extension = extensionOf(file.originalFilename ?? '');
  return byType[file.contentType] ?? (/^[a-z0-9]{1,5}$/.test(extension) ? extension.toUpperCase() : 'File');
}

/**
 * The name a design file is shown and downloaded under. School-core stores a CorelDRAW file under a name ending in .cdr
 * (and serves it so); a file stored before that may lack it, so it is added here too, the way school-core adds it
 * ("Final_Artwork" → "Final_Artwork.cdr"), and a download is never saved without the extension that opens it.
 */
export function designFileName(file: Pick<DesignFile, 'id' | 'contentType' | 'originalFilename'>): string {
  const stored = file.originalFilename?.trim() ?? '';
  if (file.contentType !== CORELDRAW) return stored || `Design file ${file.id}`;
  const name = stored.replace(/\.+$/, '') || `Design file ${file.id}`;
  return extensionOf(name) === 'cdr' ? name : `${name}.cdr`;
}

export interface DesignUsage { count: number; bytes: number; label: string }

const MIB = 1024 * 1024;

/** Megabytes to one decimal, "<0.1" for a small but non-empty total: "18.4", "0", "<0.1". */
function megabytes(bytes: number): string {
  if (bytes > 0 && bytes < 0.05 * MIB) return '<0.1';
  return (bytes / MIB).toFixed(1).replace(/\.0$/, '');
}

/** "2 of 4 files · 18.4 of 25 MB". */
export function designUsage(current: Pick<DesignFile, 'sizeBytes'>[]): DesignUsage {
  const bytes = current.reduce((total, file) => total + (file.sizeBytes || 0), 0);
  return { count: current.length, bytes, label: `${current.length} of ${MAX_DESIGN_FILES} files · ${megabytes(bytes)} of 25 MB` };
}

/** Every reason the chosen files cannot be uploaded alongside the current ones; empty when they can. */
export function uploadProblems(chosen: Pick<File, 'name' | 'size'>[], current: Pick<DesignFile, 'sizeBytes'>[]): string[] {
  const problems: string[] = [];
  const wrongType = chosen.filter((file) => !EXTENSIONS.includes(extensionOf(file.name)));
  if (wrongType.length) problems.push(`${wrongType.map((file) => file.name).join(', ')}: only PDF, CDR, PNG and JPEG files can be uploaded.`);
  if (current.length + chosen.length > MAX_DESIGN_FILES) {
    const room = Math.max(0, MAX_DESIGN_FILES - current.length);
    problems.push(room === 0
      ? `This order already has ${MAX_DESIGN_FILES} design files. Remove one before uploading another.`
      : `An order can hold ${MAX_DESIGN_FILES} design files; there is room for ${room} more.`);
  }
  const usage = designUsage(current);
  const adding = chosen.reduce((total, file) => total + file.size, 0);
  if (usage.bytes + adding > MAX_DESIGN_TOTAL_BYTES) {
    problems.push(`Design files can total 25 MB per order; these would make ${formatBytes(usage.bytes + adding)}.`);
  }
  return problems;
}

/** Previews use a fixed raster type, so a file's own type claim can never make the browser render it as a document. */
function rasterBlob(data: Blob, contentType: string): Blob | null {
  if (contentType === 'image/png') return new Blob([data], { type: 'image/png' });
  if (contentType === 'image/jpeg') return new Blob([data], { type: 'image/jpeg' });
  if (contentType === 'image/webp') return new Blob([data], { type: 'image/webp' });
  return null;
}

/** A picture URL for a raster design file: a URL, null when it could not be loaded, undefined while loading. */
export function useDesignPicture(orderId: string, file: DesignFile | undefined): string | null | undefined {
  const [url, setUrl] = useState<string | null | undefined>(undefined);
  const id = file && isRasterFile(file) ? file.id : null;
  const type = file?.contentType ?? '';
  useEffect(() => {
    setUrl(undefined);
    if (id == null) return undefined;
    let active = true;
    let created: string | null = null;
    workflowApi.designFileBlob(orderId, id).then((blob) => {
      const picture = rasterBlob(blob, type);
      created = picture ? URL.createObjectURL(picture) : null;
      if (active) setUrl(created); else if (created) URL.revokeObjectURL(created);
    }, () => { if (active) setUrl(null); });
    return () => { active = false; if (created) URL.revokeObjectURL(created); };
  }, [orderId, id, type]);
  return url;
}

/**
 * Opens a design file: images and PDFs in a new tab, CDR files (never rendered, D27) as a download. The bytes come from the
 * authenticated asset endpoint, so the file is handed to the browser as a typed blob.
 */
export async function openDesignFile(orderId: string, file: DesignFile, download = false): Promise<void> {
  const data = await workflowApi.designFileBlob(orderId, file.id);
  const cdr = isCdr(file);
  const type = cdr ? 'application/octet-stream' : file.contentType === 'application/pdf' ? 'application/pdf' : (rasterBlob(data, file.contentType)?.type ?? 'application/octet-stream');
  const url = URL.createObjectURL(new Blob([data], { type }));
  if (cdr || download || type === 'application/octet-stream') {
    const link = document.createElement('a');
    link.href = url;
    link.download = designFileName(file);
    link.rel = 'noopener';
    document.body.appendChild(link);
    link.click();
    link.remove();
  } else {
    window.open(url, '_blank', 'noopener,noreferrer');
  }
  window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

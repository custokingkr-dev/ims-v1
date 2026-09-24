import { useEffect, useId, useState } from 'react';
import { Download, FileImage, Upload, X } from 'lucide-react';
import api from '../../services/api';
import type { AssetKind, OrderAsset } from './types';
import './product-form.css';

export function OrderAssetField({ assetKind, label, requirement, orderId, asset, file, onFile, disabled = false, accept, maxBytes }: {
  assetKind: AssetKind; label: string; requirement: string; orderId?: string; asset?: OrderAsset; file?: File;
  onFile?: (file: File | undefined) => void; disabled?: boolean;
  // Both come from the category's own rule: the flex PDF is 10 MB where every image field is 5 MB,
  // and only some fields accept a PDF at all.
  accept?: string; maxBytes?: number;
}) {
  const id = useId();
  const [url, setUrl] = useState('');
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true; let objectUrl = '';
    setUrl(''); setError('');
    if (file) { objectUrl = URL.createObjectURL(file); setUrl(objectUrl); }
    else if (asset && orderId) {
      api.get<Blob>(`/supply/orders/${encodeURIComponent(orderId)}/assets/${asset.id}/content`, { responseType: 'blob' })
        .then(({ data }) => { if (active) { objectUrl = URL.createObjectURL(data); setUrl(objectUrl); } })
        .catch(() => { if (active) setError('Preview could not load. Reopen the order to retry.'); });
    }
    return () => { active = false; if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [asset?.id, file, orderId]);
  const filename = file?.name || asset?.originalFilename;
  const contentType = file?.type || asset?.contentType || '';
  const size = file?.size || asset?.sizeBytes || 0;
  const acceptedTypes = accept ? accept.split(',').map((type) => type.trim()).filter(Boolean)
    : ['image/jpeg', 'image/png', 'image/webp', ...(assetKind === 'DESIGN' ? ['application/pdf'] : [])];
  const acceptsPdf = acceptedTypes.includes('application/pdf');
  const limit = maxBytes && maxBytes > 0 ? maxBytes : 5 * 1024 * 1024;
  const limitMb = Math.round(limit / 1024 / 1024);
  const typeHint = acceptedTypes.length === 1 && acceptsPdf ? 'PDF only.'
    : acceptsPdf ? 'JPEG, PNG, WebP or PDF.' : 'JPEG, PNG or WebP.';
  return <section className="ck-product-asset" aria-labelledby={`${id}-label`}>
    <div className="ck-product-section-head"><h3 id={`${id}-label`}>{label}</h3><span className="ck-product-muted">{requirement}</span></div>
    {filename ? <div className="ck-product-asset-file">
      {url && contentType.startsWith('image/') ? <img className="ck-product-asset-preview" src={url} alt={label} /> : <FileImage size={32} aria-hidden="true" />}
      <div className="ck-product-file-name"><strong>{filename}</strong><span className="ck-product-muted">{(size / 1024 / 1024).toFixed(2)} MB{file ? ' - ready to upload' : ' - uploaded'}</span></div>
      {url && <a className="ck-btn ck-btn-ghost ck-product-icon" href={url} download={filename} title="Download file" aria-label={`Download ${filename}`}><Download size={16} /></a>}
      {file && onFile && <button type="button" className="ck-btn ck-btn-ghost ck-product-icon" disabled={disabled} onClick={() => onFile(undefined)} title="Remove selected file" aria-label={`Remove selected ${label.toLowerCase()}`}><X size={16} /></button>}
    </div> : <p className="ck-product-muted">No file attached</p>}
    {onFile && <div className="ck-product-upload-control"><label className={`ck-btn ck-btn-ghost${disabled ? ' ck-product-disabled' : ''}`} htmlFor={id}><Upload size={15} aria-hidden="true" />{filename ? 'Replace' : 'Choose file'}</label>
      <input id={id} className="ck-product-file-input" type="file" accept={acceptedTypes.join(',')} disabled={disabled} onChange={(event) => {
        const selected = event.target.files?.[0]; event.target.value = '';
        if (!selected) return;
        if (selected.size > limit) { setError(`Choose a file no larger than ${limitMb} MB.`); return; }
        if (!acceptedTypes.includes(selected.type)) { setError(`Choose a file of the accepted type. ${typeHint}`); return; }
        setError(''); onFile(selected);
      }} /><span className="ck-product-muted">{typeHint} Up to {limitMb} MB.</span></div>}
    {error && <p className="ck-product-error" role="alert">{error}</p>}
  </section>;
}

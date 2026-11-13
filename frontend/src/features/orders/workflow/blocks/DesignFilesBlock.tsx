import { useId, useMemo, useRef, useState, type DragEvent } from 'react';
import { Download, Eye, FileText, RefreshCw, Trash2, Upload } from 'lucide-react';
import { formatStamp } from '../../shared/slaFormat';
import { workflowApi, workflowError } from '../api';
import { useWorkflow } from '../context';
import {
  DESIGN_FILE_ACCEPT, DESIGN_FILE_CAPTION, MAX_DESIGN_FILES, MAX_DESIGN_TOTAL_BYTES, designFileName, designUsage, isCdr, openDesignFile, uploadProblems,
} from '../designFiles';
import { formatBytes, peopleOf, supersededOrCurrent, uploaderName } from '../model';
import type { BlockSpec } from '../types';
import { Badge, BlockCard, blockTitle } from './shared';

/**
 * Upload Design Files (D26): a drop zone and a file picker for several files, checked against the limits before anything is
 * sent, then uploaded one by one. The list shows each current file with its size, uploader and time.
 *
 * The files decide the stage's "at least one design file" gate, which the server works out when it builds the order view,
 * so the view is read again after every upload or removal along with the list.
 */
export function DesignFilesBlock({ block }: { block: BlockSpec }) {
  const { view, designFiles, toast, reload, user } = useWorkflow();
  const [chosen, setChosen] = useState<File[]>([]);
  const [problems, setProblems] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [dragging, setDragging] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const caption = useId();
  const canManage = view.can.canManageDesignFiles;
  const { current } = supersededOrCurrent(designFiles.files ?? []);
  const usage = designUsage(current);
  const people = useMemo(() => peopleOf(view, user), [view, user]);
  const refresh = () => Promise.all([designFiles.reload(), reload()]);

  const choose = (files: File[]) => {
    setChosen(files);
    setProblems(files.length ? uploadProblems(files, current) : []);
  };
  const upload = async () => {
    const blocking = uploadProblems(chosen, current);
    setProblems(blocking);
    if (blocking.length || !chosen.length) return;
    setBusy(true);
    const failures: string[] = [];
    let uploaded = 0;
    for (const file of chosen) {
      try { await workflowApi.uploadDesignFile(view.orderId, file); uploaded += 1; }
      catch (e) { failures.push(`${file.name}: ${workflowError(e, 'The file could not be uploaded.')}`); }
    }
    setBusy(false);
    setChosen([]);
    if (input.current) input.current.value = '';
    setProblems(failures);
    await (uploaded ? refresh() : designFiles.reload());
    if (uploaded) toast(uploaded === 1 ? 'Design file uploaded' : `${uploaded} design files uploaded`);
  };
  const remove = async (id: number, name: string) => {
    setBusy(true); setProblems([]);
    try { await workflowApi.deleteDesignFile(view.orderId, id); toast(`${name} removed`); }
    catch (e) { setProblems([workflowError(e, 'The file could not be removed.')]); }
    finally { setBusy(false); await refresh(); }
  };
  const open = (fileId: number, download: boolean) => {
    const file = current.find((item) => item.id === fileId);
    if (file) openDesignFile(view.orderId, file, download).catch((e: unknown) => setProblems([workflowError(e, 'The file could not be opened.')]));
  };
  const onDrop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    setDragging(false);
    if (canManage && !busy) choose([...event.dataTransfer.files]);
  };

  return (
    <BlockCard title={blockTitle(block)} badge={<Badge>{usage.label}</Badge>}>
      <p className="ck-wf-muted">Upload artwork, title-page drafts, school logos, reference images, PDFs or CDR files for this order.</p>
      {canManage ? (
        <div className={`ck-wf-dropzone${dragging ? ' is-dragging' : ''}`} onDragOver={(e) => { e.preventDefault(); setDragging(true); }}
          onDragLeave={() => setDragging(false)} onDrop={onDrop}>
          <Upload size={22} aria-hidden="true" />
          <div className="ck-wf-strong">Select files to upload</div>
          <div id={caption} className="ck-wf-muted">{DESIGN_FILE_CAPTION}</div>
          <input ref={input} type="file" multiple accept={DESIGN_FILE_ACCEPT} className="ck-sr-only" aria-label="Choose design files" aria-describedby={caption}
            disabled={busy} onChange={(e) => choose([...(e.target.files ?? [])])} />
          <button type="button" className="ck-btn ck-btn-ghost ck-btn-sm" disabled={busy} onClick={() => input.current?.click()}>Choose files</button>
          {chosen.length > 0 && <div className="ck-wf-muted">{chosen.map((file) => `${file.name} (${formatBytes(file.size)})`).join(', ')}</div>}
        </div>
      ) : (
        <p className="ck-wf-muted">Design files are uploaded while Designing Activity is in progress, by its assignee or a superadmin.</p>
      )}
      {problems.length > 0 && (
        <div className="ck-alert ck-alert-re" role="alert"><ul className="ck-wf-plain-list">{problems.map((problem) => <li key={problem}>{problem}</li>)}</ul></div>
      )}
      <div className="ck-wf-block-actions">
        {canManage && <button type="button" className="ck-btn ck-btn-g" disabled={busy || !chosen.length || uploadProblems(chosen, current).length > 0} onClick={() => void upload()}>Upload selected files</button>}
        <button type="button" className="ck-btn ck-btn-ghost" disabled={busy} onClick={() => void designFiles.reload()}><RefreshCw size={14} aria-hidden="true" /> Refresh file list</button>
      </div>
      <div className="ck-wf-usage" aria-hidden="true">
        <span style={{ width: `${Math.min(100, (usage.bytes / MAX_DESIGN_TOTAL_BYTES) * 100)}%` }} />
      </div>
      {designFiles.error && <p className="ck-wf-error" role="alert">{designFiles.error}</p>}
      {designFiles.files === null && !designFiles.error && <p className="ck-wf-muted" role="status">Loading design files…</p>}
      {designFiles.files && current.length === 0 && <p className="ck-wf-muted">Files uploaded for this order will appear here.</p>}
      {current.length > 0 && (
        <ul className="ck-wf-files" aria-label={`Design files, ${current.length} of ${MAX_DESIGN_FILES}`}>
          {current.map((file) => {
            const name = designFileName(file);
            const uploader = uploaderName(file.uploadedBy, people);
            return (
              <li key={file.id}>
                <FileText size={18} aria-hidden="true" />
                <div className="ck-wf-file-who">
                  <div className="ck-wf-strong">{name}</div>
                  <div className="ck-wf-muted">
                    {formatBytes(file.sizeBytes)}{uploader ? ` · ${uploader}` : ''} · {formatStamp(file.uploadedAt, view.timeZone)}
                  </div>
                </div>
                <button type="button" className="ck-btn ck-btn-ghost ck-btn-sm" onClick={() => open(file.id, isCdr(file))}>
                  {isCdr(file) ? <><Download size={13} aria-hidden="true" /> Download</> : <><Eye size={13} aria-hidden="true" /> Preview</>}
                  <span className="ck-sr-only"> {name}</span>
                </button>
                {canManage && (
                  <button type="button" className="ck-btn ck-btn-ghost ck-btn-sm" disabled={busy} onClick={() => void remove(file.id, name)}>
                    <Trash2 size={13} aria-hidden="true" /> Remove<span className="ck-sr-only"> {name}</span>
                  </button>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </BlockCard>
  );
}

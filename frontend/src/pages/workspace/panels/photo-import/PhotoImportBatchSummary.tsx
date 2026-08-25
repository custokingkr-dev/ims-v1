import {
  Check,
  Download,
  LoaderCircle,
  LockKeyhole,
  Play,
  RefreshCw,
  ScanSearch,
  ShieldOff,
  XCircle,
} from 'lucide-react';
import { TransferProgress } from '../../../../components/TransferProgress';
import {
  type AccessState,
  type ImportBatch,
  type OperationProgress,
  type RecoveryProgress,
  statusTone,
  workflowStep,
} from './model';

interface Props {
  batch: ImportBatch;
  appliedRowCount: number;
  busy: string;
  executionConfirmed: boolean;
  operationProgress: OperationProgress | null;
  recoveryProgress: RecoveryProgress | null;
  access: AccessState | null;
  onExecutionConfirmedChange: (confirmed: boolean) => void;
  onRunAction: (action: 'scan' | 'freeze' | 'execute') => void;
  onRecover: () => void;
  onDownloadResult: () => void;
  onCancel: () => void;
  onMarkAccessRevoked: () => void;
  onNewBatch: () => void;
}

const FINISHED_STATUSES = ['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'];

export function PhotoImportBatchSummary({
  batch,
  appliedRowCount,
  busy,
  executionConfirmed,
  operationProgress,
  recoveryProgress,
  access,
  onExecutionConfirmedChange,
  onRunAction,
  onRecover,
  onDownloadResult,
  onCancel,
  onMarkAccessRevoked,
  onNewBatch,
}: Props) {
  const currentStep = workflowStep(batch.status);
  const finished = FINISHED_STATUSES.includes(batch.status);

  return (
    <>
      <section className="pi-progress" aria-label="Import progress">
        {[
          ['Source', 'Folder bound'],
          ['Review', 'Workbook matched'],
          ['Freeze', 'Files locked'],
          ['Execute', 'Portraits applied'],
        ].map(([label, detail], index) => {
          const number = index + 1;
          const complete = number < currentStep || (number === 4 && batch.status === 'COMPLETED');
          return (
            <div className={`pi-step ${number === currentStep ? 'active' : ''} ${complete ? 'complete' : ''}`} key={label}>
              <span className="pi-step-number">{complete ? <Check size={14} /> : number}</span>
              <span><strong>{label}</strong><small>{detail}</small></span>
            </div>
          );
        })}
      </section>

      <section className="pi-batch-head">
        <div>
          <div className="pi-eyebrow">{batch.driveFolderName || 'Drive folder'}</div>
          <h2>{batch.schoolName} / {batch.academicYearLabel}</h2>
          <p>{batch.workbookFileName || 'Workbook not scanned'} <span>Batch {batch.id.slice(0, 8)}</span></p>
        </div>
        <span className={`pi-status ${statusTone(batch.status)}`}>{batch.status}</span>
      </section>

      <section className="pi-metrics" aria-label="Batch totals">
        {[
          ['Rows', batch.totalRows],
          ['Ready', batch.readyCount],
          ['Held', batch.heldCount],
          ['Errors', batch.errorCount],
          ['Applied', batch.appliedCount],
          ['Failed', batch.failedCount],
        ].map(([label, value]) => (
          <div key={label}><span>{label}</span><strong>{value}</strong></div>
        ))}
      </section>

      <section className="pi-action-band" aria-busy={!!busy}>
        <div>
          <strong>
            {batch.status === 'DRAFT' && 'Scan Drive and validate the workbook.'}
            {batch.status === 'REVIEW' && 'Review every held or error row before freezing.'}
            {batch.status === 'FROZEN' && 'The source snapshot is unchanged and ready.'}
            {batch.status === 'EXECUTING' && 'Execution can safely resume from the remaining ready rows.'}
            {['COMPLETED', 'PARTIAL', 'FAILED'].includes(batch.status) && 'Execution result is recorded per row.'}
          </strong>
          <span>{batch.readyCount} ready, {batch.heldCount} held, {batch.errorCount} blocking errors</span>
        </div>
        <div className="pi-actions">
          {['DRAFT', 'REVIEW'].includes(batch.status) && (
            <button className="ck-btn ck-btn-ghost" onClick={() => onRunAction('scan')} disabled={!!busy}>
              {busy === 'scan' ? <LoaderCircle className="pi-spin" size={16} /> : <ScanSearch size={16} />}
              {batch.status === 'DRAFT' ? 'Scan folder' : 'Rescan'}
            </button>
          )}
          {batch.status === 'REVIEW' && (
            <button
              className="ck-btn ck-btn-g"
              onClick={() => onRunAction('freeze')}
              disabled={!!busy || batch.errorCount > 0 || batch.readyCount === 0}
            >
              {busy === 'freeze' ? <LoaderCircle className="pi-spin" size={16} /> : <LockKeyhole size={16} />}
              Freeze source
            </button>
          )}
          {['FROZEN', 'EXECUTING', 'PARTIAL', 'FAILED'].includes(batch.status) && (
            <>
              <label className="pi-execute-confirm">
                <input
                  type="checkbox"
                  checked={executionConfirmed}
                  onChange={event => onExecutionConfirmedChange(event.target.checked)}
                  disabled={!!busy}
                />
                <span>Confirm {batch.schoolName}, {batch.academicYearLabel}, and {batch.readyCount} ready portraits</span>
              </label>
              <button
                className="ck-btn ck-btn-g"
                onClick={() => onRunAction('execute')}
                disabled={!!busy || !executionConfirmed}
              >
                {busy === 'execute' ? <LoaderCircle className="pi-spin" size={16} /> : <Play size={16} />}
                {batch.status === 'FROZEN'
                  ? 'Execute import'
                  : batch.status === 'EXECUTING' ? 'Resume import' : 'Retry failed'}
              </button>
            </>
          )}
          {finished && (
            <>
              {appliedRowCount > 0 && (
                <button className="ck-btn ck-btn-g" onClick={onRecover} disabled={!!busy}>
                  {busy === 'recover'
                    ? <LoaderCircle className="pi-spin" size={16} />
                    : <RefreshCw size={16} />}
                  {recoveryProgress?.resumable && recoveryProgress.processedCount > 0
                    ? 'Resume full-frame recovery'
                    : 'Restore full-frame photos'}
                </button>
              )}
              <button className="ck-btn ck-btn-ghost" onClick={onDownloadResult} disabled={!!busy}>
                {busy === 'result' ? <LoaderCircle className="pi-spin" size={16} /> : <Download size={16} />}
                Download result
              </button>
            </>
          )}
          {['DRAFT', 'REVIEW', 'FROZEN', 'PARTIAL', 'FAILED'].includes(batch.status) && (
            <button className="ck-btn ck-btn-ghost pi-danger-action" onClick={onCancel} disabled={!!busy}>
              {busy === 'cancel' ? <LoaderCircle className="pi-spin" size={16} /> : <XCircle size={16} />}
              Cancel import
            </button>
          )}
          <button className="ck-btn ck-btn-ghost" onClick={onNewBatch} disabled={!!busy}>New batch</button>
        </div>
      </section>

      {operationProgress && (
        <TransferProgress
          label={operationProgress.label}
          detail={operationProgress.detail}
          value={operationProgress.value}
          valueLabel={operationProgress.valueLabel}
          tone={operationProgress.tone}
        />
      )}

      {recoveryProgress && recoveryProgress.totalCount > 0 && (
        <TransferProgress
          label={busy === 'recover'
            ? 'Restoring full-frame photos'
            : recoveryProgress.resumable ? 'Full-frame recovery can resume' : 'Full-frame recovery complete'}
          detail={`${recoveryProgress.processedCount} of ${recoveryProgress.totalCount} reviewed: `
            + `${recoveryProgress.recoveredCount} restored, ${recoveryProgress.protectedCount} protected, `
            + `${recoveryProgress.failedCount} failed, ${recoveryProgress.pendingCount} pending.`}
          value={recoveryProgress.percentComplete}
          valueLabel={`${recoveryProgress.percentComplete}%`}
          tone={busy === 'recover'
            ? 'active'
            : recoveryProgress.failedCount > 0 ? 'error'
              : recoveryProgress.pendingCount === 0 && recoveryProgress.inProgressCount === 0
                ? 'complete' : 'active'}
        />
      )}

      {(finished || access?.overdue) && (
        <section className={`pi-access-band ${access?.overdue ? 'overdue' : ''}`}>
          <ShieldOff size={18} aria-hidden />
          <div>
            <strong>{access?.revokedAt
              ? 'Photographer access closed'
              : finished ? 'Revoke photographer Drive access' : 'Photographer access is overdue'}</strong>
            <span>
              {access?.revokedAt
                ? `Recorded ${new Date(access.revokedAt).toLocaleString()}`
                : access?.expiresAt
                  ? `Reminder due ${new Date(access.expiresAt).toLocaleDateString()}`
                  : 'Remove the photographer as an Editor after delivery.'}
            </span>
          </div>
          {!access?.revokedAt && finished && (
            <button className="ck-btn ck-btn-ghost" onClick={onMarkAccessRevoked} disabled={!!busy}>
              {busy === 'revoke' ? <LoaderCircle className="pi-spin" size={16} /> : <Check size={16} />}
              Mark revoked
            </button>
          )}
        </section>
      )}
    </>
  );
}

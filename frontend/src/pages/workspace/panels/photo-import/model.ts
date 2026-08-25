export interface SchoolContext {
  id: number;
  schoolUid: string;
  name: string;
  shortCode: string;
  academicYearId: string;
  academicYearLabel: string;
  driveFolderStatus: 'NOT_PROVISIONED' | 'PROVISIONING' | 'READY' | 'FAILED';
  driveFolderId?: string;
  driveFolderName?: string;
  driveFolderUrl?: string;
  driveFolderError?: string;
}

export interface ImportContext {
  driveConfigured: boolean;
  managedDriveConfigured: boolean;
  schools: SchoolContext[];
  mappingColumns: string[];
  mappingFileFormats: string[];
  mappingRowLimit?: number;
  imageFileLimit?: string;
  fileNameRule: string;
}

export interface ImportBatch {
  id: string;
  schoolId: number;
  schoolName: string;
  academicYearId: string;
  academicYearLabel: string;
  driveFolderId: string;
  driveFolderName?: string;
  workbookFileName?: string;
  status: string;
  totalRows: number;
  readyCount: number;
  heldCount: number;
  errorCount: number;
  appliedCount: number;
  failedCount: number;
  createdAt: string;
  photographerAccessExpiresAt?: string;
  photographerAccessRevokedAt?: string;
}

export interface AccessState {
  expiresAt?: string;
  revokedAt?: string;
  overdue: boolean;
}

export interface ImportRow {
  id: string;
  excelRow: number;
  admissionNo: string;
  workbookName: string;
  className: string;
  sectionName: string;
  imageNo?: string;
  driveFileName?: string;
  studentId?: number;
  status: 'READY' | 'HELD' | 'ERROR' | 'EXCLUDED' | 'APPLIED' | 'FAILED';
  message?: string;
  cropX: number;
  cropY: number;
  manuallyReviewed: boolean;
  sourceObjectKey?: string;
}

export interface RecoveryRowResult {
  rowId: string;
  status: 'RECOVERED' | 'ALREADY_RECOVERED' | 'IN_PROGRESS' | 'PROTECTED' | 'FAILED';
  photoKey?: string;
  message?: string;
}

export interface RecoveryProgress {
  totalCount: number;
  processedCount: number;
  recoveredCount: number;
  protectedCount: number;
  failedCount: number;
  inProgressCount: number;
  pendingCount: number;
  percentComplete: number;
  resumable: boolean;
  updatedAt?: string;
}

export interface RecoveryBatchResult {
  selectedCount: number;
  recoveredCount: number;
  alreadyRecoveredCount: number;
  inProgressCount: number;
  protectedCount: number;
  failedCount: number;
  rows: RecoveryRowResult[];
  progress: RecoveryProgress;
}

export interface OperationProgress {
  label: string;
  detail: string;
  value?: number;
  valueLabel?: string;
  tone?: 'active' | 'complete' | 'error';
}

export interface EditingState {
  row: ImportRow;
  admissionNo: string;
  imageNo: string;
  excluded: boolean;
  cropX: number;
  cropY: number;
}

export interface PreviewState {
  row: ImportRow;
  url: string;
}

export const FILTERS = ['ALL', 'READY', 'HELD', 'ERROR', 'APPLIED', 'FAILED'] as const;
export type RowFilter = typeof FILTERS[number];
export const PHOTO_IMPORT_REQUEST_CONFIG = { timeout: 120000 };
// Recovery does several remote and transactional operations per photo. Keep each browser request
// comfortably below its timeout while the backend's versioned audit makes the chunks resumable.
export const PHOTO_RECOVERY_CHUNK_SIZE = 5;

export function isTimeoutError(error: any): boolean {
  return error?.code === 'ECONNABORTED' || String(error?.message || '').toLowerCase().includes('timeout');
}

export function errorMessage(error: any): string {
  const data = error?.response?.data;
  if (typeof data === 'string' && data.trim()) return data;
  if (data?.message) return data.message;
  if (data?.detail) return data.detail;
  if (data?.code) return data.code;
  return error?.message || 'The request could not be completed.';
}

export function statusTone(status: string): string {
  if (status === 'READY' || status === 'COMPLETED' || status === 'APPLIED') return 'ok';
  if (status === 'HELD' || status === 'FROZEN' || status === 'EXECUTING') return 'warn';
  if (status === 'ERROR' || status === 'FAILED' || status === 'PARTIAL') return 'bad';
  return 'neutral';
}

export function workflowStep(status?: string): number {
  if (!status || status === 'DRAFT') return 1;
  if (status === 'REVIEW') return 2;
  if (status === 'FROZEN') return 3;
  return 4;
}

export function executionOperationProgress(current: ImportBatch): OperationProgress {
  const processed = current.appliedCount + current.failedCount;
  const total = processed + current.readyCount;
  const complete = current.status !== 'EXECUTING' && current.readyCount === 0;
  const value = total === 0 ? 100 : (processed / total) * 100;
  return {
    label: complete ? 'Photo import complete' : 'Applying student photos',
    detail: complete
      ? `${current.appliedCount} applied; ${current.failedCount} failed.`
      : `${processed} of ${total} executable rows processed; ${current.readyCount} remain.`,
    value,
    valueLabel: `${Math.round(value)}%`,
    tone: complete ? (current.failedCount > 0 ? 'error' : 'complete') : 'active',
  };
}

export function duplicateSchoolNames(schools: SchoolContext[]): Set<string> {
  const counts = new Map<string, number>();
  schools.forEach(school => counts.set(school.name, (counts.get(school.name) || 0) + 1));
  return new Set([...counts.entries()]
    .filter(([, count]) => count > 1)
    .map(([name]) => name));
}

export function schoolOptionLabel(school: SchoolContext, duplicateNames: Set<string>): string {
  if (!duplicateNames.has(school.name)) return `${school.name} (${school.shortCode})`;
  return `${school.name} (${school.shortCode}, #${school.id})`;
}

export function editingStateFor(row: ImportRow): EditingState {
  return {
    row,
    admissionNo: row.admissionNo || '',
    imageNo: row.imageNo || '',
    excluded: row.status === 'EXCLUDED',
    cropX: row.cropX ?? 0.5,
    cropY: row.cropY ?? 0.5,
  };
}

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import api from '../../../services/api';
import { GuardianDataReviewPanel } from './GuardianDataReviewPanel';

vi.mock('../../../services/api', () => ({
  default: { get: vi.fn(), post: vi.fn() },
}));

vi.mock('../../../hooks/usePermissions', () => ({
  usePermissions: () => ({ can: (permission: string) => ['student:read', 'student:update'].includes(permission) }),
}));

vi.mock('../../../contexts/AuthContext', () => ({
  useAuth: () => ({ user: { role: 'ADMIN', branchId: 7 } }),
}));

const reviewCase = {
  caseId: 'a'.repeat(64),
  caseSnapshotSha256: 'b'.repeat(64),
  schoolId: 7,
  studentId: 21,
  admissionNo: 'A-21',
  studentName: 'Anika Rao',
  relationship: 'FATHER',
  fieldName: 'name',
  legacyValue: 'Raj Rao',
  normalizedValue: 'RAJ RAO',
  issueBucket: 'CASE_ONLY',
  linkedStudents: 1,
  phoneClusterGuardians: 1,
  identityCandidates: 1,
  reviewStatus: 'PENDING',
  recommendedDecision: 'ACCEPT_NORMALIZED',
};

describe('GuardianDataReviewPanel', () => {
  beforeEach(() => {
    cleanup();
    vi.clearAllMocks();
    vi.mocked(api.get).mockImplementation(async (url: string) => {
      if (url === '/guardian-data-review/summary') return { data: {
        totalCases: 843, distinctStudents: 449, reviewed: 32, remaining: 811,
        decided: 30, deferred: 1, escalated: 1, progressPercent: 3.8,
        buckets: [{ bucket: 'CASE_ONLY', status: 'PENDING', cases: 32, students: 32 }],
      } } as any;
      if (url === '/guardian-data-review/cases') return { data: {
        content: [reviewCase], page: 0, size: 25, totalElements: 1, totalPages: 1, last: true,
      } } as any;
      throw new Error(`Unexpected GET ${url}`);
    });
    vi.mocked(api.post).mockResolvedValue({ data: { ...reviewCase, reviewStatus: 'DECIDED' } } as any);
  });

  it('shows aggregate progress and mismatch evidence', async () => {
    render(<GuardianDataReviewPanel />);

    expect(await screen.findByText('811 fields require a decision')).toBeInTheDocument();
    expect(screen.getByText('3.8% reviewed')).toBeInTheDocument();
    expect(screen.getByText('Anika Rao')).toBeInTheDocument();
    expect(screen.getByText('Raj Rao')).toBeInTheDocument();
    expect(screen.getByText('RAJ RAO')).toBeInTheDocument();
    expect(screen.getByText(/decisions never mutate records directly/i)).toBeInTheDocument();
  });

  it('records a snapshot-bound decision without applying guardian data', async () => {
    render(<GuardianDataReviewPanel />);
    await screen.findByText('Anika Rao');
    fireEvent.change(screen.getByPlaceholderText('Decision note (optional)'), {
      target: { value: 'School register confirms canonical casing.' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Record decision' }));

    await waitFor(() => expect(api.post).toHaveBeenCalledWith(
      `/guardian-data-review/cases/${reviewCase.caseId}/decisions`,
      {
        caseSnapshotSha256: reviewCase.caseSnapshotSha256,
        decision: 'ACCEPT_NORMALIZED',
        notes: 'School register confirms canonical casing.',
      },
      expect.objectContaining({
        params: { schoolId: 7 },
        headers: expect.objectContaining({ 'Idempotency-Key': expect.any(String) }),
      }),
    ));
    expect(await screen.findByText(/no student or guardian value was changed automatically/i)).toBeInTheDocument();
  });
});

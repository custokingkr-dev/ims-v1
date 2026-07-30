import { describe, expect, it } from 'vitest';
import type { WorkspaceData } from '../../../../types/workspace';
import { deriveCommandCentreCards } from './commandCentreUtils';

function workspace(overrides: Partial<WorkspaceData['dashboard']> = {}): WorkspaceData {
  return {
    school: { name: 'Test School', meta: '2026-27' },
    dashboard: {
      students: 12,
      sections: 2,
      attendancePercent: 0,
      attendancePresent: 0,
      attendanceSubmittedSections: 0,
      attendanceState: 'NOT_STARTED',
      feeCollectedLakh: 0,
      feeTargetLakh: 0,
      feesConfigured: false,
      feeOverdueCount: 0,
      firefightingActive: 0,
      pendingApprovals: 0,
      ...overrides,
    },
    recentActivity: [],
    staff: [],
    annualPlan: { terms: [] },
    fees: {
      summary: {
        progressPercent: 0,
        collected: 0,
        outstanding: 0,
        overdueCount: 0,
        target: 0,
      },
    },
    orders: [],
    firefighting: { requests: [] },
  };
}

describe('deriveCommandCentreCards', () => {
  it('does not turn unsubmitted attendance or an unconfigured fee plan into alerts', () => {
    const ids = deriveCommandCentreCards(workspace()).map(card => card.id);

    expect(ids).not.toContain('cc-attendance-low');
    expect(ids).not.toContain('cc-fee-collection');
  });

  it('creates data-backed attendance and fee actions once those modules have current data', () => {
    const input = workspace({
      attendancePercent: 72,
      attendancePresent: 8,
      attendanceSubmittedSections: 2,
      attendanceState: 'SUBMITTED',
      feeCollectedLakh: 0.1,
      feeTargetLakh: 0.5,
      feesConfigured: true,
    });
    input.fees!.summary = {
      progressPercent: 20,
      collected: 1_000_000,
      outstanding: 4_000_000,
      overdueCount: 0,
      target: 5_000_000,
    };

    const ids = deriveCommandCentreCards(input).map(card => card.id);

    expect(ids).toContain('cc-attendance-low');
    expect(ids).toContain('cc-fee-collection');
  });
});

import type { Phase } from '../../../generated/orderWorkflowClient';

/**
 * The five phases every template's steps belong to (design section 3.1, workflow_steps.phase). They are the board's
 * columns and the Stage filter's groups, in workflow order.
 */
export const PHASES: readonly Phase[] = ['INTAKE', 'PLANNING', 'PRODUCTION', 'VERIFICATION', 'DELIVERY'];

const LABELS: Record<Phase, string> = {
  INTAKE: 'Intake & design',
  PLANNING: 'Planning',
  PRODUCTION: 'Production',
  VERIFICATION: 'Verification',
  DELIVERY: 'Delivery',
};

export function phaseLabel(phase: Phase): string {
  return LABELS[phase];
}

export function isPhase(value: unknown): value is Phase {
  return typeof value === 'string' && (PHASES as readonly string[]).includes(value);
}

/** Items per board column, every column present and each column in the items' own order (the list's sort). */
export function groupByPhase<T extends { phase: Phase }>(items: readonly T[]): Record<Phase, T[]> {
  const columns = Object.fromEntries(PHASES.map((phase) => [phase, [] as T[]])) as Record<Phase, T[]>;
  for (const item of items) columns[item.phase].push(item);
  return columns;
}

/** A template's steps in one phase, in step order. */
export function stepsInPhase<T extends { order: number; phase: Phase }>(steps: readonly T[], phase: Phase): T[] {
  return steps.filter((step) => step.phase === phase).sort((left, right) => left.order - right.order);
}

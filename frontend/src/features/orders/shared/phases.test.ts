import { describe, expect, it } from 'vitest';
import type { StepSpec } from '../../../generated/orderWorkflowClient';
import { PHASES, groupByPhase, isPhase, phaseLabel, stepsInPhase } from './phases';

describe('phases (board columns)', () => {
  it('lists the five board columns in workflow order with their labels', () => {
    expect(PHASES.map(phaseLabel)).toEqual(['Intake & design', 'Planning', 'Production', 'Verification', 'Delivery']);
  });

  it('recognises only phase codes', () => {
    expect(isPhase('PRODUCTION')).toBe(true);
    expect(isPhase('production')).toBe(false);
    expect(isPhase(undefined)).toBe(false);
  });

  it('groups items into every column, keeping their order, with empty columns present', () => {
    const items = [
      { id: 'a', phase: 'PRODUCTION' as const }, { id: 'b', phase: 'INTAKE' as const }, { id: 'c', phase: 'PRODUCTION' as const },
    ];
    const columns = groupByPhase(items);
    expect(Object.keys(columns)).toEqual(PHASES);
    expect(columns.PRODUCTION.map((item) => item.id)).toEqual(['a', 'c']);
    expect(columns.INTAKE.map((item) => item.id)).toEqual(['b']);
    expect(columns.DELIVERY).toEqual([]);
  });

  it('lists a template\'s steps in one phase, for the Stage filter', () => {
    const step = (order: number, phase: StepSpec['phase']) => ({ order, phase, code: `S${order}`, title: `Step ${order}` });
    const steps = [step(1, 'INTAKE'), step(5, 'PLANNING'), step(6, 'PRODUCTION'), step(7, 'PRODUCTION')];
    expect(stepsInPhase(steps, 'PRODUCTION').map((s) => s.order)).toEqual([6, 7]);
  });
});

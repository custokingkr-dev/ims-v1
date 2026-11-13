import { useId } from 'react';
import { useWorkflow } from '../context';
import { checkedItems, checklistProgress } from '../model';
import type { BlockSpec, ChecklistSpec } from '../types';
import { Badge, BlockCard, blockTitle, useStage } from './shared';

/** A checklist's "N/M complete" badge and, for four or more items, its bar and percentage. */
export function ChecklistMeter({ spec, checked, signedOff, label }: { spec: ChecklistSpec; checked: number[]; signedOff: boolean; label: string }) {
  const progress = checklistProgress(spec, checked, signedOff);
  return (
    <>
      <Badge tone={progress.complete ? 'success' : 'warning'}>{progress.done}/{progress.total} complete</Badge>
      {progress.total >= 4 && (
        <div className="ck-wf-meter">
          <div className="ck-wf-meter-bar" role="progressbar" aria-label={`${label} progress`} aria-valuemin={0} aria-valuemax={100} aria-valuenow={progress.percent}>
            <span style={{ width: `${progress.percent}%` }} />
          </div>
          <span className="ck-wf-meter-text">{progress.percent}% complete</span>
        </div>
      )}
    </>
  );
}

/**
 * A checklist from the template. Each tick saves at once, sending only this checklist's list (a partial save), and says so
 * in a toast. The item only a sign-off may tick is read-only and shows who ticked it that way.
 */
export function ChecklistBlock({ block }: { block: BlockSpec }) {
  const { stage, editable, saveData } = useStage();
  const { busy } = useWorkflow();
  const key = block.checklist ?? '';
  const spec = stage.spec.checklists[key];
  const meter = useId();
  if (!spec) return null;
  const title = blockTitle(block) ?? 'Checklist';
  const checked = checkedItems(stage.view.data, key).filter((index) => index !== spec.signOffItem);
  const signedOff = !!stage.view.signOff;
  const toggle = (index: number, on: boolean) => {
    const next = on ? [...checked, index].sort((a, b) => a - b) : checked.filter((item) => item !== index);
    void saveData({ [key]: next }, `${title} updated`);
  };
  return (
    <BlockCard title={null}>
      <fieldset className="ck-wf-checklist" aria-describedby={meter}>
        <legend className="ck-wf-checklist-legend">{title}</legend>
        <div id={meter} className="ck-wf-checklist-meter"><ChecklistMeter spec={spec} checked={checked} signedOff={signedOff} label={title} /></div>
        <ul>
          {spec.items.map((item, index) => {
            const bySignOff = index === spec.signOffItem;
            const on = bySignOff ? signedOff : checked.includes(index);
            return (
              <li key={`${index}-${item}`} className={on ? 'is-done' : undefined}>
                <label>
                  <input type="checkbox" checked={on} disabled={!editable || bySignOff || busy} onChange={(e) => toggle(index, e.target.checked)} />
                  <span>{item}</span>
                </label>
                {bySignOff && <span className="ck-wf-muted">{signedOff ? 'Ticked by reviewer sign-off' : 'Set only by the reviewer sign-off'}</span>}
              </li>
            );
          })}
        </ul>
      </fieldset>
    </BlockCard>
  );
}

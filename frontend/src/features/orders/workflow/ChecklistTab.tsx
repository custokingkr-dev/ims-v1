import { Check, Circle } from 'lucide-react';
import { ChecklistMeter } from './blocks/ChecklistBlock';
import { useWorkflow } from './context';
import { checkedItems, checklistProgress } from './model';

/**
 * Every checklist of the order, grouped by stage, read-only (section 4.3, filling the prototype's gap). Each group says
 * N/M and links to its stage, where the items are ticked.
 */
export function ChecklistTab() {
  const { stages, goToStage } = useWorkflow();
  const groups = stages.flatMap((stage) => Object.entries(stage.spec.checklists).map(([key, spec]) => ({ stage, key, spec })));
  if (!groups.length) return <p className="ck-wf-muted">This workflow has no checklists.</p>;
  return (
    <div className="ck-wf-checklists">
      {groups.map(({ stage, key, spec }) => {
        const checked = checkedItems(stage.view.data, key);
        const signedOff = !!stage.view.signOff;
        const title = stage.spec.blocks.find((block) => block.checklist === key && block.type === 'CHECKLIST')?.title ?? stage.spec.title;
        const progress = checklistProgress(spec, checked, signedOff);
        return (
          <section key={`${stage.view.step}-${key}`} className="ck-wf-block" aria-label={`Step ${stage.view.step}: ${title}`}>
            <header className="ck-wf-block-h">
              <h4>Step {stage.view.step}: {title}</h4>
              <ChecklistMeter spec={spec} checked={checked} signedOff={signedOff} label={title} />
            </header>
            <ul className="ck-wf-readonly-list">
              {spec.items.map((item, index) => {
                const on = index === spec.signOffItem ? signedOff : checked.includes(index);
                return (
                  <li key={`${index}-${item}`} className={on ? 'is-done' : undefined}>
                    {on ? <Check size={14} aria-hidden="true" /> : <Circle size={14} aria-hidden="true" />}
                    <span>{item}</span>
                    <span className="ck-sr-only">{on ? ' (done)' : ' (not done)'}</span>
                  </li>
                );
              })}
            </ul>
            <div className="ck-wf-block-actions">
              <span className="ck-wf-muted">{progress.done} of {progress.total} ticked</span>
              <button type="button" className="ck-btn ck-btn-ghost ck-btn-sm" onClick={() => goToStage(stage.view.step, 'WORK')}>Go to stage<span className="ck-sr-only"> {stage.spec.title}</span></button>
            </div>
          </section>
        );
      })}
    </div>
  );
}

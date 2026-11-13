import { ExternalLink } from 'lucide-react';
import { safeEvidenceHref } from '../../shared/invite';
import { useWorkflow } from '../context';
import { capitalise, stageWithField, valueText } from '../model';
import type { BlockSpec } from '../types';
import { Badge, BlockCard, blockTitle, Warning } from './shared';

const count = (value: number) => value.toLocaleString('en-IN');

function SummaryStatus() {
  const { summary, summaryError } = useWorkflow();
  if (summaryError) return <p className="ck-wf-error" role="alert">{summaryError}</p>;
  if (!summary) return <p className="ck-wf-muted" role="status">Loading the school&apos;s order…</p>;
  return null;
}

/** School order folder: the order PDF link saved in Order Details, shown where planning needs it. */
export function OrderFolderBlock({ block }: { block: BlockSpec }) {
  const { stages } = useWorkflow();
  const source = stageWithField(stages, 'orderPdfLink');
  const link = source ? valueText(source.view.data.orderPdfLink).trim() : '';
  const href = safeEvidenceHref(link);
  const from = source?.spec.title ?? 'Order Details';
  return (
    <BlockCard title={blockTitle(block)} badge={<Badge>From {from}</Badge>}>
      {link
        ? (href
          ? <a className="ck-wf-link" href={href} target="_blank" rel="noopener noreferrer">{link} <ExternalLink size={13} aria-hidden="true" /><span className="ck-sr-only"> (opens in a new tab)</span></a>
          : <span>{link}</span>)
        : <Warning>No folder link saved yet. Add the folder link in {from} first.</Warning>}
    </BlockCard>
  );
}

/** Notebook title card quantities (D24), computed by school-core from the placed order lines. */
export function TitleCardQuantitiesBlock({ block }: { block: BlockSpec }) {
  const { summary } = useWorkflow();
  const cards = summary?.titleCards;
  const unmatched = cards?.unmatched ?? [];
  const unmatchedTotal = unmatched.reduce((total, line) => total + line.notebooks, 0);
  return (
    <BlockCard title={blockTitle(block)} badge={<Badge>Calculated from the school&apos;s order</Badge>}>
      <p className="ck-wf-muted">
        King/Jumbo King and Long: 1 print sheet per 8 notebooks + 13 extra sheets for wastage. Jumbo Long and FA: 1 print sheet per
        4 notebooks + 25 extra sheets for wastage.
      </p>
      <SummaryStatus />
      {summary && !cards && <p className="ck-wf-muted">Title card quantities are not available for this order.</p>}
      {cards && (
        <div className="ck-table-wrap">
          <table className="ck-table ck-wf-table">
            <thead><tr><th scope="col">Type</th><th scope="col">Size</th><th scope="col" className="ck-num">Notebooks in order</th><th scope="col" className="ck-num">Cards / sheets needed</th></tr></thead>
            <tbody>
              {cards.rows.map((row) => (
                <tr key={row.type}>
                  <td>{row.label}</td><td>{row.size}</td><td className="ck-num">{count(row.notebooks)}</td><td className="ck-num ck-wf-strong">{count(row.sheets)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {cards && unmatchedTotal > 0 && (
        <p className="ck-wf-muted">Not on a title card: {count(unmatchedTotal)} ({unmatched.map((line) => `${line.label} ${count(line.notebooks)}`).join(', ')})</p>
      )}
      <p className="ck-wf-footnote">Calculated quantity = rounded-up print sheets required + extra wastage sheets. No wastage is added when that category has zero notebooks.</p>
    </BlockCard>
  );
}

/** Saved notebook title card quantities: the same computed sheets, read where boards are procured. */
export function SavedTitleCardsBlock({ block }: { block: BlockSpec }) {
  const { summary, stages } = useWorkflow();
  const cards = summary?.titleCards;
  const planning = stages.find((stage) => stage.spec.blocks.some((item) => item.type === 'TITLE_CARD_QUANTITIES'))?.spec.title ?? 'Production Planning';
  return (
    <BlockCard title={blockTitle(block)} badge={<Badge>From {planning}</Badge>}>
      <SummaryStatus />
      {summary && !cards && <p className="ck-wf-muted">Title card quantities are not available for this order.</p>}
      {cards && (
        <div className="ck-table-wrap">
          <table className="ck-table ck-wf-table">
            <thead><tr><th scope="col">Type</th><th scope="col">Size</th><th scope="col" className="ck-num">Quantity</th></tr></thead>
            <tbody>
              {cards.rows.map((row) => <tr key={row.type}><td>{row.label}</td><td>{row.size}</td><td className="ck-num">{count(row.sheets)}</td></tr>)}
            </tbody>
          </table>
        </div>
      )}
    </BlockCard>
  );
}

/** "<Item> quantities, from the school's order form" (D25): each placed line's quantity, with the total. */
export function ItemQuantitiesBlock({ block }: { block: BlockSpec }) {
  const { summary, view } = useWorkflow();
  const quantities = summary?.quantities;
  return (
    <BlockCard title={blockTitle(block, `${capitalise(view.itemNoun || 'item')} quantities`)} badge={<Badge>From the school&apos;s order form</Badge>}>
      <SummaryStatus />
      {quantities && (
        <div className="ck-table-wrap">
          <table className="ck-table ck-wf-table">
            <thead><tr><th scope="col">Line</th><th scope="col">Details</th><th scope="col" className="ck-num">Quantity</th></tr></thead>
            <tbody>
              {quantities.lines.map((line) => <tr key={line.lineNo}><td>{line.label}</td><td>{line.details ?? '—'}</td><td className="ck-num">{count(line.quantity)}</td></tr>)}
            </tbody>
            <tfoot><tr><th scope="row" colSpan={2}>Total</th><td className="ck-num ck-wf-strong">{count(quantities.total)}</td></tr></tfoot>
          </table>
        </div>
      )}
    </BlockCard>
  );
}

interface TransferProgressProps {
  label: string;
  detail: string;
  value?: number;
  valueLabel?: string;
  ariaValueText?: string;
  tone?: 'active' | 'complete' | 'error';
}

export function TransferProgress({
  label,
  detail,
  value,
  valueLabel,
  ariaValueText,
  tone = 'active',
}: TransferProgressProps) {
  const boundedValue = value == null ? undefined : Math.min(100, Math.max(0, value));
  const percentLabel = boundedValue == null ? 'In progress' : `${Math.round(boundedValue)}%`;
  const accessibleValue = ariaValueText || valueLabel || percentLabel;

  return (
    <section className={`ck-transfer-progress ${tone}`} aria-live="polite" aria-atomic="true">
      <div className="ck-transfer-progress-head">
        <strong>{label}</strong>
        <output>{valueLabel || percentLabel}</output>
      </div>
      <div
        className={`ck-transfer-progress-track ${boundedValue == null ? 'indeterminate' : ''}`}
        role="progressbar"
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={boundedValue == null ? undefined : Math.round(boundedValue)}
        aria-valuetext={accessibleValue}
      >
        <span
          className="ck-transfer-progress-fill"
          style={boundedValue == null ? undefined : { transform: `scaleX(${boundedValue / 100})` }}
        />
      </div>
      <p>{detail}</p>
    </section>
  );
}

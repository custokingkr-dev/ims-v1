interface SkeletonProps {
  width?: string | number;
  height?: string | number;
  borderRadius?: string | number;
  className?: string;
  style?: React.CSSProperties;
}

export function Skeleton({ width, height, borderRadius, className = '', style }: SkeletonProps) {
  return (
    <div
      className={`ck-skeleton ${className}`}
      style={{ width, height, borderRadius, ...style }}
      aria-hidden="true"
    />
  );
}

export function SkeletonText({ width = '100%' }: { width?: string | number }) {
  return <div className="ck-skeleton ck-skeleton-text" style={{ width }} aria-hidden="true" />;
}

export function SkeletonRow() {
  return (
    <div className="ck-skeleton-row" aria-hidden="true">
      <div className="ck-skeleton ck-skeleton-avatar" />
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 6 }}>
        <div className="ck-skeleton ck-skeleton-text" style={{ width: '55%' }} />
        <div className="ck-skeleton ck-skeleton-text" style={{ width: '35%', height: 11 }} />
      </div>
      <div className="ck-skeleton ck-skeleton-badge" />
    </div>
  );
}

export function SkeletonTableRows({ count = 5 }: { count?: number }) {
  return (
    <>
      {Array.from({ length: count }).map((_, i) => (
        <SkeletonRow key={i} />
      ))}
    </>
  );
}

export function SkeletonStatGrid({ count = 4 }: { count?: number }) {
  return (
    <div className="ck-stats ck-s4" aria-hidden="true">
      {Array.from({ length: count }).map((_, i) => (
        <div
          key={i}
          className="ck-skeleton ck-skeleton-stat"
          style={{ animationDelay: `${i * 0.05}s` }}
        />
      ))}
    </div>
  );
}

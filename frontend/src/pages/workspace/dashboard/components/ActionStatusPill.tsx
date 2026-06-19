import React from 'react';

type Status = 'open' | 'in-progress' | 'done' | 'blocked';

interface Props {
  status: Status;
}

const PILL_CONFIG: Record<Status, { label: string; bg: string; color: string }> = {
  'open':        { label: 'Open',        bg: '#e8f0fe', color: '#1a4fa8' },
  'in-progress': { label: 'In Progress', bg: '#fff3cd', color: '#856404' },
  'done':        { label: 'Done',        bg: '#e6f4ed', color: '#1a6840' },
  'blocked':     { label: 'Blocked',     bg: '#fde8e8', color: '#c0312b' },
};

export function ActionStatusPill({ status }: Props) {
  const cfg = PILL_CONFIG[status];
  return (
    <span
      style={{
        display: 'inline-block',
        padding: '1px 7px',
        borderRadius: 10,
        fontSize: 11,
        fontWeight: 600,
        letterSpacing: '0.02em',
        background: cfg.bg,
        color: cfg.color,
      }}
    >
      {cfg.label}
    </span>
  );
}

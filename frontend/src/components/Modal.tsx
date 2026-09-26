import { useId, useRef } from 'react';
import { useDialogFocus } from '../hooks/useDialogFocus';

interface ModalProps {
  title: string;
  subtitle?: string;
  onClose: () => void;
  disabled?: boolean;
  children: React.ReactNode;
  footer: React.ReactNode;
}

export function Modal({ title, subtitle, onClose, disabled, children, footer }: ModalProps) {
  const titleId = useId();
  const descriptionId = useId();
  const ref = useRef<HTMLDivElement>(null);
  useDialogFocus(ref, true, onClose, disabled);
  return (
    <div className="ck-modal-bg" onClick={() => { if (!disabled) onClose(); }}>
      <div ref={ref} className="ck-modal" role="dialog" aria-modal="true" aria-labelledby={titleId} aria-describedby={subtitle ? descriptionId : undefined} tabIndex={-1} onClick={(e) => e.stopPropagation()}>
        <div className="ck-modal-h">
          <div>
            <div id={titleId} className="ck-modal-title">{title}</div>
            {subtitle && <div id={descriptionId} style={{ fontSize: 12, color: 'var(--ink3)', marginTop: 2 }}>{subtitle}</div>}
          </div>
          <button type="button" className="ck-modal-x" aria-label={`Close ${title}`} onClick={onClose} disabled={disabled}>×</button>
        </div>
        <div className="ck-modal-body">{children}</div>
        <div className="ck-modal-foot">{footer}</div>
      </div>
    </div>
  );
}

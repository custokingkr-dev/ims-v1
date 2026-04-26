interface ModalProps {
  title: string;
  subtitle?: string;
  onClose: () => void;
  disabled?: boolean;
  children: React.ReactNode;
  footer: React.ReactNode;
}

export function Modal({ title, subtitle, onClose, disabled, children, footer }: ModalProps) {
  return (
    <div className="ck-modal-bg" onClick={() => { if (!disabled) onClose(); }}>
      <div className="ck-modal" onClick={(e) => e.stopPropagation()}>
        <div className="ck-modal-h">
          <div>
            <div className="ck-modal-title">{title}</div>
            {subtitle && <div style={{ fontSize: 12, color: 'var(--ink3)', marginTop: 2 }}>{subtitle}</div>}
          </div>
          <button className="ck-modal-x" onClick={onClose} disabled={disabled}>×</button>
        </div>
        <div className="ck-modal-body">{children}</div>
        <div className="ck-modal-foot">{footer}</div>
      </div>
    </div>
  );
}

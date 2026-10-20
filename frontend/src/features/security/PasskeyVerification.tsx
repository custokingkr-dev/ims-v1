import { useEffect, useState } from 'react';
import { KeyRound } from 'lucide-react';
import { Modal } from '../../components/Modal';
import { identityAuthClient } from '../../services/api';
import { registerPasskey, verifyPasskey } from './passkeys';
import './security.css';

export function PasskeyVerification({ onClose, onVerified }: { onClose: () => void; onVerified: () => void }) {
  const [enrolled, setEnrolled] = useState<boolean | null>(null);
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [settingsAttempt, setSettingsAttempt] = useState(0);
  const [canonicalAddress, setCanonicalAddress] = useState('');
  const supported = typeof PublicKeyCredential !== 'undefined' && Boolean(navigator.credentials);
  useEffect(() => {
    let active = true;
    identityAuthClient.passkeyStatus().then((data) => {
      if (!active) return;
      setEnrolled(Boolean(data.enrolled));
      if (data.rpId && data.rpId !== window.location.hostname && data.canonicalOrigin) {
        const destination = new URL(data.canonicalOrigin);
        if (destination.protocol !== 'https:' || destination.hostname !== data.rpId || destination.username || destination.password) throw new Error('Invalid passkey origin');
        destination.pathname = window.location.pathname; destination.search = window.location.search;
        setCanonicalAddress(destination.href);
      }
    }).catch(() => { if (active) setError('Passkey settings are unavailable. Please retry shortly.'); });
    return () => { active = false; };
  }, [settingsAttempt]);

  async function verify() {
    setError(''); setBusy(true);
    try {
      if (!enrolled) {
        await registerPasskey(password);
        setPassword(''); setEnrolled(true);
      }
      await verifyPasskey();
      onVerified();
    } catch (failure) {
      setError(failure instanceof DOMException && failure.name === 'NotAllowedError'
        ? 'Verification was cancelled. You can try again when ready.'
        : 'Passkey verification could not be completed. Retry or contact your administrator.');
    } finally { setBusy(false); }
  }

  return <Modal title="Verify it’s you" subtitle="Protect this sensitive action with your passkey."
    onClose={onClose} disabled={busy}
    footer={<><button className="ck-btn ck-btn-ghost" onClick={onClose} disabled={busy}>Cancel</button>
      <button className="ck-btn ck-btn-primary" onClick={() => void verify()} disabled={busy || enrolled === null || !supported || Boolean(canonicalAddress) || (!enrolled && !password)}>
        {busy ? 'Verifying…' : enrolled ? 'Verify with passkey' : 'Create and verify passkey'}
      </button></>}>
    <div className="ck-passkey-intro"><KeyRound size={28} aria-hidden="true" />
      <p>{enrolled ? 'Use your device screen lock or security key. After verification, retry your action.'
        : 'Create a passkey on your device or security key, then verify it. Confirm your current password to begin.'}</p>
    </div>
    {!supported && <p role="alert">This browser does not support passkeys. Use a supported browser on a secure connection.</p>}
    {canonicalAddress && <p role="alert">Your passkey belongs to the secure workspace address. <a href={canonicalAddress}>Open the secure workspace</a> to verify.</p>}
    {enrolled === false && <label className="ck-passkey-password">Current password
      <input type="password" autoComplete="current-password" value={password} onChange={event => setPassword(event.target.value)} disabled={busy} />
    </label>}
    {error && <p role="alert" className="ck-passkey-error">{error}</p>}
    {error && enrolled === null && <button className="ck-btn ck-btn-ghost" onClick={() => { setError(''); setSettingsAttempt(attempt => attempt + 1); }}>Retry settings</button>}
    <p className="ck-passkey-note">If your passkey is unavailable, your administrator must use the audited recovery process.</p>
  </Modal>;
}

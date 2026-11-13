/**
 * Links the order workflow opens, and the introduction message it lets the operator copy (design D28, D30, D34).
 * Nothing here sends anything: the system only opens WhatsApp group invites and evidence links in a new tab. The server
 * validates every saved link; these checks only stop the screen from offering a link the server would refuse, and from
 * ever opening anything but an https link.
 */

/** Shown under an invite field the server would refuse (design section 4.5). */
export const INVALID_INVITE_MESSAGE = 'Paste a WhatsApp group invite link (https://chat.whatsapp.com/…)';

/**
 * Mirrors the server's invite check (NotebookTrackerService.validateWhatsAppInviteUrl, java.net.URI): https and
 * chat.whatsapp.com in any case, an empty port at most, and no userinfo, query or fragment (even empty ones). The raw
 * text is tested, not `new URL()`, because the browser parser normalises inputs the server refuses (`:443`,
 * `https://@…`, backslashes, dot segments). No `u` flag, so `i` never folds a non-ASCII letter onto an ASCII one.
 */
const WHATSAPP_INVITE = /^https:\/\/chat\.whatsapp\.com:?\/([A-Za-z0-9_-]{10,64})$/i;
const INVITE_ORIGIN = 'https://chat.whatsapp.com/';

/** The one spelling the server stores: https://chat.whatsapp.com/<code>, the code kept exactly; null if invalid. */
export function normaliseInvite(text: string): string | null {
  const match = WHATSAPP_INVITE.exec(text.trim());
  return match ? `${INVITE_ORIGIN}${match[1]}` : null;
}

export function isWhatsAppInvite(text: string): boolean {
  return normaliseInvite(text) !== null;
}

/** A saved invite that is safe to open, or null (the button is then disabled with its reason). */
export function safeInviteHref(url: string | null | undefined): string | null {
  return url ? normaliseInvite(url) : null;
}

/** URL fields (CDR storage, bills, sample evidence, challan, proof of delivery) hold https links of at most this length. */
export const EVIDENCE_LINK_MAX_CHARS = 1000;

/** An evidence link that is safe to open in a new tab: https, a host, no whitespace, at most 1,000 characters; else null. */
export function safeEvidenceHref(url: string | null | undefined): string | null {
  const text = url?.trim();
  if (!text || text.length > EVIDENCE_LINK_MAX_CHARS || /\s/.test(text) || !/^https:\/\//i.test(text)) return null;
  try {
    const parsed = new URL(text);
    return parsed.protocol === 'https:' && parsed.hostname ? text : null;
  } catch {
    return null;
  }
}

export interface IntroMessageValues {
  /** "Your name": prefilled with the signed-in user's name and editable. */
  name: string;
  /** The order's item noun (OrderWorkflowView.itemNoun), for templates that say {Item}. */
  itemNoun?: string | null;
}

/**
 * The template's {Name} (and the General template's {Item}) filled in. A blank value leaves its placeholder in place,
 * so the operator sees what is still missing before copying. Values are inserted literally, never as a pattern.
 */
export function fillIntroMessage(template: string, values: IntroMessageValues): string {
  const filled: Record<string, string> = { Name: values.name.trim(), Item: values.itemNoun?.trim() ?? '' };
  // One pass, so a typed name that contains "{Item}" is not filled again.
  return template.replace(/\{(Name|Item)\}/g, (placeholder, key: string) => filled[key] || placeholder);
}

export function hasUnfilledPlaceholders(message: string): boolean {
  return message.includes('{Name}') || message.includes('{Item}');
}

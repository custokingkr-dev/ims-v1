/**
 * Word limits on workflow text (design D29). The server enforces every limit; these helpers only let the screen show
 * "n/100 words" and disable a save before the server would refuse it with NOTE_TOO_LONG or INVALID_STEP_DATA.
 */

/** Stage notes and internal notes. */
export const NOTE_MAX_WORDS = 100;
/** Call notes. */
export const CALL_NOTES_MAX_WORDS = 100;
/** Head Approval comments (approve and request changes). */
export const APPROVAL_COMMENT_MAX_WORDS = 100;
/** The character cap that template WORDS fields and stage and internal notes also have. */
export const WORD_LIMITED_MAX_CHARS = 4000;
/** Call notes are also capped at 1,000 characters (workflow_call_logs.notes). */
export const CALL_NOTES_MAX_CHARS = 1000;
/** Head Approval comments are also capped at 1,000 characters (the stored design comment). */
export const APPROVAL_COMMENT_MAX_CHARS = 1000;
/** A superadmin's reason for reopening a stage. */
export const REASON_MAX_WORDS = 100;
export const REASON_MAX_CHARS = 1000;

/**
 * Java's Character.isWhitespace: tab, line feed, vertical tab, form feed, carriage return, U+001C-U+001F, and the
 * Unicode space, line and paragraph separators except the no-break ones (U+00A0, U+2007, U+202F).
 */
function isJavaWhitespace(code: number): boolean {
  return (code >= 0x09 && code <= 0x0d) || (code >= 0x1c && code <= 0x20) || code === 0x1680
    || (code >= 0x2000 && code <= 0x2006) || (code >= 0x2008 && code <= 0x200a)
    || code === 0x2028 || code === 0x2029 || code === 0x205f || code === 0x3000;
}

/** Java's default regex \s, which is ASCII only: space, tab, line feed, vertical tab, form feed, carriage return. */
const JAVA_REGEX_SPACES = /[ \t\n\v\f\r]+/;

/**
 * Counts words exactly as operations-service's NoteRules.wordCount does: blank text (String.isBlank) has none;
 * otherwise the text is trimmed (String.trim drops every character up to U+0020) and split on runs of ASCII
 * whitespace. A no-break or other non-ASCII space therefore does not separate words, which is what the server does.
 */
export function wordCount(text: string | null | undefined): number {
  if (!text) return 0;
  let blank = true;
  for (let index = 0; index < text.length && blank; index += 1) blank = isJavaWhitespace(text.charCodeAt(index));
  if (blank) return 0;
  let start = 0;
  let end = text.length;
  while (start < end && text.charCodeAt(start) <= 0x20) start += 1;
  while (end > start && text.charCodeAt(end - 1) <= 0x20) end -= 1;
  return text.slice(start, end).split(JAVA_REGEX_SPACES).length;
}

export interface WordLimit {
  count: number;
  max: number;
  /** Negative once the text is over the limit. */
  remaining: number;
  over: boolean;
  /** The counter text, e.g. "12/100 words". */
  label: string;
}

export function wordLimit(text: string | null | undefined, max: number): WordLimit {
  const count = wordCount(text);
  return { count, max, remaining: max - count, over: count > max, label: `${count}/${max} words` };
}

/** Whether the server would accept the text's length: at most `maxWords` words and `maxChars` characters. */
export function withinWordLimit(text: string | null | undefined, maxWords: number, maxChars = WORD_LIMITED_MAX_CHARS): boolean {
  return wordCount(text) <= maxWords && (text ?? '').length <= maxChars;
}

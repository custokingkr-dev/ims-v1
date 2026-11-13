import { describe, expect, it } from 'vitest';
import {
  APPROVAL_COMMENT_MAX_CHARS, APPROVAL_COMMENT_MAX_WORDS, CALL_NOTES_MAX_CHARS, CALL_NOTES_MAX_WORDS, NOTE_MAX_WORDS,
  WORD_LIMITED_MAX_CHARS, withinWordLimit, wordCount, wordLimit,
} from './words';

const NBSP = String.fromCharCode(0x00a0);
const EM_SPACE = String.fromCharCode(0x2003);
const IDEOGRAPHIC_SPACE = String.fromCharCode(0x3000);

// Each expectation is what operations-service's NoteRules.wordCount returns for the same text
// (String.isBlank, then String.trim, then split on "\\s+"), so the counter never disagrees with a 400.
describe('wordCount (server parity)', () => {
  it('counts nothing in empty, missing or blank text', () => {
    expect(wordCount('')).toBe(0);
    expect(wordCount(null)).toBe(0);
    expect(wordCount(undefined)).toBe(0);
    expect(wordCount('   ')).toBe(0);
    expect(wordCount('\n\t \r\n')).toBe(0);
  });

  it('treats runs of spaces, tabs and newlines as one separator', () => {
    expect(wordCount('one two')).toBe(2);
    expect(wordCount('one     two')).toBe(2);
    expect(wordCount('  one two  ')).toBe(2);
    expect(wordCount('one\ntwo\r\nthree\tfour')).toBe(4);
    expect(wordCount('line one\n\n\nline two')).toBe(4);
  });

  it('keeps punctuation and hyphenated words as single tokens', () => {
    expect(wordCount('Fonts outlined, colours checked.')).toBe(4);
    expect(wordCount('re-check NB-DPS_GUR-26-11-13-0042 tomorrow')).toBe(3);
  });

  it('does not split on non-ASCII spaces, as Java\'s \\s does not', () => {
    expect(wordCount(`one${NBSP}two`)).toBe(1);
    expect(wordCount(`one${EM_SPACE}two`)).toBe(1);
  });

  it('reads text made only of Unicode spaces as blank, as String.isBlank does', () => {
    expect(wordCount(`${EM_SPACE}${IDEOGRAPHIC_SPACE}`)).toBe(0);
    // A no-break space is not whitespace to Java, so it is one (invisible) word.
    expect(wordCount(NBSP)).toBe(1);
  });

  it('matches the server on control characters too', () => {
    expect(wordCount('a\vb')).toBe(2);
    // Not blank to String.isBlank, emptied by String.trim, and "".split(...) is one empty token.
    expect(wordCount(String.fromCharCode(0x01))).toBe(1);
  });
});

describe('wordLimit', () => {
  it('labels the counter like "n/100 words" and flags only text over the limit', () => {
    const atLimit = Array.from({ length: NOTE_MAX_WORDS }, () => 'w').join(' ');
    expect(wordLimit(atLimit, NOTE_MAX_WORDS)).toEqual({ count: 100, max: 100, remaining: 0, over: false, label: '100/100 words' });
    expect(wordLimit(`${atLimit} more`, NOTE_MAX_WORDS)).toMatchObject({ count: 101, remaining: -1, over: true, label: '101/100 words' });
    expect(wordLimit('', 200).label).toBe('0/200 words');
  });

  it('also enforces the character cap every word-limited text has', () => {
    expect(withinWordLimit('short note', 100)).toBe(true);
    expect(withinWordLimit('x'.repeat(WORD_LIMITED_MAX_CHARS), 100)).toBe(true);
    expect(withinWordLimit('x'.repeat(WORD_LIMITED_MAX_CHARS + 1), 100)).toBe(false);
    expect(withinWordLimit(Array.from({ length: 101 }, () => 'w').join(' '), 100)).toBe(false);
  });

  it('caps call notes and approval comments at their stored 1,000 characters', () => {
    const longWords = Array.from({ length: 90 }, () => 'abcdefghijk').join(' ');
    expect(longWords.length).toBeGreaterThan(CALL_NOTES_MAX_CHARS);
    expect(withinWordLimit(longWords, CALL_NOTES_MAX_WORDS)).toBe(true);
    expect(withinWordLimit(longWords, CALL_NOTES_MAX_WORDS, CALL_NOTES_MAX_CHARS)).toBe(false);
    expect(withinWordLimit(longWords, APPROVAL_COMMENT_MAX_WORDS, APPROVAL_COMMENT_MAX_CHARS)).toBe(false);
    expect(withinWordLimit('Matches the brief', APPROVAL_COMMENT_MAX_WORDS, APPROVAL_COMMENT_MAX_CHARS)).toBe(true);
  });
});

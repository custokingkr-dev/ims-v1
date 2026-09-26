/**
 * What an error is allowed to say to the person using the application.
 *
 * Falling back to an Error's own `message` prints whatever was thrown. On Student data export
 * that surfaced "Cannot read properties of undefined (reading 'length')" in red, on the page,
 * as the panel's error state. A server's message is written for a reader; a thrown exception's
 * is written for whoever is reading a stack trace.
 */
export function userFacingError(error: unknown, fallback: string): string {
  const response = (error as { response?: { data?: { message?: unknown } } } | null)?.response;
  const fromServer = response?.data?.message;
  if (typeof fromServer === 'string' && fromServer.trim()) return fromServer.trim();
  return fallback;
}

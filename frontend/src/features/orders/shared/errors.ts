/**
 * How the order workflow screens read a response they cannot trust and say what went wrong. Every screen of the feature
 * (the list, an order's workflow, the call log and Workflow set-up) uses these, so the same failure reads the same
 * everywhere.
 */

export const NOT_UNDERSTOOD = 'The response was not understood.';
export const WORKFLOW_UNAVAILABLE = 'The order workflow service is not available right now. Try again in a moment.';

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);

/** A list the server sent, or NOT_UNDERSTOOD: anything else is never shown as "nothing here". */
export function parseList<T>(value: unknown): T[] {
  if (!Array.isArray(value)) throw new Error(NOT_UNDERSTOOD);
  return value as T[];
}

export function httpStatus(error: unknown): number | undefined {
  const status = (error as { response?: { status?: unknown } } | null)?.response?.status;
  return typeof status === 'number' ? status : undefined;
}

function serverMessage(error: unknown): string {
  const data = (error as { response?: { data?: { message?: unknown; fieldErrors?: unknown } } } | null)?.response?.data;
  if (typeof data?.message === 'string' && data.message.trim()) return data.message;
  if (isRecord(data?.fieldErrors)) return Object.values(data.fieldErrors).filter((value): value is string => typeof value === 'string').join(' ');
  return '';
}

/**
 * What to tell the user when a call fails. A refusal the server explained (a 4xx) keeps its own words; a failing service
 * or the network (a 5xx, or no response at all) is said plainly instead of passing on text such as "Gateway upstream
 * error". A response the screen could not read keeps its own message.
 */
export function workflowError(error: unknown, fallback = 'That did not work. Please try again.'): string {
  const status = httpStatus(error);
  const failure = error as { isAxiosError?: unknown; request?: unknown; response?: unknown } | null;
  const unavailable = status !== undefined ? status >= 500 : !failure?.response && (failure?.isAxiosError === true || failure?.request != null);
  if (unavailable) return WORKFLOW_UNAVAILABLE;
  return serverMessage(error) || (error instanceof Error && error.message ? error.message : fallback);
}

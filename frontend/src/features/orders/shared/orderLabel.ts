/**
 * How screens name an order (design D10, D11, D14). The SOP order number is the headline everywhere; the CK id stays the
 * key in every API path and is shown beneath as "Ref CK-1015". Drafts and categories without an order code have no
 * number, so they are headed by the CK id alone.
 */

export interface OrderIdentity {
  /** The CK id: the key in every path, never replaced. */
  orderId: string;
  /** The SOP order number, e.g. NB-DPS_GUR-26-11-13-0042, or null/absent before one is minted. */
  orderNumber?: string | null;
}

export interface OrderLabel {
  headline: string;
  /** "Ref CK-1015", or null when the headline already is the CK id. */
  reference: string | null;
}

/** `<ItemCode>-<Initials>_<Location>-<YY>-<MM>-<DD>-<serial>`; the serial widens past 9999 (D11). */
const ORDER_NUMBER = /^([A-Z]{2,4})-([A-Z0-9]{2,8}_[A-Z0-9]{2,6})-(\d{2})-(\d{2})-(\d{2})-(\d{4,})$/;

function numberOf(order: OrderIdentity): string | null {
  const number = order.orderNumber?.trim();
  return number ? number : null;
}

export function orderHeadline(order: OrderIdentity): string {
  return numberOf(order) ?? order.orderId;
}

export function orderReference(order: OrderIdentity): string | null {
  return numberOf(order) ? `Ref ${order.orderId}` : null;
}

export function orderLabel(order: OrderIdentity): OrderLabel {
  return { headline: orderHeadline(order), reference: orderReference(order) };
}

/** One spoken name, so a row or card is not announced as two unrelated codes. */
export function orderAccessibleName(order: OrderIdentity): string {
  const number = numberOf(order);
  return number ? `Order ${number}, reference ${order.orderId}` : `Order ${order.orderId}`;
}

export interface ParsedOrderNumber {
  itemCode: string;
  schoolCode: string;
  /** The placement date in the school's time zone, as yyyy-mm-dd. */
  placedOn: string;
  serial: number;
}

/** The parts of an order number, or null when the text is not one (upper case, as minted). */
export function parseOrderNumber(text: string): ParsedOrderNumber | null {
  const match = ORDER_NUMBER.exec(text);
  if (!match) return null;
  const [, itemCode, schoolCode, yy, mm, dd, serial] = match;
  const year = 2000 + Number(yy);
  const month = Number(mm);
  const day = Number(dd);
  const date = new Date(Date.UTC(year, month - 1, day));
  if (date.getUTCFullYear() !== year || date.getUTCMonth() !== month - 1 || date.getUTCDate() !== day) return null;
  return { itemCode, schoolCode, placedOn: `${year}-${mm}-${dd}`, serial: Number(serial) };
}

/**
 * A search term that is an order number, normalised for GET /catalog/orders/by-number/{orderNumber}; null when the term
 * is anything else (a CK id, a school name, part of a number), which the list's server-side search handles instead.
 */
export function asOrderNumber(term: string): string | null {
  const candidate = term.trim().toUpperCase();
  return parseOrderNumber(candidate) ? candidate : null;
}

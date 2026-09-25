import {
  Award, Bookmark, ClipboardList, Flag, HeartPulse, IdCard, Image, MessageSquare, Notebook,
  Package, PartyPopper, PencilRuler, ReceiptText, Ribbon, Shirt, SprayCan, Utensils,
  type LucideIcon,
} from 'lucide-react';

/**
 * One drawn icon per supply category, from the same library and stroke weight as every other icon
 * in the workspace. These were emoji, which render as a different typeface on each platform and sit
 * at a different optical weight from the rest of the interface.
 *
 * A category with no entry falls back to the parcel, so seeding a new category never ships a blank
 * or a stray glyph.
 */
const ICONS: Record<string, LucideIcon> = {
  NOTEBOOKS: Notebook,
  BILLBOOKS: ReceiptText,
  CERTIFICATES: Award,
  REPORT_CARDS: ClipboardList,
  FLIERS: Image,
  FLEX: Flag,
  BELTS: Ribbon,
  // Not Ribbon again: belts and ties sit next to each other in the picker and two identical
  // glyphs make the pair unreadable at a glance.
  TIES: Bookmark,
  UNIFORMS: Shirt,
  IDCARDS: IdCard,
  STATIONERY: PencilRuler,
  HOUSEKEEPING: SprayCan,
  HEALTH: HeartPulse,
  EVENTS: PartyPopper,
  FOOD: Utensils,
  CUSTOM: MessageSquare,
};

export function CategoryIcon({ code, size = 22 }: { code: string; size?: number }) {
  const Icon = ICONS[code] || Package;
  return <Icon size={size} strokeWidth={1.75} aria-hidden="true" />;
}

import {
  Award, BadgeCheck, Boxes, Calculator, Calendar, CalendarCheck, CheckCircle2, CircleDot, Clipboard, ClipboardCheck, ClipboardList,
  Factory, FileCheck, FileImage, FileSpreadsheet, FileText, Hammer, Handshake, Hash, Image, Layers, LayoutGrid, ListChecks, Package,
  PackageCheck, Palette, PenTool, Pencil, Phone, PhoneCall, Printer, Receipt, ReceiptText, Ruler, Scissors, Send, ShieldCheck,
  SquareCheck, Stamp, Table, Truck, Users, type LucideIcon,
} from 'lucide-react';
import type { Phase } from './types';

/**
 * The stage tile's icon. Templates name a lucide icon (StepSpec.icon); only the icons below are bundled, so an unknown name
 * falls back to its phase's icon rather than pulling the whole icon set into the page.
 */
const ICONS: Record<string, LucideIcon> = {
  Award, BadgeCheck, Boxes, Calculator, Calendar, CalendarCheck, CheckCircle2, CircleDot, Clipboard, ClipboardCheck, ClipboardList,
  Factory, FileCheck, FileImage, FileSpreadsheet, FileText, Hammer, Handshake, Hash, Image, Layers, LayoutGrid, ListChecks, Package,
  PackageCheck, Palette, PenTool, Pencil, Phone, PhoneCall, Printer, Receipt, ReceiptText, Ruler, Scissors, Send, ShieldCheck,
  SquareCheck, Stamp, Table, Truck, Users,
};

const PHASE_ICONS: Record<Phase, LucideIcon> = {
  INTAKE: FileText,
  PLANNING: ListChecks,
  PRODUCTION: Printer,
  VERIFICATION: ShieldCheck,
  DELIVERY: Truck,
};

export function StageIcon({ name, phase, size = 18 }: { name?: string | null; phase: Phase; size?: number }) {
  const Icon = (name && ICONS[name]) || PHASE_ICONS[phase] || CircleDot;
  return <Icon size={size} strokeWidth={1.8} aria-hidden="true" />;
}

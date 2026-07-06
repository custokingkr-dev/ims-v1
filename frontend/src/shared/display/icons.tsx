import type { ComponentType } from 'react';
import type { LucideProps } from 'lucide-react';
import {
  LayoutDashboard, GraduationCap, IndianRupee, SlidersHorizontal,
  CalendarCheck, Clock, UserPlus, FileUp, Users, Package, ShoppingCart,
  CalendarDays, AlertCircle, Plus, ClipboardCheck, Truck,
  ClipboardList, FilePlus, Receipt, Building2, BarChart2,
  TrendingUp, Globe, Package2, School,
} from 'lucide-react';

const PANEL_ICONS: Record<string, ComponentType<LucideProps>> = {
  home:            LayoutDashboard,
  students:        GraduationCap,
  fees:            IndianRupee,
  feestructure:    SlidersHorizontal,
  attendance:      CalendarCheck,
  timetable:       Clock,
  addstudent:      UserPlus,
  bulkimport:      FileUp,
  staff:           Users,
  catalog:         Package,
  orders:          ShoppingCart,
  planning:        CalendarDays,
  'ff-dashboard':  AlertCircle,
  'ff-new':        Plus,
  'ff-approvals':  ClipboardCheck,
  'ff-orders':     Truck,
  'sa-all-orders': ClipboardList,
  'sa-new-order':  FilePlus,
  'sa-invoices':   Receipt,
  'sa-schools':    Building2,
  'sa-erp':        BarChart2,
  'sa-revenue':    TrendingUp,
  'sa-catalog':    Package2,
  'za-overview':   Globe,
  'za-schools':    Building2,
  classsetup:      School,
};

interface NavIconProps {
  panelKey: string;
  fallback?: string;
  size?: number;
  strokeWidth?: number;
}

export function NavIcon({ panelKey, fallback, size = 15, strokeWidth = 1.8 }: NavIconProps) {
  const Icon = PANEL_ICONS[panelKey];
  if (!Icon) return fallback ? <span aria-hidden>{fallback}</span> : null;
  return <Icon size={size} strokeWidth={strokeWidth} aria-hidden />;
}

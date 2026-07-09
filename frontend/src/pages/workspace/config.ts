// Navigation structure, panel identifiers, and static data configuration.
// Add new panels here - nav and title map update automatically.

export type WorkspaceData = any;

export type LegacyModuleCode =
  | 'STUDENTS' | 'ATTENDANCE' | 'FEES' | 'INVOICES'
  | 'PAYMENTS' | 'ORDERS' | 'FIREFIGHTING' | 'REPORTS';

export type ModuleGroupCode = 'SUPPLY_OS' | 'ERP';
export type ModuleCode = LegacyModuleCode | ModuleGroupCode;

export const ALL_MODULES: Array<{ code: LegacyModuleCode; label: string; icon: string; desc: string }> = [
  { code: 'STUDENTS',     label: 'Students',     icon: '🎓', desc: 'Student records, enrollment, bulk import' },
  { code: 'ATTENDANCE',   label: 'Attendance',   icon: '✓',  desc: 'Daily attendance tracking' },
  { code: 'FEES',         label: 'Fees',         icon: '₹',  desc: 'Fee collection and structure management' },
  { code: 'INVOICES',     label: 'Invoices',     icon: '🧾', desc: 'Invoice generation and management' },
  { code: 'PAYMENTS',     label: 'Payments',     icon: '💳', desc: 'Payment records and reconciliation' },
  { code: 'ORDERS',       label: 'Supply OS',    icon: '📦', desc: 'Catalog ordering and annual planning' },
  { code: 'FIREFIGHTING', label: 'Urgent Procurement', icon: '🚨', desc: 'Non-catalog urgent procurement' },
  { code: 'REPORTS',      label: 'Reports',      icon: '📊', desc: 'Analytics and export reports' },
];

export const MODULE_GROUPS: Array<{
  code: ModuleGroupCode;
  label: string;
  icon: string;
  desc: string;
  moduleCodes: LegacyModuleCode[];
}> = [
  {
    code: 'SUPPLY_OS',
    label: 'Supply OS',
    icon: '📦',
    desc: 'Supply Details and Urgent Procurement',
    moduleCodes: ['ORDERS', 'FIREFIGHTING'],
  },
  {
    code: 'ERP',
    label: 'ERP',
    icon: '🏫',
    desc: 'Students, attendance, fees, invoices, payments, and reports',
    moduleCodes: ['STUDENTS', 'ATTENDANCE', 'FEES', 'INVOICES', 'PAYMENTS', 'REPORTS'],
  },
];

export const MODULE_CHILD_CODES = MODULE_GROUPS.flatMap((group) => group.moduleCodes);

export function expandModuleGroupSelections(selections: Record<string, boolean>): Record<LegacyModuleCode, boolean> {
  const expanded = Object.fromEntries(ALL_MODULES.map((module) => [module.code, false])) as Record<LegacyModuleCode, boolean>;
  for (const group of MODULE_GROUPS) {
    for (const code of group.moduleCodes) {
      expanded[code] = !!selections[group.code];
    }
  }
  return expanded;
}

export function groupModuleSelections(moduleCodes: Iterable<string>): Record<ModuleGroupCode, boolean> {
  const normalized = new Set(Array.from(moduleCodes).map((code) => String(code).toUpperCase()));
  return Object.fromEntries(
    MODULE_GROUPS.map((group) => [
      group.code,
      normalized.has(group.code) || group.moduleCodes.some((code) => normalized.has(code)),
    ])
  ) as Record<ModuleGroupCode, boolean>;
}

export function withDerivedModuleGroups(moduleCodes: Iterable<string>): Set<string> {
  const normalized = new Set(Array.from(moduleCodes).map((code) => String(code).toUpperCase()));
  for (const [groupCode, enabled] of Object.entries(groupModuleSelections(normalized))) {
    if (enabled) normalized.add(groupCode);
  }
  return normalized;
}

export type PanelKey =
  | 'home' | 'students' | 'fees' | 'feestructure' | 'attendance' | 'timetable'
  | 'addstudent' | 'bulkimport' | 'staff' | 'catalog' | 'orders' | 'planning' | 'classsetup'
  | 'ff-dashboard' | 'ff-new' | 'ff-approvals' | 'ff-orders'
  | 'sa-all-orders' | 'sa-new-order' | 'sa-invoices'
  | 'sa-schools' | 'sa-erp' | 'sa-revenue' | 'sa-catalog'
  | 'za-overview' | 'za-schools';

export type WorkspaceNavItem = { key: PanelKey; label: string; icon: string; module?: ModuleCode };

export type WorkspaceNavSection = {
  title: string;
  fire?: boolean;
  items: WorkspaceNavItem[];
};

const OVERVIEW_NAV_SECTION: WorkspaceNavSection = {
  title: 'Overview',
  items: [
    { key: 'home', label: 'Dashboard', icon: '◼' },
  ],
};

export function filterNavSectionsForModules(
  sections: WorkspaceNavSection[],
  activeModules: Set<string>,
): WorkspaceNavSection[] {
  const seenPanelKeys = new Set<PanelKey>();
  return sections
    .map((section) => ({
      ...section,
      items: section.items.filter((item) => {
        if (item.module && !activeModules.has(item.module)) return false;
        if (seenPanelKeys.has(item.key)) return false;
        seenPanelKeys.add(item.key);
        return true;
      }),
    }))
    .filter((section) => section.items.length > 0);
}

export const ADMIN_NAV_SECTIONS: WorkspaceNavSection[] = [
  OVERVIEW_NAV_SECTION,
  {
    title: 'Supply OS',
    items: [
      { key: 'catalog',   label: 'Supply Details', icon: '⊞', module: 'SUPPLY_OS' },
      { key: 'orders',    label: 'School Orders',  icon: '📦', module: 'SUPPLY_OS' },
      { key: 'planning',  label: 'Annual plan',    icon: '🗓', module: 'SUPPLY_OS' },
      { key: 'ff-dashboard', label: 'Urgent Pipeline', icon: '📋', module: 'SUPPLY_OS' },
      { key: 'ff-new',       label: 'Urgent request',  icon: '➕', module: 'SUPPLY_OS' },
      { key: 'ff-approvals', label: 'Urgent approvals', icon: '✅', module: 'SUPPLY_OS' },
      { key: 'ff-orders',    label: 'Urgent orders',   icon: '📦', module: 'SUPPLY_OS' },
    ],
  },
  {
    title: 'ERP',
    items: [
      { key: 'students',   label: 'Students',    icon: '🎓', module: 'ERP' },
      { key: 'addstudent', label: 'Add student', icon: '➕', module: 'ERP' },
      { key: 'bulkimport', label: 'Bulk import', icon: '📥', module: 'ERP' },
      { key: 'attendance', label: 'Attendance',  icon: '✓', module: 'ERP' },
      { key: 'timetable',  label: 'Timetable',   icon: '📅', module: 'ERP' },
      { key: 'fees',         label: 'Fee Collections',   icon: '₹', module: 'ERP' },
      { key: 'feestructure', label: 'Fee Configuration', icon: '📐', module: 'ERP' },
      { key: 'staff',      label: 'Staff & HR',            icon: '👥', module: 'ERP' },
      { key: 'classsetup', label: 'Class & section setup', icon: '🏫', module: 'ERP' },
    ],
  },
];

// OPERATIONS role: daily ops access - no finance, no user management
export const OPERATIONS_NAV_SECTIONS: WorkspaceNavSection[] = [
  OVERVIEW_NAV_SECTION,
  {
    title: 'Supply OS',
    items: [
      { key: 'catalog', label: 'Supply Details', icon: '⊞', module: 'SUPPLY_OS' },
      { key: 'orders',  label: 'School Orders',  icon: '📦', module: 'SUPPLY_OS' },
      { key: 'ff-dashboard', label: 'Urgent Pipeline', icon: '📋', module: 'SUPPLY_OS' },
      { key: 'ff-new',       label: 'Urgent request',  icon: '➕', module: 'SUPPLY_OS' },
      { key: 'ff-orders',    label: 'Urgent orders',   icon: '📦', module: 'SUPPLY_OS' },
    ],
  },
  {
    title: 'ERP',
    items: [
      { key: 'students',   label: 'Students',    icon: '🎓', module: 'ERP' },
      { key: 'attendance', label: 'Attendance',  icon: '✓', module: 'ERP' },
      { key: 'addstudent', label: 'Add student', icon: '➕', module: 'ERP' },
    ],
  },
];

export const ACCOUNTANT_NAV_SECTIONS: WorkspaceNavSection[] = [
  OVERVIEW_NAV_SECTION,
  {
    title: 'Finance',
    items: [
      { key: 'fees',         label: 'Fee Collections',   icon: '₹', module: 'ERP' },
      { key: 'feestructure', label: 'Fee Configuration', icon: '▦', module: 'ERP' },
      { key: 'orders',       label: 'School Orders',     icon: '□', module: 'SUPPLY_OS' },
    ],
  },
];

export const TEACHER_NAV_SECTIONS: WorkspaceNavSection[] = [
  OVERVIEW_NAV_SECTION,
  {
    title: 'Classroom',
    items: [
      { key: 'students',   label: 'Students',   icon: '△', module: 'ERP' },
      { key: 'attendance', label: 'Attendance', icon: '✓', module: 'ERP' },
      { key: 'timetable',  label: 'Timetable',  icon: '▤', module: 'ERP' },
    ],
  },
];

export const VIEWER_NAV_SECTIONS: WorkspaceNavSection[] = [
  OVERVIEW_NAV_SECTION,
  {
    title: 'Read only',
    items: [
      { key: 'students',   label: 'Students',   icon: '△', module: 'ERP' },
      { key: 'attendance', label: 'Attendance', icon: '✓', module: 'ERP' },
      { key: 'fees',       label: 'Fees',       icon: '₹', module: 'ERP' },
      { key: 'orders',     label: 'Orders',     icon: '□', module: 'SUPPLY_OS' },
    ],
  },
];

export const ZONE_ADMIN_NAV_SECTIONS: Array<{
  title: string;
  fire?: boolean;
  items: Array<{ key: PanelKey; label: string; icon: string; module?: ModuleCode }>;
}> = [
  {
    title: 'Zone',
    items: [
      { key: 'za-overview', label: 'Zone overview', icon: '🗺' },
      { key: 'za-schools', label: 'My schools', icon: '🏫' },
    ],
  },
];

export const SUPERADMIN_NAV_SECTIONS: Array<{
  title: string;
  fire?: boolean;
  items: Array<{ key: PanelKey; label: string; icon: string; module?: ModuleCode }>;
}> = [
  {
    title: 'Operations',
    items: [
      { key: 'orders', label: 'Order approvals', icon: '📦' },
      { key: 'sa-all-orders', label: 'All orders', icon: '📋' },
      { key: 'sa-new-order', label: 'New order request', icon: '✏️' },
      { key: 'sa-invoices', label: 'Invoices', icon: '🧾' },
    ],
  },
  {
    title: 'Urgent Procurement',
    fire: true,
    items: [
      { key: 'ff-dashboard', label: 'Request pipeline', icon: '📋' },
      { key: 'ff-orders', label: 'Approve & fulfill', icon: '✅' },
    ],
  },
  {
    title: 'Schools',
    items: [
      { key: 'sa-schools', label: 'School accounts', icon: '🏫' },
      { key: 'sa-erp', label: 'ERP activity', icon: '📊' },
    ],
  },
  {
    title: 'Analytics',
    items: [
      { key: 'sa-revenue', label: 'Revenue', icon: '₹' },
      { key: 'sa-catalog', label: 'Catalog mgmt', icon: '📋' },
    ],
  },
];

export const PANEL_TITLES: Record<PanelKey, string> = {
  home: 'Dashboard',
  students: 'Students',
  fees: 'Fee Collections',
  feestructure: 'Fee Configuration',
  attendance: 'Attendance',
  timetable: 'Timetable',
  addstudent: 'Add student',
  bulkimport: 'Bulk import',
  staff: 'Staff & HR',
  classsetup: 'Class & section setup',
  catalog: 'Catalog',
  orders: 'School Orders',
  planning: 'Annual plan',
  'ff-dashboard': 'Urgent Procurement - Requests',
  'ff-new': 'Urgent Procurement - New Request',
  'ff-approvals': 'Urgent Procurement - Pending Approvals',
  'ff-orders': 'Urgent Procurement - Placed Orders',
  'sa-all-orders': 'All orders',
  'sa-new-order': 'New order request',
  'sa-invoices': 'Invoices',
  'sa-schools': 'School accounts',
  'sa-erp': 'ERP activity',
  'sa-revenue': 'Revenue',
  'sa-catalog': 'Catalog management',
  'za-overview': 'Zone overview',
  'za-schools': 'My schools',
};

export const SA_NEW_ORDER_CATEGORIES = [
  { key: 'UNIFORMS',     icon: '👕',  title: 'Uniforms & apparel',  desc: 'Sets, PE kits, alterations' },
  { key: 'NOTEBOOKS',    icon: '📘',  title: 'Notebooks & books',   desc: 'Ruled, practice, custom' },
  { key: 'IDCARDS',      icon: '🪪',  title: 'ID cards & lanyards', desc: 'PVC, photo, QR/barcode' },
  { key: 'STATIONERY',   icon: '✏️',  title: 'Stationery kits',     desc: 'Pens, pencils, geometry' },
  { key: 'HOUSEKEEPING', icon: '🧹',  title: 'Housekeeping',        desc: 'Daily, weekly, AMC' },
  { key: 'CUSTOM',       icon: '🍱',  title: 'Food & canteen',      desc: 'Canteen mgmt, pantry' },
  { key: 'EVENTS',       icon: '🎉',  title: 'Events & print',      desc: 'Trophies, banners, certs' },
  { key: 'CUSTOM',       icon: '💬',  title: 'Custom / other',      desc: 'Anything not listed' },
] as const;

export const CATALOG_TILES = [
  { key: 'UNIFORMS',     emoji: '👕', name: 'Uniforms & apparel',    desc: 'Shirts, pants, PE kit, blazers, ties, shoes',          pill: 'Recurring',  pillClass: 'pg',  headerBg: 'var(--g1)',   imgQ: 'school+uniform' },
  { key: 'NOTEBOOKS',    emoji: '📓', name: 'Notebooks',             desc: 'A4/A5 ruled, plain, graph, school diary/planner',      pill: 'Recurring',  pillClass: 'pg',  headerBg: 'var(--b1)',   imgQ: 'notebook+school' },
  { key: 'STATIONERY',   emoji: '🖊', name: 'Stationery',            desc: 'Pens, pencils, erasers, rulers, craft supplies',       pill: 'Recurring',  pillClass: 'pg',  headerBg: 'var(--pu1)',  imgQ: 'stationery+pens' },
  { key: 'IDCARDS',      emoji: '🪪', name: 'ID cards',              desc: 'PVC photo ID, lanyards, QR / barcode',                 pill: 'One-time',   pillClass: 'pam', headerBg: 'var(--am1)',  imgQ: 'identity+card' },
  { key: 'HOUSEKEEPING', emoji: '🧹', name: 'Housekeeping',          desc: 'Daily / weekly cleaning contracts, supplies',         pill: 'Service',    pillClass: 'pb',  headerBg: '#e1f5ee',     imgQ: 'cleaning+school' },
  { key: 'EVENTS',       emoji: '🏆', name: 'Events & print',        desc: 'Trophies, certificates, banners, backdrops',           pill: 'One-time',   pillClass: 'pam', headerBg: '#fdecea',     imgQ: 'school+trophy+event' },
  { key: 'HEALTH',       emoji: '🩺', name: 'Health & safety',       desc: 'First aid, sanitizers, fire equipment, PPE',          pill: 'Recurring',  pillClass: 'pg',  headerBg: 'var(--re1)',  imgQ: 'first+aid+medical' },
];

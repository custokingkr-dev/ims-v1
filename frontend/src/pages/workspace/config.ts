// Navigation structure, panel identifiers, and static data configuration.
// Add new panels here - nav and title map update automatically.

export type WorkspaceData = any;

export type ModuleCode =
  | 'STUDENTS' | 'ATTENDANCE' | 'FEES' | 'INVOICES'
  | 'PAYMENTS' | 'ORDERS' | 'FIREFIGHTING' | 'REPORTS';

export const ALL_MODULES: Array<{ code: ModuleCode; label: string; icon: string; desc: string }> = [
  { code: 'STUDENTS',     label: 'Students',     icon: '🎓', desc: 'Student records, enrollment, bulk import' },
  { code: 'ATTENDANCE',   label: 'Attendance',   icon: '✓',  desc: 'Daily attendance tracking' },
  { code: 'FEES',         label: 'Fees',         icon: '₹',  desc: 'Fee collection and structure management' },
  { code: 'INVOICES',     label: 'Invoices',     icon: '🧾', desc: 'Invoice generation and management' },
  { code: 'PAYMENTS',     label: 'Payments',     icon: '💳', desc: 'Payment records and reconciliation' },
  { code: 'ORDERS',       label: 'Supply OS',    icon: '📦', desc: 'Catalog ordering and annual planning' },
  { code: 'FIREFIGHTING', label: 'Urgent Procurement', icon: '🚨', desc: 'Non-catalog urgent procurement' },
  { code: 'REPORTS',      label: 'Reports',      icon: '📊', desc: 'Analytics and export reports' },
];

export type PanelKey =
  | 'home' | 'students' | 'fees' | 'feestructure' | 'attendance' | 'timetable'
  | 'addstudent' | 'bulkimport' | 'staff' | 'catalog' | 'orders' | 'planning' | 'classsetup'
  | 'ff-dashboard' | 'ff-new' | 'ff-approvals' | 'ff-orders'
  | 'sa-all-orders' | 'sa-new-order' | 'sa-invoices'
  | 'sa-schools' | 'sa-erp' | 'sa-revenue' | 'sa-catalog'
  | 'za-overview' | 'za-schools';

export const ADMIN_NAV_SECTIONS: Array<{
  title: string;
  fire?: boolean;
  items: Array<{ key: PanelKey; label: string; icon: string; module?: ModuleCode }>;
}> = [
  {
    title: 'Supply OS',
    items: [
      { key: 'catalog',   label: 'Catalog',       icon: '⊞', module: 'ORDERS' },
      { key: 'orders',    label: 'School Orders', icon: '📦', module: 'ORDERS' },
      { key: 'planning',  label: 'Annual plan',   icon: '🗓', module: 'ORDERS' },
    ],
  },
  {
    title: 'Urgent Procurement',
    fire: true,
    items: [
      { key: 'ff-dashboard', label: 'Request Pipeline', icon: '📋', module: 'FIREFIGHTING' },
      { key: 'ff-new',       label: 'New request',    icon: '➕', module: 'FIREFIGHTING' },
      { key: 'ff-approvals', label: 'Approvals',      icon: '✅', module: 'FIREFIGHTING' },
      { key: 'ff-orders',    label: 'Placed orders',  icon: '📦', module: 'FIREFIGHTING' },
    ],
  },
  {
    title: 'Overview',
    items: [
      { key: 'home', label: 'Dashboard', icon: '◼' },
    ],
  },
  {
    title: 'Academics',
    items: [
      { key: 'students',   label: 'Students',    icon: '🎓', module: 'STUDENTS' },
      { key: 'addstudent', label: 'Add student', icon: '➕', module: 'STUDENTS' },
      { key: 'bulkimport', label: 'Bulk import', icon: '📥', module: 'STUDENTS' },
      { key: 'attendance', label: 'Attendance',  icon: '✓',  module: 'ATTENDANCE' },
      { key: 'timetable',  label: 'Timetable',   icon: '📅' },
    ],
  },
  {
    title: 'Finance',
    items: [
      { key: 'fees',         label: 'Fee Collections',   icon: '₹',  module: 'FEES' },
      { key: 'feestructure', label: 'Fee Configuration', icon: '📐', module: 'FEES' },
    ],
  },
  {
    title: 'Setup',
    items: [
      { key: 'staff',      label: 'Staff & HR',            icon: '👥' },
      { key: 'classsetup', label: 'Class & section setup', icon: '🏫' },
    ],
  },
];

// OPERATIONS role: daily ops access - no finance, no user management
export const OPERATIONS_NAV_SECTIONS: Array<{
  title: string;
  fire?: boolean;
  items: Array<{ key: PanelKey; label: string; icon: string; module?: ModuleCode }>;
}> = [
  {
    title: 'Supply OS',
    items: [
      { key: 'catalog', label: 'Catalog',        icon: '⊞', module: 'ORDERS' },
      { key: 'orders',  label: 'All Orders', icon: '📦', module: 'ORDERS' },
    ],
  },
  {
    title: 'Urgent Procurement',
    fire: true,
    items: [
      { key: 'ff-dashboard', label: 'Request Pipeline', icon: '📋', module: 'FIREFIGHTING' },
      { key: 'ff-new',       label: 'New request',   icon: '➕', module: 'FIREFIGHTING' },
      { key: 'ff-orders',    label: 'Placed orders', icon: '📦', module: 'FIREFIGHTING' },
    ],
  },
  {
    title: 'School ERP',
    items: [
      { key: 'home',       label: 'Dashboard',   icon: '◼' },
      { key: 'students',   label: 'Students',    icon: '🎓', module: 'STUDENTS' },
      { key: 'attendance', label: 'Attendance',  icon: '✓',  module: 'ATTENDANCE' },
      { key: 'addstudent', label: 'Add student', icon: '➕', module: 'STUDENTS' },
    ],
  },
];

export const ACCOUNTANT_NAV_SECTIONS: Array<{
  title: string;
  fire?: boolean;
  items: Array<{ key: PanelKey; label: string; icon: string; module?: ModuleCode }>;
}> = [
  {
    title: 'Finance',
    items: [
      { key: 'home',         label: 'Dashboard',         icon: '◼' },
      { key: 'fees',         label: 'Fee Collections',   icon: '₹', module: 'FEES' },
      { key: 'feestructure', label: 'Fee Configuration', icon: '▦', module: 'FEES' },
      { key: 'orders',       label: 'School Orders',     icon: '□', module: 'ORDERS' },
    ],
  },
];

export const TEACHER_NAV_SECTIONS: Array<{
  title: string;
  fire?: boolean;
  items: Array<{ key: PanelKey; label: string; icon: string; module?: ModuleCode }>;
}> = [
  {
    title: 'Classroom',
    items: [
      { key: 'home',       label: 'Dashboard',  icon: '◼' },
      { key: 'students',   label: 'Students',   icon: '△', module: 'STUDENTS' },
      { key: 'attendance', label: 'Attendance', icon: '✓', module: 'ATTENDANCE' },
      { key: 'timetable',  label: 'Timetable',  icon: '▤' },
    ],
  },
];

export const VIEWER_NAV_SECTIONS: Array<{
  title: string;
  fire?: boolean;
  items: Array<{ key: PanelKey; label: string; icon: string; module?: ModuleCode }>;
}> = [
  {
    title: 'Read only',
    items: [
      { key: 'home',       label: 'Dashboard',  icon: '◼' },
      { key: 'students',   label: 'Students',   icon: '△', module: 'STUDENTS' },
      { key: 'attendance', label: 'Attendance', icon: '✓', module: 'ATTENDANCE' },
      { key: 'fees',       label: 'Fees',       icon: '₹', module: 'FEES' },
      { key: 'orders',     label: 'Orders',     icon: '□', module: 'ORDERS' },
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

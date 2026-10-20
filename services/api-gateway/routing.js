'use strict';

// First match wins. Keep specific actions before their broader domain prefixes.
// This module has no environment, authentication, or HTTP-server dependencies.
const routes = [
  route('student', /^\/api\/v1\/classes\/[^/]+\/sections\/[^/]+\/students$/),
  route('identity', '/api/v1/auth/'),
  route('identity', '/api/v1/rbac/'),
  route('identity', '/api/v1/users'),
  route('identity', /^\/api\/v1\/schools\/[^/]+\/(admin|operations-user)$/, { methods: ['POST'] }),
  route('identity', /^\/api\/v1\/zones\/[^/]+\/admin$/),
  route('tenant', '/api/v1/classes/'),
  route('tenant', '/api/v1/classes'),
  route('tenant', '/api/v1/schools/'),
  route('tenant', '/api/v1/schools'),
  route('tenant', '/api/v1/zones/'),
  route('tenant', '/api/v1/zones'),
  route('tenant', '/api/v1/academic-years'),
  route('student', '/api/v1/students/'),
  route('student', '/api/v1/students'),
  route('student', '/api/v1/workspace/students'),
  route('student', '/api/v1/student-review-items/'),
  route('student', '/api/v1/student-photo-imports/'),
  route('student', '/api/v1/student-photo-imports'),
  route('firefighting', '/api/v1/workspace/firefighting'),
  route('tenant', '/api/v1/workspace/staff'),
  route('tenant', '/api/v1/timetable/'),
  route('tenant', '/api/v1/timetable'),
  route('fee', '/api/v1/workspace/fees/'),
  route('reporting', '/api/v1/workspace/'),
  route('reporting', '/api/v1/workspace'),
  route('reporting', '/api/v1/reporting/'),
  route('attendance', '/api/v1/attendance/'),
  route('fee', '/api/v1/fee-structure'),
  route('fee', '/api/v1/fee-assignments'),
  route('fee', '/api/v1/payments'),
  route('fee', '/api/v1/fees/'),
  route('fee', '/api/v1/receipts/'),
  route('catalog', '/api/v1/supply/'),
  route('catalog', '/api/v1/catalog/'),
  route('billing', '/api/v1/billing/'),
  route('billing', '/api/v1/sa/invoices/'),
  route('billing', '/api/v1/sa/invoices'),
  route('billing', '/api/v1/customers/'),
  route('billing', '/api/v1/customers'),
  route('billing', '/api/v1/invoices/'),
  route('billing', '/api/v1/invoices'),
  route('billing', '/api/v1/billing-payments/'),
  route('billing', '/api/v1/billing-payments'),
  route('catalog', '/api/v1/sa/orders'),
  route('tenant', '/api/v1/sa/schools'),
  route('workflow', '/api/v1/workflows/'),
  route('firefighting', '/api/v1/ff/'),
  route('audit', '/api/v1/audit-logs'),
  route('notification', '/api/v1/notifications/'),
  route('reporting', '/api/v1/approvals/'),
  route('reporting', '/api/v1/approvals'),
  route('catalog', /^\/api\/v1\/dashboard\/vendor-dues\/catalog-orders\/[^/]+\/mark-paid$/),
  route('firefighting', /^\/api\/v1\/dashboard\/vendor-dues\/firefighting\/[^/]+\/mark-paid$/),
  route('fee', '/api/v1/dashboard/finance/fee-defaulters/reminders'),
  route('reporting', '/api/v1/dashboard/'),
  route('reporting', '/api/v1/dashboard'),
  route('reporting', '/api/v1/command-centre/'),
  diagnostic('notification', '/notification-api/v1/'),
  diagnostic('audit', '/audit-api/v1/'),
  diagnostic('identity', '/identity-api/v1/'),
  diagnostic('tenant', '/tenant-api/v1/'),
  diagnostic('student', '/student-api/v1/'),
  diagnostic('attendance', '/attendance-api/v1/'),
  diagnostic('fee', '/fee-api/v1/'),
  diagnostic('catalog', '/catalog-api/v1/'),
  diagnostic('workflow', '/workflow-api/v1/'),
  diagnostic('firefighting', '/firefighting-api/v1/'),
  diagnostic('reporting', '/reporting-api/v1/'),
  diagnostic('billing', '/billing-api/v1/'),
];

function route(service, matcher, options = {}) {
  const allowedMethods = Array.isArray(options.methods)
    ? new Set(options.methods.map((method) => method.toUpperCase()))
    : null;
  return {
    service,
    rewritePrefix: null,
    matches(pathname, method) {
      if (allowedMethods && method && !allowedMethods.has(method.toUpperCase())) return false;
      if (matcher instanceof RegExp) return matcher.test(pathname);
      return pathname === matcher
        || pathname.startsWith(matcher.endsWith('/') ? matcher : `${matcher}/`);
    },
    rewrite(pathname) {
      return pathname;
    },
  };
}

function diagnostic(service, prefix) {
  return {
    service,
    rewritePrefix: prefix,
    matches(pathname) {
      return pathname.startsWith(prefix);
    },
    rewrite(pathname) {
      return `/api/v1/${pathname.slice(prefix.length)}`;
    },
  };
}

function isInternalUpstreamPath(upstreamPathname) {
  return /^\/api\/v1\/(internal|pubsub)(\/|$)/.test(upstreamPathname);
}

module.exports = { routes, route, diagnostic, isInternalUpstreamPath };

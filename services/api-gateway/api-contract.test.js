'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');

const {
  inventory,
  compatibilityRoutes,
  classifyCompatibilityRequest,
  applyDeprecationHeaders,
  pathTemplateMatcher,
  parseHttpDate,
  parseDeprecationDate,
} = require('./api-contract');

test('generated contract inventories all service and compatibility surfaces', () => {
  assert.equal(inventory.kind, 'ims-api-route-inventory');
  assert.equal(inventory.summary.services, 5);
  assert.ok(inventory.summary.endpoints > 300);
  assert.equal(compatibilityRoutes.length, inventory.summary.compatibilityEndpoints);
  assert.equal(inventory.summary.diagnosticAliases, 12);
  assert.ok(inventory.summary.gatewayRoutes > 60);
  assert.equal(inventory.summary.gatewayRoutes, inventory.gatewayRoutes.length);
  assert.ok(inventory.summary.frontendCompatibilityCalls > 0);
  assert.equal(inventory.summary.frontendCompatibilityCalls, inventory.clientMigrationReferences.length);
  assert.ok(inventory.clientMigrationReferences.some((reference) =>
    reference.pathExpression === '/supply/orders/stats'
      && reference.compatibilityTemplate === '/api/v1/supply/orders/stats'));
});

test('path template matcher treats variables as one safe path segment', () => {
  const matcher = pathTemplateMatcher('/api/v1/schools/{schoolId}/admin');
  assert.equal(matcher.test('/api/v1/schools/school-1/admin'), true);
  assert.equal(matcher.test('/api/v1/schools/school/1/admin'), false);
  assert.equal(matcher.test('/api/v1/schools/school-1/admin/extra'), false);
});

test('compatibility classifier is method-aware and ignores canonical siblings', () => {
  const alias = classifyCompatibilityRequest('/api/v1/schools/school-1/admin', 'POST');
  assert.equal(alias.kind, 'controller-alias');
  assert.match(alias.id, /identity-service:POST/);
  assert.equal(classifyCompatibilityRequest('/api/v1/schools/school-1/admin', 'GET'), null);
  assert.equal(classifyCompatibilityRequest('/api/v1/billing/sa/invoices', 'GET'), null);
});

test('diagnostic alias reports its rewritten public successor', () => {
  assert.deepEqual(classifyCompatibilityRequest('/reporting-api/v1/dashboard', 'GET'), {
    id: 'diagnostic:reporting',
    kind: 'diagnostic-alias',
    template: '/reporting-api/v1/{remainder}',
    successor: '/api/v1/dashboard',
  });
});

test('deprecation headers include a successor only where one is known', () => {
  const headers = new Map();
  const response = { setHeader: (name, value) => headers.set(name, value) };
  const alias = classifyCompatibilityRequest('/api/v1/sa/invoices/invoice-1', 'GET');
  applyDeprecationHeaders(response, alias);
  assert.equal(headers.get('Deprecation'), '@1787616000');
  assert.equal(headers.get('Link'), '</api/v1/billing/sa/invoices/invoice-1>; rel="successor-version"');
  assert.equal(headers.has('Sunset'), false);
});

test('sunset configuration accepts dates and rejects invalid values', () => {
  assert.equal(parseHttpDate('Wed, 31 Dec 2026 23:59:59 GMT'), 'Thu, 31 Dec 2026 23:59:59 GMT');
  assert.equal(parseHttpDate(''), null);
  assert.throws(() => parseHttpDate('not-a-date'), /valid HTTP date/);
});

test('deprecation configuration serializes an RFC 9745 Structured Field date', () => {
  assert.deepEqual(parseDeprecationDate('2026-08-25T00:00:00Z'), {
    timestamp: 1787616000000,
    header: '@1787616000',
  });
  assert.throws(() => parseDeprecationDate('not-a-date'), /valid date/);
});

'use strict';

const inventory = require('./api-route-inventory.json');

const configuredDeprecation = parseDeprecationDate(
  process.env.GATEWAY_COMPATIBILITY_DEPRECATION || '2026-08-25T00:00:00Z',
);
const configuredSunset = parseHttpDate(process.env.GATEWAY_COMPATIBILITY_SUNSET);
if (configuredSunset && Date.parse(configuredSunset) < configuredDeprecation.timestamp) {
  throw new Error('GATEWAY_COMPATIBILITY_SUNSET must not precede GATEWAY_COMPATIBILITY_DEPRECATION');
}
const compatibilityRoutes = inventory.endpoints
  .filter((endpoint) => endpoint.classification === 'compatibility')
  .map((endpoint) => ({
    id: `${endpoint.service}:${endpoint.method}:${endpoint.path}`,
    method: endpoint.method,
    template: endpoint.path,
    matcher: pathTemplateMatcher(endpoint.path),
  }))
  .sort((left, right) => {
    const leftVariables = (left.template.match(/\{/g) || []).length;
    const rightVariables = (right.template.match(/\{/g) || []).length;
    return leftVariables - rightVariables || right.template.length - left.template.length;
  });

function classifyCompatibilityRequest(pathname, method) {
  const normalizedMethod = String(method || 'GET').toUpperCase();
  for (const alias of inventory.diagnosticAliases) {
    if (pathname.startsWith(alias.prefix)) {
      return {
        id: alias.id,
        kind: 'diagnostic-alias',
        template: `${alias.prefix}{remainder}`,
        successor: `/api/v1/${pathname.slice(alias.prefix.length)}`,
      };
    }
  }
  const route = compatibilityRoutes.find((candidate) =>
    (candidate.method === 'ANY' || candidate.method === normalizedMethod)
      && candidate.matcher.test(pathname));
  return route
    ? { id: route.id, kind: 'controller-alias', template: route.template, successor: knownSuccessor(pathname) }
    : null;
}

function applyDeprecationHeaders(res, compatibility) {
  if (!compatibility) return;
  // RFC 9745 requires a Structured Field Date (`@` plus Unix seconds), not an HTTP-date or boolean.
  res.setHeader('Deprecation', configuredDeprecation.header);
  if (configuredSunset) res.setHeader('Sunset', configuredSunset);
  if (compatibility.successor) {
    const successorLink = `<${compatibility.successor}>; rel="successor-version"`;
    const existingLink = typeof res.getHeader === 'function' ? res.getHeader('Link') : null;
    res.setHeader('Link', existingLink ? `${existingLink}, ${successorLink}` : successorLink);
  }
}

function pathTemplateMatcher(template) {
  const expression = String(template)
    .split(/(\{[^}]+\})/g)
    .map((part) => part.startsWith('{') && part.endsWith('}') ? '[^/]+' : escapeRegExp(part))
    .join('');
  return new RegExp(`^${expression}$`);
}

function escapeRegExp(value) {
  return String(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function knownSuccessor(template) {
  if (template === '/api/v1/sa/invoices' || template.startsWith('/api/v1/sa/invoices/')) {
    return template.replace('/api/v1/sa/invoices', '/api/v1/billing/sa/invoices');
  }
  return null;
}

function parseHttpDate(value) {
  if (!value) return null;
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) {
    throw new Error('GATEWAY_COMPATIBILITY_SUNSET must be a valid HTTP date');
  }
  return new Date(timestamp).toUTCString();
}

function parseDeprecationDate(value) {
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) {
    throw new Error('GATEWAY_COMPATIBILITY_DEPRECATION must be a valid date');
  }
  return { timestamp, header: `@${Math.floor(timestamp / 1000)}` };
}

module.exports = {
  inventory,
  compatibilityRoutes,
  classifyCompatibilityRequest,
  applyDeprecationHeaders,
  pathTemplateMatcher,
  knownSuccessor,
  parseHttpDate,
  parseDeprecationDate,
};

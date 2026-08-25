'use strict';

const fs = require('node:fs');
const path = require('node:path');

const repositoryRoot = path.resolve(__dirname, '..');
const outputPath = path.join(repositoryRoot, 'services', 'api-gateway', 'api-route-inventory.json');
const checkOnly = process.argv.includes('--check');

const serviceDirectories = fs.readdirSync(path.join(repositoryRoot, 'services'), { withFileTypes: true })
  .filter((entry) => entry.isDirectory() && entry.name.endsWith('-service'))
  .map((entry) => entry.name)
  .sort();

const endpoints = [];
const controllers = [];

for (const service of serviceDirectories) {
  const javaRoot = path.join(repositoryRoot, 'services', service, 'src', 'main', 'java');
  if (!fs.existsSync(javaRoot)) continue;
  for (const file of walk(javaRoot).filter((candidate) => candidate.endsWith('.java'))) {
    const source = fs.readFileSync(file, 'utf8');
    const classMatch = /\b(?:public\s+)?(?:final\s+)?class\s+([A-Za-z_$][\w$]*)/.exec(source);
    if (!classMatch) continue;
    const annotations = mappingAnnotations(source);
    if (!annotations.length) continue;

    const relativeSource = path.relative(repositoryRoot, file).replaceAll(path.sep, '/');
    const classPosition = classMatch.index;
    const classMappings = annotations.filter((annotation) => annotation.index < classPosition);
    const methodMappings = annotations.filter((annotation) => annotation.index > classPosition);
    const basePaths = classMappings.length
      ? unique(classMappings.flatMap((annotation) => annotation.paths))
      : [''];
    const classification = relativeSource.includes('/api/compat/')
      ? 'compatibility'
      : relativeSource.includes('/api/internal/')
        ? 'internal'
        : 'canonical';

    let endpointCount = 0;
    for (const annotation of methodMappings) {
      const methods = annotation.methods.length ? annotation.methods : ['ANY'];
      for (const basePath of basePaths) {
        for (const localPath of annotation.paths) {
          for (const method of methods) {
            endpoints.push({
              service,
              controller: classMatch[1],
              method,
              path: joinPaths(basePath, localPath),
              classification,
              source: relativeSource,
              line: lineNumberAt(source, annotation.index),
            });
            endpointCount += 1;
          }
        }
      }
    }

    controllers.push({
      service,
      controller: classMatch[1],
      classification,
      basePaths,
      endpointCount,
      source: relativeSource,
    });
  }
}

endpoints.sort(compareBy('service', 'path', 'method', 'controller'));
controllers.sort(compareBy('service', 'controller'));

const gatewayRoutes = parseGatewayRoutes(path.join(repositoryRoot, 'services', 'api-gateway', 'server.js'));
const diagnosticAliases = gatewayRoutes
  .filter((route) => route.kind === 'diagnostic-alias')
  .map((route) => ({
    id: `diagnostic:${route.service}`,
    service: route.service,
    prefix: route.matcher,
    classification: 'compatibility',
    successorTemplate: `/api/v1/{remainder}`,
  }));

const compatibilityEndpoints = endpoints.filter((endpoint) => endpoint.classification === 'compatibility');
const clientMigrationReferences = findFrontendCompatibilityReferences(compatibilityEndpoints);
const inventory = {
  schemaVersion: 1,
  kind: 'ims-api-route-inventory',
  description: 'Generated from Spring MVC controller annotations. Paths describe routing contracts, not request/response schemas.',
  sourceRoots: serviceDirectories.map((service) => `services/${service}/src/main/java`),
  summary: {
    services: serviceDirectories.length,
    controllers: controllers.length,
    endpoints: endpoints.length,
    canonicalEndpoints: endpoints.filter((endpoint) => endpoint.classification === 'canonical').length,
    compatibilityEndpoints: compatibilityEndpoints.length,
    internalEndpoints: endpoints.filter((endpoint) => endpoint.classification === 'internal').length,
    diagnosticAliases: diagnosticAliases.length,
    gatewayRoutes: gatewayRoutes.length,
    frontendCompatibilityCalls: clientMigrationReferences.length,
    frontendCompatibilityFiles: unique(clientMigrationReferences.map((reference) => reference.source)).length,
  },
  diagnosticAliases,
  gatewayRoutes,
  clientMigrationReferences,
  controllers,
  endpoints,
};

const rendered = `${JSON.stringify(inventory, null, 2)}\n`;
if (checkOnly) {
  const current = fs.existsSync(outputPath) ? fs.readFileSync(outputPath, 'utf8') : '';
  if (current !== rendered) {
    console.error(`API route inventory is stale. Run: node ${path.relative(repositoryRoot, __filename)}`);
    process.exit(1);
  }
  console.log(`API route inventory is current: ${inventory.summary.endpoints} endpoints, ${inventory.summary.compatibilityEndpoints} compatibility mappings, ${inventory.summary.diagnosticAliases} diagnostic aliases.`);
} else {
  fs.writeFileSync(outputPath, rendered);
  console.log(`Wrote ${path.relative(repositoryRoot, outputPath)} with ${inventory.summary.endpoints} endpoints.`);
}

function walk(directory) {
  const files = [];
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const candidate = path.join(directory, entry.name);
    if (entry.isDirectory()) files.push(...walk(candidate));
    else if (entry.isFile()) files.push(candidate);
  }
  return files;
}

function mappingAnnotations(source) {
  const annotations = [];
  const pattern = /@(?:org\.springframework\.web\.bind\.annotation\.)?(Get|Post|Put|Patch|Delete|Request)Mapping\b/g;
  let match;
  while ((match = pattern.exec(source)) !== null) {
    const type = match[1];
    let cursor = pattern.lastIndex;
    while (/\s/.test(source[cursor] || '')) cursor += 1;
    let body = '';
    if (source[cursor] === '(') {
      const close = balancedClose(source, cursor);
      if (close < 0) throw new Error(`Unclosed @${type}Mapping annotation at line ${lineNumberAt(source, match.index)}`);
      body = source.slice(cursor + 1, close);
      pattern.lastIndex = close + 1;
    }
    const paths = quotedStrings(pathExpression(body));
    annotations.push({
      index: match.index,
      paths: paths.length ? paths : [''],
      methods: mappingMethods(type, body),
    });
  }
  return annotations;
}

function balancedClose(source, open) {
  let depth = 0;
  let quote = null;
  let escaped = false;
  for (let index = open; index < source.length; index += 1) {
    const character = source[index];
    if (quote) {
      if (escaped) escaped = false;
      else if (character === '\\') escaped = true;
      else if (character === quote) quote = null;
      continue;
    }
    if (character === '"' || character === "'") quote = character;
    else if (character === '(') depth += 1;
    else if (character === ')' && --depth === 0) return index;
  }
  return -1;
}

function pathExpression(body) {
  if (!body) return '';
  const named = /(?:^|,)\s*(?:value|path)\s*=\s*/m.exec(body);
  if (named) return body.slice(named.index + named[0].length).split(/,\s*(?:consumes|produces|params|headers|method)\s*=/m, 1)[0];
  return body.split(/,\s*(?:consumes|produces|params|headers|method)\s*=/m, 1)[0];
}

function quotedStrings(expression) {
  const values = [];
  for (const match of expression.matchAll(/"((?:\\.|[^"\\])*)"/g)) {
    values.push(JSON.parse(`"${match[1]}"`));
  }
  return values;
}

function mappingMethods(type, body) {
  if (type !== 'Request') return [type.toUpperCase()];
  return unique([...body.matchAll(/RequestMethod\.([A-Z]+)/g)].map((match) => match[1]));
}

function joinPaths(basePath, localPath) {
  const joined = `${basePath || ''}/${String(localPath || '').replace(/^\/+/, '')}`
    .replace(/\/{2,}/g, '/');
  if (joined === '/') return '/';
  return joined.endsWith('/') ? joined.slice(0, -1) : joined;
}

function lineNumberAt(source, index) {
  return source.slice(0, index).split('\n').length;
}

function unique(values) {
  return [...new Set(values)];
}

function compareBy(...keys) {
  return (left, right) => {
    for (const key of keys) {
      const compared = String(left[key]).localeCompare(String(right[key]));
      if (compared) return compared;
    }
    return 0;
  };
}

function parseGatewayRoutes(file) {
  const source = fs.readFileSync(file, 'utf8');
  const routesBlock = /const routes = \[([\s\S]*?)\n\];/.exec(source);
  if (!routesBlock) throw new Error('Could not find gateway routes declaration');
  const blockStart = routesBlock.index + routesBlock[0].indexOf(routesBlock[1]);
  const routes = [];
  for (const [offset, line] of routesBlock[1].split('\n').entries()) {
    const routeMatch = /^\s*route\('([^']+)',\s*(\/.+\/|'[^']+')(?:,\s*\{\s*methods:\s*\[([^\]]+)\]\s*\})?\),\s*$/.exec(line);
    if (routeMatch) {
      const literal = routeMatch[2];
      const isRegex = literal.startsWith('/');
      routes.push({
        order: routes.length,
        service: routeMatch[1],
        kind: 'public',
        matcherType: isRegex ? 'regex' : 'prefix',
        matcher: isRegex ? literal.slice(1, -1) : literal.slice(1, -1),
        methods: routeMatch[3]
          ? [...routeMatch[3].matchAll(/'([^']+)'/g)].map((match) => match[1])
          : ['ANY'],
        source: 'services/api-gateway/server.js',
        line: lineNumberAt(source, blockStart) + offset,
      });
      continue;
    }
    const diagnosticMatch = /^\s*diagnostic\('([^']+)',\s*'([^']+)'\),\s*$/.exec(line);
    if (diagnosticMatch) {
      routes.push({
        order: routes.length,
        service: diagnosticMatch[1],
        kind: 'diagnostic-alias',
        matcherType: 'prefix',
        matcher: diagnosticMatch[2],
        methods: ['ANY'],
        rewritePrefix: '/api/v1/',
        source: 'services/api-gateway/server.js',
        line: lineNumberAt(source, blockStart) + offset,
      });
      continue;
    }
    if (line.trim()) throw new Error(`Unsupported gateway route declaration: ${line.trim()}`);
  }
  if (!routes.length) throw new Error('Gateway route inventory is empty');
  return routes;
}

function findFrontendCompatibilityReferences(compatibilityMappings) {
  const frontendRoot = path.join(repositoryRoot, 'frontend', 'src');
  if (!fs.existsSync(frontendRoot)) return [];
  const matchers = compatibilityMappings.map((endpoint) => ({
    endpoint,
    matcher: new RegExp(`^${endpoint.path.split(/(\{[^}]+\})/g)
      .map((part) => part.startsWith('{') && part.endsWith('}') ? '[^/]+' : escapeRegExp(part))
      .join('')}$`),
  })).sort((left, right) => {
    const leftVariables = (left.endpoint.path.match(/\{/g) || []).length;
    const rightVariables = (right.endpoint.path.match(/\{/g) || []).length;
    return leftVariables - rightVariables || right.endpoint.path.length - left.endpoint.path.length;
  });
  const references = [];
  const callPattern = /\bapi\.(get|post|put|patch|delete)(?:<[\s\S]{0,500}?>)?\s*\(\s*(["'`])([\s\S]*?)\2/g;
  for (const file of walk(frontendRoot).filter((candidate) => /\.tsx?$/.test(candidate) && !/\.test\.tsx?$/.test(candidate))) {
    const source = fs.readFileSync(file, 'utf8');
    for (const match of source.matchAll(callPattern)) {
      const method = match[1].toUpperCase();
      const literal = match[3];
      if (!literal.startsWith('/')) continue;
      const samplePath = `/api/v1${literal.split(/[?#]/, 1)[0].replace(/\$\{[^}]+\}/g, 'sample')}`;
      const matched = matchers.find((candidate) => candidate.endpoint.method === method
        && candidate.matcher.test(samplePath));
      if (!matched) continue;
      references.push({
        method,
        pathExpression: literal,
        compatibilityTemplate: matched.endpoint.path,
        source: path.relative(repositoryRoot, file).replaceAll(path.sep, '/'),
        line: lineNumberAt(source, match.index),
      });
    }
  }
  return references.sort(compareBy('source', 'line', 'method'));
}

function escapeRegExp(value) {
  return String(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

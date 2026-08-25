'use strict';

const fs = require('node:fs');
const path = require('node:path');

const repositoryRoot = path.resolve(__dirname, '..');
const specificationPath = path.join(
  repositoryRoot,
  'contracts',
  'openapi',
  'identity-auth.v1.openapi.json',
);
const inventoryPath = path.join(repositoryRoot, 'services', 'api-gateway', 'api-route-inventory.json');
const identityAuthSourcePath = path.join(
  repositoryRoot,
  'services',
  'identity-service',
  'src',
  'main',
  'java',
  'com',
  'custoking',
  'ims',
  'identityservice',
  'application',
  'IdentityAuthService.java',
);
const outputPath = path.join(repositoryRoot, 'frontend', 'src', 'generated', 'identityAuthApi.ts');
const checkOnly = process.argv.includes('--check');

const specification = readJson(specificationPath);
const inventory = readJson(inventoryPath);
validateContract(specification, inventory);
const rendered = renderClient(specification);

if (checkOnly) {
  const current = fs.existsSync(outputPath) ? fs.readFileSync(outputPath, 'utf8') : '';
  if (current !== rendered) {
    console.error(`Generated identity client is stale. Run: node ${path.relative(repositoryRoot, __filename)}`);
    process.exit(1);
  }
  console.log(`OpenAPI identity client is current: ${Object.keys(specification.paths).length} canonical operations.`);
} else {
  fs.mkdirSync(path.dirname(outputPath), { recursive: true });
  fs.writeFileSync(outputPath, rendered);
  console.log(`Wrote ${path.relative(repositoryRoot, outputPath)} from ${path.relative(repositoryRoot, specificationPath)}.`);
}

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

function validateContract(spec, routeInventory) {
  if (!String(spec.openapi || '').startsWith('3.1.')) {
    throw new Error('Identity contract must use OpenAPI 3.1.');
  }
  if (spec['x-ims-service'] !== 'identity-service' || spec['x-ims-controller'] !== 'AuthController') {
    throw new Error('Identity contract must name its owning service and controller.');
  }
  if (spec['x-ims-audience'] !== 'browser' || spec.servers?.length !== 1 || spec.servers[0]?.url !== '/') {
    throw new Error('Identity browser contract must be origin-relative and explicitly browser-scoped.');
  }
  const basePath = spec['x-ims-client-base-path'];
  if (basePath !== '/api/v1') throw new Error('Identity browser client must retain the gateway /api/v1 base path.');
  if (!/^[A-Za-z_$][\w$]*$/.test(spec['x-ims-client-name'] || '')) {
    throw new Error('Identity contract has an invalid TypeScript client name.');
  }

  const operationIds = new Set();
  const inventoryEndpoints = new Set(routeInventory.endpoints
    .filter((endpoint) => endpoint.service === spec['x-ims-service']
      && endpoint.controller === spec['x-ims-controller']
      && endpoint.classification === 'canonical')
    .map((endpoint) => `${endpoint.method}:${endpoint.path}`));

  for (const [contractPath, pathItem] of Object.entries(spec.paths || {})) {
    if (!contractPath.startsWith(`${basePath}/`) || contractPath.includes('{')) {
      throw new Error(`Identity browser operation must be a concrete canonical gateway path: ${contractPath}`);
    }
    if (contractPath === '/api/v1/auth/introspect') {
      throw new Error('Internal identity introspection must never be exposed by the browser client contract.');
    }
    for (const [method, operation] of Object.entries(pathItem)) {
      const normalizedMethod = method.toUpperCase();
      if (!['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].includes(normalizedMethod)) continue;
      if (!inventoryEndpoints.has(`${normalizedMethod}:${contractPath}`)) {
        throw new Error(`OpenAPI operation is absent from the generated controller inventory: ${normalizedMethod} ${contractPath}`);
      }
      if (!operation.operationId || operationIds.has(operation.operationId)) {
        throw new Error(`OpenAPI operationId must be present and unique: ${operation.operationId || contractPath}`);
      }
      operationIds.add(operation.operationId);
      successResponse(operation);
      requestSchema(operation);
    }
  }
  if (operationIds.size === 0) throw new Error('Identity contract contains no client operations.');

  const identityAuthSource = fs.readFileSync(identityAuthSourcePath, 'utf8');
  validateJavaRecordSchema(identityAuthSource, 'LoginRequest', spec.components?.schemas?.LoginRequest);
  validateJavaRecordSchema(identityAuthSource, 'AuthResponse', spec.components?.schemas?.AuthResponse);
}

function renderClient(spec) {
  const schemaEntries = Object.entries(spec.components?.schemas || {});
  const operations = [];
  for (const [contractPath, pathItem] of Object.entries(spec.paths)) {
    for (const [method, operation] of Object.entries(pathItem)) {
      if (!['get', 'post', 'put', 'patch', 'delete'].includes(method)) continue;
      const request = requestSchema(operation);
      const response = successResponse(operation);
      operations.push({
        method,
        operationId: operation.operationId,
        path: contractPath.slice(spec['x-ims-client-base-path'].length),
        requestType: request ? typeFor(request) : null,
        responseType: response.schema ? typeFor(response.schema) : 'void',
      });
    }
  }

  const lines = [
    '/* eslint-disable */',
    '// Generated by scripts/generate-openapi-typescript-client.js. Do not edit by hand.',
    `// Source: contracts/openapi/${path.basename(specificationPath)}`,
    '',
    "import type { AxiosInstance } from 'axios';",
    '',
  ];

  for (const [name, schema] of schemaEntries) {
    lines.push(...renderSchema(name, schema), '');
  }

  const clientName = spec['x-ims-client-name'];
  lines.push(`export interface ${clientName} {`);
  for (const operation of operations) {
    const request = operation.requestType ? `request: ${operation.requestType}` : '';
    lines.push(`  ${operation.operationId}(${request}): Promise<${operation.responseType}>;`);
  }
  lines.push('}', '');
  lines.push(`export function create${clientName}(http: AxiosInstance): ${clientName} {`, '  return {');
  for (const operation of operations) {
    const request = operation.requestType ? `request: ${operation.requestType}` : '';
    lines.push(`    async ${operation.operationId}(${request}) {`);
    const body = operation.requestType ? ', request' : '';
    if (operation.responseType === 'void') {
      lines.push(`      await http.${operation.method}('${operation.path}'${body});`, '    },');
    } else {
      lines.push(
        `      const response = await http.${operation.method}<${operation.responseType}>('${operation.path}'${body});`,
        '      return response.data;',
        '    },',
      );
    }
  }
  lines.push('  };', '}', '');
  return `${lines.join('\n')}`;
}

function renderSchema(name, schema) {
  if (schema.type !== 'object') return [`export type ${name} = ${typeFor(schema)};`];
  const required = new Set(schema.required || []);
  const lines = [`export interface ${name} {`];
  if (schema.additionalProperties === true) lines.push('  [key: string]: unknown;');
  for (const [propertyName, propertySchema] of Object.entries(schema.properties || {})) {
    lines.push(`  ${propertyName}${required.has(propertyName) ? '' : '?'}: ${typeFor(propertySchema)};`);
  }
  lines.push('}');
  return lines;
}

function typeFor(schema) {
  if (!schema) return 'unknown';
  if (schema.$ref) return schema.$ref.split('/').at(-1);
  if (Array.isArray(schema.type)) {
    return schema.type.map((type) => type === 'null' ? 'null' : typeFor({ ...schema, type })).join(' | ');
  }
  if (schema.enum) return schema.enum.map((value) => JSON.stringify(value)).join(' | ');
  if (schema.type === 'array') return `${typeFor(schema.items)}[]`;
  if (schema.type === 'integer' || schema.type === 'number') return 'number';
  if (schema.type === 'boolean') return 'boolean';
  if (schema.type === 'string') return 'string';
  if (schema.type === 'object') return 'Record<string, unknown>';
  return 'unknown';
}

function requestSchema(operation) {
  const content = operation.requestBody?.content;
  if (!content) return null;
  const schema = content['application/json']?.schema;
  if (!schema) throw new Error(`${operation.operationId} requestBody must expose application/json.`);
  return schema;
}

function successResponse(operation) {
  const entry = Object.entries(operation.responses || {})
    .filter(([status]) => /^2\d\d$/.test(status))
    .sort(([left], [right]) => left.localeCompare(right))[0];
  if (!entry) throw new Error(`${operation.operationId} must define a concrete 2xx response.`);
  return {
    status: entry[0],
    schema: entry[1].content?.['application/json']?.schema || null,
  };
}

function validateJavaRecordSchema(source, recordName, schema) {
  if (!schema || schema.type !== 'object') {
    throw new Error(`${recordName} must be represented by an OpenAPI object schema.`);
  }
  const match = new RegExp(`public\\s+record\\s+${recordName}\\s*\\(([\\s\\S]*?)\\)\\s*\\{`).exec(source);
  if (!match) throw new Error(`Could not locate Java record ${recordName}.`);
  const javaFields = splitJavaComponents(match[1]).map((component) => {
    const field = /^(.*?)\s+([A-Za-z_$][\w$]*)$/.exec(component.trim());
    if (!field) throw new Error(`Could not parse ${recordName} component: ${component.trim()}`);
    return { type: field[1].trim(), name: field[2] };
  });
  const schemaProperties = schema.properties || {};
  const javaNames = javaFields.map((field) => field.name);
  const schemaNames = Object.keys(schemaProperties);
  if (JSON.stringify(javaNames) !== JSON.stringify(schemaNames)) {
    throw new Error(`${recordName} OpenAPI properties drifted from its Java record components.`);
  }
  const required = new Set(schema.required || []);
  for (const field of javaFields) {
    if (!required.has(field.name)) {
      throw new Error(`${recordName}.${field.name} must remain a serialized response/request property.`);
    }
    validateJavaFieldType(recordName, field, schemaProperties[field.name]);
  }
}

function splitJavaComponents(body) {
  const components = [];
  let start = 0;
  let genericDepth = 0;
  for (let index = 0; index < body.length; index += 1) {
    if (body[index] === '<') genericDepth += 1;
    else if (body[index] === '>') genericDepth -= 1;
    else if (body[index] === ',' && genericDepth === 0) {
      components.push(body.slice(start, index));
      start = index + 1;
    }
  }
  components.push(body.slice(start));
  return components.filter((component) => component.trim());
}

function validateJavaFieldType(recordName, field, schema) {
  const types = Array.isArray(schema?.type) ? schema.type : [schema?.type];
  if (field.type === 'String' && types.includes('string')) return;
  if (field.type === 'Long' && types.includes('integer')) return;
  const list = /^List<(String|Long)>$/.exec(field.type);
  if (list && schema?.type === 'array') {
    const expectedItem = list[1] === 'Long' ? 'integer' : 'string';
    const itemTypes = Array.isArray(schema.items?.type) ? schema.items.type : [schema.items?.type];
    if (itemTypes.includes(expectedItem)) return;
  }
  throw new Error(`${recordName}.${field.name} OpenAPI type drifted from Java ${field.type}.`);
}

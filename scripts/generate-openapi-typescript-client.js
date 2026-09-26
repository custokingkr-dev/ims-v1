'use strict';

// Deliberately dependency-free. Unsupported OpenAPI features fail closed instead
// of silently producing a client with an incorrect wire contract.
const fs = require('node:fs');
const path = require('node:path');
const repositoryRoot = path.resolve(__dirname, '..');
const methods = ['get', 'post', 'put', 'patch', 'delete'];
const identifier = /^[A-Za-z_$][\w$]*$/;

function operations(spec) {
  return Object.entries(spec.paths || {}).flatMap(([url, item]) => methods
    .filter((method) => item[method]).map((method) => ({ url, method, ...item[method],
      parameters: [...(item.parameters || []), ...(item[method].parameters || [])] })));
}

function validateContract(spec, inventory) {
  if (!String(spec.openapi || '').startsWith('3.1.')) throw new Error('Contract must use OpenAPI 3.1.');
  if (!spec['x-ims-service'] || !spec['x-ims-controller']) throw new Error('Contract must name its owning service and controller.');
  if (spec['x-ims-audience'] !== 'browser' || spec.servers?.length !== 1 || spec.servers[0]?.url !== '/') {
    throw new Error('Browser contract must be origin-relative and explicitly browser-scoped.');
  }
  if (spec['x-ims-client-base-path'] !== '/api/v1') throw new Error('Browser client must retain the gateway /api/v1 base path.');
  if (!identifier.test(spec['x-ims-client-name'] || '')) throw new Error('Invalid TypeScript client name.');
  const ids = new Set();
  for (const op of operations(spec)) {
    if (!op.url.startsWith('/api/v1/') || /\/internal\/|\/introspect(?:\/|$)/.test(op.url)) throw new Error(`Non-browser path: ${op.url}`);
    if (!inventory.endpoints.some((endpoint) => endpoint.service === spec['x-ims-service']
      && endpoint.controller === (op['x-ims-controller'] || spec['x-ims-controller'])
      && endpoint.classification === 'canonical' && endpoint.method === op.method.toUpperCase() && endpoint.path === op.url)) {
      throw new Error(`OpenAPI operation is absent from the generated canonical controller inventory: ${op.method.toUpperCase()} ${op.url}`);
    }
    const sample = op.url.replace(/\{[^}]+\}/g, 'sample');
    const gateway = inventory.gatewayRoutes?.find((route) => route.kind === 'public'
      && (route.methods.includes('ANY') || route.methods.includes(op.method.toUpperCase()))
      && (route.matcherType === 'regex' ? new RegExp(route.matcher).test(sample) : sample.startsWith(route.matcher)));
    if (!gateway || gateway.service !== spec['x-ims-gateway-service']) {
      throw new Error(`Browser operation is not routed to its declared gateway owner: ${op.method.toUpperCase()} ${op.url}`);
    }
    if (!identifier.test(op.operationId || '') || ids.has(op.operationId)) throw new Error(`Invalid or duplicate operationId: ${op.operationId}`);
    ids.add(op.operationId);
    const variables = [...op.url.matchAll(/\{([^}]+)\}/g)].map((match) => match[1]);
    const names = new Set();
    for (const parameter of op.parameters) {
      if (!identifier.test(parameter.name) || names.has(parameter.name) || !['path', 'query'].includes(parameter.in)) throw new Error(`Unsupported or duplicate parameter in ${op.operationId}`);
      names.add(parameter.name);
      if (parameter.in === 'path' && (!variables.includes(parameter.name) || !parameter.required)) throw new Error(`Path parameters must be present and required in ${op.operationId}`);
      typeFor(parameter.schema);
    }
    if (variables.some((name) => !op.parameters.some((p) => p.in === 'path' && p.name === name))) throw new Error(`Missing path parameter in ${op.operationId}`);
    requestBody(op); successResponse(op);
  }
  if (!ids.size) throw new Error('Contract contains no client operations.');
  const schemas = spec.components?.schemas || {};
  function visit(value) {
    if (!value || typeof value !== 'object') return;
    if (value.$ref && (!value.$ref.startsWith('#/components/schemas/') || !schemas[value.$ref.split('/').at(-1)])) {
      // Response references are allowed and validated separately.
      if (!value.$ref.startsWith('#/components/responses/') || !spec.components?.responses?.[value.$ref.split('/').at(-1)]) throw new Error(`Unresolved reference: ${value.$ref}`);
    }
    Object.values(value).forEach(visit);
  }
  visit(spec);
  for (const [name, schema] of Object.entries(schemas)) {
    if (!identifier.test(name)) throw new Error(`Invalid schema name: ${name}`);
    typeFor(schema);
    if (schema['x-ims-java-record']) validateJavaRecordSchema(schema, name);
  }
}

function requestBody(op) {
  const content = op.requestBody?.content;
  if (!content) return null;
  const entries = Object.entries(content);
  if (entries.length !== 1 || !['application/json', 'multipart/form-data'].includes(entries[0][0])) throw new Error(`Unsupported request content in ${op.operationId}`);
  if (!op.requestBody.required) throw new Error(`Optional request body requires an explicit schema/default in ${op.operationId}`);
  return { mediaType: entries[0][0], schema: entries[0][1].schema };
}

function successResponse(op) {
  const successes = Object.entries(op.responses || {}).filter(([status]) => /^2\d\d$/.test(status));
  if (successes.length !== 1) throw new Error(`${op.operationId} must define one concrete success response.`);
  const content = successes[0][1].content;
  if (!content) return { type: 'void', binary: false };
  const entries = Object.entries(content);
  if (entries.length !== 1) throw new Error(`Ambiguous response content in ${op.operationId}`);
  const [media, value] = entries[0];
  if (media === 'application/json') return { type: typeFor(value.schema), binary: false };
  if (value.schema?.type === 'string' && value.schema.format === 'binary') return { type: 'Blob', binary: true };
  throw new Error(`Unsupported response content in ${op.operationId}`);
}

function typeFor(schema) {
  if (!schema || !Object.keys(schema).length) return 'unknown';
  if (schema.$ref) return schema.$ref.split('/').at(-1);
  if (schema.oneOf || schema.anyOf) return `(${(schema.oneOf || schema.anyOf).map(typeFor).join(' | ')})`;
  if (schema.allOf) return `(${schema.allOf.map(typeFor).join(' & ')})`;
  if (Array.isArray(schema.type)) return schema.type.map((type) => typeFor({ ...schema, type })).join(' | ');
  if (schema.type === 'null') return 'null';
  if (schema.enum) return schema.enum.map(JSON.stringify).join(' | ');
  if (schema.type === 'array') return `Array<${typeFor(schema.items)}>`;
  if (schema.type === 'integer' || schema.type === 'number') return 'number';
  if (schema.type === 'boolean') return 'boolean';
  if (schema.type === 'string') return schema.format === 'binary' ? 'Blob' : 'string';
  if (schema.type === 'object') {
    const required = new Set(schema.required || []);
    const fields = Object.entries(schema.properties || {}).map(([key, value]) => `${JSON.stringify(key)}${required.has(key) ? '' : '?'}: ${typeFor(value)};`);
    if (schema.additionalProperties) fields.push(`[key: string]: ${schema.additionalProperties === true ? 'unknown' : typeFor(schema.additionalProperties)};`);
    return fields.length ? `{ ${fields.join(' ')} }` : 'Record<string, unknown>';
  }
  throw new Error(`Unsupported schema type: ${JSON.stringify(schema)}`);
}

function renderClient(spec, sourceName) {
  const ops = operations(spec);
  const lines = ['/* eslint-disable */', '// Generated by scripts/generate-openapi-typescript-client.js. Do not edit by hand.',
    `// Source: contracts/openapi/${sourceName}`, '', "import type { AxiosInstance } from 'axios';", ''];
  for (const [name, schema] of Object.entries(spec.components?.schemas || {})) lines.push(`export type ${name} = ${typeFor(schema)};`, '');
  function signature(op, implementation = false) {
    const required = op.parameters.some((p) => p.required);
    const params = op.parameters.length ? [`parameters${!required && !implementation && !requestBody(op) ? '?' : ''}: { ${op.parameters.map((p) => `${p.name}${p.required ? '' : '?'}: ${typeFor(p.schema)};`).join(' ')} }${implementation && !required && !requestBody(op) ? ' = {}' : ''}`] : [];
    const request = requestBody(op);
    if (request) params.push(`request: ${typeFor(request.schema)}`);
    return params.join(', ');
  }
  const clientName = spec['x-ims-client-name'];
  lines.push(`export interface ${clientName} {`);
  for (const op of ops) lines.push(`  ${op.operationId}(${signature(op)}): Promise<${successResponse(op).type}>;`);
  lines.push('}', '', `export function create${clientName}(http: AxiosInstance): ${clientName} {`, '  return {');
  for (const op of ops) {
    const body = requestBody(op);
    const response = successResponse(op);
    let url = op.url.slice('/api/v1'.length);
    const hasPath = op.parameters.some((p) => p.in === 'path');
    url = hasPath ? '`' + url.replace(/\{([^}]+)\}/g, (_, name) => '${encodeURIComponent(String(parameters.' + name + '))}') + '`' : JSON.stringify(url);
    lines.push(`    async ${op.operationId}(${signature(op, true)}) {`);
    if (body?.mediaType === 'multipart/form-data') {
      const schema = body.schema.$ref ? spec.components.schemas[body.schema.$ref.split('/').at(-1)] : body.schema;
      lines.push('      const form = new FormData();');
      for (const [name, field] of Object.entries(schema.properties || {})) {
        if (field.type !== 'string' || field.format !== 'binary') throw new Error('Multipart generation supports explicit binary fields only.');
        lines.push(`      ${schema.required?.includes(name) ? '' : `if (request.${name} !== undefined) `}form.append(${JSON.stringify(name)}, request.${name});`);
      }
    }
    const options = [];
    const query = op.parameters.filter((p) => p.in === 'query');
    if (query.length) options.push(`params: { ${query.map((p) => `${p.name}: parameters.${p.name}`).join(', ')} }`);
    if (response.binary) options.push("responseType: 'blob'");
    const args = [url];
    if (['post', 'put', 'patch'].includes(op.method) && (body || options.length)) args.push(body ? (body.mediaType === 'multipart/form-data' ? 'form' : 'request') : 'undefined');
    if (options.length) args.push(`{ ${options.join(', ')} }`);
    const call = `http.${op.method}${response.type === 'void' ? '' : `<${response.type}>`}(${args.join(', ')})`;
    if (response.type === 'void') lines.push(`      await ${call};`);
    else lines.push(`      const response = await ${call};`, '      return response.data;');
    lines.push('    },');
  }
  lines.push('  };', '}', '');
  return lines.join('\n');
}

function validateJavaRecordSchema(schema, schemaName) {
  const binding = schema['x-ims-java-record'];
  const source = fs.readFileSync(path.join(repositoryRoot, binding.source), 'utf8');
  const match = new RegExp(`public\\s+record\\s+${binding.name}\\s*\\(([\\s\\S]*?)\\)\\s*\\{`).exec(source);
  if (!match) throw new Error(`Could not locate Java record ${binding.name}.`);
  const stripped = match[1].replace(/\/\*[\s\S]*?\*\/|\/\/[^\r\n]*/g, '').replace(/@[\w.]+(?:\([^)]*\))?\s*/g, '');
  const fields = stripped.split(/,(?![^<]*>)/).map((value) => /^(.*?)\s+([\w$]+)$/.exec(value.trim())).filter(Boolean);
  if (JSON.stringify(fields.map((field) => field[2])) !== JSON.stringify(Object.keys(schema.properties || {}))) throw new Error(`${schemaName} OpenAPI properties drifted from its Java record components.`);
  if (binding.requireAll && fields.some((field) => !schema.required?.includes(field[2]))) throw new Error(`${schemaName} serialized properties must remain required.`);
  for (const [, javaType, name] of fields) {
    const actual = typeFor(schema.properties[name]);
    const expected = javaType === 'String' ? 'string' : /^(Long|Integer|int|long|Double|double|BigDecimal|java.math.BigDecimal)$/.test(javaType) ? 'number' : /^(Boolean|boolean)$/.test(javaType) ? 'boolean' : /^List<String>$/.test(javaType) ? 'Array<string>' : /^List<Long>$/.test(javaType) ? 'Array<number>' : null;
    if (expected && !actual.split(' | ').includes(expected) && !schema.properties[name].enum) throw new Error(`${schemaName}.${name} type drifted from Java ${javaType}.`);
  }
}

function main() {
  const directory = path.join(repositoryRoot, 'contracts', 'openapi');
  const inventory = JSON.parse(fs.readFileSync(path.join(repositoryRoot, 'services/api-gateway/api-route-inventory.json'), 'utf8'));
  const check = process.argv.includes('--check');
  let count = 0;
  const outputs = new Set();
  for (const filename of fs.readdirSync(directory).filter((name) => name.endsWith('.openapi.json')).sort()) {
    const spec = JSON.parse(fs.readFileSync(path.join(directory, filename), 'utf8'));
    validateContract(spec, inventory);
    const basename = spec['x-ims-client-file'] || 'identityAuthApi.ts';
    if (!/^[a-zA-Z][\w]*\.ts$/.test(basename) || outputs.has(basename)) throw new Error(`Invalid or duplicate generated filename: ${basename}`);
    outputs.add(basename);
    const output = path.join(repositoryRoot, 'frontend/src/generated', basename);
    const rendered = renderClient(spec, filename);
    if (check) {
      if (!fs.existsSync(output) || fs.readFileSync(output, 'utf8') !== rendered) throw new Error(`Generated client ${basename} is stale. Run: node scripts/generate-openapi-typescript-client.js`);
    } else { fs.mkdirSync(path.dirname(output), { recursive: true }); fs.writeFileSync(output, rendered); }
    count += operations(spec).length;
  }
  console.log(`OpenAPI clients ${check ? 'are current' : 'generated'}: ${outputs.size} domains, ${count} canonical operations.`);
}

module.exports = { validateContract, renderClient, typeFor, operations };
if (require.main === module) main();

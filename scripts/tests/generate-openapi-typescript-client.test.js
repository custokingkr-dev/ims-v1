'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { validateContract, renderClient, typeFor, operations } = require('../generate-openapi-typescript-client');
const root = path.resolve(__dirname, '../..');
const inventory = require('../../services/api-gateway/api-route-inventory.json');
const read = (name) => JSON.parse(fs.readFileSync(path.join(root, 'contracts/openapi', name), 'utf8'));

test('all domain contracts match canonical inventory and their Java record bindings', () => {
  let count = 0;
  for (const name of fs.readdirSync(path.join(root, 'contracts/openapi')).filter((file) => file.endsWith('.openapi.json'))) {
    const spec = read(name);
    validateContract(spec, inventory);
    count += operations(spec).length;
    assert.equal(fs.readFileSync(path.join(root, 'frontend/src/generated', spec['x-ims-client-file']), 'utf8'), renderClient(spec, name));
  }
  assert.ok(count >= 42, `Expected critical workflow coverage, found ${count}`);
});

test('controller aliases and internal authentication operations cannot enter browser clients', () => {
  const spec = read('feeClient.v1.openapi.json');
  spec.paths['/api/v1/workspace/fees/record-payment'] = spec.paths['/api/v1/fees/payments'];
  delete spec.paths['/api/v1/fees/payments'];
  assert.throws(() => validateContract(spec, inventory), /canonical controller inventory/);
  spec.paths['/api/v1/auth/introspect'] = spec.paths['/api/v1/workspace/fees/record-payment'];
  delete spec.paths['/api/v1/workspace/fees/record-payment'];
  assert.throws(() => validateContract(spec, inventory), /Non-browser path/);
});

test('path parameters must be declared and generated paths encode untrusted identifiers', () => {
  const spec = read('quotationDocumentClient.v1.openapi.json');
  const generated = renderClient(spec, 'quotationDocumentClient.v1.openapi.json');
  assert.match(generated, /encodeURIComponent\(String\(parameters.code\)\)/);
  assert.match(generated, /encodeURIComponent\(String\(parameters.quotationId\)\)/);
  spec.paths['/api/v1/ff/requests/{code}/quotations/{quotationId}/document'].get.parameters = [];
  assert.throws(() => validateContract(spec, inventory), /Missing path parameter/);
});

test('unknown schemas, duplicate IDs, external references and unsupported media fail closed', () => {
  assert.throws(() => typeFor({ type: 'undefined' }), /Unsupported schema/);
  let spec = read('studentClient.v1.openapi.json');
  spec.paths['/api/v1/students/{id}/photo'].post.operationId = 'createStudent';
  assert.throws(() => validateContract(spec, inventory), /duplicate operationId/);
  spec = read('studentClient.v1.openapi.json');
  spec.components.schemas.StudentDetail.properties.id = { $ref: 'https://invalid.test/schema' };
  assert.throws(() => validateContract(spec, inventory), /Unresolved reference/);
  spec = read('studentClient.v1.openapi.json');
  spec.paths['/api/v1/students'].post.requestBody.content = { 'application/xml': { schema: {} } };
  assert.throws(() => validateContract(spec, inventory), /Unsupported request content/);
});

test('Java DTO additions or removals make the contract gate fail', () => {
  const spec = read('feeClient.v1.openapi.json');
  delete spec.components.schemas.RecordPaymentRequest.properties.schoolId;
  assert.throws(() => validateContract(spec, inventory), /properties drifted/);
});

test('missing gateway ownership and response-required drift fail the gate', () => {
  const spec = read('identity-auth.v1.openapi.json');
  spec.components.schemas.AuthResponse.required = ['accessToken'];
  assert.throws(() => validateContract(spec, inventory), /serialized properties must remain required/);
  const fee = read('feeClient.v1.openapi.json');
  assert.throws(() => validateContract(fee, { ...inventory, gatewayRoutes: [] }), /not routed to its declared gateway owner/);
});

test('multipart clients let the browser supply the boundary and downloads request Blob', () => {
  const result = renderClient(read('quotationDocumentClient.v1.openapi.json'), 'quotationDocumentClient.v1.openapi.json');
  assert.match(result, /new FormData\(\)/);
  assert.match(result, /form.append\("file", request.file\)/);
  assert.doesNotMatch(result, /Content-Type|Service-Token/);
  assert.match(result, /responseType: 'blob'/);
  assert.match(result, /removeDocument.*Promise<void>/);
});

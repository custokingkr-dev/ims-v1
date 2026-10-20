'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');
const root = path.resolve(__dirname, '../..');
const read = (file) => JSON.parse(fs.readFileSync(path.join(root, file), 'utf8'));
const { validateContract } = require('../generate-openapi-typescript-client');
const inventory = read('services/api-gateway/api-route-inventory.json');

test('workflow canonical decision contract requires the reviewed version and matches Java response', () => {
  const contract = read('contracts/openapi/workflowClient.v1.openapi.json');
  validateContract(contract, inventory);
  assert.deepEqual(contract.components.schemas.WorkflowDecisionRequest.required, ['expectedVersion']);
  assert.equal(contract.components.schemas.WorkflowDecisionRequest.properties.expectedVersion.minimum, 0);
  assert.equal(contract.components.schemas.WorkflowDecisionRequest.properties.notes.maxLength, 1000);
  assert.equal(Object.keys(contract.paths).length, 3);
});

test('billing payment compatibility binding remains exact without broadening canonical generation', () => {
  const contract = read('contracts/openapi/billingClient.v1.openapi.json');
  validateContract(contract, inventory);
  const binding = contract['x-ims-compatibility-request-bindings'][0];
  assert.equal(binding.path, '/api/v1/billing-payments');
  assert.equal(inventory.endpoints.filter((entry) => entry.service === 'billing-service' && entry.controller === binding.controller && entry.method === binding.method && entry.path === binding.path && entry.classification === 'compatibility').length, 1);
  assert.equal(contract.paths[binding.path], undefined);
  const schema = contract.components.schemas.CreateBillingPaymentRequest;
  assert.deepEqual(schema.required, ['invoiceId','amount','paymentMode','idempotencyKey']);
  assert.equal(schema.properties.idempotencyKey.minLength, 8);
  assert.equal(schema.properties.amount.type, 'integer');
  assert.equal(schema.properties.receivedBy, undefined);
  assert.equal(schema.properties.branchId, undefined);
});

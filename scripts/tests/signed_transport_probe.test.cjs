const test = require('node:test');
const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const path = require('node:path');
const source = path.resolve(__dirname, '../security/dev-signed-transport-probe.js');

function run(mode) {
  const result = spawnSync(process.execPath, ['-e', `
    const assert = require('node:assert/strict');
    const services = ['identity-service','school-core-service','operations-service','platform-service','billing-service'];
    const mode = ${JSON.stringify(mode)};
    process.env.PROBE_TARGETS = JSON.stringify(services.map(service => ({service, url:'https://custoking-'+service+'-dev-controlled-em.a.run.app'})));
    if (mode === 'invalid-target') process.env.PROBE_TARGETS = process.env.PROBE_TARGETS.replace('controlled-em.a.run.app','attacker.example');
    global.fetch = async (url, options) => {
      if (mode === 'invalid-target') throw Error('No request permitted for invalid targets');
      assert.equal(options.redirect, 'error');
      if (url.startsWith('http://metadata.google.internal/')) {
        assert.equal(options.headers['Metadata-Flavor'], 'Google');
        return new Response('controlled.signed.token');
      }
      assert.equal(options.method, 'GET');
      assert.equal(options.headers.Authorization, options.headers['X-Serverless-Authorization']);
      assert.equal(options.headers['X-IMS-Principal-Carrier-Token'], undefined);
      if (mode === 'timeout' && url.includes('identity-service') && !options.headers['X-Authenticated-User-Id']) {
        throw new DOMException('Controlled timeout', 'TimeoutError');
      }
      return new Response('', {status:options.headers['X-Authenticated-User-Id'] || url.endsWith('/api/v1/internal/outbox/relay') ? 403 : 200});
    };
    require(${JSON.stringify(source)});
  `], { encoding: 'utf8', timeout: 5000 });
  assert.ifError(result.error);
  return { status: result.status, rows: result.stdout.trim().split('\n').filter(Boolean).map(JSON.parse) };
}

test('five signed controls and ten negative cases require the expected status', () => {
  const {status, rows} = run('pass');
  assert.equal(status, 0);
  const cases = rows.filter(row => row.case);
  assert.equal(cases.length, 15);
  assert.equal(new Set(cases.map(row => `${row.service}:${row.case}`)).size, 15);
  assert.ok(cases.every(row => row.passed));
  assert.deepEqual(rows.at(-1), {probeComplete:true, passed:true});
});

test('a transport timeout fails the probe and cannot establish application denial', () => {
  const {status, rows} = run('timeout');
  assert.equal(status, 1);
  const identity = rows.filter(row => row.service === 'identity-service');
  assert.equal(identity[0].error, 'REQUEST_TIMEOUT');
  assert.equal(identity[1].error, 'TRANSPORT_CONTROL_FAILED');
  assert.ok(!identity.some(row => row.case === 'forged_principal_no_carrier'));
  assert.deepEqual(rows.at(-1), {probeComplete:true, passed:false});
});

test('invalid host is rejected before any request', () => {
  const {status, rows} = run('invalid-target');
  assert.equal(status, 1);
  assert.deepEqual(rows, [{probeComplete:true, passed:false, error:'PROBE_CONFIGURATION_FAILED'}]);
});

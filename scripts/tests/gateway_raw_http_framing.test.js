'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const net = require('node:net');
process.env.OTEL_SDK_DISABLED = 'true';
process.env.GATEWAY_AUTH_MODE = 'enforce';
process.env.GATEWAY_CLOUD_RUN_AUTH = 'never';
for (const prefix of ['IDENTITY','TENANT_SCHOOL','STUDENT','ATTENDANCE','FEE','CATALOG','WORKFLOW','FIREFIGHTING','REPORTING','BILLING','AUDIT','NOTIFICATION']) {
  process.env[prefix + '_SERVICE_TOKEN'] = 'controlled-loopback-fixture-only';
  process.env[prefix + '_UPSTREAM'] = 'http://127.0.0.1:9';
}
process.env.FRONTEND_UPSTREAM = 'http://127.0.0.1:9';
const { server } = require('../../services/api-gateway/server');
test('actual loopback gateway rejects ambiguous HTTP/1.1 framing before dispatch', async () => {
  let dispatches = 0; server.on('request', () => { dispatches++; });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  const request = (wire) => new Promise((resolve, reject) => {
    const socket = net.createConnection({ host: '127.0.0.1', port }); let response = '';
    socket.setTimeout(1500, () => { socket.destroy(); reject(new Error('Loopback framing deadline exceeded')); });
    socket.on('connect', () => socket.write(wire)); socket.on('data', (chunk) => { response += chunk; });
    socket.on('error', reject); socket.on('close', () => resolve(response));
  });
  try {
    for (const headers of ['Content-Length: 0\r\nContent-Length: 0\r\n', 'Content-Length: 0\r\nContent-Length: 9\r\n', 'Content-Length: 0\r\nTransfer-Encoding: chunked\r\n', 'Transfer-Encoding: chunked\r\nContent-Length: 0\r\n']) {
      const response = await request('POST /__local_framing_fixture HTTP/1.1\r\nHost: localhost\r\n' + headers + '\r\n0\r\n\r\nGET /__local_smuggled_fixture HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n');
      assert.match(response, /^HTTP\/1\.1 400 /); assert.equal(dispatches, 0);
    }
    console.log('IMS_LOCAL_HTTP_FRAMING_EVIDENCE|' + JSON.stringify({ listener: '127.0.0.1', remoteTraffic: false, cases: 4, rejected: 4, applicationDispatches: dispatches, scope: 'Node HTTP/1.1 parser only; excludes Cloud Run edge, HTTP/2 translation and Java upstream parsers' }));
  } finally { server.closeAllConnections(); await new Promise((resolve) => server.close(resolve)); }
});

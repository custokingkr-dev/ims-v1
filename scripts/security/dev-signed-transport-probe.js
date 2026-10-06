'use strict';
const targets = JSON.parse(process.env.PROBE_TARGETS || '[]');
const end = Date.now() + 110000;
const audienceHost = /^https:\/\/custoking-(identity-service|school-core-service|operations-service|platform-service|billing-service)-dev-[a-z0-9]+-em\.a\.run\.app$/;
function signal() { return AbortSignal.timeout(Math.max(1, Math.min(10000, end - Date.now()))); }
async function metadataToken(audience) {
  const response = await fetch('http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/identity?audience=' + encodeURIComponent(audience) + '&format=full', {headers: {'Metadata-Flavor': 'Google'}, redirect: 'error', signal: signal()});
  if (!response.ok) throw Error('METADATA_FAILED');
  const reader = response.body.getReader();
  let size = 0; const chunks = [];
  try { while (true) { const {value,done} = await reader.read(); if (done) break; size += value.byteLength; if (size > 2097152) throw Error('METADATA_TOO_LARGE'); chunks.push(Buffer.from(value)); } }
  finally { await reader.cancel().catch(() => {}); }
  const token = Buffer.concat(chunks).toString('utf8').trim();
  if (!/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(token)) throw Error('METADATA_INVALID');
  return token;
}
async function probe(target, name, path, token, forged, expected) {
  if (Date.now() >= end) throw Error('PROBE_DEADLINE');
  const headers = {'Authorization': 'Bearer ' + token, 'X-Serverless-Authorization': 'Bearer ' + token};
  if (forged) Object.assign(headers, {'X-Authenticated-User-Id': '999999999', 'X-Authenticated-Role': 'SUPERADMIN', 'X-Authenticated-School-Id': '999999999', 'X-Authenticated-Branch-Id': '999999999', 'X-Authenticated-Permissions': '*'});
  try {
    // GET only: internal route authorization is checked before method dispatch.
    const response = await fetch(target.url + path, {method:'GET', headers, redirect:'error', signal:signal()});
    const status = response.status;
    await response.body?.cancel().catch(() => {});
    const passed = expected.includes(status);
    console.log(JSON.stringify({service:target.service, case:name, status, passed}));
    return passed;
  } catch (_) { console.log(JSON.stringify({service:target.service, case:name, passed:false, error:'REQUEST_FAILED'})); return false; }
}
(async () => {
  const expected = ['identity-service','school-core-service','operations-service','platform-service','billing-service'];
  if (targets.length !== 5 || new Set(targets.map(t=>t.service)).size !== 5 || !targets.every(t => expected.includes(t.service) && audienceHost.test(t.url) && new URL(t.url).hostname.startsWith('custoking-' + t.service + '-dev-'))) throw Error('TARGETS_REJECTED');
  let passed = true;
  for (const target of targets) {
    try {
      const token = await metadataToken(target.url);
      // A successful transport control is necessary to distinguish Cloud IAM denial from app denial.
      const transport = await probe(target,'signed_health_control','/actuator/health',token,false,[200]);
      passed = transport && passed;
      if (!transport) { console.log(JSON.stringify({service:target.service, case:'application_negatives', passed:false, error:'TRANSPORT_CONTROL_FAILED'})); continue; }
      passed = await probe(target,'forged_principal_no_carrier','/actuator/health',token,true,[403]) && passed;
      // Known machine filter route, wrong gateway caller; GET cannot execute its POST relay.
      passed = await probe(target,'gateway_not_machine_relay_caller','/api/v1/internal/outbox/relay',token,false,[403]) && passed;
    } catch (_) { console.log(JSON.stringify({service:target.service, passed:false, error:'PROBE_SETUP_FAILED'})); passed=false; }
  }
  console.log(JSON.stringify({probeComplete:true, passed}));
  process.exitCode = passed ? 0 : 1;
})().catch(() => { console.log(JSON.stringify({probeComplete:true, passed:false, error:'PROBE_CONFIGURATION_FAILED'})); process.exitCode=1; });

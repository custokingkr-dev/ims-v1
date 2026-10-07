// One owned synthetic dev student, normal application authorization, no provider delivery.
import { chromium } from '../../frontend/node_modules/playwright/index.mjs';
import { readFileSync, writeFileSync, realpathSync } from 'node:fs';
import { basename, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
if (!process.argv.includes('--apply-dev')) throw new Error('EXPLICIT_DEV_APPLY_REQUIRED');
const argumentIndex=process.argv.indexOf('--private-file');
if(argumentIndex<0 || !process.argv[argumentIndex+1])throw new Error('OWNED_PRIVATE_FILE_REQUIRED');
const workspace=fileURLToPath(new URL('../../',import.meta.url));
const temporary=realpathSync(resolve(workspace,'tmp'));
const privateFile=realpathSync(resolve(workspace,process.argv[argumentIndex+1]));
if(dirname(privateFile)!==temporary || !/^dev-journal-[a-f0-9]{12}-private\.json$/.test(basename(privateFile)))
  throw new Error('OWNED_TEMPORARY_SCOPE_REQUIRED');
const config=JSON.parse(readFileSync(privateFile,'utf8'));
const origin='https://custoking-frontend-dev-hd4wfwk7mq-em.a.run.app';
if(config.project!=='custoking-dev' || config.schoolId!==990008101 || config.userId!==990008201 || config.studentId!==990008301
   || !/^SEC-JOURNAL-DEV-20261007-[a-f0-9]{12}$/.test(config.marker)
   || config.admissionNumber!==config.marker+'-STUDENT'
   || !/^[a-f0-9]{40}$/.test(config.expectedSourceSha) || config.guardedReady!==true
   || basename(privateFile)!=='dev-journal-'+config.marker.slice(-12)+'-private.json')
  throw new Error('EXACT_OWNED_READY_FIXTURE_REQUIRED');
const proof={schemaVersion:1,project:config.project,marker:config.marker,studentId:config.studentId,
 expectedSourceSha:config.expectedSourceSha,startedAtUtc:new Date().toISOString(),
 authenticator:'Chromium software CTAP2',physicalHardwareProof:false,providerCalls:0,checks:[],passed:false};
const output=new URL('../../tmp/dev-journal-browser-proof.json',import.meta.url);
const save=()=>writeFileSync(output,JSON.stringify(proof,null,2)+'\n');
const browser=await chromium.launch({headless:true});
try {
 const context=await browser.newContext();const page=await context.newPage();
 const cdp=await context.newCDPSession(page);await cdp.send('WebAuthn.enable');
 await cdp.send('WebAuthn.addVirtualAuthenticator',{options:{protocol:'ctap2',transport:'internal',hasResidentKey:true,
   hasUserVerification:true,isUserVerified:true,automaticPresenceSimulation:true}});
 await page.goto(origin,{waitUntil:'domcontentloaded',timeout:30000});
 const result=await page.evaluate(async(config)=>{
  const checks=[];let token;
  const api=async(method,path,body,extra={})=>{
   const control=new AbortController();const timeout=setTimeout(()=>control.abort(),30000);
   try {
    const response=await fetch(path,{method,redirect:'error',headers:{'Content-Type':'application/json',
       ...(token?{Authorization:'Bearer '+token}:{}),...extra},body:body===undefined?undefined:JSON.stringify(body),signal:control.signal});
    const reader=response.body.getReader();let size=0;const chunks=[];
    while(true){const {value,done}=await reader.read();if(done)break;size+=value.length;
      if(size>131072){await reader.cancel();throw new Error('RESPONSE_CEILING');}chunks.push(value);}
    const bytes=new Uint8Array(size);let offset=0;for(const chunk of chunks){bytes.set(chunk,offset);offset+=chunk.length;}
    return {status:response.status,data:size?JSON.parse(new TextDecoder().decode(bytes)):null};
   } finally {clearTimeout(timeout);}
  };
  const expect=(name,r,status)=>{checks.push({name,status:r.status,expected:status,passed:r.status===status});
    if(r.status!==status)throw new Error(name+':'+r.status);};
  try {
  let r=await api('POST','/api/v1/auth/login',{email:config.email,password:config.password});expect('normal-login',r,200);
  if(r.data.role!=='SCHOOL_ADMIN'||r.data.branchId!==config.schoolId||r.data.userId!==config.userId)throw new Error('PRINCIPAL_MISMATCH');
  token=r.data.accessToken??r.data.token;if(!token)throw new Error('MISSING_ACCESS_TOKEN');
  r=await api('GET','/api/v1/students/'+config.studentId);expect('owned-student-preflight',r,200);
  if(r.data.id!==config.studentId||r.data.schoolId!==config.schoolId
     ||(r.data.admissionNumber??r.data.admissionNo)!==config.admissionNumber)throw new Error('STUDENT_MARKER_MISMATCH');
  const decode=v=>Uint8Array.from(atob(v.replace(/-/g,'+').replace(/_/g,'/')),x=>x.charCodeAt(0)).buffer;
  const encode=v=>btoa(String.fromCharCode(...new Uint8Array(v))).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');
  const serialize=c=>({id:c.id,rawId:encode(c.rawId),type:c.type,clientExtensionResults:c.getClientExtensionResults(),
   authenticatorAttachment:c.authenticatorAttachment,response:'attestationObject' in c.response
    ?{clientDataJSON:encode(c.response.clientDataJSON),attestationObject:encode(c.response.attestationObject),transports:c.response.getTransports()}
    :{clientDataJSON:encode(c.response.clientDataJSON),authenticatorData:encode(c.response.authenticatorData),signature:encode(c.response.signature),
      userHandle:c.response.userHandle?encode(c.response.userHandle):null}});
  r=await api('GET','/api/v1/auth/passkeys');expect('actual-rp-status',r,200);
  if(r.data.rpId!==location.hostname)throw new Error('RP_MISMATCH');
  r=await api('POST','/api/v1/auth/passkeys/registration/options',{password:config.password});expect('registration-options',r,200);
  const registration=r.data;registration.publicKey.challenge=decode(registration.publicKey.challenge);
  registration.publicKey.user.id=decode(registration.publicKey.user.id);
  if(registration.publicKey.excludeCredentials)registration.publicKey.excludeCredentials=registration.publicKey.excludeCredentials.map(x=>({...x,id:decode(x.id)}));
  const enrolled=await navigator.credentials.create({publicKey:registration.publicKey});
  r=await api('POST','/api/v1/auth/passkeys/registration/verify',{challengeId:registration.challengeId,credential:serialize(enrolled)});
  expect('browser-registration-verified',r,200);
  r=await api('POST','/api/v1/auth/passkeys/assertion/options',{});expect('assertion-options',r,200);
  const assertion=r.data;assertion.publicKey.challenge=decode(assertion.publicKey.challenge);
  if(assertion.publicKey.allowCredentials)assertion.publicKey.allowCredentials=assertion.publicKey.allowCredentials.map(x=>({...x,id:decode(x.id)}));
  const signed=await navigator.credentials.get({publicKey:assertion.publicKey});
  r=await api('POST','/api/v1/auth/passkeys/assertion/verify',{challengeId:assertion.challengeId,credential:serialize(signed)});
  expect('fresh-step-up',r,200);if(!(Date.parse(r.data.stepUpExpiresAt)>Date.now()))throw new Error('MISSING_FRESH_STEP_UP');
  r=await api('DELETE','/api/v1/students/'+config.studentId,undefined,{'X-Student-Delete-Confirmation':encodeURIComponent(config.admissionNumber)});
  expect('confirmed-application-delete',r,200);
  if(r.data.id!==config.studentId||r.data.deleted!==true||r.data.permanent!==true)throw new Error('UNCONFIRMED_DELETE_BODY');
  r=await api('GET','/api/v1/students/'+config.studentId);expect('source-absent-through-api',r,404);
  return {checks,deleted:true};
  } catch { return {checks,deleted:false}; }
 },config);
 proof.checks=result.checks;proof.passed=result.deleted&&result.checks.every(c=>c.passed);
 proof.completedAtUtc=new Date().toISOString();
 if(!proof.passed){proof.failure='BOUNDED_APPLICATION_CHECK_FAILED';process.exitCode=1;}
 save();
 console.log(JSON.stringify({project:proof.project,studentId:proof.studentId,checks:proof.checks.length,passed:proof.passed}));
} catch(error) {
 // Never print browser/provider bodies, credentials, tokens or arbitrary exception messages.
 proof.failure='BOUNDED_BROWSER_APPLICATION_ACCEPTANCE_FAILED';save();
 console.log(JSON.stringify({passed:false,failure:proof.failure,sensitiveOutputWithheld:true}));process.exitCode=1;
} finally {await browser.close();}

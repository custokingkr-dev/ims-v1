// Actual same-origin browser passkeys, marker-only upload and application DELETE.
import { chromium } from '../../frontend/node_modules/playwright/index.mjs';
import { readFileSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { createHash, randomBytes } from 'node:crypto';
import { deflateSync } from 'node:zlib';
import { fileURLToPath } from 'node:url';
if (!process.argv.includes('--apply-dev')) throw new Error('EXPLICIT_APPLY_DEV_REQUIRED');
const root=fileURLToPath(new URL('../../',import.meta.url));
const config=JSON.parse(readFileSync(new URL('../../tmp/security-acceptance-private.json',import.meta.url)));
const restart=JSON.parse(readFileSync(new URL('../../tmp/dev-pubsub-restart-proof.json',import.meta.url)));
const reset=JSON.parse(readFileSync(new URL('../../tmp/security-final-factor-reset-proof.json',import.meta.url)));
const marker='SEC-ACPT-20261007';const project='custoking-dev';
const origin='https://custoking-frontend-dev-hd4wfwk7mq-em.a.run.app';
if(config.marker!==marker || !restart.restartVerified || restart.project!==project || reset.readyRevision!==restart.afterRevision || !reset.resetAudited)throw new Error('NEW_READY_BROKER_AND_FACTOR_RESET_REQUIRED');
if(config.credentials.length!==2 || config.credentials.some((c,i)=>c.userId!==990007201+i || c.schoolId!==990007101+i || c.role!=='SCHOOL_ADMIN'))throw new Error('EXACT_ORDINARY_FIXTURE_ACTORS_REQUIRED');
const proof={schemaVersion:1,project,marker,afterRevision:restart.afterRevision,startedAtUtc:new Date().toISOString(),authenticator:'Chromium CTAP2 software authenticator',physicalHardwareProof:false,applicationDelete:true,noProviderDelivery:true,checks:[],deletions:[],passed:false};
const proofFile=new URL('../../tmp/security-final-live-erasure-proof.json',import.meta.url);
function save(){writeFileSync(proofFile,JSON.stringify(proof,null,2));}
function owner(mode){
 try{return JSON.parse(execFileSync('python',['scripts/security/final-live-fixture-acceptance.py','--apply-dev',mode],{cwd:root,timeout:900000,encoding:'utf8',windowsHide:true,stdio:['ignore','pipe','pipe']}));}
 catch{throw new Error('BOUNDED_OWNER_HELPER_FAILED:'+mode);}
}
function crc32(bytes){let c=0xffffffff;for(const b of bytes){c^=b;for(let k=0;k<8;k++)c=(c>>>1)^((c&1)?0xedb88320:0);}return (c^0xffffffff)>>>0;}
function chunk(name,bytes){const tag=Buffer.from(name);const len=Buffer.alloc(4);len.writeUInt32BE(bytes.length);const crc=Buffer.alloc(4);crc.writeUInt32BE(crc32(Buffer.concat([tag,bytes])));return Buffer.concat([len,tag,bytes,crc]);}
function syntheticPng(seed){const hash=createHash('sha256').update(marker+seed).digest();const ihdr=Buffer.alloc(13);ihdr.writeUInt32BE(12,0);ihdr.writeUInt32BE(12,4);ihdr[8]=8;ihdr[9]=2;const raw=Buffer.alloc(12*(1+12*3));for(let y=0;y<12;y++){for(let x=0;x<12;x++){const i=y*37+1+x*3;raw[i]=hash[(x+y)%32];raw[i+1]=hash[(x*2+y)%32];raw[i+2]=hash[(x+y*2)%32];}}return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',ihdr),chunk('IDAT',deflateSync(raw)),chunk('IEND',Buffer.alloc(0))]);}
const nonce=randomBytes(12).toString('hex');const png=syntheticPng(nonce);
proof.photo={studentId:990007301,nonce,inputPngSha256:createHash('sha256').update(png).digest('hex'),inputBytes:png.length};
writeFileSync(new URL('../../tmp/security-final-synthetic-photo.png',import.meta.url),png);
const browser=await chromium.launch({headless:true});const actors=[];
try{
 for(const c of config.credentials){
  const context=await browser.newContext();const page=await context.newPage();
  const cdp=await context.newCDPSession(page);await cdp.send('WebAuthn.enable');await cdp.send('WebAuthn.addVirtualAuthenticator',{options:{protocol:'ctap2',transport:'internal',hasResidentKey:true,hasUserVerification:true,isUserVerified:true,automaticPresenceSimulation:true}});
  await page.goto(origin,{waitUntil:'domcontentloaded',timeout:30000});
  const result=await page.evaluate(async({c,marker})=>{
   const checks=[];let token;
   const api=async(method,path,body)=>{const controller=new AbortController();const t=setTimeout(()=>controller.abort(),30000);try{const response=await fetch(path,{method,redirect:'error',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},body:body===undefined?undefined:JSON.stringify(body),signal:controller.signal});const reader=response.body.getReader();const chunks=[];let size=0;while(true){const {value,done}=await reader.read();if(done)break;size+=value.length;if(size>262144){await reader.cancel();throw new Error('HTTP_BODY_CEILING');}chunks.push(value);}const all=new Uint8Array(size);let offset=0;for(const chunk of chunks){all.set(chunk,offset);offset+=chunk.length;}return {status:response.status,data:JSON.parse(new TextDecoder().decode(all))};}finally{clearTimeout(t);}};
   const requireStatus=(name,r,expected)=>{checks.push({check:name,status:r.status,expected,passed:r.status===expected});if(r.status!==expected)throw new Error(name+':'+r.status);};
   let r=await api('POST','/api/v1/auth/login',{email:c.email,password:c.password});requireStatus('fresh-normal-login',r,200);
   if(r.data.role!=='SCHOOL_ADMIN'||r.data.branchId!==c.schoolId||r.data.userId!==c.userId)throw new Error('PRINCIPAL_MISMATCH');token=r.data.accessToken??r.data.token;
   const decode=v=>Uint8Array.from(atob(v.replace(/-/g,'+').replace(/_/g,'/')),x=>x.charCodeAt(0)).buffer;
   const encode=v=>btoa(String.fromCharCode(...new Uint8Array(v))).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');
   const serialize=credential=>{const response=credential.response;return {id:credential.id,rawId:encode(credential.rawId),type:credential.type,clientExtensionResults:credential.getClientExtensionResults(),authenticatorAttachment:credential.authenticatorAttachment,response:'attestationObject'in response?{clientDataJSON:encode(response.clientDataJSON),attestationObject:encode(response.attestationObject),transports:response.getTransports()}:{clientDataJSON:encode(response.clientDataJSON),authenticatorData:encode(response.authenticatorData),signature:encode(response.signature),userHandle:response.userHandle?encode(response.userHandle):null}};};
   r=await api('GET','/api/v1/auth/passkeys');requireStatus('actual-rp-status',r,200);if(r.data.rpId!==location.hostname)throw new Error('RP_MISMATCH');
   r=await api('POST','/api/v1/auth/passkeys/registration/options',{password:c.password});requireStatus('registration-options',r,200);
   const registration=r.data;registration.publicKey.challenge=decode(registration.publicKey.challenge);registration.publicKey.user.id=decode(registration.publicKey.user.id);if(registration.publicKey.excludeCredentials)registration.publicKey.excludeCredentials=registration.publicKey.excludeCredentials.map(x=>({...x,id:decode(x.id)}));
   const registered=await navigator.credentials.create({publicKey:registration.publicKey});r=await api('POST','/api/v1/auth/passkeys/registration/verify',{challengeId:registration.challengeId,credential:serialize(registered)});requireStatus('actual-browser-registration-verified',r,200);
   r=await api('POST','/api/v1/auth/passkeys/assertion/options',{});requireStatus('assertion-options',r,200);const assertion=r.data;assertion.publicKey.challenge=decode(assertion.publicKey.challenge);if(assertion.publicKey.allowCredentials)assertion.publicKey.allowCredentials=assertion.publicKey.allowCredentials.map(x=>({...x,id:decode(x.id)}));
   const signed=await navigator.credentials.get({publicKey:assertion.publicKey});r=await api('POST','/api/v1/auth/passkeys/assertion/verify',{challengeId:assertion.challengeId,credential:serialize(signed)});requireStatus('actual-browser-fresh-step-up-verified',r,200);if(!(Date.parse(r.data.stepUpExpiresAt)>Date.now()))throw new Error('AUTHORITATIVE_FRESH_STEP_UP_MISSING');const stepUpExpiresAt=r.data.stepUpExpiresAt;
   for(let id=c.schoolId===990007101?990007301:990007311;id<=(c.schoolId===990007101?990007310:990007320);id++){r=await api('GET','/api/v1/students/'+id);requireStatus('marker-student-preflight-'+id,r,200);if(r.data.id!==id||r.data.schoolId!==c.schoolId||(r.data.admissionNumber??r.data.admissionNo)!==marker+'-STUDENT-'+(id-990007300))throw new Error('EXACT_ADMISSION_MARKER_MISMATCH');if(r.data.photoUrl)throw new Error('PHOTO_SLOT_ALREADY_POPULATED');}
   return {checks,token,stepUpExpiresAt};
  },{c,marker});
  actors.push({c,context,page,token:result.token});proof.checks.push(...result.checks.map(x=>({...x,schoolId:c.schoolId})));proof.checks.push({check:'authoritative-step-up-expiry-issued',schoolId:c.schoolId,expiresAt:result.stepUpExpiresAt,passed:Date.parse(result.stepUpExpiresAt)>Date.now()});save();
 }
 writeFileSync(new URL('../../tmp/security-final-erasure-token-private.json',import.meta.url),JSON.stringify({marker,credentials:actors.map(a=>({schoolId:a.c.schoolId,userId:a.c.userId,accessToken:a.token}))}));
 const uploaded=await actors[0].page.evaluate(async({token,png})=>{const f=new FormData();f.append('file',new Blob([Uint8Array.from(png)],{type:'image/png'}),'synthetic-security-fixture.png');const controller=new AbortController();const t=setTimeout(()=>controller.abort(),30000);try{const r=await fetch('/api/v1/students/990007301/photo',{method:'POST',redirect:'error',headers:{Authorization:'Bearer '+token},body:f,signal:controller.signal});return {status:r.status};}finally{clearTimeout(t);}},{token:actors[0].token,png:[...png]});
 proof.photo.uploadStatus=uploaded.status;save();if(uploaded.status!==200)throw new Error('ACTUAL_PHOTO_UPLOAD_FAILED:'+uploaded.status);
 owner('photo-key');proof.photo.before=owner('photo-before');save();
 for(const a of actors){
  const renewed=await a.page.evaluate(async(token)=>{
   const api=async(path,body)=>{const controller=new AbortController();const t=setTimeout(()=>controller.abort(),30000);try{const r=await fetch('/api/v1/auth/passkeys'+path,{method:'POST',redirect:'error',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:JSON.stringify(body),signal:controller.signal});const raw=await r.text();if(raw.length>16384)throw new Error('PASSKEY_RESPONSE_CEILING');return {status:r.status,data:JSON.parse(raw)};}finally{clearTimeout(t);}};
   const decode=v=>Uint8Array.from(atob(v.replace(/-/g,'+').replace(/_/g,'/')),x=>x.charCodeAt(0)).buffer;const encode=v=>btoa(String.fromCharCode(...new Uint8Array(v))).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');
   let r=await api('/assertion/options',{});if(r.status!==200)throw new Error('FRESH_DELETE_ASSERTION_OPTIONS:'+r.status);const a=r.data;a.publicKey.challenge=decode(a.publicKey.challenge);if(a.publicKey.allowCredentials)a.publicKey.allowCredentials=a.publicKey.allowCredentials.map(x=>({...x,id:decode(x.id)}));
   const c=await navigator.credentials.get({publicKey:a.publicKey});const response=c.response;const credential={id:c.id,rawId:encode(c.rawId),type:c.type,clientExtensionResults:c.getClientExtensionResults(),authenticatorAttachment:c.authenticatorAttachment,response:{clientDataJSON:encode(response.clientDataJSON),authenticatorData:encode(response.authenticatorData),signature:encode(response.signature),userHandle:response.userHandle?encode(response.userHandle):null}};
   r=await api('/assertion/verify',{challengeId:a.challengeId,credential});if(r.status!==200||!(Date.parse(r.data.stepUpExpiresAt)>Date.now()))throw new Error('FRESH_DELETE_ASSERTION_VERIFICATION:'+r.status);return {status:r.status,expiresAt:r.data.stepUpExpiresAt};
  },a.token);
  proof.checks.push({check:'renewed-actual-browser-step-up-before-delete',schoolId:a.c.schoolId,status:renewed.status,expiresAt:renewed.expiresAt,passed:true});save();
  for(let id=a.c.schoolId===990007101?990007301:990007311;id<=(a.c.schoolId===990007101?990007310:990007320);id++){
  const result=await a.page.evaluate(async({token,schoolId,marker,id})=>{
   const controller=new AbortController();const t=setTimeout(()=>controller.abort(),30000);try{const r=await fetch('/api/v1/students/'+id,{method:'DELETE',redirect:'error',headers:{Authorization:'Bearer '+token,'X-Student-Delete-Confirmation':encodeURIComponent(marker+'-STUDENT-'+(id-990007300))},signal:controller.signal});const raw=await r.text();if(raw.length>16384)throw new Error('DELETE_RESPONSE_CEILING');const data=JSON.parse(raw);return {studentId:id,schoolId,status:r.status,deleted:data.deleted===true,permanent:data.permanent===true,atUtc:new Date().toISOString(),passed:r.status===200&&data.id===id&&data.deleted===true&&data.permanent===true};}finally{clearTimeout(t);}
  },{token:a.token,schoolId:a.c.schoolId,marker,id});
  proof.deletions.push(result);save();if(!result.passed)throw new Error('ACTUAL_APPLICATION_DELETE_FAILED:'+id+':'+result.status);
  }
 }
 proof.photo.after=owner('photo-after');proof.passed=proof.deletions.length===20&&proof.deletions.every(x=>x.passed)&&proof.photo.after.passed;proof.completedAtUtc=new Date().toISOString();save();console.log(JSON.stringify({project,marker,deleted:proof.deletions.length,photoLiveObjectAfter:proof.photo.after.liveObjectAfter,softDeletedGenerationObserved:proof.photo.after.softDeletedGenerationObserved,passed:proof.passed,checkedAtUtc:proof.completedAtUtc}));
}catch(e){proof.failure=String(e.message).slice(0,180);save();console.log(JSON.stringify({project,marker,failure:proof.failure,sensitiveOutputWithheld:true}));process.exitCode=1;}
finally{await browser.close();}

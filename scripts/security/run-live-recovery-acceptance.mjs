if (!process.argv.includes('--apply-dev')) throw new Error('Explicit --apply-dev is required for isolated custoking-dev synthetic acceptance');
import {chromium} from '../../frontend/node_modules/playwright/index.mjs';
import {readFileSync,writeFileSync} from 'node:fs';
const origin='https://custoking-frontend-dev-hd4wfwk7mq-em.a.run.app';
const privateConfig=JSON.parse(readFileSync(new URL('../../tmp/security-acceptance-private.json',import.meta.url)));
const oldToken=JSON.parse(readFileSync(new URL('../../tmp/security-acceptance-token-private.json',import.meta.url))).credentials[0].accessToken;
const browser=await chromium.launch({headless:true});const pages=[];const checks=[];
async function invoke(page,action,config={}){return page.evaluate(async({action,config})=>{
 const api=async(path,body)=>{const r=await fetch('/api/v1/auth/'+path,{method:body===undefined?'GET':'POST',headers:{Authorization:'Bearer '+window.fixtureToken,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(30000)});const text=await r.text();return {status:r.status,data:text?JSON.parse(text):null};};
 const dec=v=>Uint8Array.from(atob(v.replace(/-/g,'+').replace(/_/g,'/')),c=>c.charCodeAt(0)).buffer;
 const enc=v=>btoa(String.fromCharCode(...new Uint8Array(v))).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');
 const serialize=k=>({id:k.id,rawId:enc(k.rawId),type:k.type,clientExtensionResults:k.getClientExtensionResults(),authenticatorAttachment:k.authenticatorAttachment,response:'attestationObject'in k.response?{clientDataJSON:enc(k.response.clientDataJSON),attestationObject:enc(k.response.attestationObject),transports:k.response.getTransports()}:{clientDataJSON:enc(k.response.clientDataJSON),authenticatorData:enc(k.response.authenticatorData),signature:enc(k.response.signature),userHandle:k.response.userHandle?enc(k.response.userHandle):null}});
 if(action==='login'){const r=await api('login',{email:config.email,password:config.password});if(r.status===200)window.fixtureToken=r.data.token??r.data.accessToken;return {status:r.status,role:r.data?.role,schoolId:r.data?.branchId};}
 if(action==='register'||action==='assert'){
  const first=await api('passkeys/'+(action==='register'?'registration/options':'assertion/options'),action==='register'?{password:config.password}:{});if(first.status!==200)return {status:first.status};
  const o=first.data.publicKey;o.challenge=dec(o.challenge);if(o.user)o.user.id=dec(o.user.id);for(const key of ['allowCredentials','excludeCredentials'])if(o[key])o[key]=o[key].map(d=>({...d,id:dec(d.id)}));
  const credential=action==='register'?await navigator.credentials.create({publicKey:o}):await navigator.credentials.get({publicKey:o});
  const r=await api('passkeys/'+(action==='register'?'registration/verify':'assertion/verify'),{challengeId:first.data.challengeId,credential:serialize(credential)});return {status:r.status,enrolled:r.data?.enrolled,stepUp:!!r.data?.stepUpExpiresAt};
 }
 if(action==='request')return api('passkeys/recovery/request',{userId:990007201,reason:'SEC-ACPT-20261007 isolated synthetic recovery drill; no real account'});
 if(action==='approve')return api('passkeys/recovery/approve',{recoveryId:config.recoveryId});
 if(action==='status')return api('passkeys');
 if(action==='old-access'){const r=await fetch('/api/v1/students?schoolId=990007101&page=0&size=1',{headers:{Authorization:'Bearer '+config.token},signal:AbortSignal.timeout(30000)});await r.body?.cancel();return {status:r.status};}
 },{action,config});}
const record=(name,r,expected)=>{checks.push({check:name,status:r.status,expected,passed:r.status===expected});if(r.status!==expected)throw Error(name+' status '+r.status);};
try{
 for(const admin of privateConfig.recoveryAdmins){const context=await browser.newContext();const page=await context.newPage();pages.push(page);const cdp=await context.newCDPSession(page);await cdp.send('WebAuthn.enable');await cdp.send('WebAuthn.addVirtualAuthenticator',{options:{protocol:'ctap2',transport:'internal',hasResidentKey:true,hasUserVerification:true,isUserVerified:true,automaticPresenceSimulation:true}});await page.goto(origin,{waitUntil:'domcontentloaded'});record('independent-admin-login',await invoke(page,'login',admin),200);record('password-only-recovery-denied',await invoke(page,'request'),403);record('independent-admin-passkey-enrolled',await invoke(page,'register',admin),200);record('enrollment-alone-does-not-grant-recovery',await invoke(page,'request'),403);record('independent-admin-fresh-passkey-assertion',await invoke(page,'assert'),200);}
 const requested=await invoke(pages[0],'request');record('fresh-admin-recovery-request',requested,200);const recoveryId=requested.data.recoveryId;
 record('same-admin-approval-denied',await invoke(pages[0],'approve',{recoveryId}),403);
 const approved=await invoke(pages[1],'approve',{recoveryId});record('independent-fresh-admin-approval',approved,200);if(!approved.data.requiresNewEnrollment)throw Error('Recovery did not require enrollment');
 record('completed-recovery-replay-denied',await invoke(pages[1],'approve',{recoveryId}),403);
 record('target-all-sessions-revoked',await invoke(pages[0],'old-access',{token:oldToken}),401);
 const target=privateConfig.credentials[0];record('target-owner-password-relogin',await invoke(pages[0],'login',target),200);const status=await invoke(pages[0],'status');record('recovered-owner-status',status,200);if(status.data.enrolled)throw Error('Old passkey retained after recovery');
 record('recovered-owner-new-passkey-enrollment',await invoke(pages[0],'register',target),200);record('recovered-owner-fresh-passkey-assertion',await invoke(pages[0],'assert'),200);
 const proof={schemaVersion:1,project:'custoking-dev',marker:'SEC-ACPT-20261007',targetUserId:990007201,independentApprovers:[990007203,990007204],authenticator:'Chromium CTAP2 virtual software authenticator',physicalHardwareProof:false,checks,passed:checks.every(c=>c.passed),checkedAtUtc:new Date().toISOString()};writeFileSync(new URL('../../tmp/security-recovery-live-proof.json',import.meta.url),JSON.stringify(proof,null,2));console.log(JSON.stringify({passed:proof.passed,checks:checks.length}));
}finally{await browser.close();}

if (!process.argv.includes('--apply-dev')) throw new Error('Explicit --apply-dev is required for isolated custoking-dev synthetic acceptance');
import { chromium } from '../../frontend/node_modules/playwright/index.mjs';
import { readFileSync,writeFileSync } from 'node:fs';
const gateway='https://custoking-api-gateway-dev-hd4wfwk7mq-em.a.run.app';
const origin='https://custoking-frontend-dev-hd4wfwk7mq-em.a.run.app';
const config=JSON.parse(readFileSync(new URL('../../tmp/security-acceptance-private.json',import.meta.url)));
const tokens=JSON.parse(readFileSync(new URL('../../tmp/security-acceptance-token-private.json',import.meta.url))).credentials;
const browser=await chromium.launch({headless:true});const checks=[];const contexts=[];
try {
 for (const [index,c] of config.credentials.entries()) {
  const context=await browser.newContext();contexts.push(context);const page=await context.newPage();
  const cdp=await context.newCDPSession(page);await cdp.send('WebAuthn.enable');
  await cdp.send('WebAuthn.addVirtualAuthenticator',{options:{protocol:'ctap2',transport:'internal',hasResidentKey:true,hasUserVerification:true,isUserVerified:true,automaticPresenceSimulation:true}});
  await page.goto(origin,{waitUntil:'domcontentloaded',timeout:30000});
  const result=await page.evaluate(async ({gateway,c,token,otherToken})=>{
   const checks=[];
   const api=async(path='',body,override=token)=>{const controller=new AbortController();const timer=setTimeout(()=>controller.abort(),30000);try{const response=await fetch(gateway+'/api/v1/auth/passkeys'+path,{method:body===undefined?'GET':'POST',headers:{Authorization:'Bearer '+override,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),signal:controller.signal});return {status:response.status,data:await response.json()};}finally{clearTimeout(timer);}};
   const decode=v=>Uint8Array.from(atob(v.replace(/-/g,'+').replace(/_/g,'/')),x=>x.charCodeAt(0)).buffer;
   const encode=v=>btoa(String.fromCharCode(...new Uint8Array(v))).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');
   const serialize=credential=>{const response=credential.response;const base={id:credential.id,rawId:encode(credential.rawId),type:credential.type,clientExtensionResults:credential.getClientExtensionResults(),authenticatorAttachment:credential.authenticatorAttachment};return {...base,response:'attestationObject'in response?{clientDataJSON:encode(response.clientDataJSON),attestationObject:encode(response.attestationObject),transports:response.getTransports()}:{clientDataJSON:encode(response.clientDataJSON),authenticatorData:encode(response.authenticatorData),signature:encode(response.signature),userHandle:response.userHandle?encode(response.userHandle):null}};};
   const assert=(check,status,expected)=>{checks.push({check,status,expected,passed:status===expected});if(status!==expected)throw new Error(check+': status '+status);};
   let result=await api();assert('status',result.status,200);if(result.data.rpId!==location.hostname)throw new Error('Configured RP mismatch');
   result=await api('/registration/options',{password:c.password});assert('registration-options',result.status,200);
   const ceremony=result.data;const o=ceremony.publicKey;o.challenge=decode(o.challenge);o.user.id=decode(o.user.id);if(o.excludeCredentials)o.excludeCredentials=o.excludeCredentials.map(x=>({...x,id:decode(x.id)}));
   const registered=await navigator.credentials.create({publicKey:o});const registration={challengeId:ceremony.challengeId,credential:serialize(registered)};
   result=await api('/registration/verify',registration);assert('real-browser-registration-server-verified',result.status,200);
   result=await api('/registration/verify',registration);assert('registration-replay-denied',result.status,403);
   result=await api('/assertion/options',{});assert('assertion-options',result.status,200);
   const assertion=result.data;const a=assertion.publicKey;a.challenge=decode(a.challenge);if(a.allowCredentials)a.allowCredentials=a.allowCredentials.map(x=>({...x,id:decode(x.id)}));
   const signed=await navigator.credentials.get({publicKey:a});const payload={challengeId:assertion.challengeId,credential:serialize(signed)};
   result=await api('/assertion/verify',payload,otherToken);assert('foreign-session-challenge-denied',result.status,403);
   // A foreign-session rejection must not grant step-up; obtain a new challenge after any consumption.
   result=await api('/assertion/options',{});assert('fresh-assertion-options',result.status,200);
   const fresh=result.data;fresh.publicKey.challenge=decode(fresh.publicKey.challenge);if(fresh.publicKey.allowCredentials)fresh.publicKey.allowCredentials=fresh.publicKey.allowCredentials.map(x=>({...x,id:decode(x.id)}));
   const credential=await navigator.credentials.get({publicKey:fresh.publicKey});const valid={challengeId:fresh.challengeId,credential:serialize(credential)};
   result=await api('/assertion/verify',valid);assert('real-browser-assertion-server-verified',result.status,200);if(!result.data.stepUpExpiresAt)throw new Error('No authoritative step-up expiry');checks.push({check:'authoritative-step-up-expiry-issued',status:200,expected:200,passed:Date.parse(result.data.stepUpExpiresAt)>Date.now()});
   result=await api('/assertion/verify',valid);assert('assertion-replay-denied',result.status,403);
   result=await api();assert('authoritative-enrollment-status',result.status,200);if(!result.data.enrolled)throw new Error('No authoritative enrollment status');
   return checks;
  },{gateway:origin,c,token:tokens[index].accessToken,otherToken:tokens[1-index].accessToken});
  checks.push(...result.map(check=>({...check,schoolId:c.schoolId})));
 }
 const proof={schemaVersion:1,project:'custoking-dev',marker:config.marker,authenticator:'Chromium CTAP2 virtual software authenticator',physicalHardwareProof:false,checkedAtUtc:new Date().toISOString(),checks,passed:checks.every(x=>x.passed)};
 writeFileSync(new URL('../../tmp/security-passkey-live-proof.json',import.meta.url),JSON.stringify(proof,null,2));console.log(JSON.stringify(proof));
}finally{await browser.close();}

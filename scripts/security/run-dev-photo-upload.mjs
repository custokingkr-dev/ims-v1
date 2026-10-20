// One owned synthetic dev student, normal application authorization, no provider delivery.
import { chromium } from '../../frontend/node_modules/playwright/index.mjs';
import { readFileSync, writeFileSync, realpathSync } from 'node:fs';
import { basename, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
if (!process.argv.includes('--apply-dev')) throw new Error('EXPLICIT_DEV_APPLY_REQUIRED');
const argumentIndex=process.argv.indexOf('--private-file');
if(argumentIndex<0 || !process.argv[argumentIndex+1])throw new Error('OWNED_PRIVATE_FILE_REQUIRED');
const workspace=fileURLToPath(new URL('../../',import.meta.url));
const temporary=realpathSync(resolve(workspace,'tmp/dev-photo-acceptance'));
const privateFile=realpathSync(resolve(workspace,process.argv[argumentIndex+1]));
if(dirname(privateFile)!==temporary || !/^dev-photo-[a-f0-9]{12}-private\.json$/.test(basename(privateFile)))
  throw new Error('OWNED_TEMPORARY_SCOPE_REQUIRED');
const config=JSON.parse(readFileSync(privateFile,'utf8'));
const origin='https://custoking-frontend-dev-hd4wfwk7mq-em.a.run.app';
if(config.project!=='custoking-dev' || config.schoolId!==990008401 || config.userId!==990008501 || config.studentId!==990008601
   || !/^SEC-PHOTO-DEV-20261007-[a-f0-9]{12}$/.test(config.marker)
   || config.admissionNumber!==config.marker+'-STUDENT'
   || !/^[a-f0-9]{40}$/.test(config.expectedSourceSha) || config.guardedReady!==true || config.expectedSourceSha!=='01522d74d039b9f46162572ddafd1a5c90d6e7ee'
   || basename(privateFile)!=='dev-photo-'+config.marker.slice(-12)+'-private.json')
  throw new Error('EXACT_OWNED_READY_FIXTURE_REQUIRED');
const proof={schemaVersion:1,project:config.project,marker:config.marker,studentId:config.studentId,
 expectedSourceSha:config.expectedSourceSha,startedAtUtc:new Date().toISOString(),
 authenticator:'Chromium software CTAP2',physicalHardwareProof:false,providerCalls:0,checks:[],passed:false};
const output=new URL('../../tmp/dev-photo-read-boundary-browser-proof.json',import.meta.url);
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
  const boundedBinary=async(path,options={})=>{
   const control=new AbortController();const timer=setTimeout(()=>control.abort(),30000);
   try{const response=await fetch(path,{...options,redirect:'error',headers:{Authorization:'Bearer '+token,...options.headers},signal:control.signal});
    const reader=response.body.getReader();let size=0;const chunks=[];
    while(true){const part=await reader.read();if(part.done)break;size+=part.value.length;if(size>4194304){await reader.cancel();throw new Error('BINARY_CEILING');}chunks.push(part.value);}
    const bytes=new Uint8Array(size);let offset=0;for(const chunk of chunks){bytes.set(chunk,offset);offset+=chunk.length;}
    return {status:response.status,bytes,type:response.headers.get('content-type'),count:response.headers.get('x-student-count')};
   }finally{clearTimeout(timer);}
  };
  const canvas=document.createElement('canvas');canvas.width=32;canvas.height=32;const draw=canvas.getContext('2d');
  draw.fillStyle='#245a85';draw.fillRect(0,0,32,32);draw.fillStyle='#f0c642';draw.fillRect(8,8,16,16);
  const blob=await new Promise(resolve=>canvas.toBlob(resolve,'image/png'));
  const form=new FormData();form.append('file',blob,'synthetic-pattern.png');
  let binary;
  if(!config.ownedPhoto){binary=await boundedBinary('/api/v1/students/'+config.studentId+'/photo',{method:'POST',body:form});expect('normal-owned-photo-upload',binary,200);}
  binary=await boundedBinary('/api/v1/students/'+config.studentId+'/photo/content');expect('normal-owned-photo-read',binary,200);
  if(binary.type!=='image/jpeg'||binary.bytes.length===0||binary.bytes[0]!==255||binary.bytes[1]!==216)throw new Error('EXPECTED_NORMALIZED_RASTER');
  const photoBytes=binary.bytes.length;
  binary=await boundedBinary('/api/v1/students/export/archive?schoolId='+config.schoolId);expect('school-admin-export-role-denied',binary,403);
  const archive=null;
  r=await api('POST','/api/v1/workspace/students',{schoolId:config.schoolId,fullName:config.marker+'-REJECT',admissionNumber:config.marker+'-REJECT',photoUrl:'schools/22222222-2222-4222-8222-222222222222/students/9/photos/known.jpg'});
  expect('caller-injected-foreign-key-rejected',r,400);
  return {checks,uploaded:true,photoBytes,archive};
  } catch { return {checks,uploaded:false}; }
 },config);
 if(result.archive)writeFileSync(resolve(temporary,'owned-photo-export.zip'),Buffer.from(result.archive,'base64'));
 proof.photoBytes=result.photoBytes;proof.exportSaved=Boolean(result.archive);
 proof.checks=result.checks;proof.passed=result.uploaded&&result.checks.every(c=>c.passed);
 proof.completedAtUtc=new Date().toISOString();
 if(!proof.passed){proof.failure='BOUNDED_APPLICATION_CHECK_FAILED';process.exitCode=1;}
 save();
 console.log(JSON.stringify({project:proof.project,studentId:proof.studentId,checks:proof.checks.length,passed:proof.passed}));
} catch(error) {
 // Never print browser/provider bodies, credentials, tokens or arbitrary exception messages.
 proof.failure='BOUNDED_BROWSER_APPLICATION_ACCEPTANCE_FAILED';save();
 console.log(JSON.stringify({passed:false,failure:proof.failure,sensitiveOutputWithheld:true}));process.exitCode=1;
} finally {await browser.close();}

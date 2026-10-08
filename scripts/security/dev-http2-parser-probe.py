"""Four serial HTTP/2 health streams against the owned dev GFE; ten-second network deadline."""
import argparse,datetime as dt,json,pathlib,queue,socket,ssl,struct,sys,threading,time
HOST='custoking-frontend-dev-hd4wfwk7mq-em.a.run.app';PATH='/frontend-health';DEADLINE_SECONDS=10
CASES=(('healthy_control',[],b''),('conflicting_content_lengths',[('content-length','0'),('content-length','1')],b'x'),('content_length_data_mismatch',[('content-length','2')],b'x'),('forbidden_transfer_encoding',[('transfer-encoding','chunked'),('content-length','0')],b''))
class ProbeFailure(Exception):pass

def frame(kind,flags,stream,payload=b''):
 if len(payload)>16384 or stream<0 or stream>0x7fffffff:raise ProbeFailure('FRAME_BOUND_EXCEEDED')
 return len(payload).to_bytes(3,'big')+bytes([kind,flags])+stream.to_bytes(4,'big')+payload

def string(value):
 raw=value.encode('ascii');size=len(raw)
 if size>1024:raise ProbeFailure('HEADER_BOUND_EXCEEDED')
 if size<127:return bytes([size])+raw
 size-=127;encoded=bytearray([127])
 while size>=128:encoded.append((size&127)|128);size>>=7
 encoded.append(size);return bytes(encoded)+raw

def request(headers,body):
 if len(body)>1024 or len(headers)>2:raise ProbeFailure('REQUEST_BOUND_EXCEEDED')
 # HPACK literals bypass HTTP library normalization/validation and preserve both CL fields.
 fields=[(':method','GET'),(':scheme','https'),(':authority',HOST),(':path',PATH),*headers]
 encoded=b''.join(b'\x00'+string(name)+string(value) for name,value in fields)
 return b'PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n'+frame(4,0,0)+frame(1,4|(0 if body else 1),1,encoded)+(frame(0,1,1,body) if body else b'')

def remaining(deadline):
 duration=deadline-time.monotonic()
 if duration<=0:raise ProbeFailure('FULL_NETWORK_DEADLINE')
 return duration

def receive(sock,count,deadline):
 result=bytearray()
 while len(result)<count:
  sock.settimeout(remaining(deadline));part=sock.recv(count-len(result))
  if not part:raise ProbeFailure('CONNECTION_CLOSED_WITHOUT_TERMINAL_RESULT')
  result.extend(part)
 return bytes(result)

def read_result(sock,deadline,decoder):
 result=dict(status=None,streamReset=None,goaway=None,responseBodyBytes=0,framesReceived=0)
 block=bytearray();ended=False;total=0
 while result['framesReceived']<32:
  header=receive(sock,9,deadline);size=int.from_bytes(header[:3],'big');kind=header[3];flags=header[4];stream=int.from_bytes(header[5:9],'big')&0x7fffffff
  if size>16384:raise ProbeFailure('RESPONSE_FRAME_BOUND_EXCEEDED')
  total+=size+9
  if total>65536:raise ProbeFailure('RESPONSE_TOTAL_BOUND_EXCEEDED')
  payload=receive(sock,size,deadline);result['framesReceived']+=1
  if kind==4 and not flags&1:sock.sendall(frame(4,1,0));continue
  if kind==6 and not flags&1:sock.sendall(frame(6,1,0,payload));continue
  if kind==7:
   if len(payload)<8:raise ProbeFailure('INVALID_GOAWAY')
   result['goaway']=int.from_bytes(payload[4:8],'big');return result
  if kind==3 and stream==1:
   if len(payload)!=4:raise ProbeFailure('INVALID_RST_STREAM')
   result['streamReset']=int.from_bytes(payload,'big');return result
  if kind in (1,9) and stream==1:
   ended=ended or (kind==1 and bool(flags&1))
   if kind==1:
    pad=payload[0] if flags&8 else 0;offset=(1 if flags&8 else 0)+(5 if flags&32 else 0)
    if offset+pad>len(payload):raise ProbeFailure('INVALID_HEADER_PADDING')
    payload=payload[offset:len(payload)-pad if pad else len(payload)]
   block.extend(payload)
   if len(block)>16384:raise ProbeFailure('RESPONSE_HEADER_BOUND_EXCEEDED')
   if flags&4:
    fields=decoder.decode(bytes(block));block.clear()
    statuses=[value for name,value in fields if name==':status']
    if statuses:result['status']=int(statuses[-1])
    if ended:return result
  if kind==0 and stream==1:
   result['responseBodyBytes']+=len(payload)
   if result['responseBodyBytes']>8192:raise ProbeFailure('RESPONSE_BODY_BOUND_EXCEEDED')
   if flags&1:return result
 raise ProbeFailure('RESPONSE_FRAME_COUNT_BOUND_EXCEEDED')

def accepted(name,result):
 if name=='healthy_control':return result['status']==200 and result['streamReset'] is None and result['goaway'] is None
 # An HTTP rejection cannot conceal an observed non-conforming reset code.
 # An HTTP-only 4xx proves rejection, not an observed PROTOCOL_ERROR reset.
 resets=[result[key] for key in ('streamReset','goaway') if result[key] is not None]
 if any(type(code) is not int or code!=1 for code in resets):return False
 return bool(resets) or (result['status'] is not None and 400<=result['status']<500)

def execute(decoder_factory,proof,stop,active,cases=CASES):
 deadline=time.monotonic()+DEADLINE_SECONDS
 context=ssl.create_default_context();context.set_alpn_protocols(['h2'])
 for name,headers,body in cases:
  if stop.is_set():return
  record=dict(case=name,method='GET',path=PATH,requestBodyBytes=len(body),bytesSubmitted=0,clientValidationBypassed='Raw fixed HPACK literal fields; no HTTP client normalizer')
  proof['cases'].append(record)
  try:
   raw=socket.create_connection((HOST,443),timeout=remaining(deadline));active.append(raw)
   if stop.is_set():return
   with context.wrap_socket(raw,server_hostname=HOST) as tls:
    active.append(tls);tls.settimeout(remaining(deadline))
    if tls.selected_alpn_protocol()!='h2':raise ProbeFailure('HTTP2_ALPN_NOT_NEGOTIATED')
    if stop.is_set():return
    record['alpn']='h2';wire=request(headers,body);tls.sendall(wire);record['bytesSubmitted']=len(wire)
    record.update(read_result(tls,deadline,decoder_factory()))
    record['expectedRejectionOrControl']=accepted(name,record)
    if not record['expectedRejectionOrControl']:proof['stoppedOnAnomaly']=True;return
  except Exception as error:
   record['failure']=str(error) if isinstance(error,ProbeFailure) else type(error).__name__;proof['stoppedOnAnomaly']=True;return
  finally:
   for sock in active:
    try:sock.close()
    except OSError:pass
   active.clear()
 if stop.is_set():return
 proof['allSelectedChecksPassed']=len(proof['cases'])==len(cases) and all(c.get('expectedRejectionOrControl') for c in proof['cases'])
 proof['allFourChecksPassed']=len(cases)==4 and proof['allSelectedChecksPassed']

def main():
 p=argparse.ArgumentParser();p.add_argument('--apply',action='store_true');p.add_argument('--output',required=True);p.add_argument('--hpack-library');p.add_argument('--case',choices=[c[0] for c in CASES]);a=p.parse_args()
 cases=tuple(c for c in CASES if not a.case or c[0]==a.case)
 proof=dict(project='custoking-dev',host=HOST,path=PATH,capturedAtUtc=dt.datetime.now(dt.timezone.utc).isoformat(),scope='Current public Cloud Run GFE frontend ingress only; no owned edge or universal request-smuggling assurance',maximumStreams=len(cases),maximumConcurrentConnections=1,fullNetworkDeadlineSeconds=10,maximumRequestBodyBytes=1024,cases=[],allFourChecksPassed=False,allSelectedChecksPassed=False,stoppedOnAnomaly=False)
 if not a.apply:print(json.dumps(dict(host=HOST,path=PATH,apply=False,maximumStreams=4,fullNetworkDeadlineSeconds=10)));return 0
 out=pathlib.Path(a.output)
 if out.exists():raise ProbeFailure('REFUSE_PROOF_OVERWRITE')
 if a.hpack_library:sys.path.insert(0,str(pathlib.Path(a.hpack_library).resolve()))
 from hpack import Decoder
 active=[];stop=threading.Event();finished=queue.Queue(maxsize=1)
 def work():
  try:execute(lambda:Decoder(max_header_list_size=16384),proof,stop,active,cases)
  finally:finished.put(True)
 start=time.monotonic();worker=threading.Thread(target=work,daemon=True);worker.start()
 timed_out=False
 try:finished.get(timeout=10)
 except queue.Empty:
  timed_out=True;stop.set()
  for sock in active:
   try:sock.close()
   except OSError:pass
 # Detach output from the daemon's mutable observations. A late completion
 # cannot turn an elapsed deadline into acceptance, even if all cases finished.
 snapshot=json.loads(json.dumps(proof))
 if timed_out:
  snapshot.update(failure='FULL_NETWORK_DEADLINE',stoppedOnAnomaly=True,allSelectedChecksPassed=False,allFourChecksPassed=False)
 snapshot['elapsedSeconds']=round(time.monotonic()-start,3)
 out.parent.mkdir(parents=True,exist_ok=True)
 with out.open('x',encoding='utf-8',newline='\n') as stream:stream.write(json.dumps(snapshot,indent=2)+'\n')
 print(json.dumps(snapshot))
 return 0 if snapshot['allSelectedChecksPassed'] else 1
if __name__=='__main__':raise SystemExit(main())

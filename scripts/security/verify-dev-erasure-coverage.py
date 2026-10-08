"""Offline bounded historical/external-intent reconciliation; never authorizes restore."""
import argparse, hashlib, json, re, uuid, sys, os, stat
from pathlib import Path
PROJECT='custoking-dev'
IDENTITY={'schemaVersion','project','sourceInstance','database','sourceLineageId','restoreEpoch','schoolId','studentId','studentIncarnation'}
SHA=re.compile(r'[a-f0-9]{64}')

def require(ok):
    if not ok: raise ValueError('ERASURE_COVERAGE_REJECTED')

def canonical(value):
    return (json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=True)+'\n').encode('ascii')

def parse_json(raw):
    def pairs(items):
        result={}
        for key,value in items:
            require(key not in result);result[key]=value
        return result
    return json.loads(raw,object_pairs_hook=pairs)

def uid(value):
    require(isinstance(value,str) and len(value)==36)
    require(str(uuid.UUID(value))==value)
    return value

def verify(bundle, expected_hash):
    require(isinstance(expected_hash,str) and SHA.fullmatch(expected_hash))
    require(hashlib.sha256(canonical(bundle)).hexdigest()==expected_hash)
    require(set(bundle)=={'schemaVersion','project','sourceInstance','database','lineageId','epochs','sqlReceipts','legacyCoverage'})
    require(type(bundle['schemaVersion']) is int and bundle['schemaVersion']==1 and bundle['project']==PROJECT and bundle['sourceInstance']=='custoking-db-dev' and bundle['database']=='custoking_dev')
    lineage=uid(bundle['lineageId']);epochs=bundle['epochs'];receipts=bundle['sqlReceipts']
    require(isinstance(epochs,list) and 1<=len(epochs)<=64 and isinstance(receipts,list) and len(receipts)<=1000)
    require(bundle['legacyCoverage'] in ('UNRESOLVED','OWNER_REVIEW_REQUIRED'))
    seen_epochs=set();intents={};targets=[]
    for epoch in epochs:
        require(set(epoch)=={'epochId','inventorySha256','objects'})
        eid=uid(epoch['epochId']);require(eid not in seen_epochs);seen_epochs.add(eid)
        objects=epoch['objects'];require(isinstance(objects,list) and len(objects)<=1000)
        require(isinstance(epoch['inventorySha256'],str) and SHA.fullmatch(epoch['inventorySha256']))
        require(hashlib.sha256(canonical(objects)).hexdigest()==epoch['inventorySha256'])
        for obj in objects:
            require(len(intents)<1000 and set(obj)=={'object','generation','sha256','bodyUtf8'})
            require(isinstance(obj['generation'],str) and re.fullmatch(r'[1-9][0-9]{0,19}',obj['generation']))
            require(int(obj['generation'])<=9223372036854775807)
            raw=obj['bodyUtf8'];require(isinstance(raw,str) and len(raw)<=4096)
            body=parse_json(raw);require(isinstance(body,dict) and set(body)==IDENTITY|{'kind','intentId','operationId'})
            require(canonical(body)==raw.encode('ascii') and hashlib.sha256(raw.encode('ascii')).hexdigest()==obj['sha256'])
            require(body['kind']=='student.erasure-intent.v1' and type(body['schemaVersion']) is int and body['schemaVersion']==1)
            for key in ('project','sourceInstance','database'):require(body[key]==bundle[key])
            require(body['sourceLineageId']==lineage and body['restoreEpoch']==eid)
            uid(body['studentIncarnation'])
            for key in ('schoolId','studentId'):require(type(body[key]) is int and 0<body[key]<=9223372036854775807)
            identity={k:body[k] for k in IDENTITY};intent=hashlib.sha256(canonical(identity)).hexdigest()
            require(body['intentId']==intent and intent not in intents)
            operation=str(uuid.UUID(bytes=hashlib.md5(('ims-student-erasure:'+intent).encode()).digest(),version=3))
            require(body['operationId']==operation and obj['object']=='intents/'+lineage+'/'+eid+'/'+intent+'.json')
            intents[intent]=obj
            targets.append(dict(intentId=intent,epochId=eid,schoolId=body['schoolId'],studentId=body['studentId'],studentIncarnation=body['studentIncarnation']))
    seen_receipts=set()
    for receipt in receipts:
        require(isinstance(receipt,dict) and set(receipt)=={'intentId','object','generation','sha256'})
        intent=receipt['intentId'];require(intent in intents and intent not in seen_receipts);seen_receipts.add(intent)
        require(all(receipt[k]==intents[intent][k] for k in ('object','generation','sha256')))
    return dict(schemaVersion=1,project=PROJECT,mode='OFFLINE_RECONCILIATION_ONLY',bundleSha256=expected_hash,
        epochCount=len(seen_epochs),intentCount=len(intents),receiptCount=len(receipts),externalOnlyIntentCount=len(intents)-len(receipts),
        replayTargets=sorted(targets,key=lambda t:t['intentId']),providedEpochInventoriesConsistent=True,
        globalHistoricalCoverageProven=False,physicalObjectGenerationsVerified=False,sourceLineageApproved=False,
        legacyCoverage=bundle['legacyCoverage'],restorationReady=False,deliveryResume=False,
        remainingRequirements=['Independent exhaustive epoch/object enumeration and generation reads through approved freeze boundary',
            'Approved legacy-lineage mapping and pre-journal historical coverage','Physical isolated clone lineage and complete multi-service replay verification',
            'Owner-governed epoch/cutover/resume; approved retention and recovery targets'])

def read_regular_bounded(path, limit=2*1024*1024):
    # O_NONBLOCK prevents a FIFO open from hanging before fstat can reject it.
    flags=os.O_RDONLY|getattr(os,'O_NONBLOCK',0)|getattr(os,'O_NOFOLLOW',0)|getattr(os,'O_BINARY',0)
    fd=os.open(path,flags)
    try:
        before=os.fstat(fd);require(stat.S_ISREG(before.st_mode) and before.st_size<=limit)
        chunks=[];remaining=limit+1
        while remaining:
            chunk=os.read(fd,min(65536,remaining))
            if not chunk:break
            chunks.append(chunk);remaining-=len(chunk)
        raw=b''.join(chunks);after=os.fstat(fd);current=os.stat(path,follow_symlinks=False)
        require(len(raw)<=limit and after.st_size<=limit)
        require((before.st_dev,before.st_ino,before.st_size,before.st_mtime_ns)==
                (after.st_dev,after.st_ino,after.st_size,after.st_mtime_ns))
        require(stat.S_ISREG(current.st_mode) and (current.st_dev,current.st_ino)==(after.st_dev,after.st_ino))
        return raw
    finally:os.close(fd)

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--bundle',required=True);parser.add_argument('--reviewed-canonical-sha256',required=True);parser.add_argument('--out',required=True);args=parser.parse_args()
    source=Path(args.bundle)
    result=verify(parse_json(read_regular_bounded(source).decode('utf-8')),args.reviewed_canonical_sha256)
    with Path(args.out).open('x',encoding='utf-8') as stream:json.dump(result,stream,indent=2);stream.write('\n')
if __name__=='__main__':
    try: main()
    except Exception:
        print('ERASURE_COVERAGE_REJECTED',file=sys.stderr);sys.exit(1)

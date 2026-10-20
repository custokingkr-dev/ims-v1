import copy,hashlib,importlib.util,json,pathlib,unittest,uuid,subprocess,sys,tempfile,os,stat
from unittest.mock import patch
p=pathlib.Path(__file__).parents[1]/'security/verify-dev-erasure-coverage.py'
s=importlib.util.spec_from_file_location('coverage',p);v=importlib.util.module_from_spec(s);s.loader.exec_module(v)
def seal(x):return hashlib.sha256(v.canonical(x)).hexdigest()
def fixture():
 lineage=str(uuid.UUID(int=1));epochs=[];receipts=[]
 for n in (2,3):
  eid=str(uuid.UUID(int=n));body=dict(schemaVersion=1,project=v.PROJECT,sourceInstance='custoking-db-dev',database='custoking_dev',sourceLineageId=lineage,restoreEpoch=eid,schoolId=990008402,studentId=990008602,studentIncarnation=str(uuid.UUID(int=n+10)))
  intent=seal(body);body.update(kind='student.erasure-intent.v1',intentId=intent,operationId=str(uuid.UUID(bytes=hashlib.md5(('ims-student-erasure:'+intent).encode()).digest(),version=3)))
  raw=v.canonical(body).decode();obj=dict(object='intents/'+lineage+'/'+eid+'/'+intent+'.json',generation='9007199254740993',sha256=hashlib.sha256(raw.encode()).hexdigest(),bodyUtf8=raw)
  epochs.append(dict(epochId=eid,inventorySha256=seal([obj]),objects=[obj]))
  if n==3:receipts.append(dict(intentId=intent,**{k:obj[k] for k in ('object','generation','sha256')}))
 return dict(schemaVersion=1,project=v.PROJECT,sourceInstance='custoking-db-dev',database='custoking_dev',lineageId=lineage,epochs=epochs,sqlReceipts=receipts,legacyCoverage='UNRESOLVED')
class CoverageTests(unittest.TestCase):
 def test_old_epoch_external_only_and_same_id_different_incarnation_preserved(self):
  x=fixture();r=v.verify(x,seal(x));self.assertEqual((2,2,1),(r['epochCount'],r['intentCount'],r['externalOnlyIntentCount']))
  self.assertEqual(2,len(r['replayTargets']));self.assertFalse(r['restorationReady']);self.assertFalse(r['deliveryResume']);self.assertFalse(r['globalHistoricalCoverageProven'])
 def test_receipt_without_durable_intent_is_rejected(self):
  x=fixture();x['epochs'].pop()
  with self.assertRaises(ValueError):v.verify(x,seal(x))
 def test_wrong_generation_hash_epoch_lineage_and_operation_rejected(self):
  for kind in ('generation','body','epoch','lineage','operation','object','float'):
   with self.subTest(kind=kind):
    x=fixture();o=x['epochs'][1]['objects'][0]
    if kind=='generation':x['sqlReceipts'][0]['generation']='9007199254740992'
    elif kind=='float':o['generation']=9007199254740992.0
    elif kind=='object':o['object']='intents/foreign.json'
    else:
     body=json.loads(o['bodyUtf8'])
     if kind=='body':o['bodyUtf8']+=' '
     else:
      key={'epoch':'restoreEpoch','lineage':'sourceLineageId','operation':'operationId'}[kind];body[key]=str(uuid.UUID(int=99));o['bodyUtf8']=v.canonical(body).decode()
     o['sha256']=hashlib.sha256(o['bodyUtf8'].encode()).hexdigest()
    x['epochs'][1]['inventorySha256']=seal(x['epochs'][1]['objects'])
    with self.assertRaises(ValueError):v.verify(x,seal(x))
 def test_duplicates_and_inventory_gaps_rejected(self):
  for kind in ('epoch','receipt','intent','inventory'):
   x=fixture()
   if kind=='epoch':x['epochs'].append(copy.deepcopy(x['epochs'][0]))
   elif kind=='receipt':x['sqlReceipts']*=2
   elif kind=='intent':x['epochs'][0]['objects']*=2;x['epochs'][0]['inventorySha256']=seal(x['epochs'][0]['objects'])
   else:x['epochs'][0]['inventorySha256']='0'*64
   with self.assertRaises(ValueError):v.verify(x,seal(x))
 def test_wrong_reviewed_hash_scope_or_invented_legacy_approval_rejected(self):
  x=fixture()
  with self.assertRaises(ValueError):v.verify(x,'0'*64)
  for key,val in [('project','prod'),('legacyCoverage','COMPLETE'),('schemaVersion',True)]:
   x=fixture();x[key]=val
   with self.assertRaises(ValueError):v.verify(x,seal(x))
 def test_duplicate_json_properties_rejected(self):
  with self.assertRaises(ValueError):v.parse_json('{"generation":"1","generation":"2"}')
 def test_cli_exclusive_output_cap_and_sanitized_failure(self):
  with tempfile.TemporaryDirectory() as directory:
   root=pathlib.Path(directory);source=root/'bundle.json';out=root/'proof.json';x=fixture()
   source.write_text(json.dumps(x),encoding='utf-8')
   args=[sys.executable,'-O',str(p),'--bundle',str(source),'--reviewed-canonical-sha256',seal(x),'--out',str(out)]
   first=subprocess.run(args,capture_output=True,text=True,timeout=5)
   self.assertEqual(0,first.returncode);before=out.read_bytes()
   second=subprocess.run(args,capture_output=True,text=True,timeout=5)
   self.assertEqual(1,second.returncode);self.assertEqual(before,out.read_bytes())
   self.assertEqual('ERASURE_COVERAGE_REJECTED',second.stderr.strip())
   source.write_text('x'*(2*1024*1024+1),encoding='utf-8')
   failed=subprocess.run(args,capture_output=True,text=True,timeout=5)
   self.assertEqual(1,failed.returncode);self.assertEqual('ERASURE_COVERAGE_REJECTED',failed.stderr.strip());self.assertEqual(before,out.read_bytes())

 def test_java_literal_golden_identity_and_name_uuid(self):
  # Literal outputs from JDK25 SHA256/UUID.nameUUIDFromBytes, using existing
  # StudentErasureJournalTest lineage/epoch/incarnation and student301/school101.
  identity=dict(schemaVersion=1,project='custoking-dev',sourceInstance='custoking-db-dev',database='custoking_dev',sourceLineageId='68d28569-95ab-4288-ae34-3cb1183cc51b',restoreEpoch='f3ecac24-4a25-4a29-a087-45237c3117af',schoolId=101,studentId=301,studentIncarnation='719f63db-a0f3-435f-818c-2a9fb3f0be5c')
  self.assertEqual('cc47c23b1c30c4f327bd3cd44e10173a7c9fb15b09bedc70c068661f128d9021',seal(identity))
  self.assertEqual('122954ad-2470-3f6f-a070-bdf45f787ed8',str(uuid.UUID(bytes=hashlib.md5(('ims-student-erasure:'+seal(identity)).encode()).digest(),version=3)))
 def test_fd_growth_replacement_nonregular_and_read_cap(self):
  with tempfile.TemporaryDirectory() as directory:
   path=pathlib.Path(directory)/'bundle';path.write_bytes(b'a'*16)
   realread=v.os.read;calls=[]
   def grow(fd,count):
    calls.append(count)
    with path.open('ab') as out:out.write(b'x'*100)
    return realread(fd,count)
   with patch.object(v.os,'read',side_effect=grow):
    with self.assertRaises(ValueError):v.read_regular_bounded(path,32)
   self.assertLessEqual(max(calls),33)
   path.write_bytes(b'original')
   def replace(fd,count):
    path.unlink();path.write_bytes(b'replaced');return realread(fd,count)
   with patch.object(v.os,'read',side_effect=replace):
    # Windows refuses replacing an open descriptor; POSIX inode mismatch is rejected.
    with self.assertRaises((ValueError,OSError)):v.read_regular_bounded(path,32)
   with self.assertRaises((ValueError,OSError)):v.read_regular_bounded(path.parent,32)
   if hasattr(os,'mkfifo'):
    fifo=path.parent/'pipe';os.mkfifo(fifo)
    with self.assertRaises(ValueError):v.read_regular_bounded(fifo,32)

 def test_bounds(self):
  for kind in ('epochs','body'):
   x=fixture()
   if kind=='epochs':x['epochs']*=33
   else:x['epochs'][0]['objects'][0]['bodyUtf8']=' '*4097
   with self.assertRaises(ValueError):v.verify(x,seal(x))
if __name__=='__main__':unittest.main()

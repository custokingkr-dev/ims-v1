import hashlib,importlib.util,json,unittest,subprocess,sys,tempfile
from pathlib import Path
spec=importlib.util.spec_from_file_location('proof',Path(__file__).resolve().parents[1] / 'security/read-dev-image-config-user.py')
p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p)
def digest(b):return 'sha256:'+hashlib.sha256(b).hexdigest()
class ProofTest(unittest.TestCase):
 def fixture(self,user='node'):
  config=json.dumps({'config':{'User':user,'Env':['CONTROLLED_ENV_MUST_NEVER_LEAK=value']}}).encode()
  manifest=json.dumps({'schemaVersion':2,'mediaType':next(iter(p.MANIFEST_TYPES)),'config':{'digest':digest(config),'size':len(config),'mediaType':next(iter(p.CONFIG_TYPES))}}).encode()
  return manifest,config
 def test_valid_digest_user_and_no_env_exposure(self):
  for user in ['node','101','1000:1000','nginx']:
   manifest,config=self.fixture(user)
   result=p.inspect('custoking-frontend',digest(manifest),'controlled-token',lambda i,k,d,t:manifest if k=='manifests' else config)
   self.assertEqual(user,result['configuredUser']);self.assertNotIn('CONTROLLED_ENV',json.dumps(result))
 def test_root_empty_zero_denied(self):
  for user in ['', 'root','root:1000','0','000:node']:
   manifest,config=self.fixture(user)
   with self.assertRaises(p.ProofRejected):p.inspect('custoking-frontend',digest(manifest),'controlled-token',lambda i,k,d,t:manifest if k=='manifests' else config)
 def test_manifest_and_config_tamper_rejected(self):
  manifest,config=self.fixture()
  for kind in ['manifests','blobs']:
   with self.assertRaises(p.ProofRejected):p.inspect('custoking-frontend',digest(manifest),'controlled-token',lambda i,k,d,t:(manifest if k=='manifests' else config)+(b' ' if kind==k else b''))
 def test_response_redirect_size_digest_and_path_denied(self):
  class Response:
   def __init__(self,status=200,length=None,data=b'controlled'):self.status=status;self.length=length;self.data=data
   def getheader(self,key):return self.length
   def read1(self,n):b=self.data[:n];self.data=self.data[n:];return b
  class Connection:
   sock=None
   def __init__(self,*a,**k):self.closed=False
   def request(self,*a,**k):self.requested=a;self.headers=k['headers']
   def getresponse(self):return response
   def close(self):self.closed=True
  for response in [Response(302),Response(length=str(p.LIMIT+1)),Response(data=b'x'*(p.LIMIT+1)),Response(data=b'tampered')]:
   with self.assertRaises(p.ProofRejected):p.fetch_bytes('custoking-frontend','blobs',digest(b'controlled'),'controlled-token',Connection)
  for image,kind,d in [('foreign','blobs',digest(b'')),('custoking-frontend','../foreign',digest(b'')),('custoking-frontend','blobs','sha256:../../secret')]:
   with self.assertRaises(p.ProofRejected):p.fetch_bytes(image,kind,d,'controlled-token',Connection)
 def test_google_storage_redirect_strips_auth_and_unsafe_redirects_fail(self):
  body=b'controlled'
  requests=[]
  class Response:
   def __init__(self,status,location=None):self.status=status;self.location=location;self.body=body
   def getheader(self,key):return self.location if key=='Location' else None
   def read1(self,n):value=self.body;self.body=b'';return value
  class Connection:
   sock=None
   def __init__(self,host,**kwargs):self.host=host
   def request(self,method,path,headers):requests.append((self.host,path,headers))
   def getresponse(self):return responses.pop(0)
   def close(self):pass
  responses=[Response(302,'https://storage.googleapis.com/controlled-object?signed=controlled'),Response(200)]
  self.assertEqual(body,p.fetch_bytes('custoking-frontend','blobs',digest(body),'controlled-token',Connection))
  self.assertIn('Authorization',requests[0][2]);self.assertNotIn('Authorization',requests[1][2])
  for url in ['http://storage.googleapis.com/object','https://evil.invalid/object','https://storage.googleapis.com.evil.invalid/object','https://user@storage.googleapis.com/object','https://storage.googleapis.com:444/object','https://asia-south2-docker.pkg.dev/other-repository/object']:
   responses=[Response(302,url)]
   with self.assertRaises(p.ProofRejected):p.fetch_bytes('custoking-frontend','blobs',digest(body),'controlled-token',Connection)
 def test_exact_allowlisted_refs_and_child_manifest(self):
  with tempfile.TemporaryDirectory(prefix='ims-image-proof-safe-error-') as folder:
   inventory=Path(folder)/'private-fixture.json'
   inventory.write_text(json.dumps({'services':[{'service':'frontend','runtimeRef':'controlled-secret-must-never-leak'}]}))
   result=subprocess.run([sys.executable,str(Path(__file__).resolve().parents[1]/'security/read-dev-image-config-user.py'),'--images',str(inventory)],capture_output=True,text=True,timeout=5)
   self.assertEqual(1,result.returncode)
   self.assertEqual({'passed':False,'error':'IMAGE_USER_PROOF_REJECTED'},json.loads(result.stdout))
   self.assertEqual('',result.stderr)
  with self.assertRaises(p.ProofRejected):p.targets({'services':[{'service':'frontend','runtimeRef':'https://evil.invalid/image'}]})
  index=json.dumps({'schemaVersion':2,'mediaType':'application/vnd.oci.image.index.v1+json'}).encode()
  with self.assertRaises(p.ProofRejected):p.inspect('custoking-frontend',digest(index),'controlled-token',lambda *args:index)
if __name__=='__main__':unittest.main()

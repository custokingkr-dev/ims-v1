import importlib.util,pathlib,unittest
from unittest.mock import patch
ROOT=pathlib.Path(__file__).resolve().parents[2];spec=importlib.util.spec_from_file_location('probe',ROOT/'scripts/security/dev-http2-parser-probe.py');p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p)
class Sock:
 def __init__(self,data):self.data=data;self.sent=[]
 def settimeout(self,value):pass
 def recv(self,size):value,self.data=self.data[:size],self.data[size:];return value
 def sendall(self,data):self.sent.append(data)
class Decoder:
 def decode(self,data):return [(':status','200' if data==b'\x88' else '400')]
class Http2ParserProbeTest(unittest.TestCase):
 def test_fixed_get_health_cases_and_no_large_or_nested_http_body(self):
  self.assertEqual(len(p.CASES),4);self.assertEqual(p.PATH,'/frontend-health')
  for name,headers,body in p.CASES:
   packet=p.request(headers,body);self.assertIn(b'GET',packet);self.assertNotIn(b'POST',packet);self.assertLessEqual(len(body),1024)
  with self.assertRaises(p.ProbeFailure):p.request([],b'x'*1025)
 def test_conflicting_content_length_reaches_raw_packet_unmodified(self):
  _,headers,body=p.CASES[1];packet=p.request(headers,body)
  self.assertEqual(packet.count(b'content-length'),2);self.assertTrue(packet.endswith(b'x'))
 def test_forbidden_transfer_encoding_is_not_client_filtered(self):
  packet=p.request(p.CASES[3][1],p.CASES[3][2]);self.assertIn(b'transfer-encoding',packet);self.assertIn(b'chunked',packet)
 def test_reset_protocol_error_is_sanitized(self):
  result=p.read_result(Sock(p.frame(3,0,1,(1).to_bytes(4,'big'))),p.time.monotonic()+1,Decoder())
  self.assertEqual(result['streamReset'],1);self.assertTrue(p.accepted('bad',result));self.assertIsNone(result['status'])
 def test_response_settings_ack_then_healthy_headers(self):
  sock=Sock(p.frame(4,0,0)+p.frame(1,5,1,b'\x88'));result=p.read_result(sock,p.time.monotonic()+1,Decoder())
  self.assertEqual(sock.sent,[p.frame(4,1,0)]);self.assertTrue(p.accepted('healthy_control',result));self.assertEqual(result['status'],200)
 def test_accepted_malformed_request_is_anomaly(self):
  result={'status':200,'streamReset':None,'goaway':None};self.assertFalse(p.accepted('conflicting_content_lengths',result))
 def test_full_deadline_and_frame_bounds(self):
  with self.assertRaises(p.ProbeFailure):p.remaining(p.time.monotonic()-1)
  with self.assertRaises(p.ProbeFailure):p.frame(0,0,1,b'x'*16385)
  with self.assertRaises(p.ProbeFailure):p.read_result(Sock((16385).to_bytes(3,'big')+b'\x00\x00'+(1).to_bytes(4,'big')),p.time.monotonic()+1,Decoder())
if __name__=='__main__':unittest.main()

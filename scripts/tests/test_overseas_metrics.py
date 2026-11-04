import importlib.util, pathlib, unittest, urllib.request, io, json
from unittest.mock import patch, MagicMock
spec=importlib.util.spec_from_file_location('metrics',pathlib.Path(__file__).resolve().parents[1]/'overseas-metrics.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class MetricTests(unittest.TestCase):
 def test_only_public_known_store_urls(self):
  for url in ['http://localhost/','https://play.google.com.evil/store/apps/details?id=com.a.b&hl=en&gl=US','https://play.google.com/store/apps/details?id=com.a.b&hl=en&gl=CN']:
   with patch.object(m.urllib.request,'build_opener') as opener:
    with self.assertRaises(ValueError):m.fetch({'url':url})
    opener.assert_not_called()
 def test_redirects_rejected(self):
  with self.assertRaises(ValueError):m.NoRedirect().redirect_request(None,None,302,'',{},'http://localhost')
 def test_bound_and_type(self):
  for content,typ in [(b'x'*(m.MAX_BYTES+1),'text/html'),(b'{}','application/json'),(b'','text/html')]:
   response=MagicMock();response.status=200;response.headers={'Content-Type':typ};response.read.return_value=content;response.__enter__.return_value=response
   with patch.object(m.urllib.request,'build_opener') as opener:
    opener.return_value.open.return_value=response
    with self.assertRaises(ValueError):m.fetch({'url':'https://play.google.com/store/apps/details?id=com.example.game&hl=en&gl=US'})
 def test_lease_and_raw_are_not_logged_and_failure_is_reported(self):
  with patch.object(m,'exchange',side_effect=[{'available':True,'jobId':'test','leaseToken':'SENSITIVE','url':'unused'},{'status':'FAILED','errorCode':'REMOTE_FETCH_FAILED'},{'available':False}]) as exchange,patch.object(m,'fetch',side_effect=ValueError()),patch.object(m.time,'sleep'),patch('sys.stdout',new_callable=io.StringIO) as output:
   m.collect(2);self.assertNotIn('SENSITIVE',output.getvalue());self.assertEqual('ValueError',exchange.call_args_list[1].args[1]['errorCode'])
if __name__=='__main__':unittest.main()

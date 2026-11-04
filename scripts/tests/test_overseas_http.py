import importlib.util, pathlib, threading, unittest, urllib.request, urllib.error, json
from unittest.mock import patch
ROOT=pathlib.Path(__file__).resolve().parents[1]
class HttpReceiverTests(unittest.TestCase):
    def setUp(self):
        spec=importlib.util.spec_from_file_location('http_receiver',ROOT/'overseas-http-receiver.py');self.m=importlib.util.module_from_spec(spec)
        with patch.object(pathlib.Path,'read_text',return_value='SYNTHETIC-test-token'):spec.loader.exec_module(self.m)
        self.server=self.m.http.server.HTTPServer(('127.0.0.1',0),self.m.Handler)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
        self.url='http://127.0.0.1:'+str(self.server.server_port)
        self.addCleanup(self.close)
    def close(self):self.server.shutdown();self.server.server_close();self.thread.join(2)
    def test_unauthorized_never_reads_plan_or_runs_import(self):
        with patch.object(self.m.urllib.request,'urlopen') as fetch,patch.object(self.m.subprocess,'run') as run:
            # Use saved opener because patching shared urllib.urlopen affects the client too.
            opener=urllib.request.build_opener()
            with self.assertRaises(urllib.error.HTTPError) as error:opener.open(self.url+'/overseas/plan')
            self.assertEqual(401,error.exception.code);error.exception.close();fetch.assert_not_called();run.assert_not_called()
    def test_token_cannot_invoke_arbitrary_path(self):
        req=urllib.request.Request(self.url+'/admin/run',data=b'id',headers={'Authorization':'Bearer SYNTHETIC-test-token'})
        with patch.object(self.m.subprocess,'run') as run:
            with self.assertRaises(urllib.error.HTTPError) as error:urllib.request.urlopen(req)
            self.assertEqual(404,error.exception.code);error.exception.close();run.assert_not_called()
    def test_oversize_rejected_before_import(self):
        req=urllib.request.Request(self.url+'/overseas/deliver',data=b'x',headers={'Authorization':'Bearer SYNTHETIC-test-token','Content-Length':'24000001'})
        with patch.object(self.m.subprocess,'run') as run:
            with self.assertRaises(urllib.error.HTTPError) as error:urllib.request.urlopen(req)
            self.assertEqual(413,error.exception.code);error.exception.close();run.assert_not_called()
    def test_metric_oversize_and_auth_rejected(self):
        for headers,expected in [({},401),({'Authorization':'Bearer SYNTHETIC-test-token','Content-Length':'5400001'},413)]:
            req=urllib.request.Request(self.url+'/overseas/metric-result',data=b'x',headers=headers)
            with self.assertRaises(urllib.error.HTTPError) as error:urllib.request.urlopen(req)
            self.assertEqual(expected,error.exception.code);error.exception.close()
if __name__=='__main__':unittest.main()

import datetime, importlib.util, io, json, pathlib, tempfile, unittest, zipfile, hashlib
from unittest.mock import patch
ROOT=pathlib.Path(__file__).resolve().parents[1]
def module(name):
    spec=importlib.util.spec_from_file_location(name,ROOT/(name+'.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
receiver=module('receive-overseas');collector=module('overseas-capture')
class DeliveryTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=pathlib.Path(self.temp.name);self.addCleanup(self.temp.cleanup)
    def archive(self,mutate=lambda x:x):
        folder=self.root/'source';folder.mkdir(exist_ok=True);raw=b'SYNTHETIC offline material'
        bundle={'request':{'sourceKey':'appbrain','region':'us','chartKey':'top-free','requestedTopN':100},'evidence':{'sourceUrl':'https://www.appbrain.com/stats/google-play-rankings/top_free/game/us','capturedAt':datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),'sha256':hashlib.sha256(raw).hexdigest()}}
        mutate(bundle);(folder/'bundle.json').write_text(json.dumps(bundle));(folder/'raw.bin').write_bytes(raw)
        batch={'batchId':'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee','failedCount':1,'partialCount':1,'attempts':[{'source':'appbrain','region':'us','chart':'top-free','status':'PARTIAL','bundleLocator':'source/bundle.json'},{'source':'appbrain','region':'gb','chart':'top-free','status':'FAILED','errorCode':'HTTP_ERROR'}]}
        archive=self.root/'delivery.zip';collector.package(batch,self.root,archive);return archive
    def test_partial_and_failure_are_preserved(self):
        m=receiver.validate(self.archive());self.assertEqual(1,m['sourceFailedCount']);self.assertEqual(1,len(m['materials']))
    def test_wrong_scope_date_and_url_rejected(self):
        for field,value in [('region','cn'),('chartKey','popularity')]:
            with self.assertRaises(ValueError):receiver.validate(self.archive(lambda b:b['request'].update({field:value})))
        for field,value in [('sourceUrl','http://localhost/'),('capturedAt','2020-01-01T00:00:00Z')]:
            with self.assertRaises(ValueError):receiver.validate(self.archive(lambda b:b['evidence'].update({field:value})))
    def test_unexpected_archive_member_rejected(self):
        archive=self.archive()
        with zipfile.ZipFile(archive,'a') as z:z.writestr('../escape','bad')
        with self.assertRaises(ValueError):receiver.validate(archive)
    def test_modified_raw_hash_rejected(self):
        archive=self.archive()
        with zipfile.ZipFile(archive) as z:members={i:z.read(i) for i in z.namelist()}
        members['0/raw.bin']=b'tampered'
        with zipfile.ZipFile(archive,'w') as z:
            for k,v in members.items():z.writestr(k,v)
        with self.assertRaises(ValueError):receiver.validate(archive)
    def test_all_failures_have_no_materials(self):
        with patch.object(collector.subprocess,'run',side_effect=TimeoutError()),patch('sys.stdout',new=io.StringIO()):batch=collector.collect(pathlib.Path('unused'),self.root,['us'])
        self.assertEqual(5,batch['failedCount']);archive=self.root/'failed.zip';collector.package(batch,self.root,archive)
        self.assertEqual([],receiver.validate(archive)['materials'])
    def test_nano_timestamp_supported_without_date_shift(self):
        self.assertEqual(123456,receiver.parse_utc('2026-11-03T01:02:03.123456789Z').microsecond)
if __name__=='__main__':unittest.main()

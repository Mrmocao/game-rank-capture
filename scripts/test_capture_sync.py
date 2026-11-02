"""Synthetic evidence transport tests. No SSH, database, credentials or live website access."""
import hashlib, importlib.util, json, pathlib, sys, tempfile, types, unittest, zipfile
from unittest.mock import patch

def load(name, filename):
    spec=importlib.util.spec_from_file_location(name,pathlib.Path(__file__).with_name(filename))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

sender=load('sender','sync-capture-server.py')
with patch.dict(sys.modules,{'fcntl':types.SimpleNamespace(flock=lambda *a:None,LOCK_EX=1,LOCK_NB=2),'pwd':types.SimpleNamespace()}):
    receiver=load('receiver','receive-capture.py')

class TransportTest(unittest.TestCase):
    def fixture(self,root):
        folder=root/'evidence';folder.mkdir();raw=b'SYNTHETIC source row'
        (folder/'raw.bin').write_bytes(raw)
        (folder/'bundle.json').write_text(json.dumps({'evidence':{'sha256':hashlib.sha256(raw).hexdigest()},'observation':{'businessDate':'2026-11-02'}}))
        (root/'profile-cookie-DO-NOT-SEND').write_text('SYNTHETIC SECRET')
        batch=root/'batch-test.json';batch.write_text(json.dumps({'batchId':'12345678-1234-1234-1234-123456789abc','failedCount':1,'partialCount':1,'attempts':[{'source':'synthetic','status':'PARTIAL','bundleLocator':'evidence/bundle.json','entries':1}]}))
        return batch
    def test_only_materials_are_transmitted_and_partial_is_preserved(self):
        with tempfile.TemporaryDirectory() as t:
            root=pathlib.Path(t);batch=self.fixture(root);dest=root/'out.zip';m=sender.package(batch,root,dest)
            self.assertEqual(1,m['sourcePartialCount'])
            with zipfile.ZipFile(dest) as z:self.assertEqual({'manifest.json','0/bundle.json','0/raw.bin'},set(z.namelist()))
    def test_tampered_raw_is_rejected(self):
        with tempfile.TemporaryDirectory() as t:
            root=pathlib.Path(t);batch=self.fixture(root);(root/'evidence/raw.bin').write_bytes(b'tampered')
            with self.assertRaisesRegex(ValueError,'EVIDENCE_HASH_MISMATCH'):sender.package(batch,root,root/'out.zip')
    def test_unexpected_archive_member_rejected_before_replay(self):
        with tempfile.TemporaryDirectory() as t:
            root=pathlib.Path(t);batch=self.fixture(root);dest=root/'out.zip';sender.package(batch,root,dest)
            with zipfile.ZipFile(dest,'a') as z:z.writestr('../escape','SYNTHETIC')
            with patch.object(receiver.subprocess,'run') as run:
                with self.assertRaisesRegex(ValueError,'INVALID_ARCHIVE'):receiver.receive(root,dest)
                run.assert_not_called()
    def test_acknowledged_batch_is_idempotent_without_replay_or_report(self):
        with tempfile.TemporaryDirectory() as t:
            root=pathlib.Path(t);batch=self.fixture(root);dest=root/'out.zip';m=sender.package(batch,root,dest)
            deliveries=root/'shared/deliveries';deliveries.mkdir(parents=True)
            (deliveries/(m['batchId']+'.json')).write_text(json.dumps({'sourceBatchSha256':m['sourceBatchSha256'],'status':'PARTIAL'}))
            with patch.object(receiver.subprocess,'run') as run,patch.object(receiver.urllib.request,'urlopen') as request:
                self.assertTrue(receiver.receive(root,dest)['reused']);run.assert_not_called();request.assert_not_called()
    def test_source_failure_creates_failure_receipt_without_fake_snapshot_or_report(self):
        with tempfile.TemporaryDirectory() as t:
            root=pathlib.Path(t);batch=self.fixture(root);data=json.loads(batch.read_text());data['attempts']=[{'source':'synthetic','status':'FAILED','errorCode':'SOURCE_AUTH_REQUIRED'}];data['partialCount']=0;batch.write_text(json.dumps(data));dest=root/'out.zip';sender.package(batch,root,dest)
            with patch.object(receiver.subprocess,'run') as run,patch.object(receiver.urllib.request,'urlopen') as request:
                result=receiver.receive(root,dest);self.assertEqual('SOURCE_FAILED',result['status']);self.assertIsNone(result['businessDate']);self.assertNotIn('reportId',result);run.assert_not_called();request.assert_not_called()
    def test_batch_id_cannot_reuse_different_source_manifest(self):
        with tempfile.TemporaryDirectory() as t:
            root=pathlib.Path(t);batch=self.fixture(root);dest=root/'out.zip';m=sender.package(batch,root,dest)
            deliveries=root/'shared/deliveries';deliveries.mkdir(parents=True);(deliveries/(m['batchId']+'.json')).write_text(json.dumps({'sourceBatchSha256':'different','status':'COMPLETE'}))
            with self.assertRaisesRegex(ValueError,'BATCH_CONFLICT'):receiver.receive(root,dest)

if __name__=='__main__':unittest.main()

"""Synthetic server bridge tests; never call a real source, API or database."""
import importlib.util, pathlib, sys, tempfile, types, unittest
from unittest.mock import Mock,patch
spec=importlib.util.spec_from_file_location('bridge',pathlib.Path(__file__).with_name('server-capture-reports.py'))
bridge=importlib.util.module_from_spec(spec)
with patch.dict(sys.modules,{'fcntl':types.SimpleNamespace()}):spec.loader.exec_module(bridge)

class BridgeTest(unittest.TestCase):
    def batch(self):return {'batchId':'SYNTHETIC-test','failedCount':0,'partialCount':0,'attempts':[{'source':'synthetic','chart':'test','status':'SUCCEEDED','observationId':1,'businessDate':'2026-11-02','entries':100}]}
    def report(self):return {'id':'SYNTHETIC-report','revision':1,'items':[],'researchLeads':{'items':[]}}
    def test_completed_batch_never_repeats_report_call(self):
        with tempfile.TemporaryDirectory() as t:
            post=Mock(return_value=self.report());d=pathlib.Path(t);a=bridge.finalize(self.batch(),d,post);b=bridge.finalize(self.batch(),d,post)
            self.assertEqual(a,b);post.assert_called_once();self.assertEqual('COMPLETE',a['status'])
    def test_login_failure_preserves_other_source_and_does_not_fake_rows(self):
        with tempfile.TemporaryDirectory() as t:
            batch=self.batch();batch['failedCount']=1;batch['attempts'].append({'source':'synthetic-login','status':'FAILED','errorCode':'SOURCE_AUTH_REQUIRED'})
            result=bridge.finalize(batch,pathlib.Path(t),Mock(return_value=self.report()))
            self.assertEqual('PARTIAL',result['status']);self.assertEqual(100,result['importedEntries']);self.assertTrue(result['requiresLogin'])
    def test_all_failure_has_no_report_and_no_business_date(self):
        with tempfile.TemporaryDirectory() as t:
            batch=self.batch();batch['attempts']=[{'status':'FAILED','errorCode':'SOURCE_AUTH_REQUIRED'}];batch['failedCount']=1;post=Mock()
            result=bridge.finalize(batch,pathlib.Path(t),post);post.assert_not_called();self.assertIsNone(result['businessDate']);self.assertEqual('SOURCE_FAILED',result['status'])
    def test_failed_report_retries_without_losing_saved_data(self):
        with tempfile.TemporaryDirectory() as t:
            d=pathlib.Path(t)
            with self.assertRaises(TimeoutError):bridge.finalize(self.batch(),d,Mock(side_effect=TimeoutError))
            import json
            failed=json.loads((d/'latest.json').read_text(encoding='utf-8'));self.assertTrue(failed['dataSaved']);self.assertEqual('INGESTED_REPORT_FAILED',failed['status'])
            self.assertEqual('COMPLETE',bridge.finalize(self.batch(),d,Mock(return_value=self.report()))['status'])
    def test_stale_source_day_never_relabels_and_does_not_block_current_domestic(self):
        with tempfile.TemporaryDirectory() as t:
            batch=self.batch();batch['attempts'].append({'status':'SUCCEEDED','observationId':2,'businessDate':'2026-11-01','entries':99});post=Mock(return_value=self.report())
            result=bridge.finalize(batch,pathlib.Path(t),post);self.assertEqual(['2026-11-01','2026-11-02'],result['sourceBusinessDates']);self.assertEqual('2026-11-01',result['sourceResults'][1]['businessDate']);self.assertEqual('2026-11-02',str(post.call_args[0][0]))
    def test_session_validation_does_not_extend_reminder_on_every_capture(self):
        import json
        with tempfile.TemporaryDirectory() as t:
            d=pathlib.Path(t);batch={'attempts':[{'source':'gravity-wechat' if i<3 else 'gravity-douyin','chart':str(i),'status':'SUCCEEDED','entries':100} for i in range(7)]}
            bridge.update_session(batch,d);first=json.loads((d/'source-session.json').read_text(encoding='utf-8'))
            self.assertEqual('VERIFIED',first['status']);self.assertIn('reviewAfter',first)
            bridge.update_session(batch,d);second=json.loads((d/'source-session.json').read_text(encoding='utf-8'));self.assertEqual(first['reviewAfter'],second['reviewAfter'])
            bridge.update_session({'attempts':[{'source':'gravity-wechat','status':'FAILED','errorCode':'SOURCE_AUTH_REQUIRED'}]},d)
            self.assertEqual('LOGIN_REQUIRED',json.loads((d/'source-session.json').read_text(encoding='utf-8'))['status'])
            state=dict(first,verificationRequested=True,lastLoginVerifiedAt='2000-01-01T00:00:00+00:00');bridge.atomic(d/'source-session.json',state)
            bridge.update_session(batch,d);self.assertNotEqual(state['lastLoginVerifiedAt'],json.loads((d/'source-session.json').read_text(encoding='utf-8'))['lastLoginVerifiedAt'])

if __name__=='__main__':unittest.main()

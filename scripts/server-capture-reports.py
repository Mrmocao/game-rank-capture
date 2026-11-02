"""Server-local completion bridge. Java has already validated and saved the source observations."""
import argparse, datetime, fcntl, json, os, pathlib, time, urllib.request

def atomic(path,value):
    tmp=path.with_suffix('.pending')
    tmp.write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')
    os.chmod(str(tmp),0o644);os.replace(str(tmp),str(path))

def update_session(batch,deliveries):
    gravity=[a for a in batch['attempts'] if a.get('source') in ('gravity-wechat','gravity-douyin')]
    if not gravity:return
    path=deliveries/'source-session.json';state=json.loads(path.read_text(encoding='utf-8')) if path.exists() else {}
    now=datetime.datetime.now(datetime.timezone.utc)
    if any(a.get('errorCode')=='SOURCE_AUTH_REQUIRED' for a in gravity):
        state.update(status='LOGIN_REQUIRED',lastFailureAt=now.isoformat(),reason='SOURCE_AUTH_REQUIRED')
    elif len({(a.get('source'),a.get('chart')) for a in gravity if a.get('status')=='SUCCEEDED' and a.get('entries',0)>=100})==7:
        if not state.get('lastLoginVerifiedAt') or state.get('verificationRequested'):
            state.update(lastLoginVerifiedAt=now.isoformat(),reviewAfter=(now+datetime.timedelta(days=6)).isoformat(),verificationRequested=False)
        state.update(status='VERIFIED',lastValidatedAt=now.isoformat(),reason=None)
    else:state.update(lastIncompleteAt=now.isoformat())
    state.update(source='GRAVITY_ENGINE',reportedSessionDays=7,notice='第6天提醒检查；不代表已核实的Cookie到期时间。源站要求短信或扫码时需要人工验证。')
    atomic(path,state)

def finalize(batch,deliveries,post):
    batch_id=batch['batchId'];receipt=deliveries/('server-'+batch_id+'.json')
    if receipt.exists():
        saved=json.loads(receipt.read_text(encoding='utf-8'))
        if saved['status'] in ('COMPLETE','PARTIAL','SOURCE_FAILED'):return saved
    update_session(batch,deliveries)
    attempts=batch['attempts'];accepted=[a for a in attempts if a.get('status') in ('SUCCEEDED','PARTIAL') and a.get('observationId') and a.get('businessDate')]
    dates=sorted({a['businessDate'] for a in accepted})
    result={'batchId':batch_id,'worker':'SERVER_CAPTURE','receivedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'sourceResults':[{k:a[k] for k in ('source','chart','status','entries','errorCode','businessDate') if k in a} for a in attempts],
        'importedCharts':len(accepted),'importedEntries':sum(a.get('entries',0) for a in accepted),'sourceBusinessDates':dates,
        'businessDate':max(dates) if dates else None,'sourceFailedCount':batch.get('failedCount',0),'sourcePartialCount':batch.get('partialCount',0),
        'requiresLogin':any(a.get('errorCode')=='SOURCE_AUTH_REQUIRED' for a in attempts),'dataSaved':bool(accepted)}
    if not accepted:result['status']='SOURCE_FAILED'
    else:
        day=datetime.datetime.strptime(max(dates),'%Y-%m-%d').date()
        start=day-datetime.timedelta(days=day.weekday());end=day+datetime.timedelta(days=1)
        try:
            report=post(start,end)
            result.update(reportId=report['id'],reportRevision=report['revision'],formalCount=len(report['items']),researchLeadCount=len(report.get('researchLeads',{}).get('items',[])))
        except Exception:
            result['status']='INGESTED_REPORT_FAILED';atomic(receipt,result);atomic(deliveries/'latest.json',result);raise
        result['status']='PARTIAL' if batch.get('failedCount',0) or batch.get('partialCount',0) else 'COMPLETE'
    result['completedAt']=datetime.datetime.now(datetime.timezone.utc).isoformat()
    atomic(receipt,result);atomic(deliveries/'latest.json',result);return result

def main():
    p=argparse.ArgumentParser();p.add_argument('--root',default='/opt/iaa-radar');p.add_argument('--initialize',action='store_true');p.add_argument('--once',action='store_true');args=p.parse_args()
    root=pathlib.Path(args.root);materials=root/'shared/external-captures';deliveries=root/'shared/deliveries';deliveries.mkdir(parents=True,exist_ok=True)
    state_file=deliveries/'server-state.json';state=json.loads(state_file.read_text()) if state_file.exists() else {'done':[]}
    with (deliveries/'server-reports.lock').open('w') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        if args.initialize:
            state['done']=[p.stem for p in materials.glob('batch-*.json')];atomic(state_file,state);return
        def post(start,end):
            headers={'Content-Type':'application/json'}
            token=os.environ.get('PROFILE_ACCESS_TOKEN','')
            if token:headers['Authorization']='Bearer '+token
            request=urllib.request.Request('http://127.0.0.1:18081/api/v1/admin/reports/generate?periodStart='+str(start)+'&periodEnd='+str(end),data=b'{}',headers=headers)
            with urllib.request.urlopen(request,timeout=300) as response:return json.load(response)['data']
        while True:
            queue=sorted((p for p in materials.glob('batch-*.json') if p.stem not in state['done']),key=lambda p:p.stat().st_mtime)
            heartbeat={'worker':'SERVER_CAPTURE_REPORTS','seenAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'pendingBatches':len(queue),'state':'SYNCING' if queue else 'IDLE'}
            atomic(deliveries/'heartbeat.json',heartbeat)
            for batch_file in queue:
                try:
                    result=finalize(json.loads(batch_file.read_text(encoding='utf-8')),deliveries,post)
                    state['done'].append(batch_file.stem);state['lastBatchId']=result['batchId'];atomic(state_file,state)
                    print(json.dumps({'batchId':result['batchId'],'status':result['status'],'reportId':result.get('reportId')},ensure_ascii=True),flush=True)
                except Exception as e:
                    print(json.dumps({'batchFile':batch_file.name,'status':'FAILED','errorCode':type(e).__name__}),flush=True)
            if args.once:return
            time.sleep(60)

if __name__=='__main__':main()

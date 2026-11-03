"""Server-local validator: bounded AppBrain-only input. No caller-controlled commands or paths."""
import datetime, hashlib, importlib.util, json, os, pathlib, re, signal, sys, tempfile, urllib.request, zipfile

MAX_ARCHIVE=24_000_000
MAX_EXPANDED=32_000_000
CHARTS={'top-free','top-paid','top-grossing','top-new-free','top-new-paid'}
REGIONS={'us','gb','de','fr'}


def parse_utc(value):
    match=re.fullmatch(r'(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z',value)
    if not match: raise ValueError('CAPTURE_TIME_FORMAT')
    return datetime.datetime.strptime(match.group(1),'%Y-%m-%dT%H:%M:%S').replace(microsecond=int((match.group(2) or '').ljust(6,'0')[:6]),tzinfo=datetime.timezone.utc)


def validate(archive):
    with zipfile.ZipFile(archive) as z:
        infos=z.infolist()
        if len(infos)>41 or len({i.filename for i in infos})!=len(infos) or sum(i.file_size for i in infos)>MAX_EXPANDED:
            raise ValueError('ARCHIVE_LIMIT')
        if z.getinfo('manifest.json').file_size>65536: raise ValueError('MANIFEST_LIMIT')
        manifest=json.loads(z.read('manifest.json'))
        if not re.fullmatch('[0-9a-f-]{36}',manifest['batchId']) or not re.fullmatch('[0-9a-f]{64}',manifest['sourceBatchSha256']): raise ValueError('BATCH_INVALID')
        results=manifest['sourceResults'];materials=manifest['materials']
        if not 1<=len(results)<=20 or len(materials)>20: raise ValueError('SCOPE_LIMIT')
        scopes=set();success=[]
        for row in results:
            if row['source']!='appbrain' or row['region'] not in REGIONS or row['chart'] not in CHARTS or row['status'] not in ('FAILED','PARTIAL','SUCCEEDED'): raise ValueError('SCOPE_REJECTED')
            scope=(row['region'],row['chart'])
            if scope in scopes: raise ValueError('DUPLICATE_SCOPE')
            scopes.add(scope)
            if row['status']!='FAILED': success.append(row)
        if len(success)!=len(materials): raise ValueError('MATERIAL_COUNT')
        if manifest['sourceFailedCount']!=sum(r['status']=='FAILED' for r in results) or manifest['sourcePartialCount']!=sum(r['status']=='PARTIAL' for r in results): raise ValueError('STATUS_COUNT')
        expected={'manifest.json'}
        now=datetime.datetime.now(datetime.timezone.utc)
        for i,item in enumerate(materials):
            expected.update({str(i)+'/bundle.json',str(i)+'/raw.bin'})
            if z.getinfo(str(i)+'/bundle.json').file_size>2_000_000 or z.getinfo(str(i)+'/raw.bin').file_size>4_194_304: raise ValueError('MATERIAL_LIMIT')
            bundle=json.loads(z.read(str(i)+'/bundle.json'));request=bundle['request'];evidence=bundle['evidence'];row=success[i]
            if request['sourceKey']!='appbrain' or request['region']!=row['region'] or request['chartKey']!=row['chart'] or request['requestedTopN']!=100: raise ValueError('BUNDLE_SCOPE')
            url='https://www.appbrain.com/stats/google-play-rankings/'+request['chartKey'].replace('-','_')+'/game/'+request['region']
            if evidence['sourceUrl']!=url: raise ValueError('URL_REJECTED')
            captured=parse_utc(evidence['capturedAt'])
            if captured.tzinfo is None or not now-datetime.timedelta(hours=48)<=captured<=now+datetime.timedelta(minutes=1): raise ValueError('CAPTURE_TIME')
            if hashlib.sha256(z.read(str(i)+'/raw.bin')).hexdigest()!=item['sha256'] or item['sha256']!=evidence['sha256']: raise ValueError('HASH_MISMATCH')
        if set(z.namelist())!=expected: raise ValueError('UNEXPECTED_MEMBER')
        return manifest


def main():
    import fcntl
    # Bound both upload and import lifetime; the restricted account cannot hold a root receiver forever.
    signal.alarm(650)
    root=pathlib.Path('/opt/iaa-radar');deliveries=root/'shared/deliveries'
    with (deliveries/'overseas-receive.lock').open('w') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        signal.alarm(60)
        raw=sys.stdin.buffer.read(MAX_ARCHIVE+1)
        signal.alarm(590)
        if len(raw)>MAX_ARCHIVE: raise ValueError('UPLOAD_LIMIT')
        if raw==b'PLAN\n':
            with urllib.request.urlopen('http://127.0.0.1:18081/api/v1/market/remote-capture-plan',timeout=15) as response:
                print(json.dumps(json.load(response)))
            return
        with tempfile.TemporaryDirectory(prefix='overseas-',dir=root/'shared') as temporary:
            archive=pathlib.Path(temporary)/'upload.zip';archive.write_bytes(raw);validate(archive)
            spec=importlib.util.spec_from_file_location('receiver',root/'shared/receive-capture.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
            receipt=module.receive(root,archive)
            receipt['worker']='GITHUB_ACTIONS_OVERSEAS';module.atomic(deliveries/'overseas.json',receipt)
            print(json.dumps({k:receipt.get(k) for k in ('batchId','status','importedCharts','importedEntries','reportId')},ensure_ascii=True))


if __name__=='__main__':
    try: main()
    except Exception as error:
        print(json.dumps({'status':'FAILED','errorCode':type(error).__name__}));sys.exit(1)

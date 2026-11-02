"""Windows companion: verify local evidence, deliver through pinned SSH, receive Java/weekly acknowledgement."""
import argparse, datetime, hashlib, json, logging, os, pathlib, shlex, sys, time, zipfile
import paramiko

logging.getLogger('paramiko').setLevel(logging.CRITICAL)
def atomic(path, value):
    tmp = path.with_suffix('.tmp')
    tmp.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
    os.replace(tmp, path)

class PinnedKey(paramiko.MissingHostKeyPolicy):
    def __init__(self, expected): self.expected = expected
    def missing_host_key(self, client, hostname, key):
        if hashlib.sha256(key.asbytes()).hexdigest() != self.expected:
            raise paramiko.SSHException('HOST_KEY_MISMATCH')

def connect(config):
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(PinnedKey(config['hostKeySha256']))
    client.connect(config['host'], port=config.get('port', 22), username=config['username'],
                   password=config.get('password'), key_filename=config.get('keyFile'),
                   timeout=20, auth_timeout=20, banner_timeout=20, look_for_keys=False, allow_agent=False)
    return client

def package(batch_file, materials, destination):
    raw_batch = batch_file.read_bytes()
    batch = json.loads(raw_batch)
    manifest = {'batchId': batch['batchId'], 'sourceBatchSha256': hashlib.sha256(raw_batch).hexdigest(),
                'sourcePartialCount': batch.get('partialCount', 0), 'sourceFailedCount': batch['failedCount'], 'sourceResults': [], 'materials': []}
    with zipfile.ZipFile(destination, 'w', zipfile.ZIP_DEFLATED) as archive:
        for attempt in batch['attempts']:
            manifest['sourceResults'].append({k: attempt[k] for k in ['source','chart','status','errorCode','entries','businessDate'] if k in attempt})
            if not attempt.get('bundleLocator'): continue
            bundle_path = (materials / attempt['bundleLocator']).resolve()
            if materials.resolve() not in bundle_path.parents: raise ValueError('MATERIAL_PATH_ESCAPE')
            bundle = json.loads(bundle_path.read_text(encoding='utf-8'))
            raw_path = bundle_path.with_name('raw.bin')
            if hashlib.sha256(raw_path.read_bytes()).hexdigest() != bundle['evidence']['sha256']:
                raise ValueError('EVIDENCE_HASH_MISMATCH')
            i = len(manifest['materials'])
            manifest['materials'].append({'sha256': bundle['evidence']['sha256']})
            archive.write(bundle_path, str(i) + '/bundle.json')
            archive.write(raw_path, str(i) + '/raw.bin')
        archive.writestr('manifest.json', json.dumps(manifest))
    return manifest

def tick(config, state, state_file, selected=None):
    materials = pathlib.Path(config['materialRoot']).resolve()
    queue = [pathlib.Path(selected)] if selected else sorted(materials.glob('batch-*.json'), key=lambda p:p.stat().st_mtime)
    queue = [p for p in queue if selected or p.stem not in state['done']]
    with connect(config) as client:
        root = config.get('remoteRoot', '/opt/iaa-radar')
        # Receiver and directories are installed by deployment. No cookies, browser profile or .env are sent.
        with client.open_sftp() as sftp:
            heartbeat = {'worker':'WINDOWS_CAPTURE_DELIVERY','seenAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'pendingBatches':len(queue),'state':'SYNCING' if queue else 'IDLE'}
            target=root+'/shared/deliveries/heartbeat.json'
            with sftp.file(target+'.tmp','w') as f:f.write(json.dumps(heartbeat))
            sftp.chmod(target+'.tmp',0o644)
            sftp.posix_rename(target+'.tmp',target)
        results=[]
        for batch in queue:
            archive=state_file.parent/(batch.stem+'.zip')
            manifest=package(batch,materials,archive)
            remote=root+'/shared/capture-upload/'+manifest['batchId']+'.zip'
            with client.open_sftp() as sftp:sftp.put(str(archive),remote)
            command='python3 '+shlex.quote(root+'/shared/receive-capture.py')+' '+shlex.quote(root)+' '+shlex.quote(remote)
            _,stdout,stderr=client.exec_command(command,timeout=660)
            result=json.loads(stdout.read().decode('utf-8'))
            code=stdout.channel.recv_exit_status()
            if code or result.get('status')=='FAILED':raise RuntimeError('DELIVERY_NOT_ACKNOWLEDGED')
            results.append(result)
            if batch.stem not in state['done']:state['done'].append(batch.stem)
            state['lastResult']=result;atomic(state_file,state)
            archive.unlink()
        return {'status':'OK','delivered':len(results),'results':results}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--config',required=True);parser.add_argument('--worker',action='store_true');parser.add_argument('--batch');parser.add_argument('--initialize',action='store_true');args=parser.parse_args()
    config_path=pathlib.Path(args.config).resolve();config=json.loads(config_path.read_text(encoding='utf-8-sig'))
    state_file=config_path.with_name('capture-sync-state.json');log=config_path.with_name('capture-sync.log')
    state=json.loads(state_file.read_text(encoding='utf-8')) if state_file.exists() else {'done':[]}
    if args.initialize:
        state['done']=[p.stem for p in pathlib.Path(config['materialRoot']).glob('batch-*.json')];atomic(state_file,state);print('SYNC_BASELINE_SAVED');return
    # Per-config process lock prevents duplicate startup workers and concurrent one-shot sends.
    lock=config_path.with_name('capture-sync.lock').open('a+b');lock.seek(0);lock.write(b'1');lock.flush();lock.seek(0)
    import msvcrt
    try:msvcrt.locking(lock.fileno(),msvcrt.LK_NBLCK,1)
    except OSError:raise RuntimeError('SYNC_WORKER_ALREADY_RUNNING')
    while True:
        try:result=tick(config,state,state_file,args.batch)
        except Exception as error:result={'status':'FAILED','errorCode':type(error).__name__}
        event={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'result':result}
        with log.open('a',encoding='utf-8') as f:f.write(json.dumps(event,ensure_ascii=True)+'\n')
        if not args.worker:
            print(json.dumps(result,ensure_ascii=True));return 0 if result['status']=='OK' else 1
        time.sleep(60)

if __name__=='__main__':
    try:sys.exit(main())
    except Exception as error:print(json.dumps({'status':'FAILED','errorCode':type(error).__name__}));sys.exit(1)

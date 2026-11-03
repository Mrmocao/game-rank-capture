"""Ephemeral cloud collector. No DB credentials, browser session or personal-PC dependency."""
import argparse, datetime, hashlib, json, os, pathlib, re, subprocess, tempfile, time, urllib.request, uuid, zipfile

CHARTS = ('top-free', 'top-paid', 'top-grossing', 'top-new-free', 'top-new-paid')
REGIONS = ('us', 'gb', 'de', 'fr')


def enabled_regions(base):
    with urllib.request.urlopen(base.rstrip('/') + '/api/v1/market/remote-capture-plan', timeout=30) as response:
        regions = json.load(response)['data']['regions']
    return sorted(set(regions).intersection(REGIONS))


def collect(jar, root, regions):
    batch = {'batchId': str(uuid.uuid4()), 'attempts': [], 'failedCount': 0, 'partialCount': 0}
    deadline = time.monotonic() + 750
    for region in regions:
        for chart in CHARTS:
            attempt = {'source': 'appbrain', 'region': region, 'chart': chart}
            try:
                if time.monotonic() > deadline:
                    raise RuntimeError('BATCH_DEADLINE')
                result = subprocess.run(['java', '-Xmx128m', '-jar', str(jar), '--source', 'appbrain', '--region', region,
                    '--chart', chart, '--top', '100', '--output', str(root)], capture_output=True, text=True, timeout=110)
                if result.returncode:
                    code = re.search(r'^([A-Z][A-Z0-9_]+):', result.stderr, re.M)
                    raise RuntimeError(code.group(1) if code else 'JAVA_CAPTURE_FAILED')
                folder = pathlib.Path(result.stdout.strip().splitlines()[-1]).resolve()
                if root.resolve() not in folder.parents:
                    raise RuntimeError('MATERIAL_PATH_REJECTED')
                bundle = json.loads((folder / 'bundle.json').read_text(encoding='utf-8'))
                attempt.update(status='SUCCEEDED' if bundle['coverage']['status'] == 'REQUESTED_RANGE_COMPLETE' else 'PARTIAL',
                    entries=bundle['coverage']['actualCount'], businessDate=bundle['observation']['businessDate'],
                    bundleLocator=str((folder / 'bundle.json').relative_to(root.resolve())))
                if attempt['status'] == 'PARTIAL': batch['partialCount'] += 1
            except Exception as error:
                code = str(error) if isinstance(error, RuntimeError) else type(error).__name__
                attempt.update(status='FAILED', errorCode=code if re.fullmatch('[A-Za-z0-9_]+', code) else 'CAPTURE_FAILED')
                batch['failedCount'] += 1
            batch['attempts'].append(attempt)
            print(json.dumps({k: v for k, v in attempt.items() if k != 'bundleLocator'}), flush=True)
    return batch


def package(batch, root, archive):
    payload = json.dumps(batch, sort_keys=True).encode()
    manifest = {'batchId': batch['batchId'], 'sourceBatchSha256': hashlib.sha256(payload).hexdigest(),
        'sourcePartialCount': batch['partialCount'], 'sourceFailedCount': batch['failedCount'],
        'sourceResults': [{k:v for k,v in a.items() if k != 'bundleLocator'} for a in batch['attempts']], 'materials': []}
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as z:
        for a in batch['attempts']:
            if not a.get('bundleLocator'): continue
            file = (root / a['bundleLocator']).resolve()
            if root.resolve() not in file.parents: raise ValueError('MATERIAL_PATH_REJECTED')
            bundle = json.loads(file.read_text(encoding='utf-8')); raw = file.with_name('raw.bin').read_bytes()
            if hashlib.sha256(raw).hexdigest() != bundle['evidence']['sha256']: raise ValueError('HASH_MISMATCH')
            i = len(manifest['materials']); manifest['materials'].append({'sha256': bundle['evidence']['sha256']})
            z.write(file, str(i)+'/bundle.json'); z.writestr(str(i)+'/raw.bin', raw)
        z.writestr('manifest.json', json.dumps(manifest))
    return manifest


def deliver(archive, root):
    key = os.environ.get('OVERSEAS_CAPTURE_SSH_KEY', '')
    known = os.environ.get('OVERSEAS_CAPTURE_KNOWN_HOSTS', '')
    if not key or not known: raise RuntimeError('DELIVERY_KEY_NOT_CONFIGURED')
    private = root / 'delivery-key'; private.write_text(key.strip()+'\n'); private.chmod(0o600)
    hosts = root / 'known-hosts'; hosts.write_text(known.strip()+'\n'); hosts.chmod(0o600)
    try:
        with archive.open('rb') as payload:
            result = subprocess.run(['ssh', '-i', str(private), '-o', 'IdentitiesOnly=yes', '-o', 'StrictHostKeyChecking=yes',
                '-o', 'UserKnownHostsFile='+str(hosts), '-o', 'ConnectTimeout=15', '-o', 'ServerAliveInterval=15',
                '-T', 'iaa-overseas@14.103.231.1'], stdin=payload, capture_output=True, timeout=600)
        if result.returncode: raise RuntimeError('DELIVERY_NOT_ACKNOWLEDGED')
        receipt = json.loads(result.stdout.decode('utf-8'))
        print(json.dumps({k: receipt.get(k) for k in ('batchId','status','importedCharts','importedEntries','reportId')}, ensure_ascii=True))
        return receipt
    finally:
        private.unlink(missing_ok=True)


def main():
    p=argparse.ArgumentParser();p.add_argument('--jar',type=pathlib.Path,required=True);p.add_argument('--base',default='http://14.103.231.1:18080');p.add_argument('--collect-only',action='store_true');args=p.parse_args()
    regions=enabled_regions(args.base)
    if not regions:
        print('NO_ENABLED_OVERSEAS_TARGETS');return
    with tempfile.TemporaryDirectory(prefix='iaa-overseas-') as temporary:
        root=pathlib.Path(temporary);batch=collect(args.jar.resolve(),root,regions);archive=root/'delivery.zip';package(batch,root,archive)
        if not args.collect_only: deliver(archive,root)
        if batch['failedCount']: raise RuntimeError('PARTIAL_SOURCE_FAILURE')


if __name__=='__main__':
    try: main()
    except Exception as error:
        print('OVERSEAS_CAPTURE_FAILED:'+type(error).__name__+':'+str(error) if isinstance(error,RuntimeError) else 'OVERSEAS_CAPTURE_FAILED:'+type(error).__name__)
        raise SystemExit(1)

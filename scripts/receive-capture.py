"""Server-side SSH receiver. Transport contains ranking evidence only; Java owns parsing and JDBC writes."""
import datetime, fcntl, hashlib, json, os, pathlib, pwd, re, subprocess, sys, urllib.request, zipfile

def atomic(path, value):
    tmp = path.with_suffix('.tmp')
    tmp.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
    os.chmod(str(tmp), 0o644)
    os.replace(str(tmp), str(path))

def receive(root, archive):
    deliveries = root / 'shared' / 'deliveries'
    deliveries.mkdir(parents=True, exist_ok=True)
    with (deliveries / 'receive.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        return receive_locked(root, archive, deliveries)

def receive_locked(root, archive, deliveries):
    with zipfile.ZipFile(str(archive)) as z:
        manifest = json.loads(z.read('manifest.json').decode('utf-8'))
        batch = manifest['batchId']
        if not re.fullmatch(r'[0-9a-f-]{36}', batch):
            raise ValueError('INVALID_BATCH_ID')
        receipt_file = deliveries / (batch + '.json')
        existing = json.loads(receipt_file.read_text()) if receipt_file.exists() else None
        if existing and existing['sourceBatchSha256'] != manifest['sourceBatchSha256']:
            raise ValueError('BATCH_CONFLICT')
        if existing and existing['status'] in ('COMPLETE', 'PARTIAL', 'SOURCE_FAILED'):
            return dict(existing, reused=True)
        intake = root / 'shared' / 'capture-intake' / batch
        intake.mkdir(parents=True, exist_ok=True)
        expected = {'manifest.json'}
        for i, material in enumerate(manifest['materials']):
            expected.update({str(i) + '/bundle.json', str(i) + '/raw.bin'})
        if set(z.namelist()) != expected or sum(x.file_size for x in z.infolist()) > 100_000_000:
            raise ValueError('INVALID_ARCHIVE')
        dates = []
        for i, material in enumerate(manifest['materials']):
            raw = z.read(str(i) + '/raw.bin')
            bundle_bytes = z.read(str(i) + '/bundle.json')
            bundle = json.loads(bundle_bytes.decode('utf-8'))
            if len(raw) > 4_194_304 or hashlib.sha256(raw).hexdigest() != bundle['evidence']['sha256'] or bundle['evidence']['sha256'] != material['sha256']:
                raise ValueError('EVIDENCE_HASH_MISMATCH')
            folder = intake / str(i)
            folder.mkdir(exist_ok=True)
            (folder / 'raw.bin').write_bytes(raw)
            (folder / 'bundle.json').write_bytes(bundle_bytes)
            dates.append(bundle['observation']['businessDate'])
    receipt = existing or {
        'batchId': batch, 'sourceBatchSha256': manifest['sourceBatchSha256'],
        'sourcePartialCount': manifest.get('sourcePartialCount', 0), 'sourceResults': manifest['sourceResults'], 'sourceFailedCount': manifest['sourceFailedCount'],
        'businessDate': max(dates) if dates else None, 'sourceBusinessDates': sorted(set(dates)), 'status': 'RECEIVING',
        'receivedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'reused': False}
    output = root / 'shared' / 'external-captures'
    if dates and not receipt.get('dataSaved'):
        command = ['java', '-Xmx256m', '-Dloader.main=com.youkia.iaa.collection.capture.RankingCaptureCommand',
                   '-cp', str(root / 'current' / 'iaa-radar-api-0.1.0-SNAPSHOT.jar'),
                   'org.springframework.boot.loader.launch.PropertiesLauncher', '--run', '--replay-dir', str(intake),
                   '--env-file', '/etc/iaa-radar/app.env', '--output', str(output)]
        # Environment never travels from the client. The Java command reads the server-owned JDBC file.
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, universal_newlines=True, timeout=300)
        (intake / 'replay.log').write_text(result.stdout, encoding='utf-8')
        if result.returncode:
            raise RuntimeError('JAVA_REPLAY_FAILED')
        match = re.search(r'BATCH_REPORT=(.+)', result.stdout)
        if not match: raise RuntimeError('JAVA_REPORT_MISSING')
        report_path = pathlib.Path(match.group(1).strip()).resolve()
        if output.resolve() not in report_path.parents:
            raise ValueError('INVALID_REPORT_PATH')
        report = json.loads(report_path.read_text())
        if report.get('failedCount', 0) or len(report['attempts']) != len(dates):
            raise RuntimeError('JAVA_IMPORT_INCOMPLETE')
        receipt.update(dataSaved=True, serverBatchId=report['batchId'], importedCharts=len(report['attempts']),
                       importedEntries=sum(x.get('entries', 0) for x in report['attempts']))
        account = pwd.getpwnam('iaa-radar')
        for item in report['attempts']:
            if item.get('bundleLocator'):
                folder = (output / item['bundleLocator']).parent
                for p in [folder.parent, folder] + list(folder.iterdir()):
                    os.chown(str(p), account.pw_uid, account.pw_gid)
        receipt['status'] = 'INGESTED'
        atomic(receipt_file, receipt)
    if dates:
        day = datetime.date.fromisoformat(max(dates)) if hasattr(datetime.date, 'fromisoformat') else datetime.datetime.strptime(max(dates), '%Y-%m-%d').date()
        start, end = day - datetime.timedelta(days=day.weekday()), day + datetime.timedelta(days=1)
        request = urllib.request.Request('http://127.0.0.1:18081/api/v1/admin/reports/generate?periodStart=' + str(start) + '&periodEnd=' + str(end), data=b'{}', headers={'Content-Type': 'application/json'})
        try:
            with urllib.request.urlopen(request, timeout=300) as response:
                report = json.load(response)['data']
            receipt.update(reportId=report['id'], reportRevision=report['revision'], formalCount=len(report['items']),
                           researchLeadCount=len(report.get('researchLeads', {}).get('items', [])))
        except Exception:
            receipt['status'] = 'INGESTED_REPORT_FAILED'
            atomic(receipt_file, receipt)
            atomic(deliveries / 'latest.json', receipt)
            raise RuntimeError('REPORT_GENERATION_FAILED')
    receipt['status'] = 'SOURCE_FAILED' if not dates else 'PARTIAL' if manifest['sourceFailedCount'] or manifest.get('sourcePartialCount', 0) else 'COMPLETE'
    receipt['completedAt'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    atomic(receipt_file, receipt)
    atomic(deliveries / 'latest.json', receipt)
    return receipt

if __name__ == '__main__':
    try:
        root, archive = map(pathlib.Path, sys.argv[1:])
        if not root.is_absolute() or not archive.is_file():
            raise ValueError('INVALID_RECEIVER_PATH')
        print(json.dumps(receive(root, archive), ensure_ascii=True))
    except Exception as error:
        # No command output, environment, server credentials or supplier response in the wire error.
        print(json.dumps({'status': 'FAILED', 'errorCode': type(error).__name__}))
        sys.exit(1)

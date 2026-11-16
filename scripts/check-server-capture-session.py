"""Read current server capture state over pinned SSH. Never use the local development database."""
import argparse,importlib.util,json,pathlib,sys

def main():
    p=argparse.ArgumentParser();p.add_argument('--config',default='data/deployment/capture-sync.json');args=p.parse_args()
    spec=importlib.util.spec_from_file_location('transport',pathlib.Path(__file__).with_name('sync-capture-server.py'))
    transport=importlib.util.module_from_spec(spec);spec.loader.exec_module(transport)
    config=json.loads(pathlib.Path(args.config).read_text(encoding='utf-8-sig'))
    command="""python3 - <<'PY'
import json,pathlib,urllib.request
env=dict(line.split('=',1) for line in pathlib.Path('/etc/iaa-radar/app.env').read_text().splitlines() if '=' in line and not line.startswith('#'))
req=urllib.request.Request('http://127.0.0.1:18081/api/v1/admin/capture-login/status',headers={'Authorization':'Bearer '+env.get('PROFILE_ACCESS_TOKEN','')})
with urllib.request.urlopen(req,timeout=20) as r:
 print(json.dumps(json.load(r)['data']['source'],ensure_ascii=True))
PY"""
    try:
        with transport.connect(config) as client:
            _,out,err=client.exec_command(command,timeout=30)
            body=out.read();exit_code=out.channel.recv_exit_status()
            if exit_code:raise RuntimeError('SERVER_STATUS_UNAVAILABLE')
            value=json.loads(body)
            print(json.dumps(value,ensure_ascii=True))
    except Exception:
        print('{"monitorError":"SERVER_STATUS_UNAVAILABLE"}');sys.exit(1)

if __name__=='__main__':main()

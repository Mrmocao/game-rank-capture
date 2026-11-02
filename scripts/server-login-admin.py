"""Fixed maintenance commands over a pinned SSH connection; never transports browser sessions."""
import argparse,importlib.util,json,pathlib,sys
spec=importlib.util.spec_from_file_location('capture_transport',pathlib.Path(__file__).with_name('sync-capture-server.py'))
transport=importlib.util.module_from_spec(spec);spec.loader.exec_module(transport)

def main():
    p=argparse.ArgumentParser();p.add_argument('--config',required=True);p.add_argument('--action',choices=['start','finish','status'],required=True);args=p.parse_args()
    config=json.loads(pathlib.Path(args.config).read_text(encoding='utf-8-sig'))
    with transport.connect(config) as client:
        _,out,err=client.exec_command('bash /opt/iaa-radar/shared/server-capture-login.sh '+args.action,timeout=600)
        output=out.read().decode('utf-8','replace');code=out.channel.recv_exit_status()
        # Do not relay source/browser or authentication output; these fixed helper markers are safe.
        for line in output.splitlines():
            if line.startswith(('SOURCE_LOGIN_READY:','SESSION_SAVED:','SOURCE_VERIFICATION_')):print(line)
        if code:print('MAINTENANCE_FAILED: check the server service journal.');return code
    return 0

if __name__=='__main__':
    try:sys.exit(main())
    except Exception as e:print('MAINTENANCE_FAILED: '+type(e).__name__);sys.exit(1)

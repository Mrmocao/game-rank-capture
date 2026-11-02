"""Temporary localhost-only SSH viewer for manual source login; never a capture worker."""
import argparse, hashlib, json, pathlib, select, socketserver, time, threading
import paramiko

class PinnedKey(paramiko.MissingHostKeyPolicy):
    def __init__(self, expected): self.expected=expected
    def missing_host_key(self,client,hostname,key):
        if hashlib.sha256(key.asbytes()).hexdigest()!=self.expected:raise paramiko.SSHException('HOST_KEY_MISMATCH')

def main():
    p=argparse.ArgumentParser();p.add_argument('--config',required=True);p.add_argument('--port',type=int,default=29881);args=p.parse_args()
    c=json.loads(pathlib.Path(args.config).read_text(encoding='utf-8-sig'))
    with paramiko.SSHClient() as client:
        client.set_missing_host_key_policy(PinnedKey(c['hostKeySha256']))
        client.connect(c['host'],port=c.get('port',22),username=c['username'],password=c.get('password'),key_filename=c.get('keyFile'),look_for_keys=False,allow_agent=False,timeout=20)
        client.get_transport().set_keepalive(30)
        class Handler(socketserver.BaseRequestHandler):
            def handle(self):
                channel=client.get_transport().open_channel('direct-tcpip',('127.0.0.1',6081),self.client_address)
                try:
                    while True:
                        readable,_,_=select.select([self.request,channel],[],[],30)
                        for src in readable:
                            data=src.recv(65536)
                            if not data:return
                            (channel if src is self.request else self.request).sendall(data)
                finally:channel.close()
        class Server(socketserver.ThreadingTCPServer):allow_reuse_address=True;daemon_threads=True
        with Server(('127.0.0.1',args.port),Handler) as server:
            threading.Timer(1800,server.shutdown).start()
            server.serve_forever()

if __name__=='__main__':main()

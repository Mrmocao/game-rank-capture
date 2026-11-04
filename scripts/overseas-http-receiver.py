"""Loopback-only HTTP bridge behind a pinned TLS endpoint. No shell or database access for callers."""
import hmac, http.server, json, os, pathlib, subprocess, urllib.request, zlib
ROOT=pathlib.Path('/opt/iaa-radar')
TOKEN=pathlib.Path('/etc/iaa-radar/overseas-token').read_text().strip()
LIMIT=24_000_000
def metric_payload(raw, encoding):
    if encoding == 'gzip':
        decoder=zlib.decompressobj(16+zlib.MAX_WBITS)
        data=decoder.decompress(raw,5_400_001)
        if len(data)>5_400_000 or not decoder.eof or decoder.unused_data:raise ValueError('METRIC_DECOMPRESSION_LIMIT')
        return data
    if encoding:raise ValueError('CONTENT_ENCODING_UNSUPPORTED')
    return raw
class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def respond(self,code,data):
        self.send_response(code);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
    def authorized(self):
        if not hmac.compare_digest(self.headers.get('Authorization',''),'Bearer '+TOKEN):
            self.respond(401,b'{"error":"UNAUTHORIZED"}');return False
        return True
    def do_GET(self):
        if not self.authorized():return
        if self.path!='/overseas/plan':self.respond(404,b'{}');return
        try:
            with urllib.request.urlopen('http://127.0.0.1:18081/api/v1/market/remote-capture-plan',timeout=15) as r:data=r.read(65536)
            self.respond(200,data)
        except Exception:self.respond(503,b'{"error":"PLAN_UNAVAILABLE"}')
    def do_POST(self):
        if not self.authorized():return
        if self.path in ('/overseas/metric-claim','/overseas/metric-result'):
            try:
                size=int(self.headers.get('Content-Length','0'))
                if not 1<=size<=5_400_000:self.respond(413,b'{"error":"METRIC_UPLOAD_LIMIT"}');return
                self.connection.settimeout(30);payload=self.rfile.read(size)
                if len(payload)!=size:raise ValueError('TRUNCATED')
                payload=metric_payload(payload,self.headers.get('Content-Encoding',''))
                env=dict(line.split('=',1) for line in pathlib.Path('/etc/iaa-radar/app.env').read_text().splitlines() if '=' in line and not line.startswith('#'))
                headers={'Content-Type':'application/json','X-Capture-Metrics-Token':env.get('CAPTURE_REMOTE_METRIC_TOKEN','')}
                if env.get('PROFILE_ACCESS_TOKEN',''):headers['Authorization']='Bearer '+env['PROFILE_ACCESS_TOKEN']
                suffix='claim' if self.path.endswith('-claim') else 'result'
                request=urllib.request.Request('http://127.0.0.1:18081/api/v1/admin/capture-workspace/metrics/remote/'+suffix,data=payload,headers=headers)
                with urllib.request.urlopen(request,timeout=45) as response:data=response.read(65536)
                self.respond(200,data)
            except Exception:self.respond(503,b'{"error":"METRIC_DELIVERY_FAILED"}')
            return
        if self.path!='/overseas/deliver':self.respond(404,b'{}');return
        try:
            size=int(self.headers.get('Content-Length','0'))
            if not 1<=size<=LIMIT:self.respond(413,b'{"error":"UPLOAD_LIMIT"}');return
            self.connection.settimeout(60);raw=self.rfile.read(size)
            if len(raw)!=size:raise ValueError('TRUNCATED')
            result=subprocess.run(['/usr/bin/python3','-I',str(ROOT/'shared/receive-overseas.py')],input=raw,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=650)
            # Receiver stdout is deliberately a bounded, credential-free summary.
            response=json.loads(result.stdout.decode('utf-8'));self.respond(200 if result.returncode==0 else 422,json.dumps(response).encode())
        except Exception:self.respond(503,b'{"error":"DELIVERY_FAILED"}')
if __name__=='__main__':http.server.HTTPServer(('127.0.0.1',18642),Handler).serve_forever()

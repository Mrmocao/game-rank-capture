"""Public Google Play details only. No database, paid API, login, or product writes."""
import argparse, base64, datetime, json, os, re, ssl, time, urllib.request

MAX_BYTES = 4_000_000
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError('SOURCE_REDIRECT_REJECTED')

def exchange(path, payload):
    token=os.environ.get('OVERSEAS_CAPTURE_TOKEN','')
    certificate=os.environ.get('OVERSEAS_CAPTURE_CA_CERT','')
    if not token or not certificate: raise RuntimeError('DELIVERY_AUTH_NOT_CONFIGURED')
    context=ssl.create_default_context(cadata=certificate)
    request=urllib.request.Request('https://14.103.231.1/overseas/metric-'+path,
        data=json.dumps(payload).encode(),headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
    with urllib.request.urlopen(request,context=context,timeout=45) as response:
        return json.loads(response.read(65536))['data']

def fetch(job):
    url=job['url']
    if not re.fullmatch(r'https://play\.google\.com/store/apps/details\?id=[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+&hl=en&gl=(US|GB|DE|FR)',url):
        raise ValueError('URL_SCOPE_REJECTED')
    request=urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0 (compatible; YoukiaCapture/1.0)','Accept':'text/html'})
    with urllib.request.build_opener(NoRedirect()).open(request,timeout=30) as response:
        if response.status!=200 or 'text/html' not in response.headers.get('Content-Type',''):raise ValueError('SOURCE_NOT_HTML')
        raw=response.read(MAX_BYTES+1)
    if not raw or len(raw)>MAX_BYTES: raise ValueError('SOURCE_SIZE_LIMIT')
    raw.decode('utf-8')
    return raw

def collect(limit):
    success=failed=0;deadline=time.monotonic()+1050
    for _ in range(limit):
        if time.monotonic()>deadline:break
        job=exchange('claim',{})
        if not job.get('available'):break
        payload={'jobId':job['jobId'],'leaseToken':job['leaseToken']}
        try:
            raw=fetch(job);payload.update(rawBase64=base64.b64encode(raw).decode(),capturedAt=datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00','Z'))
        except Exception as error:payload['errorCode']=type(error).__name__
        result=exchange('result',payload)
        if result.get('status')=='SUCCEEDED':success+=1
        else:failed+=1
        print(json.dumps({'jobId':job['jobId'],'status':result.get('status'),'errorCode':result.get('errorCode')}),flush=True)
        time.sleep(5)
    print(json.dumps({'succeeded':success,'failed':failed}))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--limit',type=int,default=100);args=parser.parse_args()
    if not 1<=args.limit<=100:parser.error('limit must be 1..100')
    collect(args.limit)

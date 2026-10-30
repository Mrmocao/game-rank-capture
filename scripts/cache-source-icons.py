import urllib.request,urllib.parse,json,hashlib,datetime,concurrent.futures,io,pathlib
from PIL import Image
out=pathlib.Path('validation/icon-cache');out.mkdir(exist_ok=True)
with urllib.request.urlopen('http://14.103.231.1:18080/api/v1/admin/sources/media/index',timeout=30) as r:urls=json.load(r)['data']
def one(url):
 key=hashlib.sha256(url.encode()).hexdigest();result={'sourceUrl':url,'key':key,'fetchedAt':datetime.datetime.now(datetime.timezone.utc).isoformat()}
 try:
  u=urllib.parse.urlparse(url)
  assert u.scheme=='https' and u.hostname and (u.hostname.endswith('.mzstatic.com') or u.hostname.endswith('.googleusercontent.com') or u.hostname.endswith('.aliyuncs.com'))
  with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0'}),timeout=18) as r:raw=r.read(2_000_001);result['httpStatus']=r.status
  assert len(raw)<=2_000_000
  with Image.open(io.BytesIO(raw)) as im:
   assert im.width*im.height<16_000_000
   im.thumbnail((120,120));im.convert('RGBA').save(out/(key+'.png'),'PNG')
  result['sourceSha256']=hashlib.sha256(raw).hexdigest();result['storedSha256']=hashlib.sha256((out/(key+'.png')).read_bytes()).hexdigest();result['status']='CACHED'
 except Exception as e:result['status']='FAILED';result['error']=str(e)[:150]
 return result
with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:results=list(pool.map(one,urls))
(out/'manifest.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf8')
print(json.dumps({'requested':len(results),'cached':sum(r['status']=='CACHED' for r in results),'failed':[r for r in results if r['status']=='FAILED']},ensure_ascii=True))

#!/usr/bin/env python3
"""Official digest-verified fallback when the deployment host cannot reach Docker Hub.
Requires Python 3 and requests. Contains no credentials; do not disable TLS verification.
Generated archives are operational artifacts and must not be committed.
"""
import requests,pathlib,hashlib,concurrent.futures,json,tarfile,io,time
import argparse
parser=argparse.ArgumentParser(description='Download pinned Redis/RabbitMQ from official Docker Hub and create verified Docker archives.')
parser.add_argument('--output',default='data/runtime/firecrawl-images')
root=pathlib.Path(parser.parse_args().output);root.mkdir(parents=True,exist_ok=True)
items=[('redis','registry-1.docker.io','library/redis','sha256:8da19c7399dca900c6822a1cf30f9d2285585e2c4b457ff4095b0f4500054d3f'),('rabbitmq','registry-1.docker.io','library/rabbitmq','sha256:d7af1c87c5f1eda13fcfca06db452bf3aeab6619fc3358b68535c0c02c4e52bc')]
accept='application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json'
def process(item):
 name,host,repo,ref=item;url='https://'+host+'/v2/'+repo
 auth='https://ghcr.io/token?service=ghcr.io' if host=='ghcr.io' else 'https://auth.docker.io/token?service=registry.docker.io'
 token=requests.get(auth+'&scope=repository:'+repo+':pull',timeout=30).json()['token'];h={'Authorization':'Bearer '+token,'Accept':accept}
 def manifest(digest):
  r=requests.get(url+'/manifests/'+digest,headers=h,timeout=60);r.raise_for_status();assert hashlib.sha256(r.content).hexdigest()==digest.split(':')[1];return r.json()
 j=manifest(ref)
 if 'manifests' in j:ref=next(m['digest'] for m in j['manifests'] if m['platform']['os']=='linux' and m['platform']['architecture']=='amd64');j=manifest(ref)
 def blob(b):
  d=b['digest'].split(':')[1];p=root/d
  if p.exists() and hashlib.sha256(p.read_bytes()).hexdigest()==d:return p
  for attempt in range(4):
   try:
    r=requests.get(url+'/blobs/'+b['digest'],headers=h,timeout=(30,90),stream=True);r.raise_for_status();sha=hashlib.sha256()
    with p.with_suffix('.'+name+'.partial').open('wb') as f:
     for chunk in r.iter_content(1024*1024):f.write(chunk);sha.update(chunk)
    assert sha.hexdigest()==d;p.with_suffix('.'+name+'.partial').replace(p);print(name,'verified layer',round(b['size']/1e6,1),'MB',flush=True);return p
   except Exception:
    if attempt==3:raise
 config=blob(j['config'])
 with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:list(pool.map(blob,j['layers']))
 tar=root/(name+'.tar')
 with tarfile.open(tar,'w') as t:
  t.add(config,arcname=config.name+'.json')
  layers=[]
  for layer in j['layers']:
   d=layer['digest'].split(':')[1];entry=d+'/layer.tar';layers.append(entry);t.add(root/d,arcname=entry)
  data=json.dumps([{'Config':config.name+'.json','RepoTags':['iaa-firecrawl-verified/'+name+':20261104'],'Layers':layers}]).encode();i=tarfile.TarInfo('manifest.json');i.size=len(data);t.addfile(i,io.BytesIO(data))
 result={'service':name,'manifest':ref,'imageId':j['config']['digest'],'archive':str(tar),'archiveSha256':hashlib.sha256(tar.read_bytes()).hexdigest()};(root/(name+'.json')).write_text(json.dumps(result));print('READY',name,result['imageId'],flush=True)
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:list(pool.map(process,items))

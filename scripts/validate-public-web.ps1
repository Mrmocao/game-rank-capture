param([string]$BaseUrl='http://localhost:8080',[string[]]$Countries=@('us'))
$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue'
$root=Split-Path $PSScriptRoot -Parent;$out=Join-Path $root 'validation/source-research';New-Item -ItemType Directory -Force $out|Out-Null
$rows=@()
foreach($country in $Countries){
  $url="https://www.appbrain.com/stats/google-play-rankings/top_free/game/$country"
  $resp=Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 40
  if($resp.StatusCode -ne 200){throw "AppBrain HTTP $($resp.StatusCode)"}
  $file=Join-Path $out "appbrain-$country.html";[IO.File]::WriteAllText($file,$resp.Content,[Text.UTF8Encoding]::new($false))
  $hash=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
  $result=Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/admin/sources/appbrain/collect-material?country=$country" -ContentType 'text/html; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($resp.Content)) -TimeoutSec 180
  $again=Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/admin/sources/appbrain/collect-material?country=$country" -ContentType 'text/html; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($resp.Content)) -TimeoutSec 60
  if($result.data.rawSha256 -ne $hash -or !$again.data.reused -or $again.data.snapshotId -ne $result.data.snapshotId){throw 'AppBrain hash/idempotency failed'}
  $rows += [ordered]@{country=$country;url=$url;httpStatus=[int]$resp.StatusCode;sourceUpdated=$null;rawFile=$file;rawSha256=$hash;result=$result.data;repeat=$again.data}
}
$rows|ConvertTo-Json -Depth 12|Set-Content -Encoding utf8 (Join-Path $out 'appbrain-results.json')
$rows|ForEach-Object{"country=$($_.country) entries=$($_.result.entries) covered=$($_.result.coveredTopN) completeness=$($_.result.completeness) snapshot=$($_.result.snapshotId) reused=$($_.repeat.reused)"}

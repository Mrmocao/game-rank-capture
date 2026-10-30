param([string]$BaseUrl='http://localhost:8080',[string[]]$Countries=@('cn','us'))
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$root=Split-Path $PSScriptRoot -Parent
$out=Join-Path $root 'validation'
New-Item -ItemType Directory -Force $out | Out-Null
$records=@()
foreach($country in $Countries){
    $url="https://itunes.apple.com/$country/rss/topfreeapplications/limit=100/genre=6014/json"
    $response=$null
    for($attempt=1;$attempt -le 3;$attempt++){
        try{$response=Invoke-WebRequest -Uri $url -TimeoutSec 30;break}
        catch{if($attempt -eq 3){throw};Start-Sleep -Seconds $attempt}
    }
    $raw=$response.Content
    $parsed=$raw | ConvertFrom-Json
    if(@($parsed.feed.entry).Count -eq 0){throw 'No source entries'}
    $file=Join-Path $out "apple-games-$country.json"
    [IO.File]::WriteAllText($file,$raw,[Text.UTF8Encoding]::new($false))
    $hash=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
    $uri="$BaseUrl/api/v1/admin/sources/apple-games/collect-material?country=$country&limit=100"
    $first=(Invoke-RestMethod -Method Post -Uri $uri -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($raw)) -TimeoutSec 180).data
    $again=(Invoke-RestMethod -Method Post -Uri $uri -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($raw)) -TimeoutSec 60).data
    if($first.snapshotId -ne $again.snapshotId -or !$again.reused -or $hash -ne $first.rawSha256){throw 'Idempotency/hash verification failed'}
    $record=[ordered]@{country=$country;url=$url;httpStatus=[int]$response.StatusCode;fetchedAt=[DateTimeOffset]::UtcNow.ToString('o');sourceUpdated=$parsed.feed.updated.label;rawFile=$file;rawSha256=$hash;result=$first;repeat=$again}
    $records+=$record
    $records | ConvertTo-Json -Depth 12 | Set-Content -Encoding utf8 (Join-Path $out 'collection-results.json')
    Write-Output "country=$country entries=$($first.entries) snapshot=$($first.snapshotId) completeness=$($first.completeness) repeated=$($again.reused)"
}

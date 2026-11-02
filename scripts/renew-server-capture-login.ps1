param([string]$Config='data/deployment/capture-sync.json')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$configPath=(Resolve-Path -LiteralPath (Join-Path $projectRoot $Config)).Path
$python=(Get-Command python -ErrorAction Stop).Source
$pythonw=Join-Path (Split-Path $python -Parent) 'pythonw.exe'
$admin=Join-Path $PSScriptRoot 'server-login-admin.py'
$tunnelScript=Join-Path $PSScriptRoot 'server-login-tunnel.py'
& $python $admin --config $configPath --action start
if($LASTEXITCODE -ne 0){throw 'Cannot open the server login session.'}
$tunnel=$null
try {
  $arguments='"{0}" --config "{1}"' -f $tunnelScript,$configPath
  $tunnel=Start-Process -FilePath $pythonw -ArgumentList $arguments -WindowStyle Hidden -PassThru
  $ready=$false
  for($attempt=0;$attempt -lt 20;$attempt++){
    try{Invoke-WebRequest -Uri 'http://127.0.0.1:29881/vnc.html' -TimeoutSec 2 -UseBasicParsing | Out-Null;$ready=$true;break}catch{Start-Sleep -Seconds 1}
  }
  if(!$ready){throw 'The temporary SSH viewer did not start.'}
  Start-Process 'http://127.0.0.1:29881/vnc.html?autoconnect=true&resize=scale'
  Read-Host 'Complete the source login in the remote browser, then press Enter to save and verify'
} finally {
  & $python $admin --config $configPath --action finish
  if($tunnel -and !$tunnel.HasExited){Stop-Process -Id $tunnel.Id -ErrorAction SilentlyContinue}
}
if($LASTEXITCODE -ne 0){throw 'Source verification failed. Check Information Capture for the actual failure.'}
Write-Host 'Server capture restored. This computer can be shut down. Refresh Information Capture for the source-session status.'

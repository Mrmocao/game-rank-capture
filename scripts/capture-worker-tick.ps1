param([Parameter(Mandatory=$true)][string]$Jar)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    # A versioned runtime copy avoids replacing an archive while a JVM is using it.
    if (!(Test-Path -LiteralPath $Jar -PathType Leaf)) { throw 'Capture runtime missing; reinstall worker' }
    $logDir = Join-Path $projectRoot 'data/worker-logs'
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    $log = Join-Path $logDir ('tick-' + (Get-Date -Format 'yyyy-MM-dd') + '.log')
    & java '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' '-Dloader.main=com.youkia.iaa.collection.capture.RankingCaptureCommand' -cp $Jar org.springframework.boot.loader.launch.PropertiesLauncher --tick --env-file (Join-Path $projectRoot '.env') >> $log 2>&1
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally { Pop-Location }

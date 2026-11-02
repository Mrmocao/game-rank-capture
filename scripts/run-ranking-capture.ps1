param(
    [switch]$Inspect,
    [switch]$SessionStatus,
    [switch]$Migrate,
    [switch]$Run,
    [string]$EnvFile = '.env',
    [string]$Output = '',
    [string]$ReplayDir = '',
    [string]$From = '',
    [string]$To = ''
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    & mvn -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Maven build failed' }
    # Execute plain main from the Boot jar without starting its application/schedulers.
    $captureArgs = @('--env-file', $EnvFile)
    if ($Output) { $captureArgs += @('--output', $Output) }
    if ($Inspect) { $captureArgs += '--inspect' }
    if ($SessionStatus) { $captureArgs += '--session-status' }
    if ($Migrate) { $captureArgs += '--migrate' }
    if ($Run) { $captureArgs += '--run' }
    if ($ReplayDir) { $captureArgs += @('--replay-dir', $ReplayDir) }
    if ($From) { $captureArgs += @('--from', $From) }
    if ($To) { $captureArgs += @('--to', $To) }
    if (!$Inspect -and !$SessionStatus -and !$Migrate -and !$Run) { $captureArgs += '--help' }
    & java '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' '-Dloader.main=com.youkia.iaa.collection.capture.RankingCaptureCommand' -cp 'backend/target/iaa-radar-api-0.1.0-SNAPSHOT.jar' org.springframework.boot.loader.launch.PropertiesLauncher @captureArgs
    if ($LASTEXITCODE -ne 0) { throw "Capture command exited with code $LASTEXITCODE" }
} finally { Pop-Location }

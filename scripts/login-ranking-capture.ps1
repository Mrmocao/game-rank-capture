param(
    [string]$EnvFile = '.env',
    [string]$ProfileDir = '',
    [string]$Channel = '',
    [string]$FinishFile = ''
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    $captureConfig = @{}
    if (Test-Path -LiteralPath $EnvFile) {
        foreach ($line in Get-Content -LiteralPath $EnvFile) {
            if ($line -match '^\s*([^#=]+)=(.*)$') { $captureConfig[$Matches[1].Trim()] = $Matches[2].Trim() }
        }
    }
    foreach ($key in @('CAPTURE_BROWSER_PROFILE_DIR','CAPTURE_BROWSER_CHANNEL')) {
        $value = [Environment]::GetEnvironmentVariable($key)
        if ($null -ne $value) { $captureConfig[$key] = $value }
    }
    if (!$ProfileDir) { $ProfileDir = $captureConfig['CAPTURE_BROWSER_PROFILE_DIR'] }
    if (!$ProfileDir) { throw 'Set CAPTURE_BROWSER_PROFILE_DIR in .env before login so later captures use the same session.' }
    if (!$Channel) { $Channel = $captureConfig['CAPTURE_BROWSER_CHANNEL'] }
    if (!$Channel) { $Channel = 'chrome' }
    if (!$FinishFile) { $FinishFile = Join-Path $ProfileDir ('login-finished-' + [guid]::NewGuid().ToString()) }
    & mvn -q -pl browser-capture -am package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw 'Maven build failed' }
    # Keep the running login JAR immutable while the project is rebuilt during manual login.
    $loginRuntimeDir = Join-Path $ProfileDir 'runtime'
    New-Item -ItemType Directory -Force -Path $loginRuntimeDir | Out-Null
    $loginRuntimeJar = Join-Path $loginRuntimeDir ('login-' + [guid]::NewGuid().ToString() + '.jar')
    Copy-Item -LiteralPath browser-capture/target/browser-capture-0.1.0-SNAPSHOT-cli.jar -Destination $loginRuntimeJar
    Write-Host '在专用Chrome窗口手动登录，登录后关闭该窗口即可保存会话。'
    & java '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -jar $loginRuntimeJar --profile $ProfileDir --channel $Channel --finish-file $FinishFile
    if ($LASTEXITCODE -ne 0) { throw 'Login browser did not complete normally' }
} finally { Pop-Location }

param([string]$TaskName = 'GameRankCapture-Worker')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$source = Join-Path $projectRoot 'backend/target/iaa-radar-api-0.1.0-SNAPSHOT.jar'
if (!(Test-Path -LiteralPath $source)) { throw 'Run mvn package first' }
$runtime = Join-Path $projectRoot 'data/runtime'
New-Item -ItemType Directory -Force -Path $runtime | Out-Null
$hash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.Substring(0,16).ToLowerInvariant()
$jar = Join-Path $runtime "capture-$hash.jar"
if (!(Test-Path -LiteralPath $jar)) { Copy-Item -LiteralPath $source -Destination $jar }
$javaw = Join-Path (Split-Path (Get-Command java -ErrorAction Stop).Source -Parent) 'javaw.exe'
if (!(Test-Path -LiteralPath $javaw)) { throw 'Java 21 javaw.exe is required' }
$arguments = '-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dloader.main=com.youkia.iaa.collection.capture.RankingCaptureCommand -cp "{0}" org.springframework.boot.loader.launch.PropertiesLauncher --worker --env-file "{1}"' -f $jar,(Join-Path $projectRoot '.env')
# A per-user login startup avoids Task Scheduler execution restrictions. No admin or password required.
$shortcutPath = Join-Path ([Environment]::GetFolderPath('Startup')) ($TaskName + '.lnk')
$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = $javaw
$shortcut.Arguments = $arguments
$shortcut.WorkingDirectory = $projectRoot
$shortcut.WindowStyle = 7
$shortcut.Description = 'Independent ranking capture worker; DB controls Beijing due times.'
$shortcut.Save()
# Stop only prior workers for this exact project, leaving API, other Java apps and browser sessions intact.
$workers = Get-CimInstance Win32_Process -Filter "Name='javaw.exe'" | Where-Object { $_.CommandLine -like '*RankingCaptureCommand*' -and $_.CommandLine -like '*--worker*' -and $_.CommandLine.Contains($projectRoot) }
foreach ($worker in $workers) { Stop-Process -Id $worker.ProcessId -ErrorAction Stop }
Start-Process -FilePath $javaw -ArgumentList $arguments -WorkingDirectory $projectRoot -WindowStyle Hidden
$legacy = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($legacy -and ($legacy.Actions.Arguments -join ' ').Contains((Join-Path $projectRoot 'scripts\capture-worker-tick.ps1'))) {
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
}
[PSCustomObject]@{StartupShortcut=$shortcutPath;Runtime=$jar;Schedule='Database controlled; check workspace heartbeat'}

param([string]$Config = 'data/deployment/capture-sync.json')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$configPath = (Resolve-Path -LiteralPath (Join-Path $projectRoot $Config)).Path
$pythonw = Join-Path (Split-Path (Get-Command python -ErrorAction Stop).Source -Parent) 'pythonw.exe'
if (!(Test-Path -LiteralPath $pythonw)) { throw 'Python with paramiko is required' }
$script = Join-Path $PSScriptRoot 'sync-capture-server.py'
$arguments = '"{0}" --config "{1}" --worker' -f $script,$configPath
$shortcutPath = Join-Path ([Environment]::GetFolderPath('Startup')) 'GameRankCapture-Sync.lnk'
$shortcut = (New-Object -ComObject WScript.Shell).CreateShortcut($shortcutPath)
$shortcut.TargetPath=$pythonw; $shortcut.Arguments=$arguments; $shortcut.WorkingDirectory=$projectRoot; $shortcut.WindowStyle=7
$shortcut.Description='Deliver new ranking evidence to the server and generate frozen weekly revisions'; $shortcut.Save()
$running=Get-CimInstance Win32_Process -Filter "Name='pythonw.exe'" | Where-Object { $_.CommandLine.Contains($script) -and $_.CommandLine.Contains($configPath) }
if (!$running) { Start-Process -FilePath $pythonw -ArgumentList $arguments -WorkingDirectory $projectRoot -WindowStyle Hidden }
[PSCustomObject]@{StartupShortcut=$shortcutPath;Config=$configPath;PollSeconds=60}

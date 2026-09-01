param(
    [string]$Source = "$PSScriptRoot\..\..\artifacts\publish\win-terminal",
    [string]$ServerUrl = "https://37.252.21.226"
)
$ErrorActionPreference = 'Stop'
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$isAdministrator = ([Security.Principal.WindowsPrincipal]::new($identity)).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
$installRoot = if ($isAdministrator) { 'C:\Program Files\Keytrins\MultiExchangeTerminal' } else { Join-Path $env:LOCALAPPDATA 'Programs\Keytrins\MultiExchangeTerminal' }
$dataRoot = if ($isAdministrator) { 'C:\ProgramData\Keytrins\MultiExchangeTerminal' } else { Join-Path $env:LOCALAPPDATA 'Keytrins\MultiExchangeTerminal\runtime' }
$terminalData = Join-Path $env:LOCALAPPDATA 'Keytrins\MultiExchangeTerminal'
New-Item -ItemType Directory -Force -Path $installRoot,$dataRoot,(Join-Path $dataRoot 'data'),(Join-Path $dataRoot 'logs'),(Join-Path $dataRoot 'secrets'),$terminalData | Out-Null
Copy-Item -Path (Join-Path $Source '*') -Destination $installRoot -Recurse -Force
Set-Content -LiteralPath (Join-Path $terminalData 'terminal.txt') -Value $ServerUrl -Encoding utf8NoBOM
$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut((Join-Path ([Environment]::GetFolderPath('Desktop')) 'Keytrins Multi-Exchange Terminal.lnk'))
$shortcut.TargetPath = Join-Path $installRoot 'KeytrinsMultiExchange.Terminal.exe'
$shortcut.WorkingDirectory = $installRoot
$shortcut.Description = 'Keytrins Multi-Exchange Terminal'
$shortcut.Save()
$userSid = $identity.User.Value
icacls $dataRoot /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' "*$userSid`:(OI)(CI)M" | Out-Null

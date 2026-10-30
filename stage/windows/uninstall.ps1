# TrunkDeck uninstaller (per-user). Keeps your settings unless you choose to delete them.
$ErrorActionPreference = 'Continue'
$AppName = 'TrunkDeck'
$Dest = Join-Path $env:LOCALAPPDATA "Programs\$AppName"

# Run from a temp copy so the install folder can be deleted
if ($MyInvocation.MyCommand.Path -like "$Dest*") {
    $tmp = Join-Path $env:TEMP 'trunkdeck-uninstall.ps1'
    Copy-Item -Force $MyInvocation.MyCommand.Path $tmp
    Start-Process powershell.exe -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$tmp`""
    exit
}

Add-Type -AssemblyName System.Windows.Forms
$r = [System.Windows.Forms.MessageBox]::Show("Remove $AppName from this computer?", "Uninstall $AppName", 'YesNo', 'Question')
if ($r -ne 'Yes') { exit }

Get-CimInstance Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like '*trunkdeck.jar*' -or $_.CommandLine -like "*$Dest*" } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 1

Remove-Item -Force -ErrorAction SilentlyContinue "$([Environment]::GetFolderPath('Desktop'))\$AppName.lnk"
Remove-Item -Recurse -Force -ErrorAction SilentlyContinue (Join-Path ([Environment]::GetFolderPath('Programs')) $AppName)
Remove-Item -Recurse -Force -ErrorAction SilentlyContinue "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\$AppName"
Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $Dest

$d = [System.Windows.Forms.MessageBox]::Show("Also delete your TrunkDeck systems, talkgroups, stream settings and voice codec?`n($env:APPDATA\$AppName)", $AppName, 'YesNo', 'Question')
if ($d -eq 'Yes') { Remove-Item -Recurse -Force -ErrorAction SilentlyContinue (Join-Path $env:APPDATA $AppName) }
$rec = Join-Path $env:USERPROFILE 'TrunkDeck\recordings'
if (Test-Path $rec) {
    $s = [System.Windows.Forms.MessageBox]::Show("Also delete your recordings?`n($rec)", $AppName, 'YesNo', 'Question')
    if ($s -eq 'Yes') { Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $rec }
}
[System.Windows.Forms.MessageBox]::Show("$AppName has been removed.", $AppName) | Out-Null

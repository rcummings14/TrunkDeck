# TrunkDeck installer for Windows 10/11 (per-user, no admin rights needed)
# Creates Start Menu + Desktop shortcuts and an entry in Settings > Apps so it can be uninstalled normally.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # makes downloads much faster in Windows PowerShell 5.1
try { [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12 } catch {}

$AppName       = 'TrunkDeck'
$AppVersion    = '1.7.0'
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$Pkg  = Split-Path -Parent $Here
$Dest = Join-Path $env:LOCALAPPDATA "Programs\$AppName"

function Say($m)  { Write-Host "==> $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "  ok $m" -ForegroundColor Green }
function Warn($m) { Write-Host "  !  $m" -ForegroundColor Yellow }

Write-Host ''
Write-Host '  TRUNKDECK  -  P25 trunking monitor  -  Windows installer' -ForegroundColor Cyan
Write-Host "  Installs to: $Dest"
Write-Host ''

# ---- Java runtime: Eclipse Temurin JDK 25 for Windows x64 (a full JDK: TrunkDeck builds the JMBE voice codec
#      from source on first start, which needs the Java compiler). There is no Windows-on-ARM build; ARM PCs run the
#      x64 build through Windows' built-in emulation. ----
if (-not [Environment]::Is64BitOperatingSystem) { throw 'TrunkDeck needs 64-bit Windows.' }
$JdkVer  = '25.0.4.1+1'
$JdkFile = 'OpenJDK25U-jdk_x64_windows_hotspot_25.0.4.1_1.zip'
$JdkSha  = '00c847d804f4a78e9f04f2683faf14fed898535b177b7fc704486cb0284e9283'
$JdkUrl  = "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/$JdkFile"

# ---- stop a running copy ----
Get-CimInstance Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like '*trunkdeck.jar*' -or $_.CommandLine -like "*$Dest\*" } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Milliseconds 800

# ---- copy TrunkDeck ----
Say "Copying $AppName"
New-Item -ItemType Directory -Force -Path $Dest | Out-Null
if (Test-Path "$Dest\app") { Remove-Item -Recurse -Force "$Dest\app" }
Copy-Item -Recurse -Force (Join-Path $Pkg 'app') "$Dest\app"
Copy-Item -Force (Join-Path $Here 'uninstall.ps1') "$Dest\uninstall.ps1"
Copy-Item -Force (Join-Path $Here 'TrunkDeck.bat') "$Dest\TrunkDeck.bat"
if (Test-Path (Join-Path $Pkg 'README.md')) { Copy-Item -Force (Join-Path $Pkg 'README.md') "$Dest\README.md" }
if (Test-Path (Join-Path $Pkg 'licenses')) { if (Test-Path "$Dest\licenses") { Remove-Item -Recurse -Force "$Dest\licenses" }; Copy-Item -Recurse -Force (Join-Path $Pkg 'licenses') "$Dest\licenses" }
Ok 'app files (settings, talkgroups, recordings and the voice codec are stored elsewhere and kept)'

# ---- upgrade from 1.1.x: remove the old SDRTrunk program folder (only the program TrunkDeck downloaded) ----
if (Test-Path "$Dest\engine") {
    $own = Join-Path $env:APPDATA "$AppName\jmbe"
    Get-ChildItem -Recurse -File -Filter 'jmbe-*.jar' "$Dest\engine" -ErrorAction SilentlyContinue | Where-Object { $_.Name -notlike 'jmbe-api*' } | ForEach-Object {
        New-Item -ItemType Directory -Force -Path $own | Out-Null
        if (-not (Test-Path (Join-Path $own $_.Name))) { Copy-Item $_.FullName $own }
    }
    Remove-Item -Recurse -Force "$Dest\engine"
    Ok 'removed the old SDRTrunk program folder - TrunkDeck decodes everything itself now (your data is kept)'
}

# ---- Java runtime ----
$Runtime = "$Dest\runtime"
$verFile = "$Runtime\.version"
if ((Test-Path "$Runtime\bin\javaw.exe") -and (Test-Path "$Runtime\bin\javac.exe") -and (Test-Path $verFile) -and ((Get-Content $verFile -Raw).Trim() -eq $JdkVer)) {
    Ok "Java runtime (Temurin JDK $JdkVer) already installed"
} else {
    Say "Getting the Java runtime (Eclipse Temurin JDK $JdkVer, about 200 MB)"
    $Tmp = Join-Path $env:TEMP "trunkdeck-$([guid]::NewGuid().ToString('N'))"
    New-Item -ItemType Directory -Force -Path $Tmp | Out-Null
    $ZipPath = Join-Path $Tmp 'jdk.zip'
    $Local = @((Join-Path $Here $JdkFile), (Join-Path $Pkg $JdkFile), (Join-Path $env:USERPROFILE "Downloads\$JdkFile")) | Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($Local) {
        Ok "using local file $Local"
        Copy-Item $Local $ZipPath
    } else {
        Write-Host "     $JdkUrl"
        $tries = 0
        while ($true) {
            try { Invoke-WebRequest -Uri $JdkUrl -OutFile $ZipPath -UseBasicParsing; break }
            catch { $tries++; if ($tries -ge 3) { throw "Download failed: $($_.Exception.Message)`nYou can download $JdkUrl yourself and put it next to install.ps1, then run Install.bat again." }; Start-Sleep -Seconds 3 }
        }
    }
    $Got = (Get-FileHash -Algorithm SHA256 $ZipPath).Hash.ToLower()
    if ($Got -ne $JdkSha) { throw "Download checksum mismatch (got $Got). Please try again." }
    Ok 'checksum verified'
    Expand-Archive -Path $ZipPath -DestinationPath "$Tmp\x" -Force
    $jdir = Get-ChildItem -Directory "$Tmp\x" | Select-Object -First 1
    if (-not (Test-Path "$($jdir.FullName)\bin\javaw.exe")) { throw 'The Java download does not look right (no bin\javaw.exe).' }
    if (Test-Path $Runtime) { Remove-Item -Recurse -Force $Runtime }
    Move-Item $jdir.FullName $Runtime
    Set-Content -Path $verFile -Value $JdkVer
    Remove-Item -Recurse -Force $Tmp -ErrorAction SilentlyContinue
    Ok "Java runtime installed in $Runtime"
}

# ---- shortcuts ----
Say 'Creating Start Menu and Desktop shortcuts'
$Javaw = "$Runtime\bin\javaw.exe"
$LaunchArgs  = "--enable-native-access=ALL-UNNAMED -XX:-OmitStackTraceInFastThrow -Dtrunkdeck.home=`"$Dest`" -Xmx768m -jar `"$Dest\app\trunkdeck.jar`""
$Icon  = "$Dest\app\icons\trunkdeck.ico"
$Shell = New-Object -ComObject WScript.Shell
function New-Shortcut($Path, $Target, $Arguments, $Desc) {
    $s = $Shell.CreateShortcut($Path)
    $s.TargetPath = $Target
    $s.Arguments = $Arguments
    $s.WorkingDirectory = $Dest
    $s.IconLocation = "$Icon,0"
    $s.Description = $Desc
    $s.Save()
}
$StartDir = Join-Path ([Environment]::GetFolderPath('Programs')) $AppName
New-Item -ItemType Directory -Force -Path $StartDir | Out-Null
New-Shortcut "$StartDir\$AppName.lnk" $Javaw $LaunchArgs 'P25 trunking monitor'
New-Shortcut "$StartDir\Uninstall $AppName.lnk" "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" "-NoProfile -ExecutionPolicy Bypass -File `"$Dest\uninstall.ps1`"" "Remove $AppName"
New-Shortcut "$([Environment]::GetFolderPath('Desktop'))\$AppName.lnk" $Javaw $LaunchArgs 'P25 trunking monitor'
Ok 'Start Menu > TrunkDeck, and a Desktop icon'

# ---- Settings > Apps entry ----
$Key = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\$AppName"
New-Item -Path $Key -Force | Out-Null
$size = [int]((Get-ChildItem -Recurse -File $Dest | Measure-Object -Sum Length).Sum / 1KB)
$props = @{
    DisplayName = $AppName; DisplayVersion = $AppVersion; Publisher = 'TrunkDeck'; DisplayIcon = $Icon
    InstallLocation = $Dest; UninstallString = "powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$Dest\uninstall.ps1`""
    Comments = 'P25 Phase 1 trunking monitor'
}
foreach ($k in $props.Keys) { New-ItemProperty -Path $Key -Name $k -Value $props[$k] -PropertyType String -Force | Out-Null }
New-ItemProperty -Path $Key -Name NoModify -Value 1 -PropertyType DWord -Force | Out-Null
New-ItemProperty -Path $Key -Name NoRepair -Value 1 -PropertyType DWord -Force | Out-Null
New-ItemProperty -Path $Key -Name EstimatedSize -Value $size -PropertyType DWord -Force | Out-Null
Ok 'listed in Settings > Apps (uninstall from there any time)'

Write-Host ''
Write-Host 'Installed!' -ForegroundColor Green
Write-Host 'Before the first start: install the WinUSB driver with Zadig (README, Windows step 3): Airspy = "AIRSPY" (1D50 60A1), RTL-SDR = "Bulk-In, Interface (Interface 0)" (0BDA 2838).'
Write-Host 'First start: pick your system and press START MONITORING. The first time, TrunkDeck builds the JMBE voice codec from source (about a minute).'
Write-Host ''
$open = Read-Host 'Start TrunkDeck now? [Y/n]'
if ($open -eq '' -or $open -match '^[Yy]') { Start-Process -FilePath $Javaw -ArgumentList $LaunchArgs -WorkingDirectory $Dest }

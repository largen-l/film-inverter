<#
.SYNOPSIS
  One-time toolchain setup for Film Inverter, everything under <repo>\toolchain (git-ignored).

.DESCRIPTION
  Downloads (skips anything already present):
    - Temurin JDK 17                          toolchain\jdk
    - Android SDK cmdline-tools + platform android-10 (Android 2.3.3), build-tools 30.0.3, platform-tools
    - jadx (APK decompiler)                   toolchain\jadx
    - Sony-PMCA-RE Windows release            toolchain\pmca
    - Reference sources (zip snapshots)       toolchain\src  (OpenMemories-Framework, PMCADemo,
                                                              OpenMemories-Tweak, Sony-PMCA-RE, recipe-lab)
    - Python venv with numpy, pillow, rawpy, pytest (if Python 3 is on PATH)   .venv
  Optional:  -WithNdk   Android NDK r16b (last NDK that targets Android 2.3; only needed for milestone M5)

  Writes toolchain\env.ps1 - dot-source it in new shells:  . .\toolchain\env.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\setup.ps1
#>
param([switch]$WithNdk)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # Invoke-WebRequest is 10x faster without the progress bar
$Root = Split-Path -Parent $PSScriptRoot
$TC   = Join-Path $Root 'toolchain'
$DL   = Join-Path $TC 'downloads'
New-Item -ItemType Directory -Force -Path $TC, $DL | Out-Null
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Get-File($Url, $Name, $UserAgent) {
    $dst = Join-Path $DL $Name
    if (-not (Test-Path $dst)) {
        Write-Host "  downloading $Name"
        $ua = @{}; if ($UserAgent) { $ua = @{ UserAgent = $UserAgent } }
        Invoke-WebRequest -Uri $Url -OutFile "$dst.part" -UseBasicParsing @ua
        Move-Item "$dst.part" $dst
    }
    return $dst
}

function Expand-To($Zip, $Dest) {
    if (-not (Test-Path $Dest)) {
        Write-Host "  extracting -> $Dest"
        $tmp = "$Dest.tmp"
        if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
        Expand-Archive -Path $Zip -DestinationPath $tmp
        $items = Get-ChildItem $tmp
        if ($items.Count -eq 1 -and $items[0].PSIsContainer) { Move-Item $items[0].FullName $Dest; Remove-Item $tmp }
        else { Move-Item $tmp $Dest }
    }
}

function Get-GitHubAsset($Repo, $Pattern) {
    $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/latest" -UseBasicParsing
    $asset = $rel.assets | Where-Object { $_.name -match $Pattern } | Select-Object -First 1
    if (-not $asset) { throw "no asset matching '$Pattern' in latest $Repo release" }
    return @{ url = $asset.browser_download_url; name = $asset.name; tag = $rel.tag_name }
}

Write-Host "== JDK 17 (Temurin)"
$jdkZip = Get-File 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse' 'jdk17.zip'
Expand-To $jdkZip (Join-Path $TC 'jdk')
$env:JAVA_HOME = Join-Path $TC 'jdk'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

Write-Host "== Android SDK"
$sdk = Join-Path $TC 'android-sdk'
$cmdl = Join-Path $sdk 'cmdline-tools\latest'
if (-not (Test-Path $cmdl)) {
    # If this URL ever 404s, take the current "Command line tools only / Windows" link from
    # https://developer.android.com/studio#command-line-tools-only and update it here.
    $z = Get-File 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' 'cmdline-tools.zip'
    New-Item -ItemType Directory -Force -Path (Join-Path $sdk 'cmdline-tools') | Out-Null
    Expand-To $z $cmdl
}
$sdkm = Join-Path $cmdl 'bin\sdkmanager.bat'
# android-16 is only used to compile OpenMemories-Framework (upstream builds it against android 4.1.1.4);
# apps themselves compile against android-10.
$pkgs = @('platforms;android-10', 'platforms;android-16', 'build-tools;30.0.3', 'platform-tools')
if (-not (Test-Path (Join-Path $sdk 'platforms\android-10\android.jar')) -or
    -not (Test-Path (Join-Path $sdk 'platforms\android-16\android.jar')) -or
    -not (Test-Path (Join-Path $sdk 'build-tools\30.0.3\d8.bat'))) {
    Write-Host "  accepting licenses + installing $($pkgs -join ', ')"
    # Piping a string from PowerShell 5.1 into the .bat does not reach sdkmanager's prompts;
    # a cmd.exe stdin redirect from a file does.
    $yesFile = Join-Path $DL 'yes.txt'
    Set-Content -Path $yesFile -Encoding ASCII -Value (@('y') * 30)
    & cmd.exe /c "`"$sdkm`" --sdk_root=`"$sdk`" --licenses < `"$yesFile`"" | Out-Null
    & $sdkm --sdk_root=$sdk @pkgs
    if (-not (Test-Path (Join-Path $sdk 'platforms\android-10\android.jar'))) { throw "sdkmanager did not install platforms;android-10" }
}

if ($WithNdk) {
    Write-Host "== Android NDK r16b"
    $z = Get-File 'https://dl.google.com/android/repository/android-ndk-r16b-windows-x86_64.zip' 'ndk-r16b.zip'
    Expand-To $z (Join-Path $TC 'ndk-r16b')
}

Write-Host "== jadx"
if (-not (Test-Path (Join-Path $TC 'jadx'))) {
    $a = Get-GitHubAsset 'skylot/jadx' '^jadx-[\d.]+\.zip$'
    $z = Get-File $a.url $a.name
    New-Item -ItemType Directory -Force -Path (Join-Path $TC 'jadx') | Out-Null
    Expand-Archive -Path $z -DestinationPath (Join-Path $TC 'jadx') -Force
}

Write-Host "== jasmin 2.4 (assembles the OpenMemories-Framework stubs, which are .j files)"
$jasminJar = Join-Path $TC 'jasmin\jasmin.jar'
if (-not (Test-Path $jasminJar)) {
    # SourceForge serves an HTML "download starting" page to browser-like user agents; Wget gets the file.
    $z = Get-File 'https://downloads.sourceforge.net/project/jasmin/jasmin/jasmin-2.4/jasmin-2.4.zip' 'jasmin-2.4.zip' 'Wget/1.21'
    $tmp = Join-Path $TC 'jasmin.tmp'
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
    Expand-Archive -Path $z -DestinationPath $tmp
    $j = Get-ChildItem -Recurse -Filter jasmin.jar $tmp | Select-Object -First 1
    if (-not $j) { throw "jasmin.jar not found in jasmin-2.4.zip" }
    New-Item -ItemType Directory -Force -Path (Split-Path $jasminJar) | Out-Null
    Copy-Item $j.FullName $jasminJar
    Remove-Item -Recurse -Force $tmp
}

Write-Host "== third-party jars for reference apps (toolchain\libs\<App>\*.jar, packaged by build.ps1)"
$pmcaDemoLibs = Join-Path $TC 'libs\PMCADemo'
New-Item -ItemType Directory -Force -Path $pmcaDemoLibs | Out-Null
$nano = Join-Path $pmcaDemoLibs 'nanohttpd-2.1.1.jar'
if (-not (Test-Path $nano)) {
    Copy-Item (Get-File 'https://repo1.maven.org/maven2/com/nanohttpd/nanohttpd/2.1.1/nanohttpd-2.1.1.jar' 'nanohttpd-2.1.1.jar') $nano
}

Write-Host "== git + GitHub CLI (portable, only if not already installed; used by publish-github.ps1)"
if (-not (Get-Command git -ErrorAction SilentlyContinue) -and -not (Test-Path (Join-Path $TC 'git\cmd\git.exe'))) {
    $a = Get-GitHubAsset 'git-for-windows/git' '^MinGit-[\d.]+-64-bit\.zip$'
    $z = Get-File $a.url $a.name
    Expand-Archive -Path $z -DestinationPath (Join-Path $TC 'git') -Force
}
if (-not (Get-Command gh -ErrorAction SilentlyContinue) -and -not (Test-Path (Join-Path $TC 'gh\bin\gh.exe'))) {
    $a = Get-GitHubAsset 'cli/cli' '^gh_[\d.]+_windows_amd64\.zip$'
    $z = Get-File $a.url $a.name
    Expand-Archive -Path $z -DestinationPath (Join-Path $TC 'gh') -Force
}

Write-Host "== Sony-PMCA-RE (Windows release)"
$pmca = Join-Path $TC 'pmca'
if (-not (Test-Path $pmca)) {
    New-Item -ItemType Directory -Force -Path $pmca | Out-Null
    $rel = Invoke-RestMethod -Uri 'https://api.github.com/repos/ma1co/Sony-PMCA-RE/releases/latest' -UseBasicParsing
    foreach ($as in ($rel.assets | Where-Object { $_.name -match 'win' })) {
        $f = Get-File $as.browser_download_url $as.name
        Copy-Item $f $pmca
    }
    Write-Host "  release $($rel.tag_name): $((Get-ChildItem $pmca).Name -join ', ')"
}

Write-Host "== reference sources"
$src = Join-Path $TC 'src'
New-Item -ItemType Directory -Force -Path $src | Out-Null
$repos = @('ma1co/OpenMemories-Framework', 'ma1co/PMCADemo', 'ma1co/OpenMemories-Tweak',
           'ma1co/Sony-PMCA-RE', 'voxivoid/recipe-lab-sony-pmca')
foreach ($r in $repos) {
    $name = $r.Split('/')[1]
    if (-not (Test-Path (Join-Path $src $name))) {
        $info = Invoke-RestMethod -Uri "https://api.github.com/repos/$r" -UseBasicParsing
        $z = Get-File "https://github.com/$r/archive/refs/heads/$($info.default_branch).zip" "$name.zip"
        Expand-To $z (Join-Path $src $name)
    }
}

Write-Host "== Python venv (desktop engine)"
$py = Get-Command python -ErrorAction SilentlyContinue
if ($py -and -not (Test-Path (Join-Path $Root '.venv'))) {
    & python -m venv (Join-Path $Root '.venv')
    & (Join-Path $Root '.venv\Scripts\python.exe') -m pip install --quiet --upgrade pip
    & (Join-Path $Root '.venv\Scripts\python.exe') -m pip install --quiet numpy pillow rawpy pytest
} elseif (-not $py) {
    Write-Warning "Python not on PATH - install Python 3.10+ (python.org) and re-run for the desktop engine."
}

$envFile = Join-Path $TC 'env.ps1'
@"
`$env:JAVA_HOME        = '$TC\jdk'
`$env:ANDROID_SDK_ROOT = '$sdk'
`$env:FI_BUILD_TOOLS  = '$sdk\build-tools\30.0.3'
`$env:FI_ANDROID_JAR  = '$sdk\platforms\android-10\android.jar'
`$env:Path = "`$env:JAVA_HOME\bin;$sdk\platform-tools;$TC\jadx\bin;$TC\pmca;`$env:Path;$TC\git\cmd;$TC\gh\bin"
"@ | Set-Content -Encoding UTF8 $envFile

Write-Host ""
Write-Host "Setup complete. In new shells run:  . $envFile"
Write-Host "Next: tools\build.ps1 -App PMCADemo   (toolchain sanity check), then tools\build.ps1"

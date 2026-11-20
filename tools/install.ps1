<#
.SYNOPSIS
  Install an APK on the camera with Sony-PMCA-RE (USB, camera in Mass Storage USB mode).

.EXAMPLE
  tools\install.ps1 build\FilmInverter\FilmInverter.apk
  tools\install.ps1 -Adb build\FilmInverter\FilmInverter.apk   # if Tweak's adb is enabled (faster for iteration)
#>
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [switch]$Adb
)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
. (Join-Path $Root 'toolchain\env.ps1')
$Apk = (Resolve-Path $Apk).Path

if ($Adb) {
    & adb install -r $Apk
    exit $LASTEXITCODE
}

$console = Get-ChildItem (Join-Path $Root 'toolchain\pmca') -Filter '*console*.exe' | Select-Object -First 1
if (-not $console) { throw "pmca-console not found in toolchain\pmca - run tools\setup.ps1" }
Write-Host "Camera: Menu > Setup > USB Connection = Mass Storage, then connect USB."
# The camera re-enumerates when it switches to app-install mode; through a VM's USB passthrough that
# often takes longer than pmca-console waits ("Operation timed out", exit code 0). One retry finds it.
for ($try = 1; $try -le 2; $try++) {
    $outLines = & $console.FullName install -f $Apk 2>&1 | ForEach-Object { "$_" } | Tee-Object -Variable captured
    $outLines | Write-Output        # stdout, so callers and logs see it (Out-Host bypasses redirection)
    if (-not ($captured -match 'Operation timed out')) { break }
    if ($try -eq 1) { Write-Host "`n(retrying once: the camera was still switching modes)`n"; Start-Sleep -Seconds 5 }
}
if ($captured -match 'Task completed successfully') { exit 0 }
exit 1

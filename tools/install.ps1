<#
.SYNOPSIS
  Install an APK on the camera with Sony-PMCA-RE (USB, camera in Mass Storage USB mode).

.EXAMPLE
  tools\install.ps1 build\NegInverter\NegInverter.apk
  tools\install.ps1 -Adb build\NegInverter\NegInverter.apk   # if Tweak's adb is enabled (faster for iteration)
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
& $console.FullName install -f $Apk
exit $LASTEXITCODE

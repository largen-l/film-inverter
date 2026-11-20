# Runs setup + both builds, logging everything to logs\first-run.log so the
# results can be read back remotely. Started by FIRST-RUN.cmd (double-click).
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $Root 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$log = Join-Path $logDir 'first-run.log'
Start-Transcript -Path $log -Force | Out-Null
$status = 'OK'
try {
    Write-Host "== $(Get-Date -Format s)  PowerShell $($PSVersionTable.PSVersion)  $([Environment]::OSVersion.VersionString)"
    Write-Host "== free space on A: $([math]::Round((Get-PSDrive A).Free / 1GB, 1)) GB"
    Write-Host "`n#### STEP 1: setup"
    & (Join-Path $PSScriptRoot 'setup.ps1')
    Write-Host "`n#### STEP 2: build PMCADemo (toolchain check)"
    try { & (Join-Path $PSScriptRoot 'build.ps1') -App PMCADemo }
    catch { Write-Host "PMCADemo build FAILED: $_"; $status = 'PMCADemo build failed (NegInverter still attempted)' }
    Write-Host "`n#### STEP 3: build NegInverter"
    & (Join-Path $PSScriptRoot 'build.ps1')
} catch {
    $status = "FAILED: $_"
    Write-Host $_.ScriptStackTrace
} finally {
    Write-Host "`n#### RESULT: $status"
    Stop-Transcript | Out-Null
}

<#
.SYNOPSIS
  Decompile a PMCA app with jadx and summarise which Sony APIs it uses.

.DESCRIPTION
  Main target: Sony's "Multiple Exposure" app, to learn how it blends a stored image
  into live view (the lead for an inverted live preview). Works for any APK.
  Output: toolchain\decompiled\<name>\ (sources, git-ignored) and
          docs\api-usage\<name>.txt (summary, committed).

.EXAMPLE
  tools\decompile.ps1 "D:\pmca-archive\MultipleExposure.apk"
#>
param([Parameter(Mandatory = $true)][string]$Apk)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
. (Join-Path $Root 'toolchain\env.ps1')

$Apk = (Resolve-Path $Apk).Path
$name = [IO.Path]::GetFileNameWithoutExtension($Apk) -replace '[^\w\-.]', '_'
$out = Join-Path $Root "toolchain\decompiled\$name"
$jadx = Get-ChildItem (Join-Path $Root 'toolchain\jadx\bin') -Filter 'jadx.bat' | Select-Object -First 1
& $jadx.FullName --show-bad-code --deobf -d $out $Apk
if ($LASTEXITCODE -ne 0) { Write-Warning "jadx reported errors (usually fine - partial output is still useful)" }

$srcDir = Join-Path $out 'sources'
$java = Get-ChildItem -Recurse -Filter *.java $srcDir
$report = New-Object System.Collections.Generic.List[string]
$report.Add("API usage summary for $([IO.Path]::GetFileName($Apk))  ($(Get-Date -Format s))")
$report.Add("")

$report.Add("== Sony classes imported (count of files)")
$java | Select-String -Pattern '^import (com\.sony\.[\w.]+);' | ForEach-Object { $_.Matches[0].Groups[1].Value } |
    Group-Object | Sort-Object Count -Descending | ForEach-Object { $report.Add(("{0,5}  {1}" -f $_.Count, $_.Name)) }

$report.Add("")
$report.Add("== Lines mentioning live view / overlay / blend / preview / effect / layer")
$java | Select-String -Pattern '(?i)(overlay|blend|preview|liveview|live_view|superimpose|layer|effect|mix|multiexp|alpha)' |
    Where-Object { $_.Line -notmatch '^\s*(import|//|\*)' } | Select-Object -First 400 |
    ForEach-Object { $report.Add(("{0}:{1}: {2}" -f ($_.Path.Substring($srcDir.Length + 1)), $_.LineNumber, $_.Line.Trim())) }

$report.Add("")
$report.Add("== Manifest")
$mf = Join-Path $out 'resources\AndroidManifest.xml'
if (Test-Path $mf) { Get-Content $mf | ForEach-Object { $report.Add($_) } }

$docDir = Join-Path $Root 'docs\api-usage'
New-Item -ItemType Directory -Force -Path $docDir | Out-Null
$dst = Join-Path $docDir "$name.txt"
$report | Set-Content -Encoding UTF8 $dst
Write-Host "sources : $srcDir"
Write-Host "summary : $dst"

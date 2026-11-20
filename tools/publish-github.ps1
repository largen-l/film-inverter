<#
.SYNOPSIS
  One-time: turn this folder into a git repo and publish it as a public GitHub repository.

.DESCRIPTION
  - Installs Git and GitHub CLI with winget if they are missing (asks first).
  - Signs you in to GitHub (browser) if needed.
  - git init, first commit, creates github.com/<you>/<Name>, pushes.
  Large/local stuff (toolchain\, scans\, build\, .venv\) is excluded by .gitignore.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\publish-github.ps1
  powershell -ExecutionPolicy Bypass -File tools\publish-github.ps1 -Name a6500-negative-inverter -Private
#>
param(
    [string]$Name = 'film-inverter',
    [switch]$Private
)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
# setup.ps1 puts portable git/gh in toolchain\ when they are not installed system-wide
$envFile = Join-Path $Root 'toolchain\env.ps1'
if (Test-Path $envFile) { . $envFile }

function Ensure-Tool($cmd, $wingetId) {
    if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) {
        $ans = Read-Host "$cmd is not installed. Install it with winget ($wingetId)? [y/N]"
        if ($ans -ne 'y') { throw "$cmd is required" }
        winget install --id $wingetId -e --source winget --accept-package-agreements --accept-source-agreements
        $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' + [Environment]::GetEnvironmentVariable('Path', 'User')
    }
}
Ensure-Tool git 'Git.Git'
Ensure-Tool gh 'GitHub.cli'

& gh auth status 2>$null
if ($LASTEXITCODE -ne 0) { & gh auth login --web --git-protocol https }

if (-not (git config user.name)) {
    $u = Read-Host 'Name for git commits'
    git config --global user.name $u
}
if (-not (git config user.email)) {
    $e = Read-Host 'Email for git commits (tip: use your GitHub noreply address)'
    git config --global user.email $e
}

if (-not (Test-Path (Join-Path $Root '.git'))) {
    git init -b main
}
git add -A
git status --short
git commit -m "Initial import: plan, desktop inversion engine, PMCA probe app, Windows toolchain scripts"

$vis = if ($Private) { '--private' } else { '--public' }
& gh repo create $Name $vis --source . --remote origin --push `
    --description 'In-camera color negative inversion for the Sony a6500 (PlayMemories/PMCA app) + desktop reference engine'
if ($LASTEXITCODE -ne 0) { throw 'gh repo create failed' }
& gh repo view --web

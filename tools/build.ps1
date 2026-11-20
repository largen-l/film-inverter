<#
.SYNOPSIS
  Build a PMCA (Android 2.3) APK without Gradle: aapt -> javac -> d8 -> zipalign -> apksigner (v1).

.EXAMPLE
  tools\build.ps1                      # builds app\NegToPos
  tools\build.ps1 -App PMCADemo        # builds toolchain\src\PMCADemo (toolchain sanity check)
  tools\build.ps1 -AppDir C:\some\app  # any app folder (AndroidManifest.xml + src/ or src/main/java)

Output: build\<name>\<name>.apk (debug-signed, v1 only) and <name>-unsigned.apk.
Sony-PMCA-RE's docs say the camera accepts debug and release certificates; if an
install is ever refused, try the -unsigned.apk.
#>
param(
    [string]$App = 'NegToPos',
    [string]$AppDir
)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$TC = Join-Path $Root 'toolchain'
. (Join-Path $TC 'env.ps1')

# No param() block on purpose: every token (including ones that look like -switches)
# lands in $args and is passed through to the native tool untouched.
function Run {
    $exe = $args[0]
    $rest = @(); if ($args.Count -gt 1) { $rest = $args[1..($args.Count - 1)] }
    & $exe @rest
    if ($LASTEXITCODE -ne 0) { throw "$exe failed ($LASTEXITCODE): $($rest -join ' ')" }
}

$BT = $env:NTP_BUILD_TOOLS
$AJ = $env:NTP_ANDROID_JAR
$aapt = Join-Path $BT 'aapt.exe'
# d8.bat / apksigner.bat silently do nothing without the legacy SDK 'tools\lib\find_java.bat' (and
# pass -Djava.ext.dirs, which JDK 9+ rejects), so run their jars with our JDK directly.
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$d8Jar = Join-Path $BT 'lib\d8.jar'
$zipalign = Join-Path $BT 'zipalign.exe'
$apksignerJar = Join-Path $BT 'lib\apksigner.jar'
$javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
$jar = Join-Path $env:JAVA_HOME 'bin\jar.exe'
$keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'

# ---------------------------------------------------------------- locate the app
if (-not $AppDir) {
    $AppDir = if (Test-Path (Join-Path $Root "app\$App")) { Join-Path $Root "app\$App" } else { Join-Path $TC "src\$App" }
}
if (-not (Test-Path $AppDir)) { throw "app folder not found: $AppDir" }
$name = Split-Path -Leaf $AppDir

function First-Existing([string[]]$paths) { foreach ($p in $paths) { if (Test-Path $p) { return $p } }; return $null }
$manifest = First-Existing @("$AppDir\AndroidManifest.xml", "$AppDir\src\main\AndroidManifest.xml", "$AppDir\app\src\main\AndroidManifest.xml")
if (-not $manifest) { throw "no AndroidManifest.xml under $AppDir" }
$modDir = Split-Path -Parent $manifest
# Gradle layout keeps code in <module>/java (src/main/java); flat layout in <app>/src. Never both,
# or the same classes would be compiled twice.
$srcDirs = if (Test-Path "$modDir\java") { @("$modDir\java") } else { @("$AppDir\src", "$modDir\src") | Where-Object { Test-Path $_ } | Select-Object -Unique }
$resDir = First-Existing @("$AppDir\res", "$modDir\res")
$assetsDir = First-Existing @("$AppDir\assets", "$modDir\assets")
Write-Host "app      : $name"
Write-Host "manifest : $manifest"
Write-Host "sources  : $($srcDirs -join '; ')"

# ---------------------------------------------------------------- OpenMemories stubs + framework jars (once)
$libOut = Join-Path $TC 'build-libs'
$stubsJar = Join-Path $libOut 'openmemories-stubs.jar'
$fwJar = Join-Path $libOut 'openmemories-framework.jar'
if (-not (Test-Path $stubsJar)) {
    $om = Join-Path $TC 'src\OpenMemories-Framework'
    # The stubs are Jasmin assembly (stubs/src/main/jasmin/**/*.j), assembled like upstream's build.gradle.
    $stubSrc = "$om\stubs\src\main\jasmin"
    $fwSrc = First-Existing @("$om\framework\src\main\java", "$om\framework\src")
    if (-not (Test-Path $stubSrc)) { throw "OpenMemories stubs (jasmin) not found under $om - inspect the layout and fix build.ps1" }
    $jasminJar = Join-Path $TC 'jasmin\jasmin.jar'
    if (-not (Test-Path $jasminJar)) { throw "jasmin.jar missing - re-run tools\setup.ps1" }
    New-Item -ItemType Directory -Force -Path "$libOut\stubs", "$libOut\fw" | Out-Null
    # relative paths keep the command line under Windows' 32K limit (~320 files)
    Push-Location $stubSrc
    try {
        $jfiles = @(Get-ChildItem -Recurse -Filter *.j . | ForEach-Object { Resolve-Path -Relative $_.FullName })
        if ($jfiles.Count -eq 0) { throw "no .j files under $stubSrc" }
        Run (Join-Path $env:JAVA_HOME 'bin\java.exe') -jar $jasminJar -d "$libOut\stubs" @jfiles
    } finally { Pop-Location }
    Run $jar cf $stubsJar -C "$libOut\stubs" .
    if ($fwSrc) {
        $list = Join-Path $libOut 'fw.txt'
        (Get-ChildItem -Recurse -Filter *.java $fwSrc).FullName | ForEach-Object { '"' + ($_ -replace '\\', '/') + '"' } | Set-Content $list
        # upstream compiles the framework against android 4.1 (API 16); it only uses API 16 constants,
        # which javac inlines, so the result still runs on API 10.
        $aj16 = Join-Path $env:ANDROID_SDK_ROOT 'platforms\android-16\android.jar'
        if (-not (Test-Path $aj16)) { throw "platforms;android-16 missing - re-run tools\setup.ps1" }
        Run $javac -nowarn -source 8 -target 8 -bootclasspath $aj16 -cp $stubsJar -d "$libOut\fw" "@$list"
        Run $jar cf $fwJar -C "$libOut\fw" .
    }
    Write-Host "built OpenMemories stubs/framework jars"
}

# ---------------------------------------------------------------- build
$out = Join-Path $Root "build\$name"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
$gen = "$out\gen"; $cls = "$out\classes"; $dex = "$out\dex"
New-Item -ItemType Directory -Force -Path $gen, $cls, $dex | Out-Null

$resArgs = @(); if ($resDir) { $resArgs = @('-S', $resDir) }
$assetArgs = @(); if ($assetsDir) { $assetArgs = @('-A', $assetsDir) }

Write-Host "[1/6] aapt R.java"
Run $aapt package -f -m -J $gen -M $manifest @resArgs -I $AJ

Write-Host "[2/6] javac"
$list = "$out\sources.txt"
$files = @(@($srcDirs) + $gen | ForEach-Object { (Get-ChildItem -Recurse -Filter *.java $_).FullName }) | Select-Object -Unique
$files | ForEach-Object { '"' + ($_ -replace '\\', '/') + '"' } | Set-Content $list
# extra third-party jars: <app>\libs\*.jar, plus toolchain\libs\<name>\*.jar for reference apps (setup.ps1)
$extraJars = @(@("$AppDir\libs", "$TC\libs\$name") | Where-Object { Test-Path $_ } |
    ForEach-Object { Get-ChildItem -Filter *.jar $_ } | ForEach-Object { $_.FullName })
$cp = @($stubsJar); if (Test-Path $fwJar) { $cp += $fwJar }; $cp += $extraJars
Run $javac -nowarn -encoding UTF-8 -source 8 -target 8 -bootclasspath $AJ -cp ($cp -join ';') -d $cls "@$list"
Run $jar cf "$out\classes.jar" -C $cls .

Write-Host "[3/6] d8 (min-api 10)"
$dexInputs = @("$out\classes.jar"); if (Test-Path $fwJar) { $dexInputs += $fwJar }   # framework is packaged, stubs are not
$dexInputs += $extraJars
Run $java -Xmx1024M -cp $d8Jar com.android.tools.r8.D8 --release --min-api 10 --lib $AJ --classpath $stubsJar --output $dex @dexInputs

Write-Host "[4/6] package"
$unsigned = "$out\$name-unsigned.apk"
Run $aapt package -f -M $manifest @resArgs @assetArgs -I $AJ -F $unsigned
Push-Location $dex
try { Run $aapt add $unsigned classes.dex } finally { Pop-Location }

Write-Host "[5/6] zipalign"
$aligned = "$out\$name-aligned.apk"
Run $zipalign -f 4 $unsigned $aligned

Write-Host "[6/6] sign (v1 / JAR signing, required for Android 2.3)"
$ks = Join-Path $TC 'debug.keystore'
if (-not (Test-Path $ks)) {
    Run $keytool -genkeypair -keystore $ks -storepass android -keypass android -alias ntp `
        -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=negative-to-positive debug'
}
Run $java -jar $apksignerJar sign --ks $ks --ks-pass pass:android --ks-key-alias ntp `
    --v1-signing-enabled true --v2-signing-enabled false --v3-signing-enabled false `
    --min-sdk-version 10 --out "$out\$name.apk" $aligned
Remove-Item $aligned

Write-Host ""
Write-Host "OK -> $out\$name.apk"
Write-Host "Install: tools\install.ps1 $out\$name.apk"

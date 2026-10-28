param(
    [ValidateSet('assembleDebug', 'assembleRelease', 'bundleRelease')]
    [string]$Task = 'assembleRelease',
    [switch]$Install
)

<#
.SYNOPSIS
  Build FactCheck to a signed APK (or install it over adb).
.EXAMPLE
  .\build-apk.ps1                     # signed release APK
  .\build-apk.ps1 -Task assembleDebug # debug build
  .\build-apk.ps1 -Install            # build release + install on the connected phone
#>

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path

$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
if (-not $env:JAVA_HOME) {
    $java = Get-Command java -ErrorAction SilentlyContinue
    if ($java) { $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $java.Source) }
}

$gradlew = Join-Path $ProjectRoot 'gradlew.bat'
Write-Host ">> $Task" -ForegroundColor Cyan
& $gradlew -p $ProjectRoot $Task --console=plain
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed ($LASTEXITCODE)" }

if ($Task -eq 'bundleRelease') {
    $out = Get-ChildItem (Join-Path $ProjectRoot 'app\build\outputs\bundle') -Recurse -Filter *.aab |
           Sort-Object LastWriteTime -Descending | Select-Object -First 1
} else {
    $out = Get-ChildItem (Join-Path $ProjectRoot 'app\build\outputs\apk') -Recurse -Filter *.apk |
           Sort-Object LastWriteTime -Descending | Select-Object -First 1
}

if ($out) {
    Write-Host "`nDone: $($out.FullName)  ($([math]::Round($out.Length/1MB,2)) MB)" -ForegroundColor Green
}

if ($Install) {
    $apk = Get-ChildItem (Join-Path $ProjectRoot 'app\build\outputs\apk') -Recurse -Filter *.apk |
           Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($apk) {
        Write-Host ">> adb install -r $($apk.Name)" -ForegroundColor Cyan
        & "$env:ANDROID_HOME\platform-tools\adb.exe" install -r $apk.FullName
    }
}

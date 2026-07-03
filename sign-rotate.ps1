# Builds the app and produces a rotation-signed APK so existing debug-key installs
# update seamlessly to the release key (key-rotation lineage, Option C).
#
#   Usage:  ./sign-rotate.ps1 [-Build] [-OutDir <dir>]
#     -Build   run `gradlew assembleDebug` first (otherwise uses the existing APK)
#
# Reads signing material from keystore.properties (gitignored). The rotated APK is
# release-signed on Android 13+ and debug-signed on Android 12 and below; both
# update over the current debug installs with no uninstall.

param(
    [switch]$Build,
    [string]$OutDir = "$PSScriptRoot\release"
)

$ErrorActionPreference = "Stop"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

# --- Load keystore.properties ---
$props = @{}
Get-Content "$PSScriptRoot\keystore.properties" | ForEach-Object {
    if ($_ -match '^\s*([^#][^=]+?)\s*=\s*(.+?)\s*$') { $props[$Matches[1]] = $Matches[2] }
}

$apksigner = (Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" -Recurse -Filter apksigner.bat |
    Sort-Object FullName -Descending | Select-Object -First 1).FullName

if ($Build) {
    # First pass (dry-run) counts tasks so we can show a real percentage.
    Write-Host "Preparing build..."
    $dryLines = & "$PSScriptRoot\gradlew.bat" assembleDebug --dry-run --console=plain 2>&1
    $totalTasks = [Math]::Max(($dryLines | Where-Object { $_ -match '^> Task' }).Count, 1)

    Write-Host "Building ($totalTasks tasks)..."
    $done = 0
    & "$PSScriptRoot\gradlew.bat" assembleDebug --console=plain 2>&1 | ForEach-Object {
        if ($_ -match '^> Task') {
            $done++
            $pct = [int]([Math]::Min([Math]::Round($done * 100.0 / $totalTasks), 99))
            Write-Host ("[{0,3}%] {1}" -f $pct, $_)
        } elseif ($_ -match 'BUILD SUCCESSFUL') {
            Write-Host "[100%] BUILD SUCCESSFUL"
        } elseif ($_ -match 'BUILD FAILED|error:|FAILED') {
            Write-Host $_
        }
    }
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed (exit $LASTEXITCODE)" }
}

$srcApk = "$PSScriptRoot\app\build\outputs\apk\debug\app-debug.apk"
$ver = (Select-String -Path "$PSScriptRoot\app\build.gradle" -Pattern 'versionName\s+"([^"]+)"').Matches.Groups[1].Value
$verSafe = $ver -replace '\s+', '-'

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$outApk = "$OutDir\M5-F21-Dialer-$verSafe.apk"
Copy-Item $srcApk $outApk -Force

Write-Host "Rotation-signing $outApk ..."
& $apksigner sign `
    --ks $props.debugStoreFile --ks-key-alias $props.debugKeyAlias `
    --ks-pass "pass:$($props.debugStorePassword)" --key-pass "pass:$($props.debugKeyPassword)" `
    --next-signer --ks $props.releaseStoreFile --ks-key-alias $props.releaseKeyAlias `
    --ks-pass "pass:$($props.releaseStorePassword)" --key-pass "pass:$($props.releaseKeyPassword)" `
    --lineage $props.lineageFile --min-sdk-version 29 $outApk

Write-Host "`nVerifying..."
& $apksigner verify --print-certs $outApk | Select-String -Pattern "DN:"
Write-Host "`nDone: $outApk"

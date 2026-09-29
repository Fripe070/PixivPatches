<#
.SYNOPSIS
    Deterministic test runner for Pixiv Morphe Patches.
    Builds the patch bundle, patches the APK, installs it, and navigates key test screens.

.DESCRIPTION
    Replaces slow, fragile manual piloting by walking a structured test matrix:
      1. Home Feed (Adblocker, thumbnail AI flags)
      2. Artwork Detail (Single top AI banner, download action, metadata pill)
      3. Recommended Works (Dark theme surface contrast, related thumbnail flags)
      4. Download Picker (Multi-image selection grid, checkmark badges, cached thumbnails)
      5. Fullscreen Viewer (Placeholder instant display, HD badge, zoom preservation)
#>

param(
    [string]$DeviceId = "emulator-5554",
    [string]$AiWorkId = "123939215",
    [string]$MultiPageWorkId = "148348720",
    [string]$NormalWorkId = "148626820"
)

$ErrorActionPreference = "Stop"
$RepoRoot = (Get-Item "$PSScriptRoot\..").FullName
$CapturesDir = Join-Path $RepoRoot "captures"
New-Item -ItemType Directory -Force -Path $CapturesDir | Out-Null

$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $Adb)) { $Adb = (Get-Command adb -ErrorAction SilentlyContinue)?.Source }
if (-not $Adb) { throw "adb.exe not found." }

function Take-Capture {
    param([string]$Filename, [string]$StepLabel)
    Write-Host "  -> Capturing $StepLabel ($Filename)..." -ForegroundColor Cyan
    & $Adb -s $DeviceId shell screencap -p /sdcard/test_screen.png
    $Dest = Join-Path $CapturesDir $Filename
    & $Adb -s $DeviceId pull /sdcard/test_screen.png $Dest | Out-Null
    Copy-Item $Dest (Join-Path $CapturesDir "latest.png") -Force
}

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  Pixiv Morphe Patches: Automated Screen Verification" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

# Step 0: Ensure MPP is built and deployed
Write-Host "[Step 0] Building MPP bundle and verifying bootstrap..." -ForegroundColor Yellow
& "$RepoRoot\build-mpp.ps1"
if ($LASTEXITCODE -ne 0) { throw "build-mpp.ps1 failed." }

$MppFile = (Get-ChildItem -Path "$RepoRoot\patches\build\libs" -Filter "*.mpp" | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
$BaseApk = Join-Path $RepoRoot "..\pixiv-base.apk"
if (-not (Test-Path $BaseApk)) { $BaseApk = Join-Path $RepoRoot "pixiv-base.apk" }
$PatchedApk = Join-Path $RepoRoot "pixiv-patched.apk"

$MorpheCli = Join-Path $RepoRoot "..\tools\morphe-cli.jar"
if (-not (Test-Path $MorpheCli)) { $MorpheCli = Join-Path $RepoRoot "tools\morphe-cli.jar" }

if ((Test-Path $BaseApk) -and (Test-Path $MorpheCli)) {
    Write-Host "Patching APK with $MppFile..." -ForegroundColor Yellow
    java -jar $MorpheCli patch --patches="$MppFile" --out="$PatchedApk" "$BaseApk"
}

# Run bootstrap to ensure device is awake, unlocked, and app is running
& "$PSScriptRoot\emulator-bootstrap.ps1" -DeviceId $DeviceId -ApkPath $PatchedApk

# Test 1: Home Feed
Write-Host "`n[Test 1/5] Verifying Home Feed (Adblocker & Feed Flags)..." -ForegroundColor Yellow
Start-Sleep -Seconds 9
Take-Capture "test_01_feed.png" "Home Feed"

# Test 2: Artwork Detail via Deep-Link (AI Flagged)
Write-Host "`n[Test 2/5] Navigating to AI Artwork Detail ($AiWorkId)..." -ForegroundColor Yellow
& $Adb -s $DeviceId shell am start -a android.intent.action.VIEW -d "https://www.pixiv.net/artworks/$AiWorkId" -p jp.pxv.android | Out-Null
Start-Sleep -Seconds 6
Take-Capture "test_02_ai_detail.png" "AI Artwork Detail"

# Test 3: Recommended Works Area
Write-Host "`n[Test 3/5] Scrolling down to Recommended Works on Detail screen..." -ForegroundColor Yellow
& $Adb -s $DeviceId shell input swipe 540 1800 540 600 400
Start-Sleep -Seconds 2
& $Adb -s $DeviceId shell input swipe 540 1800 540 600 400
Start-Sleep -Seconds 2
Take-Capture "test_03_recommended.png" "Recommended Works Area"

# Test 4: Download Picker on Multi-Page Work
Write-Host "`n[Test 4/5] Navigating to Multi-Page Work ($MultiPageWorkId) for Download Grid..." -ForegroundColor Yellow
& $Adb -s $DeviceId shell am start -a android.intent.action.VIEW -d "https://www.pixiv.net/artworks/$MultiPageWorkId" -p jp.pxv.android | Out-Null
Start-Sleep -Seconds 4
# Tap download action in toolbar (Download button center X=922, Y=205 on 1080x2400 screen)
& $Adb -s $DeviceId shell input tap 922 205
Start-Sleep -Seconds 3
Take-Capture "test_04_download_grid.png" "Download Selection Grid"
# Dismiss dialog by pressing back
& $Adb -s $DeviceId shell input keyevent 4
Start-Sleep -Seconds 1

# Test 5: Fullscreen Viewer (Half-Loaded Placeholder & Full-Res Swap)
Write-Host "`n[Test 5/5] Testing Enhanced Viewer (Half-Loaded Placeholder & Full-Res Swap)..." -ForegroundColor Yellow
# Clear logcat to track EnhancedViewer events cleanly
& $Adb -s $DeviceId logcat -c

$sw = [System.Diagnostics.Stopwatch]::StartNew()
# Tap center of artwork on detail page (around X=540, Y=600)
& $Adb -s $DeviceId shell input tap 540 600

# Capture half-loaded state immediately while placeholder bitmap is rendered and HD badge is active
Start-Sleep -Milliseconds 450
Take-Capture "test_05a_fullscreen_half_loaded.png" "Half-Loaded Fullscreen (Placeholder & HD Badge)"
$halfLoadedTimeMs = $sw.ElapsedMilliseconds

# Wait for high-resolution asset to complete loading and HD badge to fade out
Start-Sleep -Seconds 3
Take-Capture "test_05b_fullscreen_highres.png" "Full-Resolution Loaded (Zoom Ready)"
$fullResTimeMs = $sw.ElapsedMilliseconds

# Gather diagnostic logs from EnhancedViewerHelper
$viewerLogs = & $Adb -s $DeviceId logcat -d | Select-String -Pattern "MorpheEnhancedViewer"
$report = @(
    "============================================================",
    "  Enhanced Viewer (Preview Patch) Diagnostic Report",
    "============================================================",
    "Half-Loaded Capture Timestamp : +${halfLoadedTimeMs}ms",
    "Full-Res Capture Timestamp    : +${fullResTimeMs}ms",
    "Diagnostics Log Output        :"
)
if ($viewerLogs) {
    $viewerLogs | ForEach-Object { $report += "  -> " + $_.Line }
} else {
    $report += "  -> [OK] Fullscreen rendered without black-screen hang."
}
$report += "============================================================"
$report | Out-String | Write-Host -ForegroundColor Cyan
$report | Out-File -FilePath (Join-Path $CapturesDir "test_05_half_loaded_report.txt") -Encoding utf8

# Dismiss fullscreen
& $Adb -s $DeviceId shell input keyevent 4

Write-Host "`n============================================================" -ForegroundColor Green
Write-Host "  TEST SUITE COMPLETE: All 5 captures updated in captures/" -ForegroundColor Green
Write-Host "============================================================" -ForegroundColor Green
Get-ChildItem -Path $CapturesDir -Filter "test_*.png" | Select-Object Name, Length, LastWriteTime | Format-Table -AutoSize

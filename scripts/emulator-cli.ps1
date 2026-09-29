<#
.SYNOPSIS
    Streamlined, robust emulator automation toolkit for Pixiv testing.

.DESCRIPTION
    Provides fast, one-liner commands for:
      - Screen capture with automatic loading-screen avoidance and no SD card round-trips
      - UI hierarchy inspection and automatic element tapping
      - Robust deep-linking directly into Pixiv

.EXAMPLE
    . .\scripts\emulator-cli.ps1
    Capture-Screen "detail.png" -WaitForNode "jp.pxv.android:id/tool_bar"
    Tap-Node -Desc "Download"
    Dump-Ui "Download"
#>

$Script:Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $Script:Adb)) {
    $Script:Adb = (Get-Command adb -ErrorAction SilentlyContinue)?.Source
}
$Script:DefaultDevice = "emulator-5554"
$Script:RepoRoot = (Get-Item "$PSScriptRoot\..").FullName
$Script:CapturesDir = Join-Path $Script:RepoRoot "captures"

function Get-Adb {
    param([string]$DeviceId = $Script:DefaultDevice)
    return @($Script:Adb, "-s", $DeviceId)
}

function Invoke-AdbShell {
    param([string]$Cmd, [string]$DeviceId = $Script:DefaultDevice)
    $args = @("-s", $DeviceId, "shell") + $Cmd.Split(" ")
    & $Script:Adb $args
}

function Wait-For-ScreenReady {
    <#
    .SYNOPSIS
        Waits until the current screen is neither the Pixiv splash nor an empty spinner.
    #>
    param(
        [string]$MustHaveNode = "",
        [int]$TimeoutSec = 10,
        [string]$DeviceId = $Script:DefaultDevice
    )

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $TimeoutSec) {
        # Check current focused window
        $focus = & $Script:Adb -s $DeviceId shell "dumpsys window | grep mCurrentFocus"
        if ($focus -match "RoutingActivity" -or $focus -match "Splash") {
            Start-Sleep -Milliseconds 400
            continue
        }

        # Check UI hierarchy if a specific target node or general content is expected
        & $Script:Adb -s $DeviceId shell "uiautomator dump /sdcard/temp_dump.xml" 2>&1 | Out-Null
        $xmlContent = & $Script:Adb -s $DeviceId shell "cat /sdcard/temp_dump.xml" 2>&1

        # Check if spinner / splash is active
        $hasSpinner = ($xmlContent -match "ProgressBar" -or $xmlContent -match "CircularProgressIndicator")
        $hasPixivSplash = ($xmlContent -match "Splash" -or $xmlContent -match "brand_image")

        if (-not [string]::IsNullOrEmpty($MustHaveNode)) {
            if ($xmlContent -match [regex]::Escape($MustHaveNode)) {
                return $true
            }
        } else {
            # General readiness: not splash, and either no blocking spinner or has actual content
            $hasContent = ($xmlContent -match "tool_bar" -or $xmlContent -match "RecyclerView" -or $xmlContent -match "ThumbnailView")
            if ($hasContent -and -not $hasPixivSplash) {
                return $true
            }
        }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Capture-Screen {
    <#
    .SYNOPSIS
        Captures the emulator screen directly to a local file (no SD card roundtrip).
        Optionally waits until splash/loading spinners dismiss.
    #>
    param(
        [string]$Name = "screen.png",
        [string]$WaitForNode = "",
        [switch]$Wait,
        [int]$TimeoutSec = 8,
        [string]$DeviceId = $Script:DefaultDevice
    )

    New-Item -ItemType Directory -Force -Path $Script:CapturesDir | Out-Null
    $outPath = Join-Path $Script:CapturesDir $Name

    if ($Wait -or -not [string]::IsNullOrEmpty($WaitForNode)) {
        Wait-For-ScreenReady -MustHaveNode $WaitForNode -TimeoutSec $TimeoutSec -DeviceId $DeviceId | Out-Null
    }

    # Direct pipe capture via adb exec-out (zero filesystem overhead on device)
    & $Script:Adb -s $DeviceId exec-out screencap -p > $outPath
    Copy-Item $outPath (Join-Path $Script:CapturesDir "latest.png") -Force
    Write-Host "[Captured] $outPath ($((Get-Item $outPath).Length) bytes) -> mirrored to captures/latest.png" -ForegroundColor Green
    return $outPath
}

function Dump-Ui {
    <#
    .SYNOPSIS
        Dumps and filters UI nodes on the active screen.
    #>
    param(
        [string]$Filter = "",
        [string]$DeviceId = $Script:DefaultDevice
    )

    & $Script:Adb -s $DeviceId shell "uiautomator dump /sdcard/ui_dump.xml" | Out-Null
    $raw = & $Script:Adb -s $DeviceId shell "cat /sdcard/ui_dump.xml"
    if ([string]::IsNullOrEmpty($Filter)) {
        return $raw
    }

    # Match nodes containing the filter string
    $pattern = "<node[^>]+($([regex]::Escape($Filter)))[^>]+>"
    [regex]::Matches($raw, $pattern) | ForEach-Object {
        $node = $_.Value
        $bounds = [regex]::Match($node, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        $desc = [regex]::Match($node, 'content-desc="([^"]*)"').Groups[1].Value
        $text = [regex]::Match($node, 'text="([^"]*)"').Groups[1].Value
        $resId = [regex]::Match($node, 'resource-id="([^"]*)"').Groups[1].Value

        if ($bounds.Success) {
            $x1 = [int]$bounds.Groups[1].Value; $y1 = [int]$bounds.Groups[2].Value
            $x2 = [int]$bounds.Groups[3].Value; $y2 = [int]$bounds.Groups[4].Value
            $cx = [math]::Round(($x1 + $x2) / 2); $cy = [math]::Round(($y1 + $y2) / 2)
            [PSCustomObject]@{
                Text = $text
                Desc = $desc
                Id = $resId
                Bounds = "[$x1,$y1][$x2,$y2]"
                Center = "($cx, $cy)"
            }
        }
    }
}

function Tap-Node {
    <#
    .SYNOPSIS
        Finds a node by description or resource ID and taps its center.
    #>
    param(
        [string]$Desc = "",
        [string]$Id = "",
        [string]$Text = "",
        [string]$DeviceId = $Script:DefaultDevice
    )

    $query = if ($Desc) { $Desc } elseif ($Id) { $Id } else { $Text }
    $nodes = @(Dump-Ui -Filter $query -DeviceId $DeviceId)
    if ($nodes.Count -eq 0) {
        Write-Warning "No UI node found matching '$query'."
        return $false
    }

    $target = $nodes[0]
    if ($target.Center -match '\((\d+),\s*(\d+)\)') {
        $x = $Matches[1]; $y = $Matches[2]
        Write-Host "[Tap] Tapping '$query' at ($x, $y)..." -ForegroundColor Cyan
        & $Script:Adb -s $DeviceId shell input tap $x $y
        return $true
    }
    return $false
}

function Open-Work {
    <#
    .SYNOPSIS
        Deep-links directly into Pixiv for an artwork ID, bypassing chooser/browser.
    #>
    param(
        [Parameter(Mandatory=$true)][string]$IllustId,
        [switch]$Wait,
        [string]$DeviceId = $Script:DefaultDevice
    )

    Write-Host "[Nav] Opening artwork $IllustId in Pixiv..." -ForegroundColor Cyan
    & $Script:Adb -s $DeviceId shell am start -a android.intent.action.VIEW -d "https://www.pixiv.net/artworks/$IllustId" -p jp.pxv.android | Out-Null
    if ($Wait) {
        Wait-For-ScreenReady -MustHaveNode "jp.pxv.android:id/tool_bar" -DeviceId $DeviceId | Out-Null
    }
}

Write-Host "Pixiv Emulator Automation CLI loaded." -ForegroundColor Yellow
Write-Host "Available functions:" -ForegroundColor Gray
Write-Host "  Capture-Screen [-Name <name.png>] [-Wait] [-WaitForNode <id>]" -ForegroundColor DarkCyan
Write-Host "  Dump-Ui [-Filter <pattern>]" -ForegroundColor DarkCyan
Write-Host "  Tap-Node [-Desc <str>] [-Id <str>] [-Text <str>]" -ForegroundColor DarkCyan
Write-Host "  Open-Work -IllustId <id> [-Wait]" -ForegroundColor DarkCyan

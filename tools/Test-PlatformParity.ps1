[CmdletBinding()]
param(
    [switch]$Strict = $true
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$manifestPath = Join-Path $repoRoot 'docs\parity-manifest.json'
if (-not (Test-Path -LiteralPath $manifestPath)) {
    throw "Missing parity manifest: $manifestPath"
}

$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$errors = [System.Collections.Generic.List[string]]::new()

# 1. Version parity across Android & Windows
$nb4aPath = Join-Path $repoRoot 'nb4a.properties'
$nb4aContent = Get-Content -LiteralPath $nb4aPath -Raw -Encoding UTF8
$androidVersionMatch = [regex]::Match($nb4aContent, '(?m)^VERSION_NAME=(.+?)\s*$')
$actualAndroidVersion = if ($androidVersionMatch.Success) { $androidVersionMatch.Groups[1].Value.Trim() } else { '' }

$winGradlePath = Join-Path $repoRoot 'windows-app\build.gradle.kts'
$winGradleContent = Get-Content -LiteralPath $winGradlePath -Raw -Encoding UTF8
$winGradleMatch = [regex]::Match($winGradleContent, 'val\s+windowsPackageVersion\s*=\s*"([^"]+)"')
$actualWinGradleVersion = if ($winGradleMatch.Success) { $winGradleMatch.Groups[1].Value.Trim() } else { '' }

$winCtrlPath = Join-Path $repoRoot 'windows-app\src\main\kotlin\com\yokhan\vpn\windows\WindowsRuntimeController.kt'
$winCtrlContent = Get-Content -LiteralPath $winCtrlPath -Raw -Encoding UTF8
$winCtrlMatch = [regex]::Match($winCtrlContent, 'private\s+const\s+val\s+WINDOWS_APP_VERSION\s*=\s*"([^"]+)"')
$actualWinCtrlVersion = if ($winCtrlMatch.Success) { $winCtrlMatch.Groups[1].Value.Trim() } else { '' }

if ($actualAndroidVersion -ne $manifest.androidVersion) {
    $errors.Add("nb4a.properties VERSION_NAME='$actualAndroidVersion' != parity-manifest androidVersion='$($manifest.androidVersion)'")
}
if ($actualWinGradleVersion -ne $manifest.windowsVersion) {
    $errors.Add("windows-app/build.gradle.kts windowsPackageVersion='$actualWinGradleVersion' != parity-manifest windowsVersion='$($manifest.windowsVersion)'")
}
if ($actualWinCtrlVersion -ne $manifest.windowsVersion) {
    $errors.Add("WindowsRuntimeController WINDOWS_APP_VERSION='$actualWinCtrlVersion' != parity-manifest windowsVersion='$($manifest.windowsVersion)'")
}
if ($actualAndroidVersion -ne $actualWinGradleVersion) {
    $errors.Add("Platform version drift detected: Android='$actualAndroidVersion' vs Windows='$actualWinGradleVersion'")
}

# 2. Required capability parity anchors on both platforms
foreach ($cap in $manifest.requiredCapabilities) {
    $winFile = Join-Path $repoRoot $cap.windowsAnchor
    if (-not (Test-Path -LiteralPath $winFile)) {
        $errors.Add("[$($cap.id)] Missing Windows anchor file: $($cap.windowsAnchor)")
    } elseif ($cap.windowsPattern) {
        $winText = Get-Content -LiteralPath $winFile -Raw -Encoding UTF8
        if ($winText -notmatch $cap.windowsPattern) {
            $errors.Add("[$($cap.id)] Windows anchor '$($cap.windowsAnchor)' missing pattern '$($cap.windowsPattern)'")
        }
    }

    $andFile = Join-Path $repoRoot $cap.androidAnchor
    if (-not (Test-Path -LiteralPath $andFile)) {
        $errors.Add("[$($cap.id)] Missing Android anchor file: $($cap.androidAnchor)")
    } elseif ($cap.androidPattern) {
        $andText = Get-Content -LiteralPath $andFile -Raw -Encoding UTF8
        if ($andText -notmatch $cap.androidPattern) {
            $errors.Add("[$($cap.id)] Android anchor '$($cap.androidAnchor)' missing pattern '$($cap.androidPattern)'")
        }
    }
}

# 3. Divergences gate
$pendingDivergences = @($manifest.divergences)
if ($pendingDivergences.Count -gt 0) {
    foreach ($div in $pendingDivergences) {
        Write-Warning "[PARITY_DRIFT_PENDING_RECONCILE] id=$($div.id) platform=$($div.platform) missingOn=$($div.missingOn) summary=$($div.summary)"
        if ($Strict -or -not $div.waivedByUser) {
            $errors.Add("Unreconciled divergence '$($div.id)' missing on '$($div.missingOn)' ($($div.summary))")
        }
    }
}

if ($errors.Count -gt 0) {
    foreach ($err in $errors) {
        Write-Error $err -ErrorAction Continue
    }
    throw "Platform parity contract FAILED ($($errors.Count) error(s))."
}

Write-Host "[OK] Platform parity verified: unifiedVersion=$($manifest.unifiedVersion), Android=$actualAndroidVersion, Windows=$actualWinGradleVersion, capabilities=$($manifest.requiredCapabilities.Count), divergences=0"

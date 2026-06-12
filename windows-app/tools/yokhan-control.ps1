param(
    [Parameter(Position = 0)]
    [string]$Command = "status",

    [Parameter(Position = 1, ValueFromRemainingArguments = $true)]
    [string[]]$Rest = @()
)

$ErrorActionPreference = "Stop"

$windowsAppDir = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $windowsAppDir
$gradle = Join-Path $repoRoot "gradlew.bat"

if (-not (Test-Path $gradle)) {
    throw "gradlew.bat not found at $gradle"
}

$cliParts = @("--cli", $Command) + $Rest
$cliArgs = ($cliParts | ForEach-Object {
    if ($_ -match '\s') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ }
}) -join " "

& $gradle -q :windows-app:run "--args=$cliArgs" --no-daemon
$exitCode = $LASTEXITCODE

if ($Command -eq "probe-routes") {
    $latest = Get-ChildItem -Path (Join-Path $env:APPDATA "GATES\logs") -Filter "route-probe-*.txt" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($latest) {
        Write-Output ""
        Write-Output "Latest route probe report: $($latest.FullName)"
        Get-Content -Path $latest.FullName
    }
}

if ($Command -eq "support-bundle") {
    $latest = Get-ChildItem -Path (Join-Path $env:APPDATA "GATES\support") -Filter "support-bundle-*.zip" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($latest) {
        Write-Output ""
        Write-Output "Latest support bundle: $($latest.FullName)"
    }
}

exit $exitCode

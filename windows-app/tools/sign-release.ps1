param(
    [string]$Version = "",
    [string]$Thumbprint = $env:GATES_WINDOWS_SIGNING_THUMBPRINT,
    [string]$PfxPath = $env:GATES_WINDOWS_SIGNING_PFX,
    [string]$PfxPasswordEnv = "GATES_WINDOWS_SIGNING_PFX_PASSWORD",
    [string]$ExpectedSigner = $env:GATES_WINDOWS_UPDATE_SIGNER_SHA1,
    [string]$TimestampUrl = "http://timestamp.digicert.com"
)

$ErrorActionPreference = "Stop"

$windowsAppDir = Split-Path -Parent $PSScriptRoot
if (-not $Version) {
    $buildFile = Join-Path $windowsAppDir "build.gradle.kts"
    $buildText = Get-Content -LiteralPath $buildFile -Raw -Encoding UTF8
    $match = [regex]::Match($buildText, 'val\s+windowsPackageVersion\s*=\s*"([^"]+)"')
    if (-not $match.Success) {
        throw "Could not read windowsPackageVersion from $buildFile"
    }
    $Version = $match.Groups[1].Value
}

$artifacts = @(
    Join-Path $windowsAppDir "build\compose\binaries\main\exe\GATES-$Version.exe"
    Join-Path $windowsAppDir "build\compose\binaries\main\msi\GATES-$Version.msi"
)

foreach ($artifact in $artifacts) {
    if (-not (Test-Path -LiteralPath $artifact)) {
        throw "Artifact not found: $artifact"
    }
}

$signtool = Get-ChildItem "C:\Program Files (x86)\Windows Kits" -Recurse -Filter signtool.exe -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending |
    Select-Object -First 1
if (-not $signtool) {
    throw "signtool.exe not found. Install Windows SDK."
}

$commonArgs = @("sign", "/fd", "SHA256", "/tr", $TimestampUrl, "/td", "SHA256")
if ($PfxPath) {
    if (-not (Test-Path -LiteralPath $PfxPath)) {
        throw "PFX not found: $PfxPath"
    }
    $pfxPassword = [Environment]::GetEnvironmentVariable($PfxPasswordEnv)
    if (-not $pfxPassword) {
        throw "Environment variable $PfxPasswordEnv is required when GATES_WINDOWS_SIGNING_PFX is set."
    }
    $commonArgs += @("/f", $PfxPath, "/p", $pfxPassword)
} elseif ($Thumbprint) {
    $commonArgs += @("/sha1", ($Thumbprint -replace "\s", ""))
} else {
    throw "Set GATES_WINDOWS_SIGNING_THUMBPRINT or GATES_WINDOWS_SIGNING_PFX before signing."
}

foreach ($artifact in $artifacts) {
    & $signtool.FullName @commonArgs $artifact
    if ($LASTEXITCODE -ne 0) {
        throw "signtool failed for $artifact"
    }

    $signature = Get-AuthenticodeSignature -LiteralPath $artifact
    if ($signature.Status -ne "Valid") {
        throw "Signature is not valid for ${artifact}: $($signature.Status) $($signature.StatusMessage)"
    }
    if (-not $signature.SignerCertificate) {
        throw "Signer certificate is missing for $artifact"
    }
    if ($ExpectedSigner) {
        $actualSigner = $signature.SignerCertificate.Thumbprint -replace "\s", ""
        $normalizedExpected = $ExpectedSigner -replace "\s", ""
        if ($actualSigner -ne $normalizedExpected) {
            throw "Signer mismatch for ${artifact}: expected $normalizedExpected, got $actualSigner"
        }
    }
    Write-Output "Signed: $artifact"
}

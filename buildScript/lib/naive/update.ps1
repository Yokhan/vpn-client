param(
    [string]$Tag = ""
)

$ErrorActionPreference = "Stop"

$repo = "klzgrad/naiveproxy"
$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
$jniLibRoot = Join-Path $projectRoot "app\src\main\jniLibs"
$tempRoot = Join-Path $projectRoot ".tmp-naive-upstream"
$metadataPath = Join-Path $PSScriptRoot "upstream.json"

if ([string]::IsNullOrWhiteSpace($Tag)) {
    $release = gh release view -R $repo --json tagName,publishedAt,url,assets | ConvertFrom-Json
} else {
    $release = gh release view -R $repo $Tag --json tagName,publishedAt,url,assets | ConvertFrom-Json
}

$assets = [ordered]@{
    "arm64-v8a"   = "naiveproxy-plugin-$($release.tagName)-arm64-v8a.apk"
    "armeabi-v7a" = "naiveproxy-plugin-$($release.tagName)-armeabi-v7a.apk"
    "x86"         = "naiveproxy-plugin-$($release.tagName)-x86.apk"
    "x86_64"      = "naiveproxy-plugin-$($release.tagName)-x86_64.apk"
}

New-Item -ItemType Directory -Force $tempRoot | Out-Null
New-Item -ItemType Directory -Force $jniLibRoot | Out-Null

foreach ($assetName in $assets.Values) {
    gh release download -R $repo $release.tagName -p $assetName -D $tempRoot --clobber
}

Add-Type -AssemblyName System.IO.Compression.FileSystem

$metadata = [ordered]@{
    repository = $repo
    tag = $release.tagName
    published_at = $release.publishedAt
    release_url = $release.url
    assets = @()
}

foreach ($abi in $assets.Keys) {
    $assetName = $assets[$abi]
    $asset = $release.assets | Where-Object { $_.name -eq $assetName }
    if ($null -eq $asset) {
        throw "Missing release asset metadata for $assetName"
    }

    $apkPath = Join-Path $tempRoot $assetName
    $zip = [IO.Compression.ZipFile]::OpenRead((Resolve-Path $apkPath))
    try {
        $libEntry = $zip.Entries | Where-Object { $_.FullName -eq "lib/$abi/libnaive.so" }
        if ($null -eq $libEntry) {
            throw "libnaive.so not found inside $assetName"
        }

        $outDir = Join-Path $jniLibRoot $abi
        New-Item -ItemType Directory -Force $outDir | Out-Null
        $outPath = Join-Path $outDir "libnaive.so"
        [IO.Compression.ZipFileExtensions]::ExtractToFile($libEntry, $outPath, $true)
        $libHash = (Get-FileHash $outPath -Algorithm SHA256).Hash.ToLower()
    } finally {
        $zip.Dispose()
    }

    $metadata.assets += [ordered]@{
        abi = $abi
        apk = $assetName
        apk_sha256 = ($asset.digest -replace '^sha256:', '')
        lib_sha256 = $libHash
    }
}

$metadata | ConvertTo-Json -Depth 4 | Set-Content -Path $metadataPath -Encoding UTF8
Remove-Item -Recurse -Force $tempRoot

Write-Output "Updated vendored NaiveProxy binaries to $($release.tagName)"

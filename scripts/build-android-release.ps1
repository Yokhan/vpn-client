[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$SigningEnvironment)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$environment=(Resolve-Path -LiteralPath $SigningEnvironment).Path
if([IO.Path]::GetExtension($environment) -ne '.ps1'){throw 'Use the owner-provided private signing environment.'}
. $environment
$expected='b174e202d24f73eb703265f3270c2784eba0ae80d6a1ad0979ad9b87f7d4a504'
foreach($name in @('GATES_ANDROID_KEYSTORE_FILE','GATES_ANDROID_KEYSTORE_PASSWORD','GATES_ANDROID_KEY_ALIAS','GATES_ANDROID_KEY_PASSWORD')) {
    if([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))){throw ('Missing signing variable: '+$name)}
}
$env:GATES_SIGNING_VERIFY_PASSWORD=$env:GATES_ANDROID_KEYSTORE_PASSWORD
try {
    $certificate=& "$env:JAVA_HOME\bin\keytool.exe" -list -v -keystore $env:GATES_ANDROID_KEYSTORE_FILE -storepass:env GATES_SIGNING_VERIFY_PASSWORD -alias $env:GATES_ANDROID_KEY_ALIAS 2>&1
    if($LASTEXITCODE -ne 0){throw 'Cannot verify the configured signing certificate.'}
    $line=($certificate|Select-String 'SHA256:'|Select-Object -First 1).Line
    if(!$line -or ($line.Split(':',2)[1] -replace '[:\s]','').ToLowerInvariant() -ne $expected){throw 'Refuse release build with an unexpected signing certificate.'}
} finally {$env:GATES_SIGNING_VERIFY_PASSWORD=$null}

# Private legacy helpers may contain retired endpoints; release identity is project-owned.
$env:YOKHAN_KEYCLOAK_ISSUER='https://auth.yokhan.xyz/realms/yokhan'
$env:YOKHAN_KEYCLOAK_CLIENT_ID='gates-android'
$env:YOKHAN_KEYCLOAK_REDIRECT_SCHEME='gates'
$env:YOKHAN_KEYCLOAK_REDIRECT_URI='gates://auth/callback'
$env:YOKHAN_APP_API_BASE_URL='https://app.yokhan.xyz/gates'
if($env:YOKHAN_ROUTE_MANIFEST_ENABLED -notin @('true','1') -or
   $env:YOKHAN_ROUTE_MANIFEST_VERSION -ne '2' -or
   [string]::IsNullOrWhiteSpace($env:YOKHAN_ROUTE_MANIFEST_PUBLIC_KEYS)) {
    throw 'The approved v2 signed-manifest configuration is required.'
}
Push-Location $root
try {
    & .\gradlew.bat :app:assembleOssRelease --no-daemon --max-workers=1 --no-parallel '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process'
    if($LASTEXITCODE -ne 0){throw 'Android release assembly failed.'}
    $sdk=if($env:ANDROID_HOME){$env:ANDROID_HOME}else{Join-Path $env:LOCALAPPDATA 'Android\Sdk'}
    $tools=Join-Path $sdk 'build-tools\35.0.1'
    $artifacts=@(Get-ChildItem 'app/build/outputs/apk/oss/release' -Filter '*.apk')
    if(!$artifacts.Count){throw 'No APK artifacts were produced.'}
    foreach($apk in $artifacts) {
        $cert=& (Join-Path $tools 'apksigner.bat') verify --print-certs $apk.FullName
        if($LASTEXITCODE -ne 0){throw 'APK signature verification failed.'}
        $digest=($cert|Select-String 'certificate SHA-256 digest:'|Select-Object -First 1).Line
        if(!$digest -or ($digest -split ': ',2)[1].Trim().ToLowerInvariant() -ne $expected){throw 'Produced APK has an unexpected signer.'}
        $metadata=& (Join-Path $tools 'aapt.exe') dump badging $apk.FullName
        if($LASTEXITCODE -ne 0){throw 'APK metadata verification failed.'}
        $package=($metadata|Select-String '^package:'|Select-Object -First 1).Line
        if($package -notmatch "name='com.yokhan.gates'"){throw 'Unexpected APK package identity.'}
        [pscustomobject]@{File=$apk.Name;Bytes=$apk.Length;SignerVerified=$true;Package=$package;SHA256=(Get-FileHash $apk.FullName -Algorithm SHA256).Hash}
    }
} finally {Pop-Location}

Describe 'GATES installer service launcher contract' {
    It 'binds WinSW to the installed app-image launcher and exact service argument' {
        $xmlPath = Join-Path $PSScriptRoot '..\..\windows-service\src\main\resources\Gates.Service.xml'
        [xml]$serviceConfiguration = Get-Content -LiteralPath $xmlPath -Raw

        $serviceConfiguration.service.executable | Should Be '%BASE%\..\..\..\GATES.exe'
        $serviceConfiguration.service.arguments | Should Be '--runtime-service'
    }

    It 'requires the app-image launcher in the read-only MSI file contract' {
        $source = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'Test-GatesInstallerContract.ps1') -Raw

        $source | Should Match "'GATES.exe' = ''"
        $source | Should Match 'MSI is missing GATES.exe, the configured service launcher'
        $source | Should Match "'xray.exe' = 'app/resources/yokhan-runtime/windows'"
        $source | Should Match "'xray.sha256' = 'app/resources/yokhan-runtime/windows'"
    }

    It 'uses the guarded upgrade installer instead of installing over a running service' {
        $source = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'GATES-install.cmd') -Raw

        $source | Should Match 'sc\.exe stop GatesVpn'
        $source | Should Match 'taskkill /F /T /IM Gates\.Service\.exe'
        $source | Should Match 'Get-Process xray,sing-box'
        $source | Should Match 'explorer\.exe "C:\\Program Files\\GATES\\GATES\.exe"'
    }
}

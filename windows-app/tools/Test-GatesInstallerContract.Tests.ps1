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

    It 'uses the guarded upgrade installer and stops GatesVpn failure-restart before killing GATES.exe' {
        $source = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'GATES-install.cmd') -Raw

        $source | Should Match 'sc\.exe failure GatesVpn reset= 0'
        $source | Should Match 'sc\.exe stop GatesVpn'
        $source | Should Match 'taskkill /F /T /IM Gates\.Service\.exe'
        $source | Should Match 'Get-Process xray,sing-box'
        $source | Should Match 'explorer\.exe "C:\\Program Files\\GATES\\GATES\.exe"'
        ($source.IndexOf('sc.exe stop GatesVpn') -lt $source.IndexOf('taskkill /F /IM GATES.exe')) | Should Be $true
    }

    It 'embeds self-healing service stop and deferred RemoveExistingProducts in main.wxs' {
        $wxsPath = Join-Path $PSScriptRoot '..\src\main\jpackage\main.wxs'
        $wxs = Get-Content -LiteralPath $wxsPath -Raw

        $wxs | Should Match 'MSIRESTARTMANAGERCONTROL'
        $wxs | Should Match 'StopGatesRuntime'
        $wxs | Should Match 'StartGatesRuntime'
        $wxs | Should Match 'RemoveExistingProducts After="InstallExecute"'
    }

    It 'repairs the MSI and verifies the complete app image before starting the service' {
        $source = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'GATES-install.cmd') -Raw

        $source | Should Match 'msiexec\.exe /fa'
        $source | Should Match 'runtime\\lib\\modules'
        $source | Should Match 'Gates\.Service\.jar'
        $source | Should Match 'GATES\.cfg'
        $source | Should Match 'MISSING'
    }
}

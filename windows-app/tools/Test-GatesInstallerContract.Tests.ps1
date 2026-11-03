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
    }
}

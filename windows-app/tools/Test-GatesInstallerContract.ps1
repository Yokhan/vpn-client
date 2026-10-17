[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$MsiPath,

    [string]$ExpectedProductVersion,

    [string]$ExpectedUpgradeCode = '{33D7B058-0DA8-4ADA-9108-D4511DA47B21}'
)

$ErrorActionPreference = 'Stop'

function Get-MsiProperty {
    param(
        [Parameter(Mandatory = $true)]
        $Database,

        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    $escapedName = $Name.Replace("'", "''")
    $view = $Database.OpenView("SELECT Value FROM Property WHERE Property = '$escapedName'")
    try {
        [void]$view.Execute()
        $record = $view.Fetch()
        if ($null -eq $record) {
            throw "MSI is missing required property '$Name'."
        }
        return $record.StringData(1)
    }
    finally {
        if ($null -ne $record) {
            [void][Runtime.InteropServices.Marshal]::ReleaseComObject($record)
        }
        [void][Runtime.InteropServices.Marshal]::ReleaseComObject($view)
    }
}

function Get-MsiFileLocations {
    param(
        [Parameter(Mandatory = $true)]
        $Database
    )

    $directories = @{}
    $view = $Database.OpenView('SELECT Directory, Directory_Parent, DefaultDir FROM Directory')
    try {
        [void]$view.Execute()
        while ($null -ne ($record = $view.Fetch())) {
            try {
                $directoryId = $record.StringData(1)
                $parentId = $record.StringData(2)
                $defaultDirectory = $record.StringData(3)
                $targetDirectory = ($defaultDirectory -split ':', 2)[0]
                $directories[$directoryId] = @{
                    Parent = $parentId
                    Name = ($targetDirectory -split '\|', 2)[-1]
                }
            }
            finally {
                [void][Runtime.InteropServices.Marshal]::ReleaseComObject($record)
            }
        }
    }
    finally {
        [void][Runtime.InteropServices.Marshal]::ReleaseComObject($view)
    }

    $componentDirectories = @{}
    $view = $Database.OpenView('SELECT Component, Directory_ FROM Component')
    try {
        [void]$view.Execute()
        while ($null -ne ($record = $view.Fetch())) {
            try {
                $componentDirectories[$record.StringData(1)] = $record.StringData(2)
            }
            finally {
                [void][Runtime.InteropServices.Marshal]::ReleaseComObject($record)
            }
        }
    }
    finally {
        [void][Runtime.InteropServices.Marshal]::ReleaseComObject($view)
    }

    $locations = @{}
    $view = $Database.OpenView('SELECT FileName, Component_ FROM File')
    try {
        [void]$view.Execute()
        while ($null -ne ($record = $view.Fetch())) {
            try {
                $fileName = ($record.StringData(1) -split '\|', 2)[-1]
                $directoryId = $componentDirectories[$record.StringData(2)]
                $parts = [System.Collections.Generic.List[string]]::new()
                $seenDirectories = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)

                while ($directoryId -and $directoryId -ine 'INSTALLDIR') {
                    if (-not $seenDirectories.Add($directoryId) -or -not $directories.ContainsKey($directoryId)) {
                        $parts.Clear()
                        break
                    }
                    $directory = $directories[$directoryId]
                    $parts.Insert(0, $directory.Name)
                    $directoryId = $directory.Parent
                }

                if ($directoryId -ieq 'INSTALLDIR') {
                    if (-not $locations.ContainsKey($fileName)) {
                        $locations[$fileName] = [System.Collections.Generic.List[string]]::new()
                    }
                    $locations[$fileName].Add(($parts -join '/'))
                }
            }
            finally {
                [void][Runtime.InteropServices.Marshal]::ReleaseComObject($record)
            }
        }
    }
    finally {
        [void][Runtime.InteropServices.Marshal]::ReleaseComObject($view)
    }

    return $locations
}

$resolvedPath = (Resolve-Path -LiteralPath $MsiPath -ErrorAction Stop).Path
if ([IO.Path]::GetExtension($resolvedPath) -ine '.msi') {
    throw "Expected an .msi package: $resolvedPath"
}

$installer = $null
$database = $null
try {
    $installer = New-Object -ComObject WindowsInstaller.Installer
    $database = $installer.OpenDatabase($resolvedPath, 0)

    $productName = Get-MsiProperty -Database $database -Name 'ProductName'
    $productVersion = Get-MsiProperty -Database $database -Name 'ProductVersion'
    $productCode = Get-MsiProperty -Database $database -Name 'ProductCode'
    $upgradeCode = Get-MsiProperty -Database $database -Name 'UpgradeCode'

    if ($productName -ne 'GATES') {
        throw "Unexpected MSI ProductName '$productName'; expected 'GATES'."
    }
    if ($ExpectedProductVersion -and $productVersion -ne $ExpectedProductVersion) {
        throw "Unexpected MSI ProductVersion '$productVersion'; expected '$ExpectedProductVersion'."
    }
    if ($upgradeCode -ine $ExpectedUpgradeCode) {
        throw "Unexpected MSI UpgradeCode '$upgradeCode'; expected '$ExpectedUpgradeCode'."
    }

    $fileLocations = Get-MsiFileLocations -Database $database
    $requiredFiles = [ordered]@{
        'GATES.exe' = ''
        'Gates.Service.exe' = 'app/resources/gates-service'
        'Gates.Service.jar' = 'app/resources/gates-service'
        'Gates.Service.xml' = 'app/resources/gates-service'
        'sing-box.exe' = 'app/resources/yokhan-runtime/windows'
        'libcronet.dll' = 'app/resources/yokhan-runtime/windows'
        'LICENSE' = 'app/resources/yokhan-runtime/windows'
    }
    $missingFiles = @(
        foreach ($file in $requiredFiles.GetEnumerator()) {
            if (-not $fileLocations.ContainsKey($file.Key) -or $file.Value -notin $fileLocations[$file.Key]) {
                "$($file.Value)/$($file.Key)".TrimStart('/')
            }
        }
    )
    if ($missingFiles.Count -gt 0) {
        throw "MSI is missing required files at their native package paths: $($missingFiles -join ', ')"
    }

    [pscustomobject]@{
        MsiPath = $resolvedPath
        ProductName = $productName
        ProductVersion = $productVersion
        ProductCode = $productCode
        UpgradeCode = $upgradeCode
        VerifiedFiles = @(
            foreach ($file in $requiredFiles.GetEnumerator()) {
                if ($file.Value) { "$($file.Value)/$($file.Key)" } else { $file.Key }
            }
        )
        DatabaseMode = 'ReadOnly'
    }
}
finally {
    if ($null -ne $database) {
        [void][Runtime.InteropServices.Marshal]::ReleaseComObject($database)
    }
    if ($null -ne $installer) {
        [void][Runtime.InteropServices.Marshal]::ReleaseComObject($installer)
    }
}

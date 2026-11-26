<#
.SYNOPSIS
    Builds the portable Windows ZIP for AG Widgets.

.DESCRIPTION
    Produces ag-widgets-<ver>-windows-x64.zip from an explicit whitelist of
    root files and directories. Caches, secrets, Android sources, tests, and
    git metadata are never included.
    -DryRun validates the input and prints what would be packaged without
    creating the staging directory, the output directory, or the archive.

.PARAMETER Version
    Release version, for example v2.0.0 or 2.0.0. A leading "v" is stripped
    from file and folder names.

.PARAMETER OutputDir
    Directory that receives the archive. A relative path is resolved from the
    repository root. Defaults to "dist".

.PARAMETER SkipElectronInstall
    Do not install Electron. The archive is built from whatever is already in
    desktop\node_modules; without electron.exe it ships no Electron runtime
    (test mode).

.PARAMETER DryRun
    Print every action without writing anything.

.EXAMPLE
    .\scripts\package-windows.ps1 -Version v2.0.0

.EXAMPLE
    .\scripts\package-windows.ps1 -Version 2.0.0 -OutputDir "$env:TEMP\agw" -SkipElectronInstall
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$Version,
    [string]$OutputDir = "dist",
    [switch]$SkipElectronInstall,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot

function Write-Step([string]$Message) {
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Write-DryRun([string]$Message) {
    Write-Host "[dry-run] $Message" -ForegroundColor DarkYellow
}

# Files copied verbatim into the archive root (not recursive).
$RootFileAllowlist = @(
    "install.ps1",
    "start-widget.vbs",
    "config.example.json",
    "LICENSE",
    "README.md",
    "README.ru.md",
    "CHANGELOG.md"
)
$RequiredRootFiles = @("install.ps1", "start-widget.vbs")

# Directories copied recursively into the archive, keeping their structure.
$DirectoryAllowlist = @("agwidgets", "collector", "hook", "docs", "desktop")

# Relative directory prefixes that never go into the archive.
$ExcludedDirectoryPrefixes = @(
    "desktop/test",
    "desktop/scripts",
    "desktop/dist"
)

# File names that never go into the archive, wherever they appear.
$ExcludedFileNames = @(
    "secrets.json",
    "config.json",
    "local.properties",
    "keystore.properties",
    "status.json",
    "context.json"
)

# File extensions that never go into the archive (".lock" files are kept).
$ExcludedExtensions = @(".pyc", ".pyo", ".log", ".tmp", ".jks")

$ExcludedSegments = @("__pycache__", ".git", ".github", "android", "tests")

function Test-ExcludedRelativePath([string]$RelativePath) {
    $normalized = $RelativePath -replace '\\', '/'
    $segments = $normalized -split '/'

    # Defense in depth: caches, git metadata, and source-only trees anywhere in
    # the packed directories (mirrors the post-build archive verification).
    foreach ($segment in $segments) {
        if ($ExcludedSegments -contains $segment) {
            return $true
        }
    }
    if ($normalized -match '(^|/)node_modules/\.cache(/|$)') {
        return $true
    }
    foreach ($prefix in $ExcludedDirectoryPrefixes) {
        if ($normalized -eq $prefix -or $normalized.StartsWith($prefix + "/", [System.StringComparison]::OrdinalIgnoreCase)) {
            return $true
        }
    }

    $leaf = $segments[$segments.Length - 1]
    foreach ($name in $ExcludedFileNames) {
        if ($leaf -ieq $name) {
            return $true
        }
    }
    $extension = [System.IO.Path]::GetExtension($leaf)
    foreach ($excludedExtension in $ExcludedExtensions) {
        if ($extension -ieq $excludedExtension) {
            return $true
        }
    }
    return $false
}

function Get-PackagingPlan {
    $plan = New-Object System.Collections.ArrayList
    foreach ($fileName in $RootFileAllowlist) {
        $sourcePath = Join-Path $Root $fileName
        if (-not (Test-Path -LiteralPath $sourcePath)) {
            if ($RequiredRootFiles -contains $fileName) {
                throw "Required root file '$fileName' is missing from $Root. The portable archive cannot be built without it."
            }
            Write-Warning "Optional root file '$fileName' is missing; skipping it."
            continue
        }
        [void]$plan.Add([pscustomobject]@{
            Source = $sourcePath
            Relative = $fileName
        })
    }
    foreach ($directoryName in $DirectoryAllowlist) {
        $sourceDirectory = Join-Path $Root $directoryName
        if (-not (Test-Path -LiteralPath $sourceDirectory)) {
            Write-Warning "Directory '$directoryName' is missing; skipping it."
            continue
        }
        $files = @(Get-ChildItem -LiteralPath $sourceDirectory -Recurse -File -Force)
        foreach ($file in $files) {
            $rootRelative = $file.FullName.Substring($Root.Length) -replace '^[\\/]+', ''
            if (Test-ExcludedRelativePath -RelativePath $rootRelative) {
                continue
            }
            [void]$plan.Add([pscustomobject]@{
                Source = $file.FullName
                Relative = $rootRelative
            })
        }
    }
    return $plan.ToArray()
}

# ------------------------------------------------------------ input validation

$ver = $Version -replace '^v', ''
if ($Version -notmatch '^v?[0-9A-Za-z][0-9A-Za-z._-]*$' -or [string]::IsNullOrEmpty($ver) -or $ver -match '\.\.') {
    throw "Version '$Version' is not valid. Use a value such as v2.0.0 or 2.0.0."
}

$requiredPaths = @(
    (Join-Path $Root "agwidgets"),
    (Join-Path $Root "collector"),
    (Join-Path $Root "hook"),
    (Join-Path $Root "desktop\package.json")
)
foreach ($requiredPath in $requiredPaths) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Repository root looks wrong: '$requiredPath' was not found (root: $Root)."
    }
}

if ([System.IO.Path]::IsPathRooted($OutputDir)) {
    $outputDirAbs = $OutputDir
} else {
    $outputDirAbs = Join-Path $Root $OutputDir
}
$outputDirAbs = [System.IO.Path]::GetFullPath($outputDirAbs)

$stageName = "ag-widgets-$ver-windows-x64"
$archivePath = Join-Path $outputDirAbs "$stageName.zip"

# ------------------------------------------------------------- Electron runtime

$DesktopDir = Join-Path $Root "desktop"
$ElectronExe = Join-Path $DesktopDir "node_modules\electron\dist\electron.exe"

Write-Step "Checking Electron runtime"
if ($SkipElectronInstall) {
    if (Test-Path -LiteralPath $ElectronExe) {
        Write-Host "[OK] Electron runtime already present; -SkipElectronInstall was requested, leaving it untouched."
    } else {
        Write-Warning "Electron runtime is missing ($ElectronExe). The archive will be built without Electron (test mode)."
    }
} elseif (Test-Path -LiteralPath $ElectronExe) {
    Write-Host "[OK] Electron runtime already present"
} elseif ($DryRun) {
    Write-DryRun "Would run: npm ci --omit=optional; node node_modules\electron\install.js (in $DesktopDir)"
} else {
    Write-Step "Installing Electron dependencies"
    Push-Location $DesktopDir
    try {
        npm ci --omit=optional
        if ($LASTEXITCODE -ne 0) {
            throw "npm ci --omit=optional failed (exit code $LASTEXITCODE)"
        }
        node node_modules\electron\install.js
        if ($LASTEXITCODE -ne 0) {
            throw "Electron binary download failed (exit code $LASTEXITCODE)"
        }
    } finally {
        Pop-Location
    }
    if (-not (Test-Path -LiteralPath $ElectronExe)) {
        throw "Electron binary was not found at $ElectronExe after installation. Check network access or proxy settings and retry."
    }
    Write-Host "[OK] Electron runtime installed"
}

# --------------------------------------------------------------- packaging plan

$plan = @(Get-PackagingPlan)
$stagingRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("agw-package-" + [System.IO.Path]::GetRandomFileName())
$stageDir = Join-Path $stagingRoot $stageName

if ($DryRun) {
    Write-DryRun "Would create archive: $archivePath"
    Write-DryRun "Root files:"
    foreach ($fileName in $RootFileAllowlist) {
        if (Test-Path -LiteralPath (Join-Path $Root $fileName)) {
            Write-DryRun "  + $fileName"
        }
    }
    Write-DryRun "Directories (recursive, filtered):"
    foreach ($directoryName in $DirectoryAllowlist) {
        Write-DryRun "  + $directoryName/"
    }
    Write-DryRun "Would package $($plan.Count) files into $stageName.zip"
    Write-Host "[OK] Dry run complete - nothing was written"
    exit 0
}

# ------------------------------------------------------------------- real build

$entries = New-Object System.Collections.ArrayList
try {
    Write-Step "Staging files"
    New-Item -ItemType Directory -Force -Path $stageDir | Out-Null
    foreach ($item in $plan) {
        $destinationPath = Join-Path $stageDir $item.Relative
        $destinationParent = Split-Path -Parent $destinationPath
        if (-not (Test-Path -LiteralPath $destinationParent)) {
            New-Item -ItemType Directory -Force -Path $destinationParent | Out-Null
        }
        Copy-Item -LiteralPath $item.Source -Destination $destinationPath -Force
    }
    Write-Host "[OK] Staged $($plan.Count) files"

    Write-Step "Writing PORTABLE.txt"
    $buildDateLocal = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $buildDateUtc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-dd HH:mm:ss")
    $portableLines = @(
        "AG Widgets $ver - Windows portable build",
        "",
        "Built: $buildDateLocal (local time)",
        "Built: $buildDateUtc (UTC)",
        "",
        "1. Run .\install.ps1 (PowerShell). Node.js is not required: Electron is bundled.",
        '2. Launch "AG Widgets" from the Desktop or Start Menu shortcut.',
        "3. Pair the Android app (AG Widgets or AG Context) with the QR code in Settings -> Sync.",
        "",
        "This build contains no personal ntfy topic and no secrets."
    )
    $portableContent = ($portableLines -join "`r`n") + "`r`n"
    $portablePath = Join-Path $stageDir "PORTABLE.txt"
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($portablePath, $portableContent, $utf8NoBom)
    Write-Host "[OK] PORTABLE.txt written"

    if (-not (Test-Path -LiteralPath $outputDirAbs)) {
        New-Item -ItemType Directory -Force -Path $outputDirAbs | Out-Null
    }
    if (Test-Path -LiteralPath $archivePath) {
        Remove-Item -LiteralPath $archivePath -Force
    }
    Write-Step "Creating $archivePath"
    # -LiteralPath on the staging folder keeps ag-widgets-<ver>-windows-x64 as the archive root.
    Compress-Archive -LiteralPath $stageDir -DestinationPath $archivePath -CompressionLevel Optimal -Force

    Write-Step "Verifying the archive"
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop
    } catch {
        # PowerShell 7 / .NET Core already exposes ZipFile without loading an assembly.
    }
    $archive = [System.IO.Compression.ZipFile]::OpenRead($archivePath)
    $fileEntryCount = 0
    try {
        foreach ($entry in $archive.Entries) {
            if ($entry.Name -ne "") {
                $fileEntryCount++
            }
            [void]$entries.Add(($entry.FullName -replace '\\', '/'))
        }
    } finally {
        $archive.Dispose()
    }

    $requiredEntries = @(
        "$stageName/install.ps1",
        "$stageName/desktop/package.json",
        "$stageName/collector/collector.py",
        "$stageName/PORTABLE.txt"
    )
    $missingEntries = @()
    foreach ($requiredEntry in $requiredEntries) {
        if (-not ($entries -contains $requiredEntry)) {
            $missingEntries += $requiredEntry
        }
    }
    if ($missingEntries.Count -gt 0) {
        throw ("Archive is missing required entries: " + ($missingEntries -join ", "))
    }

    $forbiddenSegments = @(".git", "android", "tests", ".github", "__pycache__")
    $forbiddenFileNames = @("secrets.json", "config.json", "local.properties", "keystore.properties", "status.json", "context.json")
    $forbiddenSuffixes = @(".pyc", ".pyo", ".log", ".tmp", ".jks")
    $violations = @()
    foreach ($entryName in $entries) {
        $segments = $entryName -split '/'
        $leaf = $segments[$segments.Length - 1]
        $isForbidden = $false
        foreach ($forbiddenSegment in $forbiddenSegments) {
            if ($segments -contains $forbiddenSegment) {
                $isForbidden = $true
                break
            }
        }
        if (-not $isForbidden) {
            foreach ($forbiddenName in $forbiddenFileNames) {
                if ($leaf -ieq $forbiddenName) {
                    $isForbidden = $true
                    break
                }
            }
        }
        if (-not $isForbidden) {
            foreach ($forbiddenSuffix in $forbiddenSuffixes) {
                if ($leaf.ToLowerInvariant().EndsWith($forbiddenSuffix)) {
                    $isForbidden = $true
                    break
                }
            }
        }
        if ($isForbidden) {
            $violations += $entryName
        }
    }
    if ($violations.Count -gt 0) {
        $preview = ($violations | Select-Object -First 20) -join ", "
        throw ("Archive contains forbidden entries: " + $preview)
    }
    Write-Host "[OK] Archive verified: no forbidden paths"

    $archiveSizeMb = [math]::Round((Get-Item -LiteralPath $archivePath).Length / 1MB, 1)
    Write-Host ""
    Write-Host "[OK] Packaged $archivePath ($fileEntryCount files, $archiveSizeMb MB)"
    $hash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash
    Write-Host "SHA256: $hash"
} catch {
    Write-Host "[FAIL] $($_.Exception.Message)" -ForegroundColor Red
    exit 1
} finally {
    if (Test-Path -LiteralPath $stagingRoot) {
        Remove-Item -LiteralPath $stagingRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

exit 0

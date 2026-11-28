<#
.SYNOPSIS
  Build the signed community APKs locally and publish a GitHub Release.

.DESCRIPTION
  The signing key never leaves this machine: the repository intentionally has no
  Actions secrets, so `.github/workflows/release.yml` is a verification build
  only (it refuses to publish unsigned APKs). This script does what the workflow
  would do otherwise, from a trusted checkout:

    1. checks the requested tag against `versionName` in build.gradle.kts,
    2. builds both flavors with `-Pcommunity=true` and the local keystore,
    3. copies the APKs to `dist/` and writes `<file>.sha256` + `SHA256SUMS.txt`,
    4. prints the signing certificate so a wrong key is visible before publishing,
    5. creates the annotated tag, pushes it, creates the GitHub Release and
       uploads every asset.

  `-DryRun` stops after step 4 and changes nothing outside `dist/`.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\release-local.ps1 -Version v1.1.0 -DryRun
  powershell -ExecutionPolicy Bypass -File .\scripts\release-local.ps1 -Version v1.1.0
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Version,

    [string]$KeystoreProperties = $env:AGW_KEYSTORE_PROPERTIES,

    [string]$NotesFile,

    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$Android = Join-Path $Root 'android'
$Dist = Join-Path $Root 'dist'

function Step([string]$Text) {
    Write-Host ''
    Write-Host "== $Text" -ForegroundColor Cyan
}

function Fail([string]$Text) {
    Write-Host "ERROR: $Text" -ForegroundColor Red
    exit 1
}

# --- 1. Version and repository checks -------------------------------------

Step 'Validating the release'

if ($Version -notmatch '^v[0-9A-Za-z][0-9A-Za-z._-]*$' -or $Version.Contains('..')) {
    Fail "Version must look like v1.2.3 (got '$Version')"
}
$VersionNumber = $Version.Substring(1)

$GradleFile = Join-Path $Android 'app\build.gradle.kts'
if (-not (Test-Path $GradleFile)) { Fail "Missing $GradleFile" }
$GradleText = Get-Content -Raw -Path $GradleFile
$VersionNameMatch = [regex]::Match($GradleText, 'versionName\s*=\s*"([^"]+)"')
if (-not $VersionNameMatch.Success) { Fail 'build.gradle.kts has no versionName' }
$GradleVersion = $VersionNameMatch.Groups[1].Value
if ($GradleVersion -ne $VersionNumber) {
    # The workflow enforces the same rule: the published APK must report its tag.
    Fail "Tag $Version does not match versionName '$GradleVersion'. Bump android/app/build.gradle.kts (and desktop/package.json) first."
}

Push-Location $Root
try {
    $Branch = (git rev-parse --abbrev-ref HEAD).Trim()
    if ($Branch -ne 'main') { Fail "Release from 'main' (now on '$Branch')" }

    $Dirty = git status --porcelain
    if ($Dirty) { Fail "Working tree is not clean:`n$Dirty" }

    $ExistingTag = git tag --list $Version
    if ($ExistingTag) {
        Fail "Tag $Version already exists. Choose a new version instead of moving a published tag."
    }
} finally {
    Pop-Location
}

if (-not $KeystoreProperties) {
    $KeystoreProperties = Join-Path $env:LOCALAPPDATA 'ag-widgets\keys\keystore.properties'
}
if (-not (Test-Path $KeystoreProperties)) {
    Fail "No keystore properties at $KeystoreProperties (pass -KeystoreProperties or set AGW_KEYSTORE_PROPERTIES)"
}
$KeystoreProps = @{}
foreach ($line in Get-Content $KeystoreProperties) {
    if ($line -match '^\s*([^#=]+?)\s*=\s*(.*)$') { $KeystoreProps[$Matches[1]] = $Matches[2].Trim() }
}
foreach ($key in @('storeFile', 'storePassword', 'keyAlias', 'keyPassword')) {
    if (-not $KeystoreProps.ContainsKey($key) -or -not $KeystoreProps[$key]) {
        Fail "keystore.properties has no '$key'"
    }
}
Write-Host "  version: $VersionNumber (tag $Version)"
Write-Host "  keystore: $($KeystoreProps['storeFile']) [alias $($KeystoreProps['keyAlias'])]"

# --- 2. Build both flavors ------------------------------------------------

Step 'Building signed release APKs (-Pcommunity=true)'

if (-not $env:JAVA_HOME) {
    $candidate = 'C:\Program Files\Android\Android Studio\jbr'
    if (Test-Path $candidate) { $env:JAVA_HOME = $candidate }
    else { Fail 'JAVA_HOME is not set and Android Studio JBR was not found' }
}

$env:AGW_KEYSTORE_PROPERTIES = $KeystoreProperties
Push-Location $Android
try {
    & .\gradlew.bat ':app:assembleFullRelease' ':app:assembleContextRelease' '-Pcommunity=true' '--console=plain'
    if ($LASTEXITCODE -ne 0) { Fail "Gradle failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}

# --- 3. Collect assets ----------------------------------------------------

Step 'Collecting assets'

New-Item -ItemType Directory -Force -Path $Dist | Out-Null
$Assets = @(
    @{ Source = 'app\build\outputs\apk\full\release\app-full-release.apk';       Name = "AG-Widgets-$VersionNumber.apk" },
    @{ Source = 'app\build\outputs\apk\context\release\app-context-release.apk'; Name = "AG-Context-$VersionNumber.apk" }
)
foreach ($asset in $Assets) {
    $from = Join-Path $Android $asset.Source
    if (-not (Test-Path $from)) { Fail "Missing build output: $from" }
    Copy-Item -Force -Path $from -Destination (Join-Path $Dist $asset.Name)
}

$ApkSigner = Get-ChildItem (Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools') -Recurse -Filter 'apksigner.bat' |
    Sort-Object FullName -Descending | Select-Object -First 1
if (-not $ApkSigner) { Fail 'apksigner.bat not found in the Android SDK' }
$CertLine = & $ApkSigner.FullName verify --print-certs (Join-Path $Dist $Assets[0].Name) 2>&1 |
    Select-String 'certificate SHA-256 digest' | Select-Object -First 1
if (-not $CertLine) { Fail 'The APK is not signed: refusing to publish' }
Write-Host "  $($CertLine.Line.Trim())"

$HashLines = @()
foreach ($asset in $Assets) {
    $path = Join-Path $Dist $asset.Name
    $hash = (Get-FileHash -Algorithm SHA256 -Path $path).Hash.ToLowerInvariant()
    $line = "$hash  $($asset.Name)"
    [System.IO.File]::WriteAllText("$path.sha256", $line + "`n", [System.Text.Encoding]::ASCII)
    $HashLines += $line
    Write-Host "  $line"
}
[System.IO.File]::WriteAllText(
    (Join-Path $Dist 'SHA256SUMS.txt'),
    ($HashLines -join "`n") + "`n",
    [System.Text.Encoding]::ASCII
)

if ($DryRun) {
    Step 'Dry run finished'
    Write-Host "  assets are in $Dist; the tag was not created and nothing was published"
    exit 0
}

# --- 4. Tag, push, publish ------------------------------------------------

Step 'Creating and pushing the tag'

Push-Location $Root
try {
    & git tag -a $Version -m "AG Widgets $VersionNumber"
    if ($LASTEXITCODE -ne 0) { Fail 'git tag failed' }
    & git push origin $Version
    if ($LASTEXITCODE -ne 0) { Fail 'git push of the tag failed' }

    $remote = (git remote get-url origin).Trim()
} finally {
    Pop-Location
}

if ($remote -notmatch 'github\.com[:/](?<owner>[^/]+)/(?<repo>[^/.]+)(\.git)?$') {
    Fail "Cannot parse owner/repo from origin: $remote"
}
$Owner = $Matches['owner']
$Repo = $Matches['repo']

Step 'Publishing the GitHub Release'

$credentialInput = @('protocol=https', 'host=github.com', '') -join "`n"
$credentialOutput = $credentialInput | git credential fill
$tokenLine = $credentialOutput | Where-Object { $_ -like 'password=*' } | Select-Object -First 1
if (-not $tokenLine) { Fail 'git credential fill returned no GitHub token' }
$Token = $tokenLine.Substring('password='.Length)
$Headers = @{
    Authorization          = "Bearer $Token"
    Accept                 = 'application/vnd.github+json'
    'X-GitHub-Api-Version' = '2022-11-28'
}

if ($NotesFile) {
    if (-not (Test-Path $NotesFile)) { Fail "Notes file not found: $NotesFile" }
    $Body = Get-Content -Raw -Path $NotesFile
} else {
    $Body = @"
Community builds of AG Widgets $VersionNumber, both flavors, with a private ntfy topic left empty (``-Pcommunity=true``).

- **AG Widgets** (``dev.agwidgets``): Antigravity context and quotas, plus ZCode.
- **AG Context** (``dev.agwidgets.context``): Antigravity only, no ZCode client or token.

Verify the downloads with ``SHA256SUMS.txt``. Highlights and fix rationale are in ``CHANGELOG.md`` and ``docs/BUGFIXES.md``.
"@
}

$Prerelease = $VersionNumber.Contains('-')
$ReleasePayload = @{
    tag_name   = $Version
    name       = "AG Widgets $VersionNumber"
    body       = $Body
    draft      = $false
    prerelease = $Prerelease
} | ConvertTo-Json -Depth 4

$Release = Invoke-RestMethod -Method Post -Uri "https://api.github.com/repos/$Owner/$Repo/releases" `
    -Headers $Headers -ContentType 'application/json' -Body $ReleasePayload
$UploadUrl = ($Release.upload_url -replace '\{.*\}', '')
Write-Host "  release: $($Release.html_url)"

foreach ($asset in $Assets) {
    $path = Join-Path $Dist $asset.Name
    foreach ($file in @($path, "$path.sha256")) {
        $name = Split-Path -Leaf $file
        $uploaded = Invoke-RestMethod -Method Post -Uri "${UploadUrl}?name=$name" -Headers $Headers `
            -ContentType 'application/vnd.github+json' -InFile $file
        Write-Host "  uploaded $($uploaded.name) ($($uploaded.size) bytes)"
    }
}
$sums = Invoke-RestMethod -Method Post -Uri "${UploadUrl}?name=SHA256SUMS.txt" -Headers $Headers `
    -ContentType 'application/vnd.github+json' -InFile (Join-Path $Dist 'SHA256SUMS.txt')
Write-Host "  uploaded $($sums.name)"

Step 'Done'
Write-Host "  $($Release.html_url)"

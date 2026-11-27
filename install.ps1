<#
.SYNOPSIS
    Installs or removes AG Widgets on Windows: Antigravity hook, collector
    sidecar, CLI status line, Electron dependencies, and launcher shortcuts.

.DESCRIPTION
    Runtime configuration and secrets are written to
    %LOCALAPPDATA%\ag-widgets, never into the repository.
    -DryRun prints every action as "[dry-run] <action>" and changes nothing.
    -Uninstall reverts the Antigravity integration and removes the shortcuts;
    %LOCALAPPDATA%\ag-widgets is kept and reported at the end.

.PARAMETER PythonPath
    Path to python.exe. Defaults to the first "python" on PATH.

.PARAMETER NoShortcut
    Skip desktop and Start Menu shortcut creation.

.PARAMETER DryRun
    Print every action without creating files, backups, shortcuts, or running
    npm ci and the collector.

.PARAMETER Uninstall
    Remove the hook, sidecar, status line, and shortcuts. Works with -DryRun.

.EXAMPLE
    .\install.ps1

.EXAMPLE
    .\install.ps1 -DryRun

.EXAMPLE
    .\install.ps1 -Uninstall -DryRun

.EXAMPLE
    .\install.ps1 -Uninstall
#>
param(
    [string]$PythonPath = "",
    [switch]$NoShortcut,
    [switch]$DryRun,
    [switch]$Uninstall
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path

$HooksPath = Join-Path $HOME ".gemini\config\hooks.json"
$GeminiConfigPath = Join-Path $HOME ".gemini\config\config.json"
$SidecarDir = Join-Path $HOME ".gemini\config\sidecars\ag-widgets-collector"
$CliSettingsPath = Join-Path $HOME ".gemini\antigravity-cli\settings.json"
$RuntimeDir = Join-Path $env:LOCALAPPDATA "ag-widgets"
$BackupsRoot = Join-Path $RuntimeDir "backups"

function Write-Step([string]$Message) {
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Write-DryRun([string]$Message) {
    Write-Host "[dry-run] $Message" -ForegroundColor DarkYellow
}

function Backup-File([string]$Path, [string]$BackupDir) {
    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }
    New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
    $destination = Join-Path $BackupDir (Split-Path -Leaf $Path)
    Copy-Item -LiteralPath $Path -Destination $destination -Force
    Write-Host "    Backup: $destination"
}

function Read-JsonObject([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        return $null
    }
    $raw = Get-Content -LiteralPath $Path -Raw -Encoding UTF8
    if (-not $raw -or -not $raw.Trim()) {
        return $null
    }
    return $raw | ConvertFrom-Json
}

function Write-JsonObject([string]$Path, $Data) {
    $directory = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $directory)) {
        New-Item -ItemType Directory -Force -Path $directory | Out-Null
    }
    $json = $Data | ConvertTo-Json -Depth 32
    $tempPath = Join-Path $directory ([IO.Path]::GetRandomFileName() + ".tmp")
    try {
        $encoding = New-Object System.Text.UTF8Encoding($false)
        [IO.File]::WriteAllText($tempPath, $json + "`n", $encoding)
        Move-Item -LiteralPath $tempPath -Destination $Path -Force
    } finally {
        if (Test-Path -LiteralPath $tempPath) {
            Remove-Item -LiteralPath $tempPath -Force
        }
    }
}

function Test-NodeDependencies {
    $problems = @()
    $nodeCommand = Get-Command node -ErrorAction SilentlyContinue
    $npmCommand = Get-Command npm -ErrorAction SilentlyContinue
    if (-not $nodeCommand) {
        $problems += "Node.js 22.12+ is not installed (node was not found in PATH)."
    } else {
        $previousErrorAction = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $nodeVersionText = (& node -p "process.versions.node" 2>$null | Out-String).Trim()
        $nodeExitCode = $LASTEXITCODE
        $ErrorActionPreference = $previousErrorAction
        if ($nodeExitCode -ne 0 -or -not $nodeVersionText) {
            $problems += "The Node.js version could not be read."
        } else {
            $nodeVersion = $null
            try {
                $nodeVersion = [version]$nodeVersionText
            } catch {
                $nodeVersion = $null
            }
            if (-not $nodeVersion) {
                $problems += "The Node.js version '$nodeVersionText' could not be parsed."
            } elseif ($nodeVersion -lt [version]"22.12.0") {
                $problems += "Node.js $nodeVersionText is too old; 22.12+ is required."
            }
        }
    }
    if (-not $npmCommand) {
        $problems += "npm was not found in PATH."
    }
    if ($problems.Count -eq 0) {
        return
    }
    $message = (($problems -join " ") + " Install Node.js 22.12+ from https://nodejs.org/ and reopen the terminal.")
    if ($DryRun) {
        Write-Warning $message
    } else {
        throw $message
    }
}

if ($Uninstall) {
    # ---------------------------------------------------------------- uninstall
    Write-Step "Removing AG Widgets integration"
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $BackupDir = Join-Path $BackupsRoot "uninstall-$timestamp"

    # 1. Context hook.
    if (-not (Test-Path -LiteralPath $HooksPath)) {
        Write-Host "[OK] $HooksPath does not exist"
    } else {
        $hooks = Read-JsonObject -Path $HooksPath
        if ($hooks -and ($hooks.PSObject.Properties.Name -contains "ag-widgets-context")) {
            if ($DryRun) {
                Write-DryRun "Would remove hook 'ag-widgets-context' from $HooksPath"
            } else {
                Backup-File -Path $HooksPath -BackupDir $BackupDir
                $hooks.PSObject.Properties.Remove("ag-widgets-context")
                Write-JsonObject -Path $HooksPath -Data $hooks
                Write-Host "[OK] Removed hook 'ag-widgets-context' from $HooksPath"
            }
        } else {
            Write-Host "[OK] Hook 'ag-widgets-context' is not registered"
        }
    }

    # 2. Sidecar manifest directory.
    if (-not (Test-Path -LiteralPath $SidecarDir)) {
        Write-Host "[OK] $SidecarDir does not exist"
    } elseif ($DryRun) {
        Write-DryRun "Would remove $SidecarDir"
    } else {
        Remove-Item -LiteralPath $SidecarDir -Recurse -Force
        Write-Host "[OK] Removed $SidecarDir"
    }

    # 3. Sidecar entry in the Antigravity config.
    if (-not (Test-Path -LiteralPath $GeminiConfigPath)) {
        Write-Host "[OK] $GeminiConfigPath does not exist"
    } else {
        $geminiConfig = Read-JsonObject -Path $GeminiConfigPath
        $sidecars = $null
        if ($geminiConfig -and ($geminiConfig.PSObject.Properties.Name -contains "sidecars")) {
            $sidecars = $geminiConfig.sidecars
        }
        if ($sidecars -and ($sidecars.PSObject.Properties.Name -contains "ag-widgets-collector")) {
            if ($DryRun) {
                Write-DryRun "Would remove sidecar 'ag-widgets-collector' from $GeminiConfigPath"
            } else {
                Backup-File -Path $GeminiConfigPath -BackupDir $BackupDir
                $sidecars.PSObject.Properties.Remove("ag-widgets-collector")
                Write-JsonObject -Path $GeminiConfigPath -Data $geminiConfig
                Write-Host "[OK] Removed sidecar 'ag-widgets-collector' from $GeminiConfigPath"
            }
        } else {
            Write-Host "[OK] Sidecar 'ag-widgets-collector' is not enabled"
        }
    }

    # 4. CLI status line: restore the newest backup or remove only our entry.
    if (-not (Test-Path -LiteralPath $CliSettingsPath)) {
        Write-Host "[OK] $CliSettingsPath does not exist"
    } else {
        $cliSettings = Read-JsonObject -Path $CliSettingsPath
        if (-not $cliSettings -or -not ($cliSettings.PSObject.Properties.Name -contains "statusLine")) {
            Write-Host "[OK] No statusLine configured in $CliSettingsPath"
        } else {
            $backupSettingsPath = $null
            if (Test-Path -LiteralPath $BackupsRoot) {
                $backupDirs = @(Get-ChildItem -LiteralPath $BackupsRoot -Directory -Filter "install-*" | Sort-Object -Property Name -Descending)
                foreach ($backupDirItem in $backupDirs) {
                    $candidate = Join-Path $backupDirItem.FullName "settings.json"
                    if (-not (Test-Path -LiteralPath $candidate)) {
                        continue
                    }
                    # Skip backups created during a reinstall that already contain agy_statusline.py.
                    $candidateJson = Read-JsonObject -Path $candidate
                    $candidateCmd = ""
                    if ($candidateJson -and ($candidateJson.PSObject.Properties.Name -contains "statusLine") -and $candidateJson.statusLine -and $candidateJson.statusLine.command) {
                        $candidateCmd = [string]$candidateJson.statusLine.command
                    }
                    if ($candidateCmd -match 'agy_statusline\.py') {
                        continue
                    }
                    $backupSettingsPath = $candidate
                    break
                }
            }
            if ($backupSettingsPath) {
                if ($DryRun) {
                    Write-DryRun "Would restore statusLine in $CliSettingsPath from $backupSettingsPath"
                } else {
                    $backupSettings = Read-JsonObject -Path $backupSettingsPath
                    Backup-File -Path $CliSettingsPath -BackupDir $BackupDir
                    if ($backupSettings -and ($backupSettings.PSObject.Properties.Name -contains "statusLine")) {
                        $cliSettings | Add-Member -NotePropertyName "statusLine" -NotePropertyValue $backupSettings.statusLine -Force
                    } else {
                        $cliSettings.PSObject.Properties.Remove("statusLine")
                    }
                    Write-JsonObject -Path $CliSettingsPath -Data $cliSettings
                    Write-Host "[OK] Restored statusLine in $CliSettingsPath from $backupSettingsPath"
                }
            } else {
                $statusCommand = ""
                if ($cliSettings.statusLine -and $cliSettings.statusLine.command) {
                    $statusCommand = [string]$cliSettings.statusLine.command
                }
                if ($statusCommand -match 'agy_statusline\.py') {
                    if ($DryRun) {
                        Write-DryRun "Would remove statusLine from $CliSettingsPath"
                    } else {
                        Backup-File -Path $CliSettingsPath -BackupDir $BackupDir
                        $cliSettings.PSObject.Properties.Remove("statusLine")
                        Write-JsonObject -Path $CliSettingsPath -Data $cliSettings
                        Write-Host "[OK] Removed statusLine from $CliSettingsPath"
                    }
                } else {
                    Write-Warning "statusLine in $CliSettingsPath does not point to agy_statusline.py and no backup exists. Leaving it in place."
                }
            }
        }
    }

    # 5. Launcher shortcuts.
    $shortcutPaths = @(
        (Join-Path ([Environment]::GetFolderPath("Desktop")) "AG Widgets.lnk"),
        (Join-Path ([Environment]::GetFolderPath("Programs")) "AG Widgets.lnk")
    )
    foreach ($shortcutPath in $shortcutPaths) {
        if (-not (Test-Path -LiteralPath $shortcutPath)) {
            Write-Host "[OK] $shortcutPath does not exist"
        } elseif ($DryRun) {
            Write-DryRun "Would remove shortcut $shortcutPath"
        } else {
            Remove-Item -LiteralPath $shortcutPath -Force
            Write-Host "[OK] Removed shortcut $shortcutPath"
        }
    }

    # 6. Runtime data is intentionally kept.
    if (Test-Path -LiteralPath $RuntimeDir) {
        Write-Host ""
        Write-Host "Runtime data was kept in $RuntimeDir" -ForegroundColor Yellow
        Write-Host "Delete it manually if you no longer need the stored topic and secrets:"
        Write-Host "    Remove-Item -LiteralPath `"$RuntimeDir`" -Recurse -Force"
    } else {
        Write-Host "[OK] $RuntimeDir does not exist"
    }

    Write-Host ""
    Write-Host "=========================================" -ForegroundColor Cyan
    if ($DryRun) {
        Write-Host "Dry run complete - nothing was changed" -ForegroundColor Yellow
        Write-Host "Run .\install.ps1 -Uninstall without -DryRun to apply the steps above."
    } else {
        Write-Host "AG Widgets was removed from Antigravity and the shortcuts" -ForegroundColor Green
    }
    Write-Host "=========================================" -ForegroundColor Cyan
    exit 0
}

# --------------------------------------------------------------------- install

# 1. Dependency checks happen before any change.
if (-not $PythonPath) {
    $pythonCommand = Get-Command python -ErrorAction SilentlyContinue
    if (-not $pythonCommand -or -not $pythonCommand.Source) {
        throw "Python 3.10+ not found in PATH. Install it from https://www.python.org/downloads/ and reopen the terminal, or pass -PythonPath explicitly."
    }
    $PythonPath = $pythonCommand.Source
}
if ($PythonPath -like "*\WindowsApps\*") {
    throw "The 'python' on PATH is the Microsoft Store stub ($PythonPath). Install Python 3.10+ from https://www.python.org/downloads/ and pass -PythonPath explicitly."
}
if (-not (Test-Path -LiteralPath $PythonPath)) {
    throw "Python not found at '$PythonPath'. Pass -PythonPath with the full path to python.exe."
}

$previousErrorAction = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& $PythonPath -c "import sys; assert sys.version_info >= (3, 10)" 2>$null | Out-Null
$pythonExitCode = $LASTEXITCODE
$ErrorActionPreference = $previousErrorAction
if ($pythonExitCode -ne 0) {
    throw "Python 3.10+ is required, but '$PythonPath' is older or broken. Install it from https://www.python.org/downloads/."
}
$pythonVersion = (& $PythonPath -c "import sys; print('.'.join(str(part) for part in sys.version_info[:3]))" 2>$null | Out-String).Trim()

Test-NodeDependencies

Write-Step "Python: $pythonVersion ($PythonPath)"
Write-Step "Project: $Root"
if ($DryRun) {
    Write-Host "[dry-run] No files, backups, or shortcuts will be created." -ForegroundColor DarkYellow
}

# 2. Back up the existing Antigravity configuration.
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$BackupDir = Join-Path $BackupsRoot "install-$timestamp"
if ($DryRun) {
    Write-DryRun "Would back up hooks.json, config.json, and settings.json to $BackupDir"
} else {
    Write-Step "Backing up Antigravity configuration"
    Backup-File -Path $HooksPath -BackupDir $BackupDir
    Backup-File -Path $GeminiConfigPath -BackupDir $BackupDir
    Backup-File -Path $CliSettingsPath -BackupDir $BackupDir
    if (Test-Path -LiteralPath $BackupDir) {
        Write-Host "[OK] Backups: $BackupDir"
    } else {
        Write-Host "[OK] No existing configuration to back up"
    }
}

# 3. Register the Antigravity hook, sidecar, CLI status line, and Python path.
Write-Step "Registering Antigravity integration"
if ($DryRun) {
    Write-DryRun "Would register hook 'ag-widgets-context' in $HooksPath"
    Write-DryRun "Would write the sidecar manifest to $(Join-Path $SidecarDir "sidecar.json")"
    Write-DryRun "Would enable sidecar 'ag-widgets-collector' in $GeminiConfigPath"
    Write-DryRun "Would configure statusLine in $CliSettingsPath"
    Write-DryRun "Would ensure $(Join-Path $RuntimeDir "config.json") and store python_path"
} else {
    $setupScript = @'
import json
import os
import sys
from pathlib import Path

root = Path(sys.argv[1]).resolve()
python = sys.argv[2]
home = Path.home()


def load(path):
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def save(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp_path = path.with_name(f"{path.name}.{os.getpid()}.tmp")
    try:
        temp_path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        os.replace(temp_path, path)
    finally:
        try:
            temp_path.unlink(missing_ok=True)
        except OSError:
            pass


# (a) Context hook.
hooks_path = home / ".gemini" / "config" / "hooks.json"
hooks = load(hooks_path)
hook_command = f'"{python}" "{root / "hook" / "context_hook.py"}"'
hooks["ag-widgets-context"] = {
    "PostInvocation": [{"type": "command", "command": hook_command, "timeout": 5}],
    "Stop": [{"type": "command", "command": hook_command, "timeout": 5}],
}
save(hooks_path, hooks)
print(f"[OK] Hook registered in {hooks_path}")

# (b) Collector sidecar, scheduled once per minute.
sidecar_path = home / ".gemini" / "config" / "sidecars" / "ag-widgets-collector" / "sidecar.json"
sidecar = {
    "display_name": "AG Widgets collector",
    "description": "Collects Antigravity quota, ZCode status, and active-chat context",
    "builtin": "schedule",
    "args": ["* * * * *", python, str(root / "collector" / "collector.py")],
}
save(sidecar_path, sidecar)
print(f"[OK] Sidecar manifest written to {sidecar_path}")

# (c) Enable the sidecar without dropping foreign entries.
antigravity_config_path = home / ".gemini" / "config" / "config.json"
antigravity_config = load(antigravity_config_path)
sidecars = antigravity_config.get("sidecars")
if not isinstance(sidecars, dict):
    sidecars = {}
sidecars["ag-widgets-collector"] = {"enabled": True}
antigravity_config["sidecars"] = sidecars
save(antigravity_config_path, antigravity_config)
print(f"[OK] Sidecar enabled in {antigravity_config_path}")

# (d) CLI status line. Never replace a foreign status line silently.
cli_config_path = home / ".gemini" / "antigravity-cli" / "settings.json"
cli_config = load(cli_config_path)
existing_status_line = cli_config.get("statusLine")
if isinstance(existing_status_line, dict):
    existing_command = str(existing_status_line.get("command", ""))
    if "agy_statusline.py" not in existing_command:
        print(f"[WARN] Replacing a statusLine from another tool in {cli_config_path}; a backup is in %LOCALAPPDATA%\\ag-widgets\\backups.")
cli_config["statusLine"] = {
    "type": "command",
    "command": f'"{python}" "{root / "collector" / "agy_statusline.py"}"',
    "stack_with_default": True,
}
save(cli_config_path, cli_config)
print(f"[OK] Status line configured in {cli_config_path}")

# (e) Runtime configuration and the interpreter for on-demand refresh.
local_app_data = os.environ.get("LOCALAPPDATA") or str(Path.home() / "AppData" / "Local")
config_path = Path(local_app_data) / "ag-widgets" / "config.json"
if config_path.exists():
    config = load(config_path)
else:
    example_path = root / "config.example.json"
    config = load(example_path) if example_path.exists() else {}
    print(f"[OK] Created {config_path} from config.example.json")
config["python_path"] = python
save(config_path, config)
print(f"[OK] Python path saved in {config_path}")
'@

    $tempBase = [IO.Path]::GetTempFileName()
    $tempScript = $tempBase + ".py"
    try {
        $encoding = New-Object System.Text.UTF8Encoding($false)
        [IO.File]::WriteAllText($tempScript, $setupScript, $encoding)
        & $PythonPath $tempScript $Root $PythonPath
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to register Antigravity integration (exit code $LASTEXITCODE)"
        }
    } finally {
        if (Test-Path -LiteralPath $tempScript) {
            Remove-Item -LiteralPath $tempScript -Force
        }
        if (Test-Path -LiteralPath $tempBase) {
            Remove-Item -LiteralPath $tempBase -Force
        }
    }
}

# 4. Electron dependencies.
$DesktopDir = Join-Path $Root "desktop"
$ElectronDir = Join-Path $DesktopDir "node_modules\electron"
$ElectronExe = Join-Path $DesktopDir "node_modules\electron\dist\electron.exe"
$ElectronInstallScript = Join-Path $DesktopDir "node_modules\electron\install.js"
$LockPath = Join-Path $DesktopDir "package-lock.json"
if ((Test-Path -LiteralPath $ElectronDir) -and (Test-Path -LiteralPath $ElectronExe)) {
    Write-Host "[OK] Electron dependencies and binary already installed"
} else {
    if (-not (Test-Path -LiteralPath $ElectronDir)) {
        if (-not (Test-Path -LiteralPath $LockPath)) {
            if ($DryRun) {
                Write-Warning "desktop\package-lock.json is missing; npm ci cannot run."
            } else {
                throw "desktop\package-lock.json is missing, so 'npm ci' cannot run. Restore the lock file and retry."
            }
        } elseif ($DryRun) {
            Write-DryRun "Would run: npm ci (in $DesktopDir)"
        } else {
            Write-Step "Installing Electron dependencies"
            Push-Location $DesktopDir
            try {
                npm ci
                if ($LASTEXITCODE -ne 0) { throw "npm ci failed (exit code $LASTEXITCODE)" }
            } finally {
                Pop-Location
            }
        }
    }

    if (-not (Test-Path -LiteralPath $ElectronExe)) {
        if ($DryRun) {
            Write-DryRun "Would run: node node_modules\electron\install.js (in $DesktopDir)"
        } else {
            Write-Step "Downloading the Electron binary"
            if (-not (Test-Path -LiteralPath $ElectronInstallScript)) {
                throw "Electron install script not found at $ElectronInstallScript, so the binary cannot be downloaded. Run 'npm ci' in $DesktopDir and retry."
            }
            Push-Location $DesktopDir
            try {
                node node_modules\electron\install.js
                if ($LASTEXITCODE -ne 0) { throw "Electron binary download failed (exit code $LASTEXITCODE)" }
            } finally {
                Pop-Location
            }
            if (-not (Test-Path -LiteralPath $ElectronExe)) {
                throw "Electron binary was not downloaded at $ElectronExe. Check network access or proxy settings and retry."
            }
        }
    }
}

# 5. Run the collector once to create the ntfy topic and verify the pipeline.
Write-Step "Running the collector for the first time"
$result = $null
if ($DryRun) {
    Write-DryRun "Would run: $PythonPath collector\collector.py --force --show-topic"
} else {
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $PythonPath (Join-Path $Root "collector\collector.py") --force --show-topic 2>&1
        $collectorExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    Write-Host $output
    $jsonLine = @($output | ForEach-Object { "$_" } | Where-Object { $_ -match '^\s*\{' } | Select-Object -Last 1)
    if (-not $jsonLine) {
        throw "Collector did not return JSON. See the output above."
    }
    $result = $jsonLine | ConvertFrom-Json
    if ($collectorExitCode -ne 0) {
        throw "Collector failed (exit code $collectorExitCode). See the output above."
    }
    if ($result.status_written -ne $true) {
        throw "Collector did not write the status (status_written=$($result.status_written)). See the output above."
    }
}

# 6. Launcher shortcuts.
if ($NoShortcut) {
    Write-Host "[OK] Shortcuts skipped (-NoShortcut)"
} elseif ($DryRun) {
    Write-DryRun "Would create 'AG Widgets.lnk' on the desktop and in the Start Menu"
} else {
    Write-Step "Creating shortcuts"
    $iconPath = Join-Path $Root "desktop\assets\icon.ico"
    $launcher = Join-Path $Root "start-widget.vbs"
    $wscript = Join-Path $env:WINDIR "System32\wscript.exe"
    $shortcutPaths = @(
        (Join-Path ([Environment]::GetFolderPath("Desktop")) "AG Widgets.lnk"),
        (Join-Path ([Environment]::GetFolderPath("Programs")) "AG Widgets.lnk")
    )
    $shell = New-Object -ComObject WScript.Shell
    foreach ($shortcutPath in $shortcutPaths) {
        $shortcut = $shell.CreateShortcut($shortcutPath)
        $shortcut.TargetPath = $wscript
        $shortcut.Arguments = '"' + $launcher + '"'
        $shortcut.WorkingDirectory = $Root
        $shortcut.IconLocation = "$iconPath,0"
        $shortcut.Description = "AG Widgets - Antigravity and ZCode"
        $shortcut.Save()
    }
    Write-Host "[OK] Desktop and Start Menu shortcuts created"
}

Write-Host ""
Write-Host "=========================================" -ForegroundColor Cyan
if ($DryRun) {
    Write-Host "Dry run complete - nothing was changed" -ForegroundColor Yellow
    Write-Host "Run .\install.ps1 without -DryRun to apply the steps above."
} else {
    Write-Host "Installation complete" -ForegroundColor Green
    if ($result -and $result.topic) {
        Write-Host "Private ntfy topic: $($result.topic)" -ForegroundColor Yellow
        Write-Host "(Keep it private. Enter the same topic in the Android app.)"
    } else {
        Write-Host "Private ntfy topic: not returned by the collector" -ForegroundColor Yellow
    }
    Write-Host ""
    Write-Host "Next steps:"
    Write-Host "  1. Check the installation:"
    Write-Host "       python collector\collector.py --doctor"
    Write-Host "  2. Optional: sign in to ZCode for vouchers and balance:"
    Write-Host "       python collector\zcode_auth.py"
    Write-Host "  3. Launch the desktop widget from the 'AG Widgets' shortcut"
    Write-Host "     or run: cd desktop; npm start"
    Write-Host "  4. Install the Android APK, enter the ntfy topic, and tap 'Sign in to Z.ai'"
}
Write-Host "=========================================" -ForegroundColor Cyan
exit 0

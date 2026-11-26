"""Release packaging and installer workflow tests.

The project ships two GitHub-hosted artifacts that must stay in sync with the
repository's signing and packaging contracts:

* ``.github/workflows/release.yml`` builds and publishes the Android APKs and the
  portable Windows ZIP.  It must keep the existing ``AGW_KEYSTORE_PROPERTIES``
  signing scheme, both product flavors, a zero-leak check, and least-privilege
  permissions.
* ``scripts/package-windows.ps1`` builds a Windows portability ZIP; ``install.ps1``
  must accept a pre-bundled Electron runtime so the portable ZIP never runs
  ``npm ci``.

The static tests are plain text/regex checks (PyYAML is not installed in CI).
The runtime tests exercise the PowerShell scripts on Windows only and always
work on a synthetic repository tree, never on the real ``desktop/node_modules``.
"""

from __future__ import annotations

import re
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

WORKFLOW = ROOT / ".github" / "workflows" / "release.yml"
PACKAGER = ROOT / "scripts" / "package-windows.ps1"
INSTALLER = ROOT / "install.ps1"
GRADLE = ROOT / "android" / "app" / "build.gradle.kts"

JOB_IDS = ("verify", "build-android", "build-windows", "publish-release")

# Every ``uses:`` target must be one of these first-party actions.
ALLOWED_ACTIONS = frozenset(
    {
        "actions/checkout",
        "actions/setup-python",
        "actions/setup-node",
        "actions/setup-java",
        "actions/upload-artifact",
        "actions/download-artifact",
        "gradle/actions/setup-gradle",
    }
)


def _read(path: Path) -> str:
    """Read a text file, tolerating a UTF-8 BOM and CRLF line endings."""
    text = path.read_text(encoding="utf-8-sig")
    return text.replace("\r\n", "\n").replace("\r", "\n")


def _yaml_block(text: str, key: str) -> str:
    """Return the indented body of ``key:`` from *text* (``""`` when absent).

    The parser stops at the first non-blank line whose indentation is not deeper
    than the ``key:`` line, which is enough to isolate a job or an input entry
    without a YAML dependency.
    """
    lines = text.splitlines()
    pattern = re.compile(rf"^([ \t]*){re.escape(key)}\s*:\s*$")
    for index, line in enumerate(lines):
        match = pattern.match(line)
        if not match:
            continue
        indent = len(match.group(1))
        block = [line]
        for candidate in lines[index + 1 :]:
            if not candidate.strip():
                block.append(candidate)
                continue
            current_indent = len(candidate) - len(candidate.lstrip())
            if current_indent <= indent:
                break
            block.append(candidate)
        return "\n".join(block)
    return ""


def _needs_contains(block: str, name: str) -> bool:
    """True when a job block lists *name* in ``needs:``."""
    for match in re.finditer(r"^[ \t]*needs\s*:[ \t]*(.*)$", block, re.MULTILINE):
        rest = match.group(1).strip()
        if rest:
            if rest.startswith("["):
                items = [item.strip().strip("'\"") for item in rest.strip("[]").split(",")]
            else:
                items = [rest.strip("'\"")]
            if name in items:
                return True
            continue
        # Block-style list on the following lines.
        for line in block[match.end() :].splitlines():
            if not line.strip():
                continue
            if not re.match(r"^[ \t]*-[ \t]*\S", line):
                break
            item = line.strip().lstrip("-").strip().strip("'\"")
            if item == name:
                return True
    return False


def _top_level_block(text: str, key: str) -> str:
    """Return the body of an unindented top-level ``key:`` block."""
    lines = text.splitlines()
    pattern = re.compile(rf"^{re.escape(key)}\s*:\s*$")
    for index, line in enumerate(lines):
        if not pattern.match(line):
            continue
        block = [line]
        for candidate in lines[index + 1 :]:
            if not candidate.strip():
                block.append(candidate)
                continue
            if not candidate.startswith((" ", "\t")):
                break
            block.append(candidate)
        return "\n".join(block)
    return ""


def _gradle_commands(text: str) -> list[str]:
    """Collect logical ``gradlew`` invocations, joining ``\\`` continuations."""
    lines = text.splitlines()
    commands: list[str] = []
    index = 0
    while index < len(lines):
        line = lines[index]
        stripped = line.lstrip()
        if "gradlew" in line and not stripped.startswith("#") and "chmod" not in line:
            chunk = [line]
            while chunk[-1].rstrip().endswith("\\") and index + 1 < len(lines):
                index += 1
                chunk.append(lines[index])
            commands.append("\n".join(chunk))
        index += 1
    return commands


def _first_call_index(text: str, function_name: str) -> int:
    """Character offset of the first call to *function_name*.

    Works for both a standalone call and an inline ``if (...) { name }`` form;
    the function definition and comment lines are skipped.
    """
    definition = re.compile(rf"\bfunction\s+{re.escape(function_name)}\b", re.IGNORECASE)
    for match in re.finditer(rf"\b{re.escape(function_name)}\b", text):
        line_start = text.rfind("\n", 0, match.start()) + 1
        line_end = text.find("\n", match.end())
        if line_end == -1:
            line_end = len(text)
        line = text[line_start:line_end]
        if definition.search(line):
            continue
        if line.lstrip().startswith("#"):
            continue
        return match.start()
    return -1


class ReleaseWorkflowStaticTests(unittest.TestCase):
    """Static contract for ``.github/workflows/release.yml``."""

    def _text(self) -> str:
        self.assertTrue(WORKFLOW.exists(), f"{WORKFLOW} does not exist")
        text = _read(WORKFLOW)
        self.assertTrue(text.strip(), f"{WORKFLOW} is empty")
        return text

    def test_workflow_exists_and_non_empty(self) -> None:
        self.assertTrue(WORKFLOW.is_file(), f"{WORKFLOW} is not a regular file")
        text = self._text()
        self.assertGreater(len(text), 500, "release workflow looks truncated")

    def test_triggers_and_dispatch_inputs(self) -> None:
        text = self._text()

        self.assertIn("tags:", text, "push tags trigger is missing")
        tags_ok = bool(
            re.search(r"tags\s*:\s*\[[^\]]*['\"]v\*['\"]", text)
        ) or bool(
            re.search(
                r"tags\s*:\s*\n(?:[ \t]+.*\n)*?[ \t]+-[ \t]*['\"]?v\*['\"]?",
                text,
            )
        )
        self.assertTrue(tags_ok, "push trigger must include the 'v*' tag pattern")

        self.assertIn("workflow_dispatch", text, "workflow_dispatch trigger is missing")

        dispatch = _yaml_block(text, "workflow_dispatch")
        scope = dispatch or text
        for name in ("tag", "prerelease", "publish"):
            block = _yaml_block(scope, name)
            self.assertTrue(block, f"workflow_dispatch input {name!r} is missing")

        prerelease = _yaml_block(scope, "prerelease")
        self.assertTrue(
            re.search(r"^\s*default\s*:\s*true\s*$", prerelease, re.MULTILINE),
            "prerelease input must default to true",
        )

        publish = _yaml_block(scope, "publish")
        self.assertTrue(
            re.search(r"^\s*default\s*:\s*false\s*$", publish, re.MULTILINE),
            "publish input must default to false",
        )

    def test_four_jobs_and_needs_graph(self) -> None:
        text = self._text()

        blocks = {}
        for job_id in JOB_IDS:
            match = re.search(rf"^[ \t]*{re.escape(job_id)}\s*:\s*$", text, re.MULTILINE)
            self.assertIsNotNone(match, f"job {job_id!r} is missing")
            block = _yaml_block(text, job_id)
            self.assertTrue(block, f"job {job_id!r} has an empty body")
            blocks[job_id] = block

        for job_id in ("build-android", "build-windows"):
            self.assertTrue(
                _needs_contains(blocks[job_id], "verify"),
                f"job {job_id!r} must depend on 'verify'",
            )

        publish = blocks["publish-release"]
        self.assertIn("needs:", publish, "publish-release has no needs:")
        for required in ("build-android", "build-windows"):
            self.assertTrue(
                _needs_contains(publish, required),
                f"publish-release must depend on {required!r}",
            )

    def test_both_flavors_are_built(self) -> None:
        text = self._text()
        lines = text.splitlines()

        self.assertTrue(
            any("assembleFullRelease" in line and "assembleContextRelease" in line for line in lines),
            "release build must assemble both flavors in one gradlew command",
        )
        self.assertTrue(
            any("assembleFullDebug" in line and "assembleContextDebug" in line for line in lines),
            "debug build must assemble both flavors in one gradlew command",
        )

    def test_community_flag_on_every_build_command(self) -> None:
        text = self._text()

        occurrences = text.count("-Pcommunity=true")
        self.assertGreaterEqual(
            occurrences,
            4,
            f"expected at least 4 occurrences of -Pcommunity=true, found {occurrences}",
        )

        commands = _gradle_commands(text)
        self.assertTrue(commands, "no gradlew command found in the workflow")
        for command in commands:
            task_part = re.sub(r".*?gradlew", "", command, count=1)
            tokens = [token for token in re.split(r"\s+", task_part) if token and token != "\\"]
            # ``gradlew --stop`` / ``--version`` call no Gradle task, so they are
            # not builds and need no community flag.
            if not any(not token.startswith("-") for token in tokens):
                continue
            self.assertIn(
                "-Pcommunity=true",
                command,
                f"gradlew command is missing -Pcommunity=true:\n{command}",
            )

    def test_keystore_signing_contract(self) -> None:
        text = self._text()

        self.assertIn("AGW_KEYSTORE_PROPERTIES", text, "signing env var is not used")

        keystore_path_lines = [
            line
            for line in text.splitlines()
            if "RUNNER_TEMP" in line and "keystore.properties" in line
        ]
        self.assertTrue(
            keystore_path_lines,
            "keystore.properties must be written to $RUNNER_TEMP",
        )

        chmod_lines = [line for line in text.splitlines() if "chmod" in line]
        self.assertTrue(
            any("600" in line and ".jks" in line for line in chmod_lines),
            "the JKS keystore must be chmod 600",
        )
        self.assertTrue(
            any("600" in line and ("keystore.properties" in line or "properties" in line) for line in chmod_lines),
            "keystore.properties must be chmod 600",
        )

        self.assertIn("if: always()", text, "secret cleanup step must run with 'if: always()'")
        self.assertTrue(
            any(
                "rm -f" in line and (".jks" in line or "keystore" in line or "properties" in line)
                for line in text.splitlines()
            ),
            "secret cleanup step must 'rm -f' the keystore material",
        )

    def test_local_properties_only_holds_sdk_dir(self) -> None:
        text = self._text()
        lines = text.splitlines()

        # Only lines that actually write the file matter; a zero-leak guard may
        # legitimately grep for 'agw.topic' to prove it is absent.
        write_lines = [
            line
            for line in lines
            if "local.properties" in line
            and re.search(r"(?:>>?|Out-File|Set-Content|Add-Content|WriteAllText)", line)
        ]
        self.assertTrue(
            write_lines,
            "the workflow never writes android/local.properties",
        )
        self.assertTrue(
            any("sdk.dir" in line for line in write_lines),
            "android/local.properties must be created with sdk.dir",
        )
        for line in write_lines:
            self.assertNotIn(
                "agw.topic",
                line,
                f"local.properties must never be written with agw.topic:\n{line}",
            )

    def test_zero_leak_guards_present(self) -> None:
        text = self._text()
        for marker in ("AGW_TOPIC", "ZCODE_ENABLED", "dev.agwidgets.context", "APPLICATION_ID"):
            self.assertIn(marker, text, f"zero-leak guard for {marker!r} is missing")

    def test_checksums_and_github_release(self) -> None:
        text = self._text()
        for marker in ("SHA256SUMS.txt", ".sha256", "sha256sum"):
            self.assertIn(marker, text, f"checksum marker {marker!r} is missing")
        self.assertIn("gh release create", text, "gh release create is missing")
        self.assertIn("--generate-notes", text, "release notes generation is missing")

    def test_permissions_are_least_privilege(self) -> None:
        text = self._text()

        global_permissions = _top_level_block(text, "permissions")
        self.assertTrue(global_permissions, "workflow-level permissions block is missing")
        self.assertIn("contents: read", global_permissions, "workflow needs 'contents: read'")
        self.assertNotIn(
            "contents: write",
            global_permissions,
            "workflow-level permissions must not grant write",
        )

        self.assertEqual(
            text.count("contents: write"),
            1,
            "'contents: write' must appear exactly once",
        )
        write_index = text.find("contents: write")
        job_match = re.search(r"^[ \t]*publish-release\s*:\s*$", text, re.MULTILINE)
        self.assertIsNotNone(job_match, "publish-release job is missing")
        self.assertGreater(
            write_index,
            job_match.start(),
            "'contents: write' must live inside the publish-release job",
        )
        self.assertIn(
            "contents: write",
            _yaml_block(text, "publish-release"),
            "publish-release must grant contents: write",
        )

    def test_only_first_party_actions_are_used(self) -> None:
        text = self._text()
        uses = [value.strip().strip("'\"") for value in re.findall(r"uses:\s*([^\s#]+)", text)]
        self.assertTrue(uses, "no actions found in the release workflow")
        for target in uses:
            base = target.split("@", 1)[0]
            self.assertIn(
                base,
                ALLOWED_ACTIONS,
                f"unexpected third-party action: {target!r}",
            )

    def test_no_dangerous_shell_patterns(self) -> None:
        text = self._text()
        self.assertIsNone(
            re.search(r"(?m)^\s*set\s+-[a-zA-Z]*x[a-zA-Z]*\b", text),
            "shell trace mode ('set -x') must not be enabled",
        )
        for secret in ("ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEYSTORE_B64"):
            self.assertIsNone(
                re.search(rf"echo[^\n]*{secret}", text),
                f"secrets must never be echoed: {secret}",
            )


class AndroidSigningContractTests(unittest.TestCase):
    """The working Gradle signing schema must not be replaced by a second one."""

    def test_gradle_reads_keystore_properties(self) -> None:
        self.assertTrue(GRADLE.exists(), f"{GRADLE} does not exist")
        text = _read(GRADLE)
        for marker in (
            "AGW_KEYSTORE_PROPERTIES",
            "storeFile",
            "storePassword",
            "keyAlias",
            "keyPassword",
            'buildConfigField("String", "AGW_TOPIC"',
            'applicationId = "dev.agwidgets"',
            '"dev.agwidgets.context"',
            "ZCODE_ENABLED",
            'gradleProperty("community")',
        ):
            self.assertIn(marker, text, f"{GRADLE} lost the signing contract marker {marker!r}")

    def test_workflow_does_not_define_a_second_signing_scheme(self) -> None:
        self.assertTrue(WORKFLOW.exists(), f"{WORKFLOW} does not exist")
        text = _read(WORKFLOW)
        self.assertNotIn(
            "signingConfigs",
            text,
            "the workflow must not re-declare Gradle signingConfigs",
        )
        self.assertIsNone(
            re.search(r"storeFile\s*[:=]\s*['\"]?([A-Za-z]:[/\\]|/)", text),
            "the workflow must not hard-code a keystore path with storeFile=",
        )


class WindowsPackagerStaticTests(unittest.TestCase):
    """Static contract for ``scripts/package-windows.ps1``."""

    def _text(self) -> str:
        self.assertTrue(PACKAGER.exists(), f"{PACKAGER} does not exist")
        text = _read(PACKAGER)
        self.assertTrue(text.strip(), f"{PACKAGER} is empty")
        return text

    def test_packager_exists_with_parameters_and_archive_logic(self) -> None:
        text = self._text()
        for marker in (
            "param(",
            "$Version",
            "$OutputDir",
            "SkipElectronInstall",
            "DryRun",
            "Compress-Archive",
            "PORTABLE.txt",
            "ZipFile",
        ):
            self.assertIn(marker, text, f"{PACKAGER} is missing {marker!r}")

    def test_packager_whitelist_and_blacklist(self) -> None:
        text = self._text()
        for marker in (
            "install.ps1",
            "start-widget.vbs",
            "config.example.json",
            "collector",
            "hook",
            "agwidgets",
            "desktop",
            "docs",
        ):
            self.assertIn(marker, text, f"whitelist entry {marker!r} is missing")

        for marker in (
            ".git",
            "secrets.json",
            "local.properties",
            "android",
            "tests",
            "__pycache__",
        ):
            self.assertIn(marker, text, f"blacklist entry {marker!r} is missing")

    def test_packager_is_powershell_5_compatible(self) -> None:
        text = self._text()
        self.assertIsNone(
            re.search(r"\?\?", text),
            "PowerShell 7 null-coalescing operator '??' must not be used",
        )
        self.assertNotIn("utf8NoBOM", text, "utf8NoBOM is PowerShell 7 only")
        self.assertNotIn("-Depth ", text, "-Depth is not portable to Windows PowerShell 5.1")

        ternary = re.search(r"\?\s*\S+\s*:\s*", text)
        matched = ternary.group(0) if ternary else ""
        self.assertIsNone(
            ternary,
            f"PowerShell 7 ternary operator is not allowed: {matched!r}",
        )


class InstallerPortableTests(unittest.TestCase):
    """``install.ps1`` must honour a pre-bundled Electron runtime."""

    def _text(self) -> str:
        self.assertTrue(INSTALLER.exists(), f"{INSTALLER} does not exist")
        return _read(INSTALLER)

    def test_bundled_electron_is_checked_before_node_dependencies(self) -> None:
        text = self._text()
        normalised = text.replace("\\", "/")

        electron_index = normalised.find("node_modules/electron/dist/electron.exe")
        self.assertNotEqual(electron_index, -1, "$ElectronExe is no longer defined")

        call_index = _first_call_index(text, "Test-NodeDependencies")
        self.assertNotEqual(call_index, -1, "Test-NodeDependencies is never called")
        self.assertLess(
            electron_index,
            call_index,
            "$ElectronExe must be defined before Test-NodeDependencies is called",
        )

    def test_node_dependencies_call_is_guarded(self) -> None:
        text = self._text()
        call_index = _first_call_index(text, "Test-NodeDependencies")
        self.assertNotEqual(call_index, -1, "Test-NodeDependencies is never called")

        window_start = max(0, call_index - 500)
        window = text[window_start : call_index + 200]
        direct_guard = re.search(r"if\s*\(\s*-not\s*\$HasBundledElectron", window, re.IGNORECASE)
        inverse_guard = (
            re.search(r"if\s*\(\s*\$HasBundledElectron", window, re.IGNORECASE)
            and re.search(r"\belse\b", window, re.IGNORECASE)
        )
        self.assertTrue(
            direct_guard or inverse_guard,
            "Test-NodeDependencies must be skipped when a bundled Electron runtime exists",
        )

    def test_pre_bundled_message_is_printed(self) -> None:
        self.assertIn(
            "Pre-bundled Electron runtime found",
            self._text(),
            "the installer must announce the pre-bundled Electron runtime",
        )

    def test_help_documents_dry_run_and_uninstall(self) -> None:
        text = self._text()
        help_match = re.search(r"<#(.*?)#>", text, re.DOTALL)
        self.assertIsNotNone(help_match, "the comment-based help block is missing")
        help_text = help_match.group(1)
        self.assertIn(".PARAMETER DryRun", help_text, "help does not document -DryRun")
        self.assertIn(".PARAMETER Uninstall", help_text, "help does not document -Uninstall")

    def test_installer_never_writes_agw_topic(self) -> None:
        self.assertNotIn(
            "agw.topic",
            self._text(),
            "install.ps1 must not write the ntfy topic itself",
        )


def _write(path: Path, content: str | bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(content, bytes):
        path.write_bytes(content)
    else:
        path.write_text(content, encoding="utf-8")


def _build_synthetic_repo(base: Path) -> Path:
    """Create a tiny fake repository with every whitelisted and blacklisted entry."""
    repo = base / "repo"
    script_copy = repo / "scripts" / "package-windows.ps1"
    script_copy.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(PACKAGER, script_copy)

    for name in (
        "install.ps1",
        "start-widget.vbs",
        "config.example.json",
        "LICENSE",
        "README.md",
        "README.ru.md",
        "CHANGELOG.md",
    ):
        _write(repo / name, f"# synthetic {name}\n")

    _write(repo / "agwidgets" / "__init__.py", "")
    _write(repo / "collector" / "collector.py", "# synthetic collector\n")
    _write(repo / "collector" / "__pycache__" / "collector.cpython-312.pyc", b"\x00stub")
    _write(repo / "hook" / "context_hook.py", "# synthetic hook\n")
    _write(repo / "docs" / "INSTALL_GUIDE.md", "# synthetic install guide\n")
    _write(repo / "desktop" / "package.json", '{"name": "synthetic", "version": "0.0.0"}\n')
    _write(repo / "desktop" / "main.js", "// synthetic main\n")
    _write(repo / "desktop" / "test" / "smoke.test.js", "// excluded test\n")
    _write(repo / "desktop" / "scripts" / "check-syntax.js", "// excluded script\n")
    _write(
        repo / "desktop" / "node_modules" / "electron" / "dist" / "electron.exe",
        b"MZ synthetic electron stub\n",
    )
    _write(repo / "android" / "local.properties", "sdk.dir=/synthetic\n")
    _write(repo / "tests" / "test_x.py", "# excluded test\n")
    _write(repo / ".git" / "HEAD", "ref: refs/heads/main\n")
    _write(repo / "secrets.json", "{}\n")
    return repo


def _describe(result: subprocess.CompletedProcess[str]) -> str:
    return (
        f"exit={result.returncode}\n"
        f"--- stdout ---\n{result.stdout}\n"
        f"--- stderr ---\n{result.stderr}"
    )


@unittest.skipUnless(sys.platform == "win32", "packaging runtime tests need Windows")
class WindowsPackagerRuntimeTests(unittest.TestCase):
    """Run the PowerShell packaging and installer scripts for real."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.powershell = shutil.which("pwsh") or shutil.which("powershell")
        if not cls.powershell:
            raise unittest.SkipTest("PowerShell is not available")

    def _run(self, script: Path, args: list[str], cwd: Path) -> subprocess.CompletedProcess[str]:
        self.assertTrue(script.exists(), f"{script} does not exist")
        command = [
            self.powershell,
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(script),
            *args,
        ]
        try:
            return subprocess.run(
                command,
                cwd=str(cwd),
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=300,
            )
        except subprocess.TimeoutExpired:
            self.fail(f"PowerShell timed out: {' '.join(command)}")
            raise  # pragma: no cover - keeps type checkers happy

    def _tempdir(self) -> Path:
        path = Path(tempfile.mkdtemp(prefix="agw-packaging-"))
        self.addCleanup(shutil.rmtree, path, True)
        return path

    def test_dry_run_creates_nothing(self) -> None:
        if not PACKAGER.exists():
            self.skipTest(f"{PACKAGER} is not present yet")

        temp_root = self._tempdir()
        temp_out = temp_root / "out"
        dist_existed = (ROOT / "dist").exists()

        result = self._run(
            PACKAGER,
            ["-Version", "v0.0.0-test", "-OutputDir", str(temp_out), "-DryRun"],
            cwd=ROOT,
        )
        self.assertEqual(result.returncode, 0, _describe(result))
        self.assertIn("[dry-run]", result.stdout.casefold(), _describe(result))
        self.assertFalse(temp_out.exists(), f"dry run created {temp_out}")
        if not dist_existed:
            self.assertFalse(
                (ROOT / "dist").exists(),
                "dry run created the real dist/ directory",
            )

    def test_packages_synthetic_tree(self) -> None:
        if not PACKAGER.exists():
            self.skipTest(f"{PACKAGER} is not present yet")

        base = self._tempdir()
        repo = _build_synthetic_repo(base)
        version = "v9.9.9-test"
        top = "ag-widgets-9.9.9-test-windows-x64"

        result = self._run(
            repo / "scripts" / "package-windows.ps1",
            ["-Version", version, "-SkipElectronInstall", "-OutputDir", str(repo / "dist")],
            cwd=repo,
        )
        self.assertEqual(result.returncode, 0, _describe(result))

        archive = repo / "dist" / f"{top}.zip"
        self.assertTrue(archive.is_file(), f"expected archive {archive}; {_describe(result)}")

        with zipfile.ZipFile(archive) as bundle:
            names = [name.replace("\\", "/") for name in bundle.namelist()]
            required = {
                f"{top}/install.ps1",
                f"{top}/desktop/package.json",
                f"{top}/desktop/node_modules/electron/dist/electron.exe",
                f"{top}/collector/collector.py",
                f"{top}/PORTABLE.txt",
                f"{top}/docs/INSTALL_GUIDE.md",
            }
            missing = sorted(required - set(names))
            self.assertEqual(missing, [], f"archive is missing whitelisted entries: {missing}")

            for name in names:
                self.assertTrue(
                    name.startswith(f"{top}/"),
                    f"archive entry is outside the top-level folder: {name!r}",
                )

            forbidden_segments = {"android", "tests", ".git", "test", "scripts", "__pycache__"}
            leaks: list[str] = []
            for name in names:
                for segment in name.split("/")[1:]:
                    if segment in forbidden_segments:
                        leaks.append(name)
                        break
            self.assertEqual(sorted(set(leaks)), [], f"blacklisted paths leaked into the ZIP: {leaks}")

            forbidden_names = {"secrets.json", "local.properties"}
            for name in names:
                base_name = name.rsplit("/", 1)[-1]
                self.assertNotIn(base_name, forbidden_names, f"forbidden file in ZIP: {name!r}")
                self.assertFalse(
                    base_name.endswith((".pyc", ".log", ".tmp")),
                    f"temporary or compiled file leaked into the ZIP: {name!r}",
                )

            portable_name = f"{top}/PORTABLE.txt"
            self.assertIn(portable_name, names, "PORTABLE.txt is missing from the archive")
            portable = bundle.read(portable_name).decode("utf-8", "replace")
            for needle in ("9.9.9-test", "install.ps1", "AG Widgets", "AG Context"):
                self.assertIn(
                    needle,
                    portable,
                    f"PORTABLE.txt does not mention {needle!r}:\n{portable}",
                )

    def test_installer_dry_run_exit_zero(self) -> None:
        if not INSTALLER.exists():
            self.skipTest(f"{INSTALLER} is not present yet")

        for extra in (["-DryRun"], ["-Uninstall", "-DryRun"]):
            with self.subTest(args=extra):
                result = self._run(INSTALLER, extra, cwd=ROOT)
                self.assertEqual(result.returncode, 0, _describe(result))
                self.assertIn("[dry-run]", result.stdout.casefold(), _describe(result))


if __name__ == "__main__":
    unittest.main()

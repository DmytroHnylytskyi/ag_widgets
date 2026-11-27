"""Documentation integrity tests for the READMEs, the install guides, and the changelog.

Four kinds of rot are guarded here:

* every relative link in the published docs points at a real file;
* the agent runbook covers every check that ``collector.py --doctor`` can report;
* the READMEs and both install guides name both product flavors and their ids;
* no document leaks a secret or a personal path.

Everything is local and deterministic: no network, no subprocess, no writes.
"""

from __future__ import annotations

import re
import sys
import unittest
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

DOCS = (
    "README.md",
    "README.ru.md",
    "docs/INSTALL_GUIDE.md",
    "docs/INSTALL_GUIDE.ru.md",
    "docs/AGENT_INSTALL.md",
    "docs/CONTEXT_HEALTH.md",
    "docs/RELEASING.md",
    "CHANGELOG.md",
)

# Documents that every user sees before downloading: they must explain both
# builds, because the package ids differ and both apps can be installed together.
FLAVOR_DOCS = (
    "README.md",
    "README.ru.md",
    "docs/INSTALL_GUIDE.md",
    "docs/INSTALL_GUIDE.ru.md",
)

# ``full``'s id is a prefix of ``context``'s id, so the ``full`` pattern must not
# be satisfied by the ``context`` package name.
_FLAVOR_MARKERS = (
    ("full", "AG Widgets", "dev.agwidgets", re.compile(r"dev\.agwidgets(?!\.context)(?![\w-])")),
    (
        "context",
        "AG Context",
        "dev.agwidgets.context",
        re.compile(r"dev\.agwidgets\.context(?![\w-])"),
    ),
)

REQUIRED_CHECKS = frozenset(
    {
        "python_version",
        "agy_cli",
        "annotations_dir",
        "antigravity_hook",
        "antigravity_sidecar",
        "runtime_config",
        "state_freshness",
        "secrets_storage",
        "ntfy_reachability",
        "zcode_api",
    }
)

# A fenced block starts with ``` (plus an optional info string) and ends at a
# line that begins with ``` — links inside fences are never rendered.
_FENCE_RE = re.compile(r"^```.*?^```[^\n]*$", re.DOTALL | re.MULTILINE)
# Inline links and images share one shape; the leading ``!`` is optional.
_LINK_RE = re.compile(r"!?\[[^\]]*\]\(([^)]*)\)")
_SKIP_PREFIXES = ("http://", "https://", "mailto:", "ftp://", "#", "<")

# A changelog may link to anchors, tags, commits, or absolute URLs instead of
# files in the repository; none of those can be resolved on disk.
_REFERENCE_TARGET_RE = re.compile(
    r"""^(?:
        /                                             # root-absolute URL path
      | [a-z][a-z0-9+.-]*:                            # any scheme, e.g. https: or C:
      | (?:\.{1,2}/)*(?:compare|commits?|releases?)/   # GitHub compare/tag paths
      | v?\d+\.\d+(?:\.\d+)*(?:[-+][0-9A-Za-z.-]+)?$  # a version or tag name
      | [0-9a-f]{7,40}$                               # a commit SHA
    )""",
    re.IGNORECASE | re.VERBOSE,
)

# Guard against real local home paths, topics, and JWTs; allow neutral placeholders like `<name>` or `<user>`.
_PERSONAL_PATTERNS = (
    (
        re.compile(r"C:\\Users\\(?!(?:<[^>]+>|you|user|username|name)\b)[A-Za-z0-9._-]+", re.IGNORECASE),
        "personal Windows home path",
    ),
    (re.compile(r"\bagw-[0-9a-f]{24}\b"), "real-looking ntfy topic"),
    (re.compile(r"\beyJ[A-Za-z0-9_-]{20,}"), "JWT"),
)


def _link_targets(text: str, *, skip_references: bool = False) -> list[str]:
    """Return relative link/image targets from *text*, fenced blocks removed.

    When *skip_references* is true, anchors, tags, commits, and absolute URLs
    are dropped as well; the changelog uses that mode because those targets are
    not files in the repository.
    """
    content = _FENCE_RE.sub("", text)
    targets: list[str] = []
    for match in _LINK_RE.finditer(content):
        raw = match.group(1).strip()
        if not raw or raw.startswith("<"):
            continue
        # Drop an optional markdown title: ``(path "title")``.
        target = raw.split()[0]
        if target.startswith(_SKIP_PREFIXES):
            continue
        # Fragments and queries are not part of the file name.
        target = target.split("#", 1)[0].split("?", 1)[0]
        if not target:
            continue
        if skip_references and _REFERENCE_TARGET_RE.match(target):
            continue
        targets.append(target)
    return targets


class DocumentationIntegrityTests(unittest.TestCase):
    def test_all_docs_exist_and_non_empty(self) -> None:
        for relative in DOCS:
            with self.subTest(doc=relative):
                path = ROOT / relative
                self.assertTrue(path.exists(), f"{relative} does not exist")
                self.assertTrue(path.is_file(), f"{relative} is not a regular file")
                self.assertGreater(path.stat().st_size, 0, f"{relative} is empty")
                text = path.read_text(encoding="utf-8")
                self.assertTrue(text.strip(), f"{relative} contains only whitespace")

    def test_relative_markdown_links_resolve(self) -> None:
        failures: list[str] = []
        for relative in DOCS:
            doc = ROOT / relative
            if not doc.exists():
                failures.append(f"{relative} -> <document is missing>")
                continue
            text = doc.read_text(encoding="utf-8")
            targets = _link_targets(text, skip_references=relative == "CHANGELOG.md")
            for target in targets:
                candidate = (doc.parent / unquote(target)).resolve()
                if not candidate.exists():
                    failures.append(f"{relative} -> {target}")
        self.assertEqual(
            failures,
            [],
            "Broken relative links:\n" + "\n".join(failures),
        )

    def test_readmes_and_install_guides_document_both_flavors(self) -> None:
        """User-facing docs name both builds and both package ids, at any casing."""
        failures: list[str] = []
        for relative in FLAVOR_DOCS:
            doc = ROOT / relative
            if not doc.exists():
                failures.append(f"{relative}: <document is missing>")
                continue
            folded = doc.read_text(encoding="utf-8").casefold()
            for flavor, name, package, package_re in _FLAVOR_MARKERS:
                missing = [label for label, present in (
                    (f"app name {name!r}", name.casefold() in folded),
                    (f"package id {package!r}", bool(package_re.search(folded))),
                ) if not present]
                if missing:
                    failures.append(
                        f"{relative}: {flavor} flavor is not documented "
                        f"(missing " + ", ".join(missing) + ")"
                    )
        self.assertEqual(
            failures,
            [],
            "Both flavor names and package ids must appear in every install entry point:\n"
            + "\n".join(failures),
        )

    def test_agent_runbook_covers_all_doctor_checks(self) -> None:
        from agwidgets import doctor

        # ``_HINTS`` is the single source of truth for check names; the two
        # online-only checks are added defensively in case they ever move.
        expected = set(doctor._HINTS) | {"ntfy_reachability", "zcode_api"}

        self.assertLessEqual(
            REQUIRED_CHECKS,
            set(doctor._HINTS),
            "doctor._HINTS lost a check that the docs promise",
        )
        self.assertEqual(len(expected), 10, f"unexpected doctor check set: {sorted(expected)}")

        runbook = (ROOT / "docs" / "AGENT_INSTALL.md").read_text(encoding="utf-8")
        missing = sorted(name for name in expected if name not in runbook)
        self.assertEqual(
            missing,
            [],
            "docs/AGENT_INSTALL.md does not mention doctor checks: " + ", ".join(missing),
        )
        self.assertIn("--doctor-json", runbook)

    def test_docs_contain_no_secrets_or_personal_paths(self) -> None:
        problems: list[str] = []
        for relative in DOCS:
            doc = ROOT / relative
            if not doc.exists():
                problems.append(f"{relative}: document is missing")
                continue
            text = doc.read_text(encoding="utf-8")
            for pattern, label in _PERSONAL_PATTERNS:
                if pattern.search(text):
                    problems.append(f"{relative}: matches {label}")
        self.assertEqual(
            problems,
            [],
            "Secrets or personal paths found in docs:\n" + "\n".join(problems),
        )


if __name__ == "__main__":
    unittest.main()

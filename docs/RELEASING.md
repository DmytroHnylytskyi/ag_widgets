# Releasing AG Widgets

## Products

Two Gradle product flavors share one codebase:

| Flavor | applicationId | App name | ZCode |
|---|---|---|---|
| `full` | `dev.agwidgets` | AG Widgets | yes |
| `context` | `dev.agwidgets.context` | AG Context | no |

## Signing key

The Android release key lives outside the repository:

```text
%LOCALAPPDATA%\ag-widgets\keys\
  ag-widgets-release.jks
  keystore.properties
  README.txt
```

`keystore.properties` contains:

```properties
storeFile=C:/Users/<user>/AppData/Local/ag-widgets/keys/ag-widgets-release.jks
storePassword=<secret>
keyAlias=agwidgets
keyPassword=<secret>
```

Never commit or copy these files into the project. Back them up: if the key
is lost, existing users cannot install an update over the app.

CI can override the path with `AGW_KEYSTORE_PROPERTIES`.

## Build signed community APKs

`-Pcommunity=true` keeps `BuildConfig.AGW_TOPIC` empty, so no personal ntfy
topic is embedded. Use `--no-configuration-cache` for release builds: the
signing key is read from an external file, which Gradle's configuration cache
does not track.

```powershell
cd android
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleFullRelease :app:assembleContextRelease `
  -Pcommunity=true --no-configuration-cache
```

Outputs:

```text
android/app/build/outputs/apk/full/release/app-full-release.apk
android/app/build/outputs/apk/context/release/app-context-release.apk
```

## Verify

```powershell
$apksigner = "$env:LOCALAPPDATA\Android\Sdk\build-tools\37.0.0\apksigner.bat"
$full = "android\app\build\outputs\apk\full\release\app-full-release.apk"
$context = "android\app\build\outputs\apk\context\release\app-context-release.apk"

& $apksigner verify --verbose --print-certs $full
& $apksigner verify --verbose --print-certs $context

& "$env:LOCALAPPDATA\Android\Sdk\build-tools\37.0.0\aapt.exe" dump badging $full
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\37.0.0\aapt.exe" dump badging $context
```

Check package ids and empty topics in the generated BuildConfig files:

```powershell
Get-Content android\app\build\generated\source\buildConfig\full\release\dev\agwidgets\BuildConfig.java |
  Select-String "APPLICATION_ID|AGW_TOPIC|ZCODE_ENABLED"
Get-Content android\app\build\generated\source\buildConfig\context\release\dev\agwidgets\BuildConfig.java |
  Select-String "APPLICATION_ID|AGW_TOPIC|ZCODE_ENABLED"
```

Expected:

- full: `dev.agwidgets`, `AGW_TOPIC=""`, `ZCODE_ENABLED=true`
- context: `dev.agwidgets.context`, `AGW_TOPIC=""`, `ZCODE_ENABLED=false`

`aapt dump badging` does **not** print `AGW_TOPIC`; use BuildConfig or scan the
DEX if you need to prove that no topic is embedded.

## Tests

```powershell
.\gradlew.bat :app:testFullDebugUnitTest :app:testContextDebugUnitTest
```

## Release assets

Use one tag per release, for example `v1.0.0`, with:

```text
AG-Widgets-1.0.0.apk
AG-Widgets-1.0.0.apk.sha256
AG-Context-1.0.0.apk
AG-Context-1.0.0.apk.sha256
```

Release notes are in `CHANGELOG.md`; keep the GitHub release description short
and include both SHA-256 values.

## Personal build (not for publication)

A personal APK embeds your own `ntfy_topic` from the encrypted runtime secrets
(`python collector/collector.py --show-topic`). Build it locally only, keep it
outside the repository, and never attach it to a public release:

1. Add `agw.topic=<topic>` to `android/local.properties`.
2. Run `.\gradlew.bat :app:assembleFullRelease --no-configuration-cache`
   (or `assembleFullDebug` for a debug build).
3. Copy the APK to `%LOCALAPPDATA%\ag-widgets\`.
4. Remove `agw.topic` from `local.properties` immediately afterwards.

## Local release (the current policy)

The signing key is **not** stored in GitHub. This repository is public, and a
key that can sign updates for every installed copy is worth more than the
convenience of a fully automated publish: anyone who could push a workflow
would be able to read it. So releases are built here and published from a
trusted checkout:

```powershell
# dry run: build both flavors, print the signing certificate and the hashes
powershell -ExecutionPolicy Bypass -File .\scripts\release-local.ps1 -Version v1.1.0 -DryRun

# real run: tag, push the tag, create the release and upload every asset
powershell -ExecutionPolicy Bypass -File .\scripts\release-local.ps1 -Version v1.1.0
```

The script refuses to continue when the tag does not match `versionName`, when
the tree is dirty, when the tag already exists, when the keystore is missing or
when the built APK is unsigned. It writes `AG-Widgets-<ver>.apk`,
`AG-Context-<ver>.apk`, one `.sha256` per file and `SHA256SUMS.txt` into `dist/`,
then publishes exactly those files.

`.github/workflows/release.yml` stays in the repository as a **verification
build**: it runs the tests, builds both flavors and the portable ZIP, and then
**skips publishing with a warning** because the signing secrets are absent. That
is intentional — a `v*` tag must never publish debuggable APKs, and the step that
does publish also requires `signed == 'true'`.

## Automated GitHub Releases (not used by default)

The workflow below can publish on its own if the four signing secrets are ever
added. For this project they are deliberately omitted (see above).

## Automated GitHub Releases

`.github/workflows/release.yml` builds and publishes every asset in one run:

| Job | What it does |
|---|---|
| `verify` | Python tests and the Desktop tests (`node --test desktop/test/*.test.js`). |
| `build-android` | Both flavors, `-Pcommunity=true --no-configuration-cache`. |
| `build-windows` | Portable ZIP via `scripts/package-windows.ps1`. |
| `publish-release` | Per-file SHA-256 checksums, `SHA256SUMS.txt`, `gh release create`. |

Triggers:

| Trigger | Behaviour |
|---|---|
| Push of a `v*` tag | Builds and publishes the release automatically, but only when the signing secrets are present (see below). |
| `workflow_dispatch` | Manual run. `publish` defaults to `false`: a safe dry-run that leaves the artifacts in Actions and creates no release; `prerelease` defaults to `true`. |

The tag must match `versionName` in `android/app/build.gradle.kts`: bump
`versionName` and `versionCode` in the release PR before tagging, otherwise the
`build-android` job fails instead of publishing an APK that reports the wrong
version.

### Signing secrets (optional)

| Secret | Meaning |
|---|---|
| `ANDROID_KEYSTORE_B64` | base64 of `ag-widgets-release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | `storePassword` |
| `ANDROID_KEY_ALIAS` | `keyAlias` (`agwidgets`) |
| `ANDROID_KEY_PASSWORD` | `keyPassword` |

With all four present the workflow decodes them into `$RUNNER_TEMP/release.jks`
and `$RUNNER_TEMP/keystore.properties`, hands Gradle the properties file through
`AGW_KEYSTORE_PROPERTIES` (the same override described in `Signing key`) and
deletes both temporary files in a step marked `if: always()`. The secrets are
scoped to the two steps that need them (secret detection and the signed build),
so the SDK setup and the unit-test run never receive them. Secret values are
never echoed into the log.

Without the secrets the workflow still produces `*-community-debug.apk` for the
run, but **publication is refused** with an error: a version tag never publishes
debuggable APKs.

### Artifact names

```text
AG-Widgets-<ver>.apk                       dev.agwidgets, signed
AG-Widgets-<ver>-community-debug.apk       no keystore secrets
AG-Context-<ver>.apk                       dev.agwidgets.context, signed
AG-Context-<ver>-community-debug.apk       no keystore secrets
ag-widgets-<ver>-windows-x64.zip           portable desktop build
<file>.sha256                              one per asset
SHA256SUMS.txt                             all checksums
```

### Zero-leak checks in the workflow

- `android/local.properties` is generated with `sdk.dir` only — no `agw.topic`.
- Both generated `BuildConfig.java` files are compared against the expected
  `APPLICATION_ID`, `AGW_TOPIC` (`""`) and `ZCODE_ENABLED`, so a personal topic
  cannot reach a published APK.

### Windows portable ZIP

`scripts/package-windows.ps1 -Version <tag> -OutputDir dist` builds the archive
from a whitelist: `.git/`, `android/`, `tests/`, `.github/`, `local.properties`,
`secrets.json`, `__pycache__/` and `*.pyc` are physically absent from the ZIP.
Electron is bundled, so the receiving machine needs no Node.js; `PORTABLE.txt`
inside the archive explains the first run.

### Check before publishing

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\package-windows.ps1 -Version v0.0.0-test -DryRun
```

Then run the workflow manually on a fork with `publish: false` and inspect the
artifacts in Actions before pushing the tag.

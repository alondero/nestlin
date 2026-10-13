# Releasing

This guide is for maintainers preparing and validating Nestlin releases. The [Release workflow](../.github/workflows/build.yml) is the single owner of application and native assets.

## Before tagging

1. Choose an unused stable `vX.Y.Z` version. The tag is the version authority. The first candidate prepared for this process is [v0.2.0](releases/v0.2.0.md); review its scope before cutting the tag.
2. Copy the previous notes to `docs/releases/vX.Y.Z.md`, change the title to `# Nestlin vX.Y.Z`, and update Highlights, Downloads, Known limitations and Save compatibility from commits since the last published tag. Link the notes from this page. Run `python tools/release.py validate vX.Y.Z`; incomplete notes or invalid versions fail before building.
3. Run `./gradlew build validateTaskGraph --warning-mode=fail`, `python tools/docs_lint.py`, and `git diff --check`. Run relevant bootcheck, Mesen2 and native/manual acceptance checks for changed areas. Document unavailable checks.
4. Merge the release changes through a PR to the default branch. Tag that merged commit: `git tag vX.Y.Z <merged-sha>` then `git push origin vX.Y.Z`. The workflow checks default-branch ancestry and builds the exact tag commit.
5. Monitor the Release run. To retry, dispatch Release with the existing tag; selecting a branch does not change the source commit. Runs for the same tag serialize. A published release cannot be overwritten by this workflow.

Save states are versioned snapshots. Changes to `.nstl` require migration coverage and explicit compatibility notes. Document battery `.sav` changes separately because they are user data.

## Release artifacts

The workflow verifies the fast suite, isolated performance guards, documentation and Gradle task graph before packaging. Four native runner jobs build Windows x86_64, Linux x86_64, macOS x86_64 and macOS aarch64. Each builds a fresh platform-specific fat JAR and vendored RA library, with remote native downloads disabled, and runs native contract tests.

[tools/release.py](../tools/release.py) owns packaging. For local reproduction with JDK 21 and a C compiler:

```text
./gradlew clean shadowJar testNativeRa -x fetchNativeRa --warning-mode=fail
python tools/release.py package v0.2.0 windows-x86_64
```

Set `JAVA_HOME` to the packaging JDK and use the platform matching the host. The script creates `build/release/assets`; an existing packaging directory is rejected, so start each attempt with Gradle clean. The release maintainer owns this tool. Exit codes: 0 success, 1 validation/build failure, 2 command-line misuse. No signing credentials are required.

The script uses [jpackage app images](https://docs.oracle.com/en/java/javase/21/jpackage/packaging-overview.html) and a bundled runtime containing all JDK modules, avoiding missing reflection/JNA/audio modules. Windows ships a ZIP; Unix ships tar.gz to preserve executable permissions. Each archive includes user documentation, project and native license material, and runtime legal notices. ROMs and user configuration are excluded. Platform-specific `.jar` files require Java 21 and should not be interchanged across systems.

Stable `rcheevos_facade-<platform>.zip` assets preserve Gradle's native-download interface. The macOS library is universal even though JavaFX and the app runtime are architecture-specific. The old native workflow only points maintainers to Release, preventing competing draft writers.

## Validation matrix

| Area | Minimum evidence |
| --- | --- |
| JVM application | JDK 21 build, fast suite, isolated performance guards and task graph validation. |
| Packaged launcher | Native launcher executes `nra-smoke` and boots a temporary NROM rendering fixture for 120 frames (the nestest header/layout with a small rendering program; never distributed) using its bundled runtime. |
| Desktop | Extract each archive; open a legally owned ROM, check display, sound, controls, save/load, and exit. Test with host Java absent. |
| Native RA | Native contract tests and packaged smoke must pass; missing native capability blocks release. Follow [manual RA acceptance](MANUAL_RA_ACCEPTANCE.md). |
| Mappers/timing | Relevant bootcheck and available Mesen2 structured comparison. |
| Documentation | Notes, docs lint, and accurate limits/migration statements. |

## Rollback and failure handling

Only after every platform passes does the workflow create or update a draft with checked-in notes and `SHA256SUMS.txt`. Inspect its complete asset list and manually test the extracted desktop bundles before publishing in GitHub Releases. Headless checks do not validate a JavaFX window, sound device or gamepad. Linux users need system desktop libraries; macOS and Windows bundles are unsigned and may require OS approval.

Keep a failing candidate unpublished. Rerun the existing tag only for infrastructure failures; source corrections need a new version/tag. Never move a published tag. For a defective published release, explain the affected version and publish a corrected release with migration/rollback instructions.

The release agent procedure is [.claude/skills/release/SKILL.md](../.claude/skills/release/SKILL.md). Invoke it with: `Use the release skill to prepare Nestlin vX.Y.Z`.

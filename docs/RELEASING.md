# Releasing

This guide is for maintainers producing a Nestlin build or native RetroAchievements assets. It documents the current GitHub Actions flow; verify the workflow files before changing a release process.

## Before tagging

1. Confirm the intended semantic version and use a `vX.Y.Z` tag. The tag/release title is the current version authority; there is no generated changelog file, so release notes must summarize user-visible changes, compatibility changes, known limits, and migration notes.
2. Run `./gradlew build` and `./gradlew docsLint`.
3. Run the relevant real-ROM boot, Mesen2, native-RA, and manual acceptance checks for the changed areas.
4. Review `git diff --check`, generated-file status, and the final documentation links.
5. Confirm no ROMs, save files, credentials, native build output, or local configuration are staged.
6. Record known compatibility limits and release notes. Do not imply that a mapper stub or skipped oracle lane is fully supported.

Save states are versioned emulator snapshots. A release that changes the `.nstl` format must update `SaveStateMigrationTest`, document the migration or incompatibility in its release notes, and preserve a clear failure mode for states that cannot be migrated. Battery-backed `.sav` behavior should be called out separately because it is user data rather than an emulator snapshot.

## Release artifacts

The runnable fat JAR is produced at `build/libs/nestlin-all.jar` by `shadowJar`/`uberJar`. The release workflow in `.github/workflows/build.yml` creates a draft GitHub release and attaches that JAR.

Native RetroAchievements assets are built by `.github/workflows/release-native-libs.yml` for Linux x86_64, Windows x86_64, and macOS universal. Each archive contains the platform library and a manifest fragment with a SHA-256. The consumer validates the manifest and falls back to `NoOpRetroAchievementsService` if the asset is missing or incompatible.

## Validation matrix

| Area | Minimum evidence |
| --- | --- |
| JVM application | JDK 21 build and fast test suite. |
| User launch | Fat JAR starts and a smoke ROM reaches a rendered frame. |
| Mappers/timing/rendering | Relevant `bootcheck` and, where available, Mesen2 structured comparison. |
| Native RA | Platform native smoke or an explicit documented unavailable-tool result. |
| Documentation | `docsLint`, link review, and release notes. |
| Security | No secrets or unlicensed ROM assets in the release or logs. |

## Rollback and failure handling

Release workflows create drafts so a maintainer can inspect assets before publishing. If an asset is wrong, leave the draft unpublished or replace the asset rather than asking users to download a known-bad build. If a published release is defective, mark it clearly, publish a corrected release, and document the affected version and migration/rollback instructions.

Native library download failure is intentionally non-fatal to the JVM build. Treat a missing native library as a degraded optional feature, but treat a checksum mismatch, native smoke regression, or broken JAR as release-blocking.

The latest published release and the default development branch are the supported versions. Older releases may remain downloadable but do not receive compatibility or security fixes by default.

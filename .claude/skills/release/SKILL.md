---
name: release
description: Prepares and validates repeatable Nestlin GitHub releases with portable desktop bundles and release notes. Use when preparing a Nestlin release, retrying release packaging, or publishing a validated draft.
---

# Release Nestlin

Read [the release guide](../../../docs/RELEASING.md) as the process authority. Work in the active worktree.

1. Inspect git status, remote, default branch, existing tags and GitHub releases. Confirm the requested version is unused. If no version was supplied, propose one from the last published release and the scope of changes before tagging.
2. Compare commits since the last published tag (or all history for the first release). Write `docs/releases/vX.Y.Z.md` with concrete user changes, downloads, known limits and save compatibility, including the source-checked write/minimum-readable version lines. Preserve limitations and label missing validation. Link the notes from the release guide.
3. Run the guide's pre-tag checks, including the four-leg branch dry run and the JUnit-report gate (executed native tests, zero skips). Completion means all mandatory checks pass and optional/manual evidence is accurately recorded. Fix failures before proceeding.
4. Commit the changes, push the branch and open a PR with verification evidence. Completion means the source and notes are reviewable. If asked only to prepare a release, hand off this PR.
5. When authorized to cut the release, verify the PR is merged, resolve its exact default-branch commit and create/push an unused matching tag. Never tag the unmerged working branch. Monitor the Release workflow through all platform jobs.
6. On failure, inspect job logs and fix the cause. Infrastructure-only retries dispatch the same existing tag. Source changes use a new candidate tag and notes. A missing native library or failed packaged smoke blocks the release.
7. Inspect draft assets and checksums; execute the desktop and manual RA checklist from the guide. Report platform checks that cannot be performed. Publish only when the user has authorized publication and required manual evidence is present.
8. Return the PR or release URL, version, checks run and any outstanding manual validation. A prepared PR or untested draft is not a published release.

Example: `Use the release skill to prepare Nestlin v0.2.0` prepares notes and a PR. `Cut Nestlin v0.2.0 from the merged release PR` also tags and builds the draft. `Publish the validated v0.2.0 draft` requests publication after acceptance evidence.

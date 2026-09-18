# Security policy

## Scope

Report vulnerabilities in Nestlin itself, the native RetroAchievements façade, build/release automation, credential handling, or release downloads. Do not use a public issue for secrets, account data, or an exploitable vulnerability.

## Reporting

Use GitHub's private vulnerability reporting or a private maintainer contact listed on the repository profile. Include the affected commit/release, operating system, reproduction steps, impact, and a minimal proof of concept when safe. Do not include passwords, access tokens, RetroAchievements credentials, commercial ROMs, or private logs.

If private reporting is unavailable, open a minimal public issue that says only that a security report needs a private channel; do not publish exploit details until a fix or mitigation is available.

## Supported versions

Only the latest published release and the default development branch are expected to receive security fixes. This is a hobby project without a guaranteed response-time SLA; reports are still welcome and will be handled as promptly as project capacity allows.

## Release integrity

Native release assets are accompanied by manifest checksums and validated at load time. A checksum mismatch or unexpected native binary should be reported privately, not bypassed by disabling verification.

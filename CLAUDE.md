# Notes for Claude

## Releasing a new version
Every push to `main` builds the APK. A GitHub release is published only when the version is new.

For every change meant for the user's phone:
1. Bump `versionName` (semantic version: `MAJOR.MINOR.PATCH`, e.g. `1.8.0` → `1.8.1` for fixes, `1.9.0` for features) **and** `versionCode` (+1) in `app/build.gradle.kts`.
2. **Always add a section `## vX.Y.Z` at the top of `CHANGELOG.md`** describing what changed since the previous version, in plain words for the user (what they'll notice, not code details). The workflow uses this section as the release description and fails if it's missing.
3. Push to `main`. The workflow tags the release `vX.Y.Z` and uploads `Retag-X.Y.Z.apk`. The installed app name stays "Retag" (`android:label`).

If the version wasn't bumped, the push still builds but no release is published (the tag already exists).

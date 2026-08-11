# GlucoDroid — Claude Code Instructions

App package: `cloud.glucodroid`

## Package and app name — NEVER change

The application package must always be `cloud.glucodroid` and the app label must always be `GlucoDroid`.
These are set in `Common/build.gradle`:
- `defaultConfig { applicationId "cloud.glucodroid" }`
- Every release build type must have `resValue "string", "app_name", "GlucoDroid"`

If an upstream merge changes either of these, revert immediately and do not ship until fixed.

## Git workflow

Never push directly to `origin/glucodroid`. All fixes and features go through a PR:

1. Create a feature branch off `glucodroid` (e.g. `fix/<short-name>` or `feat/<short-name>`).
2. Commit the changes on that branch.
3. Push the feature branch: `git push -u origin <branch>`.
4. Open a PR with `gh pr create --base glucodroid --head <branch> --title "..." --body "..."`.
5. Merge with `gh pr merge <n> --squash --delete-branch` once CI is green.
6. The `glucodroid` branch itself is updated by the squash merge — never `git push` to it directly.

`origin` is `robster7674/glucodroid` (this repo). `upstream` is `ctqvva/JugglucoNG` — **never push to upstream** under any circumstances; it is read-only mirror reference.

## Release process

Every release follows these steps in order — do not skip any:

1. Bump `versionName` / `versionCode` in `Common/build.gradle` defaultConfig.
2. Build: `./gradlew assembleMobileRelease`.
3. Copy APK to `~/Downloads/glucodroid.apk` (exact filename — never rename).
4. Commit the version bump + any other release-blocker fixes on the feature branch.
5. Open and merge the PR into `glucodroid` (squash, delete branch).
6. Tag the merged commit on `glucodroid`: `git tag -a vX.Y.Z -m "vX.Y.Z" && git push origin vX.Y.Z`.
7. Create the GitHub release with `gh release create vX.Y.Z --prerelease --title "vX.Y.Z" --notes-file <notes.md> --target glucodroid`. (If `gh release create` fails on scope, use the REST API with `gh auth token`.)
8. **Upload the APK as a release asset** — `~/Downloads/glucodroid.apk` MUST be attached to the release as `glucodroid.apk`. A release with notes but no APK is incomplete and the release is not done. Verify `browser_download_url` is present in the upload response.

## Fresh clone setup

After cloning, initialise the libjuice native submodule before building:

```
git submodule update --init
echo "sdk.dir=/home/rob/android-sdk" > local.properties
```

## Build

After a successful build, copy the output APK to:

```
~/Downloads/glucodroid.apk
```

The file must always be named exactly `glucodroid.apk` — nothing else, ever.

Build command:

```
./gradlew assembleMobileRelease
```

The APK will be under `Common/build/outputs/apk/mobile/release/`.

Flavours collapsed to a single `wearos` dimension (`mobile` / `wear`) during
the 1.0.9→1.1.0 upstream rebase — the old `libreVersion` / `SiBionics` /
`DexCom` / `google` / `nogoogle` flavour dimensions are gone; every sensor
backend and Play Services are now compiled into every build unconditionally.
Use the `mobile` flavour for the phone APK (`wear` is the Wear OS
companion). minSdk is 26 for both flavours.

Build types are `release` (clean production build, minified + shrunk),
`releasedub`/`releasedub2` (upstream's parallel-install debug variants,
suffixed `.dub`/`.dub2`), and `debug`. There is no `releaser` type — use
`release`.

There are no ABI-trimming Gradle properties anymore (the old `-Pno_x86
-Pno_x86_64` flags read by a since-removed flavour catalog) — a release
build now always compiles native code for all 5 ABIs, so expect longer
build times (~10-15 min) than older instructions implied.

If the build cache returns a stale APK (wrong package name or version), run `./gradlew clean` first.

# Releasing HylaPocket

A release is a tag: pushing `vX.Y.Z` runs the Release workflow
(`.github/workflows/release.yml`), which builds the APK, signs it, and
leaves it in a GitHub release with its `SHA256SUMS`. The same steps run
locally with `scripts/release.sh`.

## Steps

1. Pick the version: a patch (0.1.0 → 0.1.1) for fixes, a minor for features.
2. Set it in `android/app/build.gradle.kts` (`versionName`, and `versionCode`
   goes up by one).
3. Say what changed since the last tag in the release notes
   (`git log vX.Y.Z..HEAD`), for the people using the app, not for who reads
   the code.
4. Commit, then tag and let the script build and publish:

   ```sh
   scripts/release.sh -n "$(git log --pretty='- %s' v0.1.0..HEAD)"
   ```

   It builds `:app:assembleRelease`, signs the APK, tags `vX.Y.Z`, pushes
   `master` and the tag, and creates the GitHub release with
   `HylaPocket-vX.Y.Z.apk` and its `.sha256`.

5. Watch the tag's workflow run finish:

   ```sh
   gh run list --workflow release.yml --limit 1
   gh run watch <run id> --exit-status
   ```

   The workflow runs on the tag; if it finds the release already made by the
   script, it uploads over it (`--clobber`) rather than failing.

## Signing

The APK is signed with the keystore in the `HYLA_KEYSTORE_B64` secret (base64
of the `.jks`), with `HYLA_KEYSTORE_PASSWORD`, `HYLA_KEY_ALIAS` and
`HYLA_KEY_PASSWORD`. Without it, both the workflow and the script fall back to
the debug keystore, which is for testing only: an APK signed with it cannot
be updated by one signed with another key, and Play refuses it.

To sign locally with the release key:

```sh
HYLA_KEYSTORE=~/keys/hylapocket.jks HYLA_KEYSTORE_PASSWORD=… \
HYLA_KEY_ALIAS=hylapocket HYLA_KEY_PASSWORD=… scripts/release.sh
```

## The tag must match the version

`scripts/release.sh` reads `versionName` and refuses to run when `vX.Y.Z`
already exists. A tag that disagrees with the version in the build file
publishes an APK named for the tag with the version inside it from the build
file, so set the version first, then release.

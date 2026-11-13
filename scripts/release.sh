#!/bin/sh
# Builds a release APK, tags it, and leaves it in a GitHub release, as Hyla's own releases are made:
# the tag is the word to release, and the release holds the artifact.
#
#   scripts/release.sh [-n "notes"] [--no-push]
#
# The version comes from android/app/build.gradle.kts (versionName), and the tag is vX.Y.Z. Signing
# uses $HYLA_KEYSTORE ($HYLA_KEYSTORE_PASSWORD, $HYLA_KEY_ALIAS, $HYLA_KEY_PASSWORD) when set, else
# the debug keystore, which is for testing only.
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"

notes=""
push=1
while [ $# -gt 0 ]; do
	case "$1" in
	-n|--notes) notes="$2"; shift 2 ;;
	--no-push) push=0; shift ;;
	*) echo "usage: scripts/release.sh [-n notes] [--no-push]" >&2; exit 2 ;;
	esac
done

version=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' android/app/build.gradle.kts | head -1)
tag="v$version"
[ -n "$version" ] || { echo "release: no versionName in android/app/build.gradle.kts" >&2; exit 1; }
if git rev-parse -q --verify "refs/tags/$tag" >/dev/null; then
	echo "release: $tag already exists" >&2
	exit 1
fi

echo "release: building $tag"
cd android
JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || echo "")} \
ANDROID_HOME=${ANDROID_HOME:-$HOME/android-sdk} \
	./gradlew --console=plain :app:assembleRelease
cd "$root"

unsigned=android/app/build/outputs/apk/release/app-release-unsigned.apk
aligned=android/app/build/outputs/apk/release/app-release-aligned.apk
apk="android/app/build/outputs/apk/release/HylaPocket-$tag.apk"
build_tools=${ANDROID_HOME:-$HOME/android-sdk}/build-tools/36.1.0

if [ -n "${HYLA_KEYSTORE:-}" ]; then
	ks=$HYLA_KEYSTORE
	ks_pass=${HYLA_KEYSTORE_PASSWORD:-}
	alias=${HYLA_KEY_ALIAS:-}
	key_pass=${HYLA_KEY_PASSWORD:-$ks_pass}
else
	ks=$HOME/.android/debug.keystore
	ks_pass=android
	alias=androiddebugkey
	key_pass=android
	echo "release: signing with the debug keystore, for testing only" >&2
fi

"$build_tools/zipalign" -p -f 4 "$unsigned" "$aligned"
"$build_tools/apksigner" sign --ks "$ks" --ks-pass "pass:$ks_pass" --key-pass "pass:$key_pass" \
	--ks-key-alias "$alias" --out "$apk" "$aligned"
rm -f "$aligned"

(cd "$(dirname "$apk")" && shasum -a 256 "$(basename "$apk")" > "HylaPocket-$tag.sha256")

echo "release: $apk"
git tag "$tag"
if [ "$push" = 1 ]; then
	git push origin "$(git rev-parse --abbrev-ref HEAD)"
	git push origin "$tag"
	if [ -z "$notes" ]; then
		previous=$(git describe --tags --abbrev=0 "$tag^" 2>/dev/null || echo "")
		range=${previous:+$previous..}$tag
		notes=$(git log --pretty='- %s' "$range" | head -40)
	fi
	gh release create "$tag" \
		--title "HylaPocket $version" \
		--notes "${notes:-Release $version}" \
		"$apk#HylaPocket-$tag.apk" \
		"$(dirname "$apk")/HylaPocket-$tag.sha256#HylaPocket-$tag.sha256"
	echo "release: https://github.com/$(gh repo view --json nameWithOwner -q .nameWithOwner)/releases/tag/$tag"
else
	echo "release: tagged $tag (not pushed)"
fi

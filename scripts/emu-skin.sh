#!/bin/sh
# Puts a device skin on the HylaPocket emulator, with the screen size and density that skin
# draws, and restarts the emulator: a skin's layout is the framebuffer, so the two go together.
#
#   scripts/emu-skin.sh                       the skin the AVD has, and the screen it shows
#   scripts/emu-skin.sh <pixel_7|pixel_8|pixel_8_pro> [--no-restart]
#
# The skins are the device art Android Studio draws, from
# github.com/larskristianhaga/Android-emulator-skins: one the SDK lacks is installed from there,
# in $ANDROID_SDK/skins. The SDK is $ANDROID_SDK_ROOT, $ANDROID_HOME, or ~/android-sdk.
set -eu

avd=hylapocket
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/android-sdk}}
adb="$sdk/platform-tools/adb"
emulator="$sdk/emulator/emulator"
cfg="$HOME/.android/avd/$avd.avd/config.ini"
skins="$sdk/skins"
repo=https://raw.githubusercontent.com/larskristianhaga/Android-emulator-skins/master

usage() {
	cat <<EOF
usage: scripts/emu-skin.sh [skin] [--no-restart]

  pixel_7      1080x2400 420dpi
  pixel_8      1080x2400 420dpi
  pixel_8_pro  1344x2992 480dpi

With no skin it prints the one the AVD has. --no-restart writes the AVD's config and leaves
the emulator as it is, to apply at the next start.
EOF
}

# spec prints a skin's name, screen in pels, and density: the size its device has.
spec() {
	case "$1" in
	pixel_7)     echo "pixel_7 1080 2400 420" ;;
	pixel_8)     echo "pixel_8 1080 2400 420" ;;
	pixel_8_pro) echo "pixel_8_pro 1344 2992 480" ;;
	*) return 1 ;;
	esac
}

# serial is the emulator running, or nothing.
serial() {
	"$adb" devices 2>/dev/null | awk '/^emulator-[0-9]+[[:space:]]+device$/ {print $1; exit}'
}

status() {
	name=$(sed -n 's/^skin\.name = //p' "$cfg" | tail -1)
	width=$(sed -n 's/^hw\.lcd\.width = //p' "$cfg" | tail -1)
	height=$(sed -n 's/^hw\.lcd\.height = //p' "$cfg" | tail -1)
	density=$(sed -n 's/^hw\.lcd\.density = //p' "$cfg" | tail -1)
	printf 'emu-skin: %s has %s at %sx%s %sdpi\n' \
		"$avd" "${name:-(no skin)}" "${width:-?}" "${height:-?}" "${density:-?}"
	s=$(serial)
	if [ -n "$s" ]; then
		printf 'emu-skin: %s shows %s, %s\n' "$s" \
			"$("$adb" -s "$s" shell wm size 2>/dev/null | tr -d '\r')" \
			"$("$adb" -s "$s" shell wm density 2>/dev/null | tr -d '\r')"
	fi
}

# install puts a skin's files in the SDK, unless they are there already.
install() {
	dir="$skins/$1"
	[ -f "$dir/layout" ] && return 0
	mkdir -p "$dir"
	for f in layout back.webp mask.webp; do
		if ! curl -fsSL "$repo/$1/$f" -o "$dir/$f"; then
			echo "emu-skin: cannot download $1/$f" >&2
			rm -rf "$dir"
			exit 1
		fi
	done
	echo "emu-skin: installed $1 in $dir"
}

# write sets the skin and the screen it draws for in the AVD's config, leaving the rest.
write() {
	name=$1 width=$2 height=$3 density=$4
	cp "$cfg" "$cfg.bak"
	awk -v name="$name" -v path="$skins/$name" -v w="$width" -v h="$height" -v d="$density" '
		/^skin\.name = / || /^skin\.path = / { next }
		/^hw\.lcd\.width = / || /^hw\.lcd\.height = / || /^hw\.lcd\.density = / { next }
		{ print }
		END {
			print "hw.lcd.width = " w
			print "hw.lcd.height = " h
			print "hw.lcd.density = " d
			print "skin.name = " name
			print "skin.path = " path
		}' "$cfg.bak" > "$cfg"
}

# restart stops the emulator and starts it again, and holds the screen upright: the virtual
# sensor otherwise turns it on its side as Android boots.
restart() {
	s=$(serial)
	if [ -n "$s" ]; then
		"$adb" -s "$s" emu kill >/dev/null 2>&1 || true
		i=0
		while [ -n "$(serial)" ] && [ "$i" -lt 40 ]; do
			sleep 0.5
			i=$((i + 1))
		done
	fi
	echo "emu-skin: starting $avd"
	nohup "$emulator" -avd "$avd" -netdelay none -netspeed full >"${TMPDIR:-/tmp}/emu-skin.log" 2>&1 &
	"$adb" wait-for-device
	i=0
	while [ "$i" -lt 180 ]; do
		[ "$("$adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
		sleep 1
		i=$((i + 1))
	done
	"$adb" shell settings put system accelerometer_rotation 0 >/dev/null 2>&1 || true
	"$adb" shell settings put system user_rotation 0 >/dev/null 2>&1 || true
	"$adb" shell cmd statusbar collapse >/dev/null 2>&1 || true
}

skin=""
apply=0
restart_after=1
while [ $# -gt 0 ]; do
	case "$1" in
	-h|--help) usage; exit 0 ;;
	--no-restart) restart_after=0; shift ;;
	-*) echo "emu-skin: unknown option $1" >&2; usage >&2; exit 2 ;;
	*)
		if ! spec "$1" >/dev/null; then
			echo "emu-skin: no such skin: $1" >&2
			usage >&2
			exit 2
		fi
		skin=$1
		apply=1
		shift
		;;
	esac
done

if [ "$apply" -eq 0 ]; then
	status
	exit 0
fi

read -r name width height density <<EOF
$(spec "$skin")
EOF
install "$name"
write "$name" "$width" "$height" "$density"
echo "emu-skin: $avd takes $name at ${width}x${height} ${density}dpi"
if [ "$restart_after" -eq 1 ]; then
	restart
fi
status

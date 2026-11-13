#!/usr/bin/env python3
"""Builds the app's libtailcat.so: the tailcat client of native/tailcat (Go, with the JNI in
native/tailcat/jni/jni.c) for every ABI in app/src/main/jniLibs.

With --host it builds native/.build/tailcat/host/libtailcat.dylib as well, the same code for this
computer, which the JVM tests load so that the JNI and the network path run without a device. That
one needs a JDK for jni.h (JAVA_HOME, or --jni-dir).

Requires Go 1.27 and an Android NDK: ANDROID_NDK_HOME, else the newest NDK 28 of the SDK named in
local.properties, ANDROID_HOME or ANDROID_SDK_ROOT. Nothing rebuilds while every input is older
than the last build (native/.build/tailcat/stamp); --force rebuilds anyway.
"""
import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "native/tailcat"
BUILD = ROOT / "native/.build/tailcat"
JNILIBS = ROOT / "app/src/main/jniLibs"
ABIS = {
    "arm64-v8a": ("arm64", "aarch64-linux-android"),
    "x86_64": ("amd64", "x86_64-linux-android"),
}
API = 26  # minSdk: the NDK's clang wrappers are named for it.
HOST_OUT = BUILD / "host/libtailcat.dylib"


def inputs():
    return [p for p in MODULE.rglob("*") if p.is_file()] + [Path(__file__).resolve()]


def up_to_date(abi, host):
    s = BUILD / "stamp"
    if not s.exists():
        return False
    if host and not HOST_OUT.exists():
        return False
    if not host and not (JNILIBS / abi / "libtailcat.so").exists():
        return False
    t = s.stat().st_mtime
    return all(p.stat().st_mtime <= t for p in inputs())


def go():
    for cand in (os.environ.get("GO") or "", "/usr/local/go/bin/go", shutil.which("go")):
        if cand and Path(cand).exists():
            return cand
    sys.exit("build-tailcat: Go not found; set GO")


def ndk():
    if os.environ.get("ANDROID_NDK_HOME"):
        return Path(os.environ["ANDROID_NDK_HOME"])
    sdks = []
    props = ROOT / "local.properties"
    for line in props.read_text().splitlines() if props.exists() else []:
        if line.startswith("sdk.dir="):
            sdks.append(Path(line.split("=", 1)[1].strip()))
    sdks += [Path(os.environ[v]) for v in ("ANDROID_HOME", "ANDROID_SDK_ROOT") if os.environ.get(v)]
    found = [n for sdk in sdks for n in (sdk / "ndk").glob("28.*")]
    if not found:
        sys.exit("build-tailcat: no NDK 28; set ANDROID_NDK_HOME")
    return sorted(found, key=lambda p: [int(x) for x in p.name.split(".") if x.isdigit()])[-1]


def build(goos, goarch, out, cc=None, cflags=None, debug=False):
    out.parent.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, CGO_ENABLED="1", GOOS=goos, GOARCH=goarch, GOTOOLCHAIN="local", GOFLAGS="-mod=mod")
    if cc:
        env["CC"] = str(cc)
    if cflags:
        env["CGO_CFLAGS"] = " ".join(cflags)
    ld = "" if debug else "-s -w"
    subprocess.run(
        [go(), "build", "-trimpath", "-buildmode=c-shared", f"-ldflags={ld}", "-o", str(out), "."],
        cwd=MODULE, env=env, check=True,
    )


def jdk_include(jni_dir):
    if jni_dir:
        return [f"-I{jni_dir}"]
    home = os.environ.get("JAVA_HOME")
    if not home:
        sys.exit("build-tailcat --host: set JAVA_HOME, or give --jni-dir")
    inc = Path(home) / "include"
    return [f"-I{inc}", f"-I{inc / ('darwin' if sys.platform == 'darwin' else 'linux')}"]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", action="store_true", help="also build for this computer, for the JVM tests")
    ap.add_argument("--jni-dir", type=Path, help="the folder that holds jni.h (default JAVA_HOME/include)")
    ap.add_argument("--force", action="store_true", help="rebuild even when up to date")
    ap.add_argument("--debug", action="store_true", help="keep symbols")
    args = ap.parse_args()

    host = args.host or "darwin" == sys.platform
    if all(up_to_date(a, host) for a in ABIS) and not args.force:
        print("build-tailcat: up to date")
        return

    clang = ndk() / "toolchains/llvm/prebuilt" / ("darwin-x86_64" if sys.platform == "darwin" else "linux-x86_64") / "bin"
    for abi, (goarch, target) in ABIS.items():
        out = JNILIBS / abi / "libtailcat.so"
        if up_to_date(abi, host) and not args.force:
            print(f"build-tailcat: {abi} up to date")
            continue
        print(f"build-tailcat: {abi} -> {out.relative_to(ROOT)}")
        build("android", goarch, out, clang / f"{target}{API}-clang", debug=args.debug)

    if host and (not up_to_date("arm64-v8a", True) or args.force):
        print(f"build-tailcat: host -> {HOST_OUT.relative_to(ROOT)}")
        build("darwin", "arm64" if os.uname().machine == "arm64" else "amd64", HOST_OUT,
              cflags=jdk_include(args.jni_dir), debug=args.debug)

    BUILD.mkdir(parents=True, exist_ok=True)
    (BUILD / "stamp").write_text("")


if __name__ == "__main__":
    main()

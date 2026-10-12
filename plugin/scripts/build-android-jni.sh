#!/usr/bin/env bash
# Build libpocket_iroh.so for Android ABIs into app/app/src/main/jniLibs.
# Requires: rustup, cargo-ndk, ANDROID_NDK_HOME (or Android SDK ndk/).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
IROH="$ROOT/plugin/iroh"
OUT="$ROOT/app/app/src/main/jniLibs"
# arm64-v8a is required for devices; x86_64 for emulator CI.
ABIS=(${POCKET_ANDROID_ABIS:-arm64-v8a x86_64})

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  if [[ -n "${ANDROID_HOME:-}" ]]; then
    # Prefer the highest installed NDK side-by-side package.
    ANDROID_NDK_HOME="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
  fi
fi
if [[ -z "${ANDROID_NDK_HOME:-}" || ! -d "${ANDROID_NDK_HOME}" ]]; then
  echo "ANDROID_NDK_HOME is not set and no \$ANDROID_HOME/ndk/* found" >&2
  exit 1
fi
export ANDROID_NDK_HOME

if ! command -v cargo-ndk >/dev/null 2>&1; then
  echo "installing cargo-ndk…"
  cargo install cargo-ndk --locked
fi

TARGETS=()
NDK_ARGS=()
for abi in "${ABIS[@]}"; do
  NDK_ARGS+=(-t "$abi")
  case "$abi" in
    arm64-v8a) TARGETS+=(aarch64-linux-android) ;;
    armeabi-v7a) TARGETS+=(armv7-linux-androideabi) ;;
    x86_64) TARGETS+=(x86_64-linux-android) ;;
    x86) TARGETS+=(i686-linux-android) ;;
    *) echo "unknown ABI: $abi" >&2; exit 1 ;;
  esac
done

rustup target add "${TARGETS[@]}"
mkdir -p "$OUT"
echo "building pocket-ffi → $OUT (${ABIS[*]}) with NDK $ANDROID_NDK_HOME"
(
  cd "$IROH"
  cargo ndk "${NDK_ARGS[@]}" -o "$OUT" build --release -p pocket-ffi
)

# cargo-ndk may also copy hashed cdylibs from dependency crates (e.g. libiroh-*.so).
# libpocket_iroh.so is self-contained (only needs libc/libm/libdl) — drop the extras.
STRIP="$(find "${ANDROID_NDK_HOME}" -name 'llvm-strip' -type f 2>/dev/null | head -1 || true)"
for abi in "${ABIS[@]}"; do
  dir="$OUT/$abi"
  so="$dir/libpocket_iroh.so"
  if [[ ! -f "$so" ]]; then
    echo "missing $so" >&2
    exit 1
  fi
  find "$dir" -type f -name '*.so' ! -name 'libpocket_iroh.so' -delete
  if [[ -n "$STRIP" ]]; then
    "$STRIP" --strip-unneeded "$so"
  fi
  ls -lh "$so"
done
echo "android jniLibs ready"

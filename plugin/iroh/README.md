# pocket-iroh

Desktop sidecar + Android JNI client for dsh-plugin-pocket over iroh (ALPN `dsh-pocket/1`).

Modeled on [dsh-tether](https://github.com/zexadev/dsh-tether) `tether-host`: N0 discovery/relays, 8s path upgrade window (`direct` if selected remote is an IP, else `relay`). The sidecar is a **dumb pipe** of newline-delimited pocket JSON frames — same `hello` / `pair` token auth as WebSocket.

## Crates

| Crate | Role |
|---|---|
| `pocket-iroh` | ALPN, secret key, line IO, path probe |
| `pocket-host` | stdio JSON ↔ plugin; accept iroh peers |
| `pocket-ffi` | `libpocket_iroh.so` JNI for Android (`IrohNative`) |

## Desktop sidecar (one-line install)

From the repo root:

```bash
cd plugin && npm run build:iroh
```

Needs a Rust toolchain ([rustup](https://rustup.rs)). On next `dsh web` start the plugin also:

1. Looks for an already-built binary (`iroh/target/release/pocket-host`, env `DSH_POCKET_HOST`, …)
2. Else downloads the CI prebuilt for this platform from the `dsh-android-latest` release (linux-x64 / darwin-arm64)
3. Else runs `cargo build --release` if `cargo` is on `PATH`

If all of that fails it logs a clear message and **keeps WebSocket-only mode** (Tailscale/LAN still work).

## Android JNI

CI builds `arm64-v8a` + `x86_64` with NDK + cargo-ndk and bundles them into the debug APK. Locally:

```bash
# ANDROID_NDK_HOME must point at an NDK (r26+)
cd plugin && npm run build:iroh:android
```

Outputs `app/app/src/main/jniLibs/<abi>/libpocket_iroh.so` (gitignored; produced in CI).

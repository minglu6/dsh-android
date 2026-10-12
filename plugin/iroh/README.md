# pocket-iroh

Desktop sidecar + Android JNI client for dsh-plugin-pocket over iroh (ALPN `dsh-pocket/1`).

Modeled on [dsh-tether](https://github.com/zexadev/dsh-tether) `tether-host`: N0 discovery/relays, 8s path upgrade window (`direct` if selected remote is an IP, else `relay`). Unlike tether’s approval/proxy wire protocol, this sidecar is a **dumb pipe** of newline-delimited pocket JSON frames — the same `hello` / `pair` token auth as WebSocket.

## Crates

| Crate | Role |
|---|---|
| `pocket-iroh` | ALPN, secret key, line IO, path probe |
| `pocket-host` | stdio JSON ↔ plugin; accept iroh peers |
| `pocket-ffi` | `libpocket_iroh.so` JNI for Android (`IrohNative`) |

## Build

```bash
# Desktop sidecar (required for plugin iroh mode)
cargo build --release -p pocket-host --manifest-path Cargo.toml
# or: cd ../ && npm run build:iroh

# Android JNI (optional; needs NDK + cargo-ndk)
# cargo ndk -t arm64-v8a -t x86_64 -o ../../app/app/src/main/jniLibs build --release -p pocket-ffi
```

Without the Android `.so`, the app still builds and connects over Tailscale/LAN WebSocket; iroh is skipped when `IrohNative.available` is false.

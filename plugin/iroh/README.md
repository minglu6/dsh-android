# pocket-iroh

Desktop sidecar + Android JNI client for dsh-plugin-pocket over iroh (ALPN `dsh-pocket/1`).

Modeled on [dsh-tether](https://github.com/zexadev/dsh-tether): N0 discovery/relays, 8s path upgrade (`direct` if IP, else `relay`). Pairing string is `nodeId#code` (QR: `dshpocket://pair?n=&c=`). The sidecar is a dumb pipe of pocket JSON frames — same `hello` / `pair` token auth as before.

**Line limit:** `MAX_LINE` / `MAX_UNPAIRED_LINE` are **1 MiB** in `pocket-iroh` (shared by `pocket-host` and Android `pocket-ffi`). The plugin also slims `sessions.list` to the fields Android `SessionSummary` needs so a large desktop session roster stays well under the cap.

## Crates

| Crate | Role |
|---|---|
| `pocket-iroh` | ALPN, secret key, line IO, path probe |
| `pocket-host` | stdio JSON ↔ plugin; accept iroh peers |
| `pocket-ffi` | `libpocket_iroh.so` JNI for Android (`IrohNative`) |

## Desktop sidecar (one-line install)

```bash
cd plugin && npm run build:iroh
```

Needs [rustup](https://rustup.rs). On `dsh web` start the plugin also resolves → downloads CI prebuilt → `cargo build` if needed. Without a sidecar, remote phone pairing is unavailable (no Tailscale fallback).

## Android JNI

CI builds `arm64-v8a` + `x86_64` with NDK + cargo-ndk into the debug APK. Locally:

```bash
cd plugin && npm run build:iroh:android
```

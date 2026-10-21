# pocket-iroh (legacy desktop sidecar)

Optional desktop sidecar for dsh-plugin-pocket over iroh (ALPN `dsh-pocket/1`).

**Phone path no longer uses this.** Enable only with plugin `config.iroh: true`. Android does not ship `libpocket_iroh.so` / JNI.

Modeled on [dsh-tether](https://github.com/zexadev/dsh-tether): N0 discovery + custom Tokyo relay (`https://iroh.9643275.xyz.`). Pairing string was historically `nodeId#code`; the current phone happy path is `dshpocket://pair?h=host:port&c=`.

| Crate | Role |
|---|---|
| `pocket-iroh` | ALPN, secret key, line IO, path probe |
| `pocket-host` | stdio JSON ↔ plugin; accept iroh peers |

```bash
cd plugin && npm run build:iroh
```

**Line limit:** `MAX_LINE` / `MAX_UNPAIRED_LINE` are **1 MiB** in `pocket-iroh`.

## Local transport tests

The host and phone simulator support an explicit `--local-only` fixture mode. It binds loopback only and disables public address lookup and relays. The host's ready frame includes `direct_addr`; pass that address with `phone-sim --local-only --peer <endpoint_id> --peer-addr <direct_addr>`.

The Node end-to-end cases run real QUIC/TLS, ALPN authentication, and device pairing without starting a WebSocket listener. They do not depend on public DNS or the Tokyo relay to connect two processes on the same runner. This is transport/protocol coverage, not proof of public relay availability. Omitting the flag retains the normal N0 discovery and configured relay behavior.

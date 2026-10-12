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

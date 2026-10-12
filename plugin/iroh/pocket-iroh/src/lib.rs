//! Shared iroh primitives for dsh-plugin-pocket.
//! ALPN `dsh-pocket/1`: one bi-stream of newline-delimited pocket JSON frames
//! (same RPC as the WebSocket transport). Path reporting matches dsh-tether.

use std::path::Path;
use std::time::Duration;

use anyhow::{bail, Context as _, Result};
use iroh::endpoint::{presets, Connection, RecvStream, RelayMode, SendStream};
use iroh::{Endpoint, RelayUrl, SecretKey};
pub const ALPN: &[u8] = b"dsh-pocket/1";
/// Per-line cap for pocket JSON frames over iroh (host + Android FFI share this).
/// 64 KiB was too small for a real `sessions.list` (~47 KiB @ 33 sessions with full
/// projections); 1 MiB leaves headroom for catalog + history pages.
pub const MAX_LINE: usize = 1024 * 1024;
pub const MAX_UNPAIRED_LINE: usize = 1024 * 1024;

/// Tokyo self-hosted relay used by pocket-host and the Android JNI stack.
///
/// Trailing DNS dot is the FQDN form iroh 1.3 encourages on [`RelayUrl`] (same
/// convention as n0's built-in relay hostnames). Parsing without the dot still
/// works; we bake the dotted form in to avoid search-domain relative lookups.
pub const DEFAULT_RELAY_URL: &str = "https://iroh.9643275.xyz.";

/// Optional comma-separated relay URL override for desktop `pocket-host`.
/// Unset → [`DEFAULT_RELAY_URL`]. Android never reads this (default is baked in).
pub const RELAY_URLS_ENV: &str = "DSH_IROH_RELAY_URLS";

/// Termux / Android JNI DNS can panic before ndk-context init; pin Google DNS.
#[cfg(target_os = "android")]
pub fn android_dns_resolver() -> iroh::dns::DnsResolver {
    use iroh::dns::DnsProtocol;
    use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
    const GOOGLE: [IpAddr; 4] = [
        IpAddr::V4(Ipv4Addr::new(8, 8, 8, 8)),
        IpAddr::V4(Ipv4Addr::new(8, 8, 4, 4)),
        IpAddr::V6(Ipv6Addr::new(0x2001, 0x4860, 0x4860, 0, 0, 0, 0, 0x8888)),
        IpAddr::V6(Ipv6Addr::new(0x2001, 0x4860, 0x4860, 0, 0, 0, 0, 0x8844)),
    ];
    iroh::dns::DnsResolver::builder()
        .with_nameservers(GOOGLE.into_iter().flat_map(|ip| {
            let addr = SocketAddr::new(ip, 53);
            [(addr, DnsProtocol::Udp), (addr, DnsProtocol::Tcp)]
        }))
        .build()
}

fn parse_relay_urls(raw: &str) -> Result<Vec<RelayUrl>> {
    let mut urls = Vec::new();
    for part in raw.split(',') {
        let part = part.trim();
        if part.is_empty() {
            continue;
        }
        urls.push(
            part.parse::<RelayUrl>()
                .with_context(|| format!("invalid relay URL in {RELAY_URLS_ENV}: {part}"))?,
        );
    }
    if urls.is_empty() {
        bail!("{RELAY_URLS_ENV} is set but contains no relay URLs");
    }
    Ok(urls)
}

/// Resolve relay URLs: desktop may override via [`RELAY_URLS_ENV`]; Android always
/// uses [`DEFAULT_RELAY_URL`].
pub fn relay_urls() -> Result<Vec<RelayUrl>> {
    #[cfg(not(target_os = "android"))]
    match std::env::var(RELAY_URLS_ENV) {
        Ok(raw) => return parse_relay_urls(&raw),
        Err(std::env::VarError::NotPresent) => {}
        Err(std::env::VarError::NotUnicode(_)) => {
            bail!("{RELAY_URLS_ENV} is not valid UTF-8");
        }
    }
    Ok(vec![DEFAULT_RELAY_URL
        .parse()
        .expect("DEFAULT_RELAY_URL must parse")])
}

pub fn relay_mode() -> Result<RelayMode> {
    Ok(RelayMode::custom(relay_urls()?))
}

/// Endpoint builder: N0 discovery (pkarr) + Tokyo custom relay (or env override).
pub fn endpoint_builder() -> Result<iroh::endpoint::Builder> {
    let builder = Endpoint::builder(presets::N0).relay_mode(relay_mode()?);
    #[cfg(target_os = "android")]
    let builder = builder.dns_resolver(android_dns_resolver());
    Ok(builder)
}

fn restrict(path: &Path, mode: u32) -> Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(path, std::fs::Permissions::from_mode(mode))
            .with_context(|| format!("cannot tighten permissions of {}", path.display()))?;
    }
    let _ = (path, mode);
    Ok(())
}

fn create_private_dir(dir: &Path) -> Result<()> {
    std::fs::create_dir_all(dir).with_context(|| format!("cannot create {}", dir.display()))?;
    restrict(dir, 0o700)
}

pub fn write_private(path: &Path, bytes: &[u8]) -> Result<()> {
    let dir = path.parent().unwrap_or_else(|| Path::new("."));
    create_private_dir(dir)?;
    let tmp = dir.join(format!(
        ".{}.tmp",
        path.file_name().and_then(|n| n.to_str()).unwrap_or("write")
    ));
    let mut opts = std::fs::OpenOptions::new();
    opts.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt as _;
        opts.mode(0o600);
    }
    let mut file = opts
        .open(&tmp)
        .with_context(|| format!("cannot write {}", tmp.display()))?;
    use std::io::Write as _;
    let written = file.write_all(bytes).and_then(|()| file.sync_all());
    drop(file);
    if let Err(e) = written {
        let _ = std::fs::remove_file(&tmp);
        return Err(e).with_context(|| format!("cannot write {}", tmp.display()));
    }
    if let Err(e) = std::fs::rename(&tmp, path) {
        let _ = std::fs::remove_file(&tmp);
        return Err(e).with_context(|| format!("cannot replace {}", path.display()));
    }
    restrict(path, 0o600)
}

pub fn load_or_create_secret(path: &Path) -> Result<SecretKey> {
    if let Ok(bytes) = std::fs::read(path) {
        let arr: [u8; 32] = bytes.as_slice().try_into().with_context(|| {
            format!("identity key damaged (not 32 bytes): {}", path.display())
        })?;
        restrict(path, 0o600)?;
        return Ok(SecretKey::from_bytes(&arr));
    }
    let key = SecretKey::generate();
    write_private(path, &key.to_bytes())
        .with_context(|| format!("cannot save identity key to {}", path.display()))?;
    Ok(key)
}

pub async fn read_line_bounded(recv: &mut RecvStream, max: usize) -> Result<String> {
    let mut buf = Vec::new();
    let mut byte = [0u8; 1];
    loop {
        let Some(n) = recv.read(&mut byte).await? else {
            bail!("peer closed mid-line");
        };
        if n == 0 {
            continue;
        }
        if byte[0] == b'\n' {
            return Ok(String::from_utf8(buf).context("control stream is not UTF-8")?);
        }
        buf.push(byte[0]);
        if buf.len() > max {
            bail!("line over limit ({max} bytes)");
        }
    }
}

pub async fn write_line(send: &mut SendStream, line: &str) -> Result<()> {
    use tokio::io::AsyncWriteExt as _;
    send.write_all(line.as_bytes()).await?;
    send.write_all(b"\n").await?;
    Ok(())
}

/// Poll selected path for up to 8s; IP remote ⇒ direct, else relay (tether convention).
pub async fn report_path(conn: Connection) -> Option<(String, String)> {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(8);
    loop {
        let selected = conn.paths().iter().find(|p| p.is_selected()).map(|p| {
            let addr = p.remote_addr();
            (
                if addr.is_ip() {
                    "direct".to_string()
                } else {
                    "relay".to_string()
                },
                format!("{addr:?}"),
            )
        });
        let done = tokio::time::Instant::now() >= deadline;
        if let Some((kind, remote)) = selected {
            if kind == "direct" || done {
                return Some((kind, remote));
            }
        } else if done {
            return None;
        }
        tokio::time::sleep(Duration::from_millis(500)).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn alpn_is_pocket() {
        assert_eq!(ALPN, b"dsh-pocket/1");
    }

    #[test]
    fn max_line_is_at_least_1_mib() {
        assert!(MAX_LINE >= 1024 * 1024, "MAX_LINE={MAX_LINE}");
        assert!(MAX_UNPAIRED_LINE >= 1024 * 1024, "MAX_UNPAIRED_LINE={MAX_UNPAIRED_LINE}");
    }

    #[test]
    fn default_relay_url_is_fqdn_with_trailing_dot() {
        assert!(
            DEFAULT_RELAY_URL.ends_with("xyz."),
            "iroh 1.3 encourages FQDN trailing-dot RelayUrls; got {DEFAULT_RELAY_URL}"
        );
        let url: RelayUrl = DEFAULT_RELAY_URL.parse().expect("default relay must parse");
        assert!(url.to_string().contains("iroh.9643275.xyz"));
    }

    #[test]
    fn parse_relay_urls_comma_separated() {
        let urls =
            parse_relay_urls("https://a.example./, https://b.example./").expect("valid list");
        assert_eq!(urls.len(), 2);
    }

    #[test]
    fn parse_relay_urls_rejects_empty_and_garbage() {
        assert!(parse_relay_urls("").is_err());
        assert!(parse_relay_urls("   ,  , ").is_err());
        assert!(parse_relay_urls("not-a-url").is_err());
    }

    #[test]
    fn endpoint_builder_uses_custom_relay() {
        // Ensure builder construction succeeds with the baked-in Tokyo relay.
        let _ = endpoint_builder().expect("endpoint builder with default relay");
    }

    #[test]
    fn secret_roundtrip() {
        let dir = std::env::temp_dir().join(format!("pocket-iroh-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let path = dir.join("identity.key");
        let a = load_or_create_secret(&path).unwrap();
        let b = load_or_create_secret(&path).unwrap();
        assert_eq!(a.to_bytes(), b.to_bytes());
        let _ = std::fs::remove_dir_all(&dir);
    }
}

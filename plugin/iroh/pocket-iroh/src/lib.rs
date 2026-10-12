//! Shared iroh primitives for dsh-plugin-pocket.
//! ALPN `dsh-pocket/1`: one bi-stream of newline-delimited pocket JSON frames
//! (same RPC as the WebSocket transport). Path reporting matches dsh-tether.

use std::path::Path;
use std::time::Duration;

use anyhow::{bail, Context as _, Result};
use iroh::endpoint::{presets, Connection, RecvStream, SendStream};
use iroh::{Endpoint, SecretKey};
pub const ALPN: &[u8] = b"dsh-pocket/1";
pub const MAX_LINE: usize = 64 * 1024;
pub const MAX_UNPAIRED_LINE: usize = 64 * 1024;

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

pub fn endpoint_builder() -> iroh::endpoint::Builder {
    let builder = Endpoint::builder(presets::N0);
    #[cfg(target_os = "android")]
    let builder = builder.dns_resolver(android_dns_resolver());
    builder
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

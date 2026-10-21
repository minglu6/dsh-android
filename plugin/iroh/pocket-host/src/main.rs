//! pocket-host: desktop iroh sidecar for dsh-plugin-pocket.
//!
//! Stdio speaks newline-JSON with the Node plugin. Each accepted phone
//! connection opens one bi-stream that carries the same pocket JSON frames as
//! the WebSocket transport (auth tokens identical). Path kind is reported as
//! `direct` or `relay` after an 8s upgrade window (same rule as dsh-tether).

use std::collections::HashMap;
use std::net::{Ipv4Addr, SocketAddr};
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};

use anyhow::{bail, Context as _, Result};
use clap::{Parser, Subcommand};
use iroh::endpoint::{presets, Connection, RelayMode};
use iroh::{Endpoint, EndpointAddr, EndpointId};
use pocket_iroh::{
    endpoint_builder, load_or_create_secret, read_line_bounded, report_path, write_line, ALPN,
    MAX_LINE,
};
use serde::{Deserialize, Serialize};
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::sync::{mpsc, Mutex};

#[derive(Parser)]
#[command(name = "pocket-host", about = "dsh-pocket iroh sidecar")]
struct Cli {
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// Sidecar mode: stdio JSON-lines ↔ plugin, iroh ↔ phone.
    Host {
        #[arg(long)]
        data_dir: Option<PathBuf>,
        /// Bind only loopback, with relay and public address lookup disabled.
        #[arg(long)]
        local_only: bool,
    },
    /// Phone stand-in for integration tests.
    PhoneSim {
        #[arg(long)]
        peer: String,
        #[arg(long)]
        data_dir: Option<PathBuf>,
        /// First pocket JSON line to send (pair or hello). Default: wait for stdin lines.
        #[arg(long)]
        first: Option<String>,
        /// Connect to a local host without relay or public address lookup.
        #[arg(long, requires = "peer_addr")]
        local_only: bool,
        #[arg(long, requires = "local_only")]
        peer_addr: Option<SocketAddr>,
    },
    /// Print this machine's endpoint id.
    Id {
        #[arg(long)]
        data_dir: Option<PathBuf>,
    },
}

#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "kebab-case")]
enum PluginIn {
    SessionSend { id: String, line: String },
    SessionClose { id: String },
}

#[derive(Serialize)]
#[serde(tag = "type", rename_all = "kebab-case")]
enum PluginOut {
    Ready {
        endpoint_id: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        direct_addr: Option<SocketAddr>,
    },
    PeerSession { id: String, peer: String },
    PeerData { id: String, line: String },
    PeerPath { peer: String, kind: String, remote: String },
    PeerSessionEnd { id: String, peer: String },
}

struct Session {
    tx: mpsc::Sender<String>,
    conn: Connection,
}

struct HostState {
    sessions: HashMap<String, Session>,
}

fn emit(msg: &PluginOut) {
    println!("{}", serde_json::to_string(msg).expect("PluginOut serializable"));
}

fn default_data_dir() -> PathBuf {
    dirs::config_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join("dsh-pocket")
}

#[tokio::main]
async fn main() -> Result<()> {
    let cli = Cli::parse();
    match cli.cmd {
        Cmd::Id { data_dir } => {
            let dir = data_dir.unwrap_or_else(default_data_dir);
            let secret = load_or_create_secret(&dir.join("identity.key"))?;
            println!("{}", secret.public());
        }
        Cmd::Host { data_dir, local_only } => {
            host_main(data_dir.unwrap_or_else(default_data_dir), local_only).await?
        }
        Cmd::PhoneSim {
            peer,
            data_dir,
            first,
            local_only,
            peer_addr,
        } => {
            let dir = data_dir.unwrap_or_else(|| {
                dirs::config_dir()
                    .unwrap_or_else(|| PathBuf::from("."))
                    .join("dsh-pocket-phone-sim")
            });
            phone_sim_main(dir, peer, first, local_only, peer_addr).await?
        }
    }
    Ok(())
}

fn sidecar_endpoint_builder(local_only: bool) -> Result<iroh::endpoint::Builder> {
    if local_only {
        Ok(Endpoint::builder(presets::Minimal)
            .clear_ip_transports()
            .bind_addr((Ipv4Addr::LOCALHOST, 0))?
            .relay_mode(RelayMode::Disabled))
    } else {
        endpoint_builder()
    }
}

async fn host_main(data_dir: PathBuf, local_only: bool) -> Result<()> {
    let secret = load_or_create_secret(&data_dir.join("identity.key"))?;
    let ep = sidecar_endpoint_builder(local_only)?
        .secret_key(secret)
        .alpns(vec![ALPN.to_vec()])
        .bind()
        .await
        .context("failed to start iroh endpoint")?;
    let direct_addr = if local_only {
        Some(ep.bound_sockets().into_iter().find(|addr| addr.ip().is_loopback())
            .context("local endpoint did not bind a loopback socket")?)
    } else {
        None
    };
    emit(&PluginOut::Ready {
        endpoint_id: ep.id().to_string(),
        direct_addr,
    });

    let state = Arc::new(Mutex::new(HostState {
        sessions: HashMap::new(),
    }));
    let next_id = Arc::new(AtomicU64::new(1));
    tokio::spawn(stdin_loop(state.clone()));

    loop {
        tokio::select! {
            _ = tokio::signal::ctrl_c() => break,
            incoming = ep.accept() => {
                let Some(incoming) = incoming else { break };
                let state = state.clone();
                let next_id = next_id.clone();
                tokio::spawn(async move {
                    if let Err(e) = handle_phone(incoming, state, next_id).await {
                        eprintln!("[pocket-host] inbound failed: {e:#}");
                    }
                });
            }
        }
    }
    ep.close().await;
    Ok(())
}

async fn stdin_loop(state: Arc<Mutex<HostState>>) {
    let mut lines = BufReader::new(tokio::io::stdin()).lines();
    while let Ok(Some(line)) = lines.next_line().await {
        let msg: PluginIn = match serde_json::from_str(&line) {
            Ok(m) => m,
            Err(e) => {
                eprintln!("[pocket-host] bad plugin message: {e}: {line}");
                continue;
            }
        };
        match msg {
            PluginIn::SessionSend { id, line } => {
                let s = state.lock().await;
                if let Some(sess) = s.sessions.get(&id) {
                    let _ = sess.tx.try_send(line);
                }
            }
            PluginIn::SessionClose { id } => {
                let mut s = state.lock().await;
                if let Some(sess) = s.sessions.remove(&id) {
                    sess.conn.close(0u32.into(), b"close");
                }
            }
        }
    }
}

async fn handle_phone(
    incoming: iroh::endpoint::Incoming,
    state: Arc<Mutex<HostState>>,
    next_id: Arc<AtomicU64>,
) -> Result<()> {
    let conn = incoming
        .await
        .context("failed to accept connection")?;
    let peer = conn.remote_id().to_string();
    let (mut send, mut recv) = conn
        .accept_bi()
        .await
        .context("failed to accept bi-stream")?;

    let id = format!("s{}", next_id.fetch_add(1, Ordering::Relaxed));
    let (tx, mut rx) = mpsc::channel::<String>(256);
    {
        let mut s = state.lock().await;
        s.sessions.insert(
            id.clone(),
            Session {
                tx,
                conn: conn.clone(),
            },
        );
    }
    emit(&PluginOut::PeerSession {
        id: id.clone(),
        peer: peer.clone(),
    });

    {
        let conn = conn.clone();
        let peer = peer.clone();
        tokio::spawn(async move {
            if let Some((kind, remote)) = report_path(conn).await {
                emit(&PluginOut::PeerPath { peer, kind, remote });
            }
        });
    }

    let writer = async {
        while let Some(line) = rx.recv().await {
            if write_line(&mut send, &line).await.is_err() {
                break;
            }
        }
    };
    let reader = async {
        let mut pending = Vec::new();
        loop {
            match read_line_bounded(&mut recv, &mut pending, MAX_LINE).await {
                Ok(line) => emit(&PluginOut::PeerData {
                    id: id.clone(),
                    line,
                }),
                Err(_) => break,
            }
        }
    };
    tokio::join!(writer, reader);

    {
        let mut s = state.lock().await;
        s.sessions.remove(&id);
    }
    emit(&PluginOut::PeerSessionEnd { id, peer });
    Ok(())
}

async fn phone_sim_main(
    data_dir: PathBuf,
    peer: String,
    first: Option<String>,
    local_only: bool,
    peer_addr: Option<SocketAddr>,
) -> Result<()> {
    let peer: EndpointId = peer
        .trim()
        .parse()
        .map_err(|e| anyhow::anyhow!("invalid --peer: {e}"))?;
    let mut peer = EndpointAddr::new(peer);
    if let Some(addr) = peer_addr {
        if !addr.ip().is_loopback() {
            bail!("--peer-addr must be a loopback address in --local-only mode");
        }
        peer = peer.with_ip_addr(addr);
    }
    let secret = load_or_create_secret(&data_dir.join("identity.key"))?;
    let ep = sidecar_endpoint_builder(local_only)?.secret_key(secret).bind().await?;
    eprintln!("[phone-sim] local id: {}", ep.id());
    let conn = ep
        .connect(peer, ALPN)
        .await
        .context("connect to host failed")?;
    let (mut send, mut recv) = conn.open_bi().await.context("open bi failed")?;

    if let Some(line) = first {
        write_line(&mut send, &line).await?;
    }

    // When stdin is not a TTY (tests pipe `ignore`/`pipe`), EOF must not tear down
    // the session — otherwise the peer closes before the host accepts the bi-stream.
    let mut stdin = BufReader::new(tokio::io::stdin()).lines();
    let mut stdin_open = true;
    let mut pending = Vec::new();
    loop {
        tokio::select! {
            r = read_line_bounded(&mut recv, &mut pending, MAX_LINE) => {
                let line = r?;
                println!("{line}");
            }
            r = stdin.next_line(), if stdin_open => {
                match r? {
                    Some(line) if !line.is_empty() => write_line(&mut send, &line).await?,
                    Some(_) => {}
                    None => stdin_open = false,
                }
            }
            reason = conn.closed() => bail!("connection closed: {reason}"),
            _ = tokio::signal::ctrl_c() => break,
        }
    }
    ep.close().await;
    Ok(())
}

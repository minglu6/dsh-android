//! JNI bindings for the Android iroh client (`dev.dsh.pocket.app.iroh.IrohNative`).
//! Library name: `libpocket_iroh.so`.

use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use iroh::EndpointId;
use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;
use pocket_iroh::{
    endpoint_builder, load_or_create_secret, read_line_bounded, report_path, write_line, ALPN,
    MAX_LINE,
};
use tokio::runtime::Runtime;
use tokio::sync::Mutex;

struct SharedRt {
    rt: Runtime,
}

struct PocketEndpoint {
    shared: Arc<SharedRt>,
    ep: iroh::Endpoint,
}

struct PocketSession {
    shared: Arc<SharedRt>,
    conn: iroh::endpoint::Connection,
    send: Mutex<iroh::endpoint::SendStream>,
    recv: Mutex<iroh::endpoint::RecvStream>,
    path: Arc<Mutex<Option<String>>>,
}

thread_local! {
    static LAST_ERROR: std::cell::RefCell<Option<String>> = const { std::cell::RefCell::new(None) };
}

fn set_err(msg: impl ToString) {
    LAST_ERROR.with(|e| *e.borrow_mut() = Some(msg.to_string()));
}

fn clear_err() {
    LAST_ERROR.with(|e| *e.borrow_mut() = None);
}

fn take_err() -> Option<String> {
    LAST_ERROR.with(|e| e.borrow_mut().take())
}

fn jstr(env: &mut JNIEnv, s: JString) -> Result<String, String> {
    env.get_string(&s)
        .map(|v| v.into())
        .map_err(|e| e.to_string())
}

fn to_jstring(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s)
        .map(|j| j.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_dev_dsh_pocket_app_iroh_IrohNative_lastError(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    match take_err() {
        Some(s) => to_jstring(&mut env, &s),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_dsh_pocket_app_iroh_IrohNative_endpointCreate(
    mut env: JNIEnv,
    _class: JClass,
    data_dir: JString,
) -> jlong {
    clear_err();
    let dir = match jstr(&mut env, data_dir) {
        Ok(s) => PathBuf::from(s),
        Err(e) => {
            set_err(e);
            return 0;
        }
    };
    let shared = Arc::new(SharedRt {
        rt: match Runtime::new() {
            Ok(rt) => rt,
            Err(e) => {
                set_err(e.to_string());
                return 0;
            }
        },
    });
    let ep = shared.rt.block_on(async {
        let secret = load_or_create_secret(&dir.join("identity.key"))?;
        endpoint_builder()
            .secret_key(secret)
            .bind()
            .await
            .map_err(|e| anyhow::anyhow!("{e:#}"))
    });
    match ep {
        Ok(ep) => Box::into_raw(Box::new(PocketEndpoint { shared, ep })) as jlong,
        Err(e) => {
            set_err(format!("{e:#}"));
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_dsh_pocket_app_iroh_IrohNative_endpointId(
    mut env: JNIEnv,
    _class: JClass,
    ep: jlong,
) -> jstring {
    if ep == 0 {
        return std::ptr::null_mut();
    }
    let ep = unsafe { &*(ep as *const PocketEndpoint) };
    to_jstring(&mut env, &ep.ep.id().to_string())
}

#[no_mangle]
pub extern "system" fn Java_dev_dsh_pocket_app_iroh_IrohNative_endpointConnect(
    mut env: JNIEnv,
    _class: JClass,
    ep: jlong,
    peer: JString,
    timeout_ms: jlong,
) -> jlong {
    clear_err();
    if ep == 0 {
        set_err("null endpoint");
        return 0;
    }
    let ep = unsafe { &*(ep as *const PocketEndpoint) };
    let peer_s = match jstr(&mut env, peer) {
        Ok(s) => s,
        Err(e) => {
            set_err(e);
            return 0;
        }
    };
    let peer: EndpointId = match peer_s.trim().parse() {
        Ok(p) => p,
        Err(e) => {
            set_err(format!("invalid peer id: {e}"));
            return 0;
        }
    };
    let endpoint = ep.ep.clone();
    let result = ep.shared.rt.block_on(async {
        let conn = tokio::time::timeout(
            Duration::from_millis((timeout_ms as u64).max(1)),
            endpoint.connect(peer, ALPN),
        )
        .await
        .map_err(|_| anyhow::anyhow!("connect timed out"))?
        .map_err(|e| anyhow::anyhow!("connect failed: {e:#}"))?;
        let (send, recv) = conn
            .open_bi()
            .await
            .map_err(|e| anyhow::anyhow!("open bi failed: {e:#}"))?;
        Ok::<_, anyhow::Error>((conn, send, recv))
    });
    match result {
        Ok((conn, send, recv)) => {
            let path = Arc::new(Mutex::new(None));
            {
                let c = conn.clone();
                let path = path.clone();
                ep.shared.rt.spawn(async move {
                    if let Some((kind, _)) = report_path(c).await {
                        *path.lock().await = Some(kind);
                    }
                });
            }
            Box::into_raw(Box::new(PocketSession {
                shared: ep.shared.clone(),
                conn,
                send: Mutex::new(send),
                recv: Mutex::new(recv),
                path,
            })) as jlong
        }
        Err(e) => {
            set_err(format!("{e:#}"));
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_dsh_pocket_app_iroh_IrohNative_endpointClose(
    _env: JNIEnv,
    _class: JClass,
    ep: jlong,
) {
    if ep == 0 {
        return;
    }
    let ep = unsafe { Box::from_raw(ep as *mut PocketEndpoint) };
    ep.shared.rt.block_on(async {
        ep.ep.close().await;
    });
}

#[no_mangle]
pub extern "system" fn Java_dev_dsh_pocket_app_iroh_IrohNative_sessionSend(
    mut env: JNIEnv,
    _class: JClass,
    sess: jlong,
    line: JString,
) -> jint {
    clear_err();
    if sess == 0 {
        set_err("null session");
        return -1;
    }
    let sess = unsafe { &*(sess as *const PocketSession) };
    let line = match jstr(&mut env, line) {
        Ok(s) => s,
        Err(e) => {
            set_err(e);
            return -1;
        }
    };
    match sess.shared.rt.block_on(async {
        let mut send = sess.send.lock().await;
        write_line(&mut send, &line).await
    }) {
        Ok(()) => 0,
        Err(e) => {
            set_err(format!("{e:#}"));
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_dsh_pocket_app_iroh_IrohNative_sessionRecv(
    mut env: JNIEnv,
    _class: JClass,
    sess: jlong,
    timeout_ms: jlong,
) -> jstring {
    clear_err();
    if sess == 0 {
        set_err("null session");
        return std::ptr::null_mut();
    }
    let sess = unsafe { &*(sess as *const PocketSession) };
    match sess.shared.rt.block_on(async {
        let mut recv = sess.recv.lock().await;
        match tokio::time::timeout(
            Duration::from_millis((timeout_ms as u64).max(1)),
            read_line_bounded(&mut recv, MAX_LINE),
        )
        .await
        {
            Ok(Ok(line)) => Ok(Some(line)),
            Ok(Err(e)) => Err(e),
            Err(_) => Ok(None),
        }
    }) {
        Ok(Some(line)) => to_jstring(&mut env, &line),
        Ok(None) => to_jstring(&mut env, ""),
        Err(e) => {
            set_err(format!("{e:#}"));
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_dsh_pocket_app_iroh_IrohNative_sessionWaitPath(
    mut env: JNIEnv,
    _class: JClass,
    sess: jlong,
    timeout_ms: jlong,
) -> jstring {
    if sess == 0 {
        return to_jstring(&mut env, "unknown");
    }
    let sess = unsafe { &*(sess as *const PocketSession) };
    let kind = sess.shared.rt.block_on(async {
        let deadline =
            tokio::time::Instant::now() + Duration::from_millis((timeout_ms as u64).max(1));
        loop {
            if let Some(k) = sess.path.lock().await.clone() {
                return k;
            }
            if tokio::time::Instant::now() >= deadline {
                if let Some((kind, _)) = report_path(sess.conn.clone()).await {
                    *sess.path.lock().await = Some(kind.clone());
                    return kind;
                }
                return "unknown".into();
            }
            tokio::time::sleep(Duration::from_millis(200)).await;
        }
    });
    to_jstring(&mut env, &kind)
}

#[no_mangle]
pub extern "system" fn Java_dev_dsh_pocket_app_iroh_IrohNative_sessionClose(
    _env: JNIEnv,
    _class: JClass,
    sess: jlong,
) {
    if sess == 0 {
        return;
    }
    let sess = unsafe { Box::from_raw(sess as *mut PocketSession) };
    sess.conn.close(0u32.into(), b"bye");
}

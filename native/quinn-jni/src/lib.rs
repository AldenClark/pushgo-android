use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc as std_mpsc;
use std::sync::{Arc, LazyLock, Mutex};
use std::time::{Duration, Instant};

use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong, jstring};
use jni::{EnvUnowned, Outcome};
use pushgo_warp_profile::{PrivatePayloadEnvelope, PushgoWireProfile};
use serde::Deserialize;
use tokio::runtime::Runtime;
use tokio::sync::mpsc::error::TryRecvError;
use tokio::sync::{mpsc, watch};
use warp_link::{client_run_with_shutdown, warp_link_core};
use warp_link_core::{
    AppDecision, ClientApp, ClientAppStateHint, ClientConfig, ClientEvent, ClientPolicy,
    ClientPowerHint, ClientPowerTier, HelloCtx, PinnedTransport, PolicyInput, ProbeRttSource,
    TransportKind,
};

const PRIVATE_CONNECT_BUDGET_MS: u64 = 4_500;
const PRIVATE_TCP_DELAY_FOREGROUND_MS: u64 = 350;
const PRIVATE_WSS_DELAY_FOREGROUND_MS: u64 = 1_300;
const PRIVATE_TCP_DELAY_BACKGROUND_MS: u64 = 1_000;
const PRIVATE_WSS_DELAY_BACKGROUND_MS: u64 = 2_800;
const WIRE_VERSION_V2: u8 = 2;
const DEFAULT_WSS_SUBPROTOCOL: &str = "pushgo-private.v1";

#[derive(Debug, Clone)]
struct NativePinnedTransport {
    transport: TransportKind,
    expires_at_unix_ms: Option<i64>,
}

#[derive(Debug, Clone, Default)]
struct NativeSchedulerPolicy {
    force_reconnect_nonce: u64,
    pinned_transport: Option<NativePinnedTransport>,
    disabled_transports: Vec<TransportKind>,
}

#[derive(Debug, Deserialize)]
struct SessionConfig {
    host: String,
    #[serde(default)]
    quic_port: Option<u16>,
    #[serde(default)]
    quic_enabled: Option<bool>,
    #[serde(default)]
    wss_port: Option<u16>,
    #[serde(default)]
    wss_enabled: Option<bool>,
    #[serde(default)]
    tcp_port: Option<u16>,
    #[serde(default)]
    tcp_enabled: Option<bool>,
    #[serde(default)]
    wss_path: Option<String>,
    #[serde(default)]
    wss_subprotocol: Option<String>,
    #[serde(default)]
    bearer_token: Option<String>,
    #[serde(default)]
    cert_pin_sha256: Option<String>,
    #[serde(default)]
    quic_cert_pin_sha256: Option<String>,
    #[serde(default)]
    tcp_cert_pin_sha256: Option<String>,
    #[serde(default)]
    wss_cert_pin_sha256: Option<String>,
    identity: String,
    #[serde(default, alias = "auth_token")]
    gateway_token: Option<String>,
    #[serde(default)]
    resume_token: Option<String>,
    #[serde(default)]
    last_acked_seq: Option<u64>,
    #[serde(default)]
    perf_tier: Option<String>,
    #[serde(default)]
    app_state: Option<String>,
    #[serde(default)]
    ack_wait_timeout_ms: Option<u64>,
    #[serde(default)]
    connect_budget_ms: Option<u64>,
    #[serde(default)]
    backoff_max_ms: Option<u64>,
    #[serde(default)]
    scheduler_v2_enabled: Option<bool>,
    #[serde(default)]
    drain_timeout_ms: Option<u64>,
    #[serde(default)]
    cutover_guard_ms: Option<u64>,
    #[serde(default)]
    initial_pin_transport: Option<String>,
    #[serde(default)]
    initial_pin_ttl_ms: Option<u64>,
}

#[derive(Clone)]
struct EventApp {
    hello: Arc<Mutex<HelloCtx>>,
    power_hint: Arc<Mutex<Option<ClientPowerHint>>>,
    pending_acks: Arc<Mutex<HashMap<u64, std_mpsc::SyncSender<AppDecision>>>>,
    next_ack_id: Arc<AtomicU64>,
    probe_request_epoch: Arc<AtomicU64>,
    consumed_probe_epoch: Arc<AtomicU64>,
    scheduler_policy: Arc<Mutex<NativeSchedulerPolicy>>,
    ack_wait_timeout_ms: u64,
    session_started_at: Instant,
    tx: mpsc::UnboundedSender<String>,
}

impl ClientApp for EventApp {
    fn on_hello(&self) -> HelloCtx {
        self.hello
            .lock()
            .map(|value| value.clone())
            .unwrap_or_else(|_| HelloCtx::default())
    }

    fn on_event(&self, event: ClientEvent) -> AppDecision {
        match event {
            ClientEvent::Message { transport, msg } => {
                let (payload, decode_ok) = decode_payload_map(msg.payload.as_ref());
                if !decode_ok {
                    let event = serde_json::json!({
                        "type": "message",
                        "transport": transport.to_string(),
                        "delivery_id": msg.id,
                        "seq": msg.seq,
                        "payload": payload,
                        "payload_len": msg.payload.len(),
                        "decode_ok": false,
                    })
                    .to_string();
                    let _ = self.tx.send(event);
                    return AppDecision::AckInvalidPayload;
                }

                let ack_id = self.next_ack_id.fetch_add(1, Ordering::Relaxed);
                let (decision_tx, decision_rx) = std_mpsc::sync_channel(1);
                {
                    let mut pending = match self.pending_acks.lock() {
                        Ok(guard) => guard,
                        Err(_) => return AppDecision::Ignore,
                    };
                    pending.insert(ack_id, decision_tx);
                }

                let event = serde_json::json!({
                    "type": "message",
                    "transport": transport.to_string(),
                    "delivery_id": msg.id,
                    "seq": msg.seq,
                    "ack_id": ack_id,
                    "payload": payload,
                    "payload_len": msg.payload.len(),
                    "decode_ok": true,
                    "elapsed_ms": elapsed_ms(self.session_started_at),
                })
                .to_string();
                if self.tx.send(event).is_err() {
                    if let Ok(mut pending) = self.pending_acks.lock() {
                        pending.remove(&ack_id);
                    }
                    return AppDecision::Ignore;
                }

                match decision_rx.recv_timeout(Duration::from_millis(self.ack_wait_timeout_ms)) {
                    Ok(decision) => decision,
                    Err(_) => {
                        if let Ok(mut pending) = self.pending_acks.lock() {
                            pending.remove(&ack_id);
                        }
                        AppDecision::Ignore
                    }
                }
            }
            other => {
                let _ = self.tx.send(event_to_json(&other, self.session_started_at));
                AppDecision::Ignore
            }
        }
    }

    fn power_hint(&self) -> Option<ClientPowerHint> {
        self.power_hint.lock().ok().and_then(|value| *value)
    }

    fn take_probe_request(&self) -> bool {
        let requested = self.probe_request_epoch.load(Ordering::Relaxed);
        let consumed = self.consumed_probe_epoch.load(Ordering::Relaxed);
        if requested <= consumed {
            return false;
        }
        self.consumed_probe_epoch
            .store(requested, Ordering::Relaxed);
        true
    }

    fn scheduler_policy(&self) -> PolicyInput {
        let snapshot = self.scheduler_policy.lock().map(|value| value.clone()).ok();
        let Some(snapshot) = snapshot else {
            return PolicyInput::default();
        };
        PolicyInput {
            disabled_transports: snapshot.disabled_transports.clone(),
            pinned_transport: snapshot.pinned_transport.map(|value| PinnedTransport {
                transport: value.transport,
                expires_at_unix_ms: value.expires_at_unix_ms,
            }),
            force_reconnect_nonce: snapshot.force_reconnect_nonce,
        }
    }
}

struct Session {
    task: Mutex<tokio::task::JoinHandle<()>>,
    shutdown_tx: watch::Sender<bool>,
    events: Mutex<mpsc::UnboundedReceiver<String>>,
    hello: Arc<Mutex<HelloCtx>>,
    power_hint: Arc<Mutex<Option<ClientPowerHint>>>,
    pending_acks: Arc<Mutex<HashMap<u64, std_mpsc::SyncSender<AppDecision>>>>,
    probe_request_epoch: Arc<AtomicU64>,
    scheduler_policy: Arc<Mutex<NativeSchedulerPolicy>>,
}

static NEXT_HANDLE: AtomicU64 = AtomicU64::new(1);
static RUNTIME: LazyLock<Result<Runtime, String>> =
    LazyLock::new(|| Runtime::new().map_err(|e| format!("create runtime failed: {e}")));
static SESSIONS: LazyLock<Mutex<HashMap<u64, Arc<Session>>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

fn runtime() -> Result<&'static Runtime, String> {
    match &*RUNTIME {
        Ok(runtime) => Ok(runtime),
        Err(err) => Err(err.clone()),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_ethan_pushgo_notifications_WarpLinkNativeBridge_nativeSessionStart(
    mut env: EnvUnowned,
    _class: JClass,
    config_json: JString,
) -> jlong {
    let Some(config_raw) = jstring_to_rust(&mut env, &config_json) else {
        return 0;
    };
    let parsed: SessionConfig = match serde_json::from_str(&config_raw) {
        Ok(v) => v,
        Err(_) => return 0,
    };

    let hello = HelloCtx {
        identity: parsed.identity,
        auth_token: parsed
            .gateway_token
            .clone()
            .or_else(|| parsed.bearer_token.clone()),
        resume_token: parsed.resume_token,
        last_acked_seq: parsed.last_acked_seq,
        supported_wire_versions: vec![WIRE_VERSION_V2],
        supported_payload_versions: vec![1],
        perf_tier: None,
        app_state: None,
        metadata: std::collections::BTreeMap::new(),
    };
    let initial_power_hint =
        parse_power_hint(parsed.app_state.as_deref(), parsed.perf_tier.as_deref());
    let ack_wait_timeout_ms = parsed
        .ack_wait_timeout_ms
        .unwrap_or(10_000)
        .clamp(1_000, 14_000);
    let mut policy = ClientPolicy::default();
    let (tcp_delay_ms, wss_delay_ms) =
        private_transport_connect_delays(parsed.app_state.as_deref());
    policy.connect_budget_ms = parsed
        .connect_budget_ms
        .unwrap_or(PRIVATE_CONNECT_BUDGET_MS)
        .clamp(1_500, 30_000);
    policy.tcp_delay_ms = tcp_delay_ms;
    policy.wss_delay_ms = wss_delay_ms;
    policy.backoff_max_ms = parsed.backoff_max_ms.unwrap_or(policy.backoff_max_ms).clamp(10_000, 180_000);
    policy.scheduler_v2_enabled = parsed.scheduler_v2_enabled.unwrap_or(true);
    policy.drain_timeout_ms = parsed.drain_timeout_ms.unwrap_or(8_000);
    policy.cutover_guard_ms = parsed.cutover_guard_ms.unwrap_or(1_500);

    let config = ClientConfig {
        host: parsed.host,
        quic_port: parsed.quic_port.unwrap_or(443),
        wss_port: parsed.wss_port.or(parsed.quic_port).unwrap_or(443),
        tcp_port: parsed.tcp_port.unwrap_or(5223),
        wss_path: parsed.wss_path.unwrap_or_else(|| "/private/ws".to_string()),
        quic_alpn: "pushgo-quic".to_string(),
        tcp_alpn: "pushgo-tcp".to_string(),
        wss_subprotocol: parsed
            .wss_subprotocol
            .map(|value| value.trim().to_string())
            .filter(|value| !value.is_empty())
            .or_else(|| Some(DEFAULT_WSS_SUBPROTOCOL.to_string())),
        tls_server_name: None,
        bearer_token: parsed.bearer_token,
        cert_pin_sha256: parsed.cert_pin_sha256,
        quic_cert_pin_sha256: parsed.quic_cert_pin_sha256,
        tcp_cert_pin_sha256: parsed.tcp_cert_pin_sha256,
        wss_cert_pin_sha256: parsed.wss_cert_pin_sha256,
        policy,
        wire_profile: std::sync::Arc::new(PushgoWireProfile::new()),
    };

    let (tx, rx) = mpsc::unbounded_channel();
    let task_tx = tx.clone();
    let hello = Arc::new(Mutex::new(hello));
    let power_hint = Arc::new(Mutex::new(initial_power_hint));
    let pending_acks = Arc::new(Mutex::new(HashMap::new()));
    let next_ack_id = Arc::new(AtomicU64::new(1));
    let probe_request_epoch = Arc::new(AtomicU64::new(0));
    let consumed_probe_epoch = Arc::new(AtomicU64::new(0));
    let initial_pin_transport = parsed
        .initial_pin_transport
        .as_deref()
        .and_then(parse_transport_kind);
    let initial_pin_ttl_ms = parsed.initial_pin_ttl_ms.filter(|value| *value > 0);
    let mut disabled_transports = Vec::new();
    if parsed.quic_enabled == Some(false) {
        disabled_transports.push(TransportKind::Quic);
    }
    if parsed.tcp_enabled == Some(false) {
        disabled_transports.push(TransportKind::Tcp);
    }
    if parsed.wss_enabled == Some(false) {
        disabled_transports.push(TransportKind::Wss);
    }
    let scheduler_policy = Arc::new(Mutex::new(NativeSchedulerPolicy {
        force_reconnect_nonce: 0,
        pinned_transport: initial_pin_transport.map(|transport| NativePinnedTransport {
            transport,
            expires_at_unix_ms: initial_pin_ttl_ms.map(|ttl| epoch_millis_now().saturating_add(ttl as i64)),
        }),
        disabled_transports,
    }));
    let app = EventApp {
        hello: Arc::clone(&hello),
        power_hint: Arc::clone(&power_hint),
        pending_acks: Arc::clone(&pending_acks),
        next_ack_id,
        probe_request_epoch: Arc::clone(&probe_request_epoch),
        consumed_probe_epoch,
        scheduler_policy: Arc::clone(&scheduler_policy),
        ack_wait_timeout_ms,
        session_started_at: Instant::now(),
        tx,
    };
    let (shutdown_tx, shutdown_rx) = watch::channel(false);
    let runtime = match runtime() {
        Ok(value) => value,
        Err(_) => return 0,
    };
    let task = runtime.spawn(async move {
        let profile_event = serde_json::json!({
            "type": "session_profile",
            "connect_budget_ms": config.policy.connect_budget_ms,
            "tcp_delay_ms": tcp_delay_ms,
            "wss_delay_ms": wss_delay_ms,
            "quic_port": config.quic_port,
            "tcp_port": config.tcp_port,
            "wss_port": config.wss_port,
        });
        let _ = task_tx.send(profile_event.to_string());
        let result = client_run_with_shutdown(config, app, shutdown_rx).await;
        let terminal_event = match result {
            Ok(()) => serde_json::json!({
                "type": "session_ended",
                "reason": "client_run_completed",
                "error": serde_json::Value::Null,
            }),
            Err(error) => serde_json::json!({
                "type": "session_ended",
                "reason": "client_run_failed",
                "error": error.to_string(),
            }),
        };
        let _ = task_tx.send(terminal_event.to_string());
    });

    let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
    let mut sessions = match SESSIONS.lock() {
        Ok(guard) => guard,
        Err(_) => return 0,
    };
    sessions.insert(
        handle,
        Arc::new(Session {
            task: Mutex::new(task),
            shutdown_tx,
            events: Mutex::new(rx),
            hello,
            power_hint,
            pending_acks,
            probe_request_epoch,
            scheduler_policy,
        }),
    );
    handle as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_ethan_pushgo_notifications_WarpLinkNativeBridge_nativeSessionPollEvent(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    timeout_ms: jint,
) -> jstring {
    let session = match SESSIONS.lock() {
        Ok(guard) => guard.get(&(handle as u64)).cloned(),
        Err(_) => return std::ptr::null_mut(),
    };
    let Some(session) = session else {
        return std::ptr::null_mut();
    };

    let timeout_ms = timeout_ms.max(0) as u64;
    let deadline = (timeout_ms > 0).then(|| Instant::now() + Duration::from_millis(timeout_ms));
    let result = loop {
        let recv_result = {
            let mut events = match session.events.lock() {
                Ok(guard) => guard,
                Err(_) => return std::ptr::null_mut(),
            };
            events.try_recv()
        };
        match recv_result {
            Ok(value) => break Some(value),
            Err(TryRecvError::Disconnected) => break None,
            Err(TryRecvError::Empty) => {}
        }
        if let Some(limit) = deadline {
            if Instant::now() >= limit {
                break None;
            }
        }
        std::thread::sleep(Duration::from_millis(1));
    };

    let Some(text) = result else {
        return std::ptr::null_mut();
    };
    rust_to_jstring_raw(&mut env, text)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_ethan_pushgo_notifications_WarpLinkNativeBridge_nativeSessionStop(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) {
    let session = match SESSIONS.lock() {
        Ok(mut guard) => guard.remove(&(handle as u64)),
        Err(_) => return,
    };
    let Some(session) = session else {
        return;
    };
    let _ = session.shutdown_tx.send(true);
    if let Ok(mut pending) = session.pending_acks.lock() {
        pending.clear();
    }
    if let Ok(task) = session.task.lock() {
        task.abort();
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_ethan_pushgo_notifications_WarpLinkNativeBridge_nativeSessionReplaceAuthToken(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    auth_token: JString,
) -> jint {
    let token = match jstring_to_rust(&mut env, &auth_token) {
        Some(value) => {
            let trimmed = value.trim().to_string();
            (!trimmed.is_empty()).then_some(trimmed)
        }
        None => return 0,
    };

    let session = match SESSIONS.lock() {
        Ok(guard) => guard.get(&(handle as u64)).cloned(),
        Err(_) => return 0,
    };
    let Some(session) = session else {
        return 0;
    };
    let mut hello = match session.hello.lock() {
        Ok(guard) => guard,
        Err(_) => return 0,
    };
    hello.auth_token = token;
    1
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_ethan_pushgo_notifications_WarpLinkNativeBridge_nativeSessionResolveMessage(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    ack_id: jlong,
    status: jint,
) -> jint {
    if handle == 0 || ack_id <= 0 {
        return 0;
    }
    let decision = match status {
        1 => AppDecision::AckOk,
        2 => AppDecision::AckInvalidPayload,
        _ => AppDecision::Ignore,
    };
    let session = match SESSIONS.lock() {
        Ok(guard) => guard.get(&(handle as u64)).cloned(),
        Err(_) => return 0,
    };
    let Some(session) = session else {
        return 0;
    };
    let sender = match session.pending_acks.lock() {
        Ok(mut guard) => guard.remove(&(ack_id as u64)),
        Err(_) => return 0,
    };
    let Some(sender) = sender else {
        return 0;
    };
    if sender.send(decision).is_ok() { 1 } else { 0 }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_ethan_pushgo_notifications_WarpLinkNativeBridge_nativeSessionRequestProbe(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return 0;
    }
    let session = match SESSIONS.lock() {
        Ok(guard) => guard.get(&(handle as u64)).cloned(),
        Err(_) => return 0,
    };
    let Some(session) = session else {
        return 0;
    };
    session.probe_request_epoch.fetch_add(1, Ordering::Relaxed);
    1
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_ethan_pushgo_notifications_WarpLinkNativeBridge_nativeSessionForceReconnect(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return 0;
    }
    let session = match SESSIONS.lock() {
        Ok(guard) => guard.get(&(handle as u64)).cloned(),
        Err(_) => return 0,
    };
    let Some(session) = session else {
        return 0;
    };
    let mut policy = match session.scheduler_policy.lock() {
        Ok(guard) => guard,
        Err(_) => return 0,
    };
    policy.force_reconnect_nonce = policy.force_reconnect_nonce.saturating_add(1);
    1
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_ethan_pushgo_notifications_WarpLinkNativeBridge_nativeSessionPinTransport(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    transport: JString,
    ttl_ms: jlong,
) -> jint {
    if handle == 0 {
        return 0;
    }
    let transport_raw = match jstring_to_rust(&mut env, &transport) {
        Some(value) => value,
        None => return 0,
    };
    let Some(transport) = parse_transport_kind(transport_raw.as_str()) else {
        return 0;
    };
    let expires_at_unix_ms = if ttl_ms > 0 {
        Some(epoch_millis_now().saturating_add(ttl_ms))
    } else {
        None
    };
    let session = match SESSIONS.lock() {
        Ok(guard) => guard.get(&(handle as u64)).cloned(),
        Err(_) => return 0,
    };
    let Some(session) = session else {
        return 0;
    };
    let mut policy = match session.scheduler_policy.lock() {
        Ok(guard) => guard,
        Err(_) => return 0,
    };
    policy.pinned_transport = Some(NativePinnedTransport {
        transport,
        expires_at_unix_ms,
    });
    policy.force_reconnect_nonce = policy.force_reconnect_nonce.saturating_add(1);
    1
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_ethan_pushgo_notifications_WarpLinkNativeBridge_nativeSessionClearPin(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return 0;
    }
    let session = match SESSIONS.lock() {
        Ok(guard) => guard.get(&(handle as u64)).cloned(),
        Err(_) => return 0,
    };
    let Some(session) = session else {
        return 0;
    };
    let mut policy = match session.scheduler_policy.lock() {
        Ok(guard) => guard,
        Err(_) => return 0,
    };
    policy.pinned_transport = None;
    policy.force_reconnect_nonce = policy.force_reconnect_nonce.saturating_add(1);
    1
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_ethan_pushgo_notifications_WarpLinkNativeBridge_nativeSessionSetPowerHint(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    app_state: JString,
    power_tier: JString,
) -> jint {
    let app_state_raw = match jstring_to_rust(&mut env, &app_state) {
        Some(value) => value,
        None => return 0,
    };
    let app_state_normalized = app_state_raw.trim().to_ascii_lowercase();
    let hint = if app_state_normalized.is_empty() {
        None
    } else {
        let Some(app_state_hint) = parse_app_state(Some(app_state_normalized.as_str())) else {
            return 0;
        };
        let tier_raw = match jstring_to_rust(&mut env, &power_tier) {
            Some(value) => value.trim().to_ascii_lowercase(),
            None => return 0,
        };
        let tier = if tier_raw.is_empty() {
            None
        } else {
            let Some(value) = parse_power_tier(Some(tier_raw.as_str())) else {
                return 0;
            };
            Some(value)
        };
        Some(ClientPowerHint {
            app_state: app_state_hint,
            preferred_tier: tier,
        })
    };

    let session = match SESSIONS.lock() {
        Ok(guard) => guard.get(&(handle as u64)).cloned(),
        Err(_) => return 0,
    };
    let Some(session) = session else {
        return 0;
    };
    let mut slot = match session.power_hint.lock() {
        Ok(guard) => guard,
        Err(_) => return 0,
    };
    *slot = hint;
    1
}

fn jstring_to_rust(env: &mut EnvUnowned<'_>, value: &JString<'_>) -> Option<String> {
    match env
        .with_env(|jni_env| value.try_to_string(jni_env))
        .into_outcome()
    {
        Outcome::Ok(value) => Some(value),
        Outcome::Err(_) | Outcome::Panic(_) => None,
    }
}

fn rust_to_jstring_raw(env: &mut EnvUnowned<'_>, value: String) -> jstring {
    match env
        .with_env(|jni_env| jni_env.new_string(value))
        .into_outcome()
    {
        Outcome::Ok(value) => value.into_raw(),
        Outcome::Err(_) | Outcome::Panic(_) => std::ptr::null_mut(),
    }
}

fn event_to_json(event: &ClientEvent, session_started_at: Instant) -> String {
    let elapsed_ms = elapsed_ms(session_started_at);
    match event {
        ClientEvent::Connected { transport } => serde_json::json!({
            "type": "connected",
            "transport": transport.to_string(),
            "elapsed_ms": elapsed_ms,
        })
        .to_string(),
        ClientEvent::Welcome { welcome } => serde_json::json!({
            "type": "welcome",
            "resume_token": welcome.resume_token,
            "heartbeat_secs": welcome.heartbeat_secs,
            "ping_interval_secs": welcome.ping_interval_secs,
            "idle_timeout_secs": welcome.idle_timeout_secs,
            "max_backoff_secs": welcome.max_backoff_secs,
            "auth_expires_at_unix_secs": welcome.auth_expires_at_unix_secs,
            "auth_refresh_before_secs": welcome.auth_refresh_before_secs,
            "wire_version": welcome.negotiated_wire_version,
            "payload_version": welcome.negotiated_payload_version,
            "elapsed_ms": elapsed_ms,
        })
        .to_string(),
        ClientEvent::Disconnected { transport, reason } => serde_json::json!({
            "type": "disconnected",
            "transport": transport.to_string(),
            "reason": reason,
            "elapsed_ms": elapsed_ms,
        })
        .to_string(),
        ClientEvent::Reconnecting {
            attempt,
            backoff_ms,
        } => serde_json::json!({
            "type": "reconnecting",
            "attempt": attempt,
            "backoff_ms": backoff_ms,
            "elapsed_ms": elapsed_ms,
        })
        .to_string(),
        ClientEvent::Fatal { error } => serde_json::json!({
            "type": "fatal",
            "error": error,
            "elapsed_ms": elapsed_ms,
        })
        .to_string(),
        ClientEvent::ProbeRtt {
            transport,
            rtt_ms,
            source,
        } => serde_json::json!({
            "type": "probe_rtt",
            "transport": transport.to_string(),
            "rtt_ms": rtt_ms,
            "source": match source {
                ProbeRttSource::Manual => "manual",
                ProbeRttSource::IdleKeepalive => "idle_keepalive",
            },
            "elapsed_ms": elapsed_ms,
        })
        .to_string(),
        ClientEvent::SchedulerStateChanged { state, reason_code } => serde_json::json!({
            "type": "scheduler_state_changed",
            "state": format!("{state:?}").to_ascii_lowercase(),
            "reason_code": reason_code,
            "elapsed_ms": elapsed_ms,
        })
        .to_string(),
        ClientEvent::CandidateStarted {
            from,
            to,
            decision_id,
        } => serde_json::json!({
            "type": "candidate_started",
            "from": from.to_string(),
            "to": to.to_string(),
            "decision_id": decision_id,
            "elapsed_ms": elapsed_ms,
        })
        .to_string(),
        ClientEvent::CandidateReady {
            from,
            to,
            decision_id,
        } => serde_json::json!({
            "type": "candidate_ready",
            "from": from.to_string(),
            "to": to.to_string(),
            "decision_id": decision_id,
            "elapsed_ms": elapsed_ms,
        })
        .to_string(),
        ClientEvent::CutoverCommitted {
            from,
            to,
            decision_id,
        } => serde_json::json!({
            "type": "cutover_committed",
            "from": from.to_string(),
            "to": to.to_string(),
            "decision_id": decision_id,
            "elapsed_ms": elapsed_ms,
        })
        .to_string(),
        ClientEvent::CutoverRollback {
            restored,
            failed,
            decision_id,
            reason,
        } => serde_json::json!({
            "type": "cutover_rollback",
            "restored": restored.to_string(),
            "failed": failed.to_string(),
            "decision_id": decision_id,
            "reason": reason,
            "elapsed_ms": elapsed_ms,
        })
        .to_string(),
        ClientEvent::DeadConnectionDetected {
            transport,
            reason_code,
        } => serde_json::json!({
            "type": "dead_connection_detected",
            "transport": transport.to_string(),
            "reason_code": reason_code,
            "elapsed_ms": elapsed_ms,
        })
        .to_string(),
        ClientEvent::RecoveryTierEntered { tier, reason_code } => serde_json::json!({
            "type": "recovery_tier_entered",
            "tier": tier,
            "reason_code": reason_code,
            "elapsed_ms": elapsed_ms,
        })
        .to_string(),
        ClientEvent::DecisionTrace { trace } => serde_json::json!({
            "type": "decision_trace",
            "decision_id": trace.decision_id,
            "winner_layer": format!("{:?}", trace.winner_layer).to_ascii_lowercase(),
            "reason_code": trace.reason_code,
            "selected_transport": trace.selected_transport.map(|value| value.to_string()),
            "inputs_digest": trace.inputs_digest,
            "suppressed_candidates": trace
                .suppressed_candidates
                .iter()
                .map(ToString::to_string)
                .collect::<Vec<_>>(),
            "elapsed_ms": elapsed_ms,
        })
        .to_string(),
        ClientEvent::Message { .. } => serde_json::json!({
            "type": "internal_error",
            "error": "message event path should be handled in ClientApp",
        })
        .to_string(),
    }
}

fn elapsed_ms(session_started_at: Instant) -> u64 {
    session_started_at
        .elapsed()
        .as_millis()
        .try_into()
        .unwrap_or(u64::MAX)
}

fn epoch_millis_now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|value| value.as_millis() as i64)
        .unwrap_or(0)
}

fn parse_transport_kind(raw: &str) -> Option<TransportKind> {
    match raw.trim().to_ascii_lowercase().as_str() {
        "quic" => Some(TransportKind::Quic),
        "tcp" => Some(TransportKind::Tcp),
        "wss" | "ws" => Some(TransportKind::Wss),
        _ => None,
    }
}

fn decode_payload_map(bytes: &[u8]) -> (serde_json::Value, bool) {
    let decoded: Result<PrivatePayloadEnvelope, _> = postcard::from_bytes(bytes);
    match decoded {
        Ok(envelope) => {
            if envelope.payload_version != 1 {
                return (
                    serde_json::json!({
                        "_payload_version": envelope.payload_version,
                        "_decode": "unsupported_version",
                    }),
                    false,
                );
            }
            match serde_json::to_value(envelope.data) {
                Ok(value) => (value, true),
                Err(_) => (serde_json::json!({}), false),
            }
        }
        Err(_) => (serde_json::json!({}), false),
    }
}

fn parse_power_hint(app_state: Option<&str>, perf_tier: Option<&str>) -> Option<ClientPowerHint> {
    let app_state = parse_app_state(app_state)?;
    Some(ClientPowerHint {
        app_state,
        preferred_tier: parse_power_tier(perf_tier),
    })
}

fn private_transport_connect_delays(app_state: Option<&str>) -> (u64, u64) {
    match parse_app_state(app_state) {
        Some(ClientAppStateHint::Foreground) => (
            PRIVATE_TCP_DELAY_FOREGROUND_MS,
            PRIVATE_WSS_DELAY_FOREGROUND_MS,
        ),
        _ => (
            PRIVATE_TCP_DELAY_BACKGROUND_MS,
            PRIVATE_WSS_DELAY_BACKGROUND_MS,
        ),
    }
}

fn parse_app_state(value: Option<&str>) -> Option<ClientAppStateHint> {
    let normalized = value?.trim().to_ascii_lowercase();
    match normalized.as_str() {
        "foreground" => Some(ClientAppStateHint::Foreground),
        "background" => Some(ClientAppStateHint::Background),
        _ => None,
    }
}

fn parse_power_tier(value: Option<&str>) -> Option<ClientPowerTier> {
    let normalized = value?.trim().to_ascii_lowercase();
    match normalized.as_str() {
        "high" => Some(ClientPowerTier::High),
        "balanced" => Some(ClientPowerTier::Balanced),
        "low" => Some(ClientPowerTier::Low),
        _ => None,
    }
}

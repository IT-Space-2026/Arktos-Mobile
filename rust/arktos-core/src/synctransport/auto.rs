//! SYNC10 — the "auto" [`SyncTransport`]: LAN fast path with
//! transparent Tor fallback.
//!
//! One transport surface over two legs: a QUIC/mDNS `lan` leg for
//! co-located paired devices and the embedded-Arti `tor` leg for
//! everything else.  Callers keep dialing the same endpoints —
//! `connect("<peer>.onion:<port>")` resolves through the LAN
//! discovery table first (a co-located peer advertises its onion in
//! the mDNS TXT `on=` record) and falls back to a Tor circuit when
//! the peer is not on the local network.  `lan://` endpoints always
//! take the LAN leg; `mem:` endpoints reach whatever the tor leg is
//! (in tests the leg slot can hold the loopback).
//!
//! Auth is identical on both wires: device-cert mTLS inside QUIC on
//! the LAN, restricted-discovery client auth on Tor — the member
//! grant set in `authorized_clients.json` is shared, so a paired
//! device is admitted by whichever wire it arrives on and an
//! unpaired one is refused by both, pre-app-byte.
//!
//! The backend requires both features compiled in (an "auto" that
//! can only ever pick one wire is a lie about its fallback);
//! RUNTIME leg failures degrade honestly — a box with no multicast
//! still syncs over Tor, a box where Tor can't bootstrap still gets
//! the LAN fast path.
//!
//! Link ids are namespaced: callers see only the auto id space; the
//! poll drain rewrites inner-leg ids on the way out.

use std::collections::{HashMap, VecDeque};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use serde_json::{json, Value};

use crate::error::{Fail, RcResult, RcStatus};
use crate::synctransport::lan::LanTransport;
use crate::synctransport::{
    authorized_set, bad_arg, drain_events, not_found, push_event, save_authorized, SyncTransport,
    TransportEvent,
};

/// Which leg a link id belongs to.
const LEG_TOR: u8 = 0;
const LEG_LAN: u8 = 1;

fn leg_name(leg: u8) -> &'static str {
    if leg == LEG_LAN {
        "lan"
    } else {
        "tor"
    }
}

fn no_link(link: u64) -> Fail {
    not_found::<()>(format!("no link {link}")).unwrap_err()
}

pub struct AutoTransport {
    dir: PathBuf,
    lan: Option<Arc<LanTransport>>,
    /// Late-bound: the tor leg bootstraps on a background
    /// thread so a cold Arti boot never holds start() hostage.
    tor: Mutex<Option<Arc<dyn SyncTransport>>>,
    /// own-event queue (leg startup failures surface here).
    events: Mutex<VecDeque<TransportEvent>>,
    /// outer link id -> (leg, inner id)
    links: Mutex<HashMap<u64, (u8, u64)>>,
    /// inner -> outer, per leg.
    ids: [Mutex<HashMap<u64, u64>>; 2],
    next_link: AtomicU64,
}

impl AutoTransport {
    /// Assemble the composite from already-started legs — the real
    /// constructor delegates here after attempting each leg, and
    /// tests inject a loopback as the tor slot.
    pub fn from_legs(
        dir: &Path,
        lan: Option<Arc<LanTransport>>,
        tor: Option<Arc<dyn SyncTransport>>,
        boot_errors: Vec<String>,
    ) -> Arc<Self> {
        let t = Arc::new(AutoTransport {
            dir: dir.to_path_buf(),
            lan,
            tor: Mutex::new(tor),
            events: Mutex::new(VecDeque::new()),
            links: Mutex::new(HashMap::new()),
            ids: [Mutex::new(HashMap::new()), Mutex::new(HashMap::new())],
            next_link: AtomicU64::new(1),
        });
        for reason in boot_errors {
            push_event(&t.events, TransportEvent::Failed { reason });
        }
        // Swallow each leg's startup Ready — the composite reports
        // ONE Ready for the whole transport.
        if let Some(l) = t.tor.lock().unwrap_or_else(|e| e.into_inner()).as_ref() {
            let _ = l.poll();
        }
        if let Some(l) = &t.lan {
            let _ = l.poll();
        }
        push_event(&t.events, TransportEvent::Ready);
        t
    }

    /// Start both legs under `dir` (`<data root>/sync`).  Each leg's
    /// failure degrades honestly rather than sinking the composite;
    /// only a total loss is an error.
    pub fn start(dir: &Path) -> RcResult<Arc<Self>> {
        let mut errors = Vec::new();
        let lan = match LanTransport::start(dir) {
            Ok(l) => Some(l),
            Err(e) => {
                errors.push(format!("lan leg: {}", e.msg));
                None
            }
        };
        if lan.is_none() {
            // No LAN: tor is the only hope — wait for it synchronously.
            let tor: Option<Arc<dyn SyncTransport>> = match super::tor::TorTransport::start(dir) {
                Ok(t) => Some(t),
                Err(e) => {
                    errors.push(format!("tor leg: {}", e.msg));
                    return Err(Fail {
                        status: RcStatus::Unavailable,
                        msg: format!("auto transport: no leg came up — {}", errors.join("; ")),
                    });
                }
            };
            return Ok(Self::from_legs(dir, lan, tor, errors));
        }
        let t = Self::from_legs(dir, lan, None, errors);
        // Tor leg boots in the background — a cold Arti bootstrap
        // (30-60s+) used to block pair/join taps on a working LAN.
        {
            let t2 = Arc::clone(&t);
            let dir = t.dir.clone();
            std::thread::Builder::new()
                .name("arktos-tor-leg".into())
                .spawn(move || match super::tor::TorTransport::start(&dir) {
                    Ok(leg) => {
                        *t2.tor.lock().unwrap_or_else(|e| e.into_inner()) =
                            Some(leg as Arc<dyn SyncTransport>);
                    }
                    Err(e) => push_event(
                        &t2.events,
                        TransportEvent::Failed {
                            reason: format!("tor leg: {}", e.msg),
                        },
                    ),
                })
                .ok();
        }
        Ok(t)
    }

    fn leg(&self, leg: u8) -> Option<Arc<dyn SyncTransport>> {
        match leg {
            LEG_LAN => self.lan.clone().map(|l| l as Arc<dyn SyncTransport>),
            _ => self.tor.lock().unwrap_or_else(|e| e.into_inner()).clone(),
        }
    }

    /// Route a connect: explicit `lan://` to the lan leg, `mem:` to
    /// the tor slot; anything else is a tor endpoint — resolved
    /// through LAN discovery first when the peer announced its
    /// onion there.
    fn route(&self, endpoint: &str) -> (u8, String) {
        if endpoint.starts_with("lan://") {
            return (LEG_LAN, endpoint.to_string());
        }
        if endpoint.starts_with("mem:") {
            return (LEG_TOR, endpoint.to_string());
        }
        if let Some(lan) = &self.lan {
            if let Some(p) = lan.discovered_by_onion(endpoint) {
                return (LEG_LAN, format!("lan://{}", p.device_id));
            }
        }
        (LEG_TOR, endpoint.to_string())
    }

    /// Allocate the caller-facing link id for a (leg, inner) pair.
    fn outer_id(&self, leg: u8, inner: u64) -> u64 {
        let mut map = self.ids[leg as usize]
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        if let Some(id) = map.get(&inner) {
            return *id;
        }
        let id = self.next_link.fetch_add(1, Ordering::SeqCst);
        map.insert(inner, id);
        self.links
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .insert(id, (leg, inner));
        id
    }

    /// Drain one leg's queue, rewriting link ids into our space and
    /// tagging failures with the leg name.
    fn drain_leg(&self, leg: u8, out: &mut Vec<TransportEvent>) {
        let Some(l) = self.leg(leg) else { return };
        for ev in l.poll() {
            out.push(match ev {
                TransportEvent::Incoming { link, from } => TransportEvent::Incoming {
                    link: self.outer_id(leg, link),
                    from,
                },
                TransportEvent::Frame { link, payload } => TransportEvent::Frame {
                    link: self.outer_id(leg, link),
                    payload,
                },
                TransportEvent::LinkClosed { link, reason } => {
                    let outer = {
                        let mut map = self.ids[leg as usize]
                            .lock()
                            .unwrap_or_else(|e| e.into_inner());
                        map.remove(&link)
                    };
                    match outer {
                        Some(id) => {
                            self.links
                                .lock()
                                .unwrap_or_else(|e| e.into_inner())
                                .remove(&id);
                            TransportEvent::LinkClosed { link: id, reason }
                        }
                        None => continue,
                    }
                }
                TransportEvent::Failed { reason } => TransportEvent::Failed {
                    reason: format!("{} leg: {reason}", leg_name(leg)),
                },
                other => other, // Ready / Bootstrap pass through
            });
        }
    }
}

impl SyncTransport for AutoTransport {
    fn backend(&self) -> &'static str {
        "auto"
    }

    /// Per-leg view: which legs are live, whether mDNS is listening,
    /// and the discovered peer table — the data that answers "why
    /// did my dial take the tor leg".
    fn debug_json(&self) -> Value {
        let tor_up = self.tor.lock().unwrap_or_else(|e| e.into_inner()).is_some();
        json!({
            "backend": "auto",
            "lan": self.lan.is_some(),
            "mdns": self.lan.as_ref().map(|l| l.discovery_active()).unwrap_or(false),
            "tor": tor_up,
            "peers": self.discovered_peers_json(),
        })
    }

    fn endpoint(&self) -> RcResult<String> {
        // The stable name peers dial — lan is discovered, not dialed.
        if let Some(t) = self.tor.lock().unwrap_or_else(|e| e.into_inner()).as_ref() {
            if let Ok(e) = t.endpoint() {
                return Ok(e);
            }
        }
        // Tor leg still bootstrapping — the onion name is keystore-
        // derived, so it exists before the client is live.
        if let Ok(e) = super::tor::endpoint_for(&self.dir) {
            return Ok(e);
        }
        if let Some(l) = &self.lan {
            return l.endpoint();
        }
        bad_arg("auto transport has no live leg")
    }

    fn authorize(&self, nickname: &str, discovery_key: &str) -> RcResult<()> {
        let mut first_err = None;
        let mut touched = false;
        for leg in [self.leg(LEG_LAN), self.leg(LEG_TOR)].into_iter().flatten() {
            touched = true;
            if let Err(e) = leg.authorize(nickname, discovery_key) {
                first_err.get_or_insert(e);
            }
        }
        match first_err {
            Some(e) => Err(e),
            None if !touched => {
                let mut set = authorized_set(&self.dir);
                set.insert(nickname.to_string(), discovery_key.to_string());
                save_authorized(&self.dir, &set)
            }
            None => Ok(()),
        }
    }

    fn deauthorize(&self, nickname: &str) -> RcResult<bool> {
        let mut removed = false;
        let mut touched = false;
        for leg in [self.leg(LEG_LAN), self.leg(LEG_TOR)].into_iter().flatten() {
            touched = true;
            removed |= leg.deauthorize(nickname)?;
        }
        if !removed && !touched {
            let mut set = authorized_set(&self.dir);
            if set.remove(nickname).is_some() {
                save_authorized(&self.dir, &set)?;
                removed = true;
            }
        }
        Ok(removed)
    }

    fn authorized(&self) -> Vec<String> {
        authorized_set(&self.dir).keys().cloned().collect()
    }

    fn discovery_key(&self, peer_onion: &str) -> RcResult<String> {
        super::tor::discovery_key(&self.dir, peer_onion)
    }

    fn import_discovery_key(&self, peer_onion: &str, secret: &[u8; 32]) -> RcResult<()> {
        super::tor::import_discovery_key(&self.dir, peer_onion, secret)
    }

    fn rotate_discovery_key(&self, peer_onion: &str) -> RcResult<String> {
        super::tor::rotate_discovery_key(&self.dir, peer_onion)
    }

    fn connect(&self, endpoint: &str, as_nickname: &str) -> RcResult<u64> {
        // An onion endpoint right after bring-up: mDNS needs a few
        // seconds to hear the peer's announce. Give LAN discovery a
        // short window before committing to a Tor circuit — on a
        // shared network that window is the difference between a
        // sub-second dial and a ~2min tor deadline.
        if self.lan.is_some()
            && !endpoint.starts_with("lan://")
            && !endpoint.starts_with("mem:")
        {
            for _ in 0..30 {
                if self.route(endpoint).0 == LEG_LAN {
                    break;
                }
                std::thread::sleep(std::time::Duration::from_millis(500));
            }
        }
        // A tor-routed dial right after bring-up: the leg fills on a
        // background thread once Arti finishes bootstrapping — wait
        // for it instead of failing the connect cold.
        if self.route(endpoint).0 == LEG_TOR {
            for _ in 0..180 {
                if self.leg(LEG_TOR).is_some() {
                    break;
                }
                std::thread::sleep(std::time::Duration::from_millis(500));
            }
        }
        let (leg, target) = self.route(endpoint);
        let l = self.leg(leg).ok_or_else(|| Fail {
            status: RcStatus::Unavailable,
            msg: format!(
                "auto: {} leg is not running (endpoint {endpoint})",
                leg_name(leg)
            ),
        })?;
        let inner = l.connect(&target, as_nickname)?;
        Ok(self.outer_id(leg, inner))
    }

    fn send(&self, link: u64, frame: &[u8]) -> RcResult<()> {
        let (leg, inner) = *self
            .links
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(&link)
            .ok_or_else(|| no_link(link))?;
        self.leg(leg)
            .ok_or_else(|| no_link(link))?
            .send(inner, frame)
    }

    fn close_link(&self, link: u64) -> RcResult<bool> {
        let taken = self
            .links
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&link);
        let Some((leg, inner)) = taken else {
            return Ok(false);
        };
        self.ids[leg as usize]
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&inner);
        Ok(self
            .leg(leg)
            .map(|l| l.close_link(inner).unwrap_or(false))
            .unwrap_or(false))
    }

    fn poll(&self) -> Vec<TransportEvent> {
        let mut out = drain_events(&self.events);
        self.drain_leg(LEG_TOR, &mut out);
        self.drain_leg(LEG_LAN, &mut out);
        out
    }

    fn link_count(&self) -> usize {
        self.links.lock().unwrap_or_else(|e| e.into_inner()).len()
    }

    fn discovered_peers_json(&self) -> Vec<Value> {
        if let Some(l) = &self.lan {
            return l.discovered_peers_json();
        }
        Vec::new()
    }

    fn stop(&self) {
        if let Some(l) = &self.lan {
            l.stop();
        }
        if let Some(t) = self.tor.lock().unwrap_or_else(|e| e.into_inner()).as_ref() {
            t.stop();
        }
        self.links
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clear();
        for m in &self.ids {
            m.lock().unwrap_or_else(|e| e.into_inner()).clear();
        }
    }
}

impl Drop for AutoTransport {
    fn drop(&mut self) {
        self.stop();
    }
}

// ---- tests -------------------------------------------------------------------
//
// The composite is exercised with a REAL lan leg (the routing
// decision rides its discovery table) and a loopback in the tor
// slot — no real Tor needed to prove the fallback branch.

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sync::SyncEngine;
    use crate::synctransport::lan::LanConfig;
    use crate::synctransport::LoopbackTransport;
    use std::net::Ipv4Addr;
    use std::path::PathBuf;
    use std::thread::sleep;
    use std::time::Duration;

    fn tmpdir(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "rustcore-auto-{}-{}-{}",
            std::process::id(),
            tag,
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn engine(root: &Path) -> SyncEngine {
        SyncEngine::open(root.to_str().unwrap()).unwrap()
    }

    fn member_nick(device_id: &str) -> String {
        format!("member-{}", &device_id[..16.min(device_id.len())])
    }

    fn cfg(group_n: u8, mport: u16, onion: &str) -> LanConfig {
        LanConfig {
            quic_port: 0,
            mdns_group: Ipv4Addr::new(239, 255, 77, group_n),
            mdns_port: mport,
            mdns_iface: Ipv4Addr::UNSPECIFIED,
            extra_addrs: Vec::new(),
            onion: Some(onion.to_string()),
            query_every: Duration::from_millis(150),
        }
    }

    fn pair_into(t: &LanTransport, root: &Path, peer: &SyncEngine, peer_id: &str) {
        t.authorize(&member_nick(peer_id), "descriptor:x25519:test")
            .unwrap();
        engine(root)
            .member_add(&peer.public_key(), "p.onion", "")
            .unwrap();
    }

    fn multicast_ok() -> bool {
        crate::synctransport::lan::mdns::bind_socket(
            Ipv4Addr::new(239, 255, 77, 241),
            15401,
            Ipv4Addr::UNSPECIFIED,
        )
        .is_ok()
    }

    fn wait_incoming(t: &Arc<dyn SyncTransport>, tries: u32) -> u64 {
        for _ in 0..tries {
            for ev in t.poll() {
                if let TransportEvent::Incoming { link, .. } = ev {
                    return link;
                }
            }
            sleep(Duration::from_millis(15));
        }
        panic!("no Incoming link");
    }

    fn wait_frame(t: &Arc<dyn SyncTransport>, link: u64, tries: u32) -> Vec<u8> {
        for _ in 0..tries {
            for ev in t.poll() {
                if let TransportEvent::Frame { link: l, payload } = ev {
                    if l == link {
                        return payload;
                    }
                }
            }
            sleep(Duration::from_millis(10));
        }
        panic!("no frame on link {link}");
    }

    /// The headline behavior: a peer's onion endpoint resolves
    /// through mDNS discovery and the link lands on the QUIC leg —
    /// the caller dialed a tor endpoint and got the LAN fast path
    /// transparently.  An undiscovered endpoint falls through to the
    /// tor leg.
    #[test]
    fn discovered_onion_routes_lan_else_tor() {
        if !multicast_ok() {
            eprintln!("SKIP discovered_onion_routes_lan_else_tor: no multicast iface");
            return;
        }
        let (ra, rb) = (tmpdir("oa"), tmpdir("ob"));
        let (ea, eb) = (engine(&ra), engine(&rb));
        let (ida, idb) = (ea.device_id(), eb.device_id());
        let lan_a = LanTransport::start_with(
            &ra.join("sync"),
            cfg(202, 15502, "auto-a.onion:14430"),
        )
        .unwrap();
        let lan_b = LanTransport::start_with(
            &rb.join("sync"),
            cfg(202, 15502, "auto-b.onion:14430"),
        )
        .unwrap();
        pair_into(&lan_a, &ra, &eb, &idb);
        pair_into(&lan_b, &rb, &ea, &ida);

        let tor_stub = LoopbackTransport::start("auto-loop");
        let auto = AutoTransport::from_legs(
            &ra.join("sync"),
            Some(lan_a.clone()),
            Some(tor_stub.clone() as Arc<dyn SyncTransport>),
            vec![],
        );

        // B's discovery reaches A's table — the onion is what `auto`
        // keys on.
        let mut found = false;
        for _ in 0..240 {
            if lan_a.discovered_by_onion("auto-b.onion:14430").is_some() {
                found = true;
                break;
            }
            sleep(Duration::from_millis(50));
        }
        assert!(found, "A never discovered B's onion advertisement");

        // Dial B's TOR endpoint: routed onto the LAN leg — B sees a
        // lan:// Incoming and the frame round-trips over QUIC.
        let our = auto.connect("auto-b.onion:14430", "self").unwrap();
        let lan_b_dyn: Arc<dyn SyncTransport> = lan_b.clone();
        let theirs = wait_incoming(&lan_b_dyn, 200);
        auto.send(our, b"LANFAST").unwrap();
        assert_eq!(wait_frame(&lan_b_dyn, theirs, 200), b"LANFAST");

        // An endpoint no peer advertises falls through to the tor
        // leg (the loopback reports its own not-found — proof the
        // dial went there, not to lan's NotFound).
        let err = auto.connect("never.onion:14430", "self").unwrap_err();
        assert!(
            err.msg.contains("no loopback listener"),
            "fell to lan instead of tor leg: {}",
            err.msg
        );

        // A mem: endpoint explicitly lands on the tor leg too.
        let peer_loop = LoopbackTransport::start("mempeer");
        peer_loop.authorize("self", "").unwrap();
        let mem_link = auto.connect("mem:mempeer", "self").unwrap();
        assert_ne!(mem_link, our, "outer link ids must be namespaced");
        auto.send(mem_link, b"VIA-TOR").unwrap();
        let peer_loop_dyn: Arc<dyn SyncTransport> = peer_loop.clone();
        assert_eq!(wait_frame(&peer_loop_dyn, 1, 200), b"VIA-TOR");

        // Close maps back to the right inner link.
        assert!(auto.close_link(mem_link).unwrap());
        assert!(!auto.close_link(mem_link).unwrap());
        auto.stop();
        lan_a.stop();
        lan_b.stop();
        peer_loop.stop();
        tor_stub.stop();
    }

    /// Grants fan out to both legs AND the shared file — pairing
    /// writes once, both wires admit.
    #[test]
    fn grant_fans_out_to_both_legs_and_file() {
        let ra = tmpdir("ga");
        let lan_a = LanTransport::start_with(
            &ra.join("sync"),
            cfg(203, 15503, "auto-c.onion:14430"),
        )
        .unwrap();
        let tor_stub = LoopbackTransport::start("grant-loop");
        let auto = AutoTransport::from_legs(
            &ra.join("sync"),
            Some(lan_a.clone()),
            Some(tor_stub.clone() as Arc<dyn SyncTransport>),
            vec![],
        );
        auto.authorize("member-0123456789abcdef", "descriptor:x25519:K")
            .unwrap();
        assert!(lan_a.authorized().contains(&"member-0123456789abcdef".to_string()));
        assert!(tor_stub.authorized().contains(&"member-0123456789abcdef".to_string()));
        let file = crate::synctransport::authorized_set(&ra.join("sync"));
        assert!(file.contains_key("member-0123456789abcdef"));
        assert!(auto.deauthorize("member-0123456789abcdef").unwrap());
        assert!(lan_a.authorized().is_empty());
        auto.stop();
        tor_stub.stop();
        lan_a.stop();
    }
}

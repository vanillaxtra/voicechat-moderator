"""
VoicechatModerator — faster-whisper WebSocket transcription service.

Designed to run publicly on a VPS and serve multiple Minecraft servers
simultaneously.  No authentication is required; abuse protection is
provided by connection caps, per-connection rate limiting, and automatic
Discord abuse alerts.

Protocol
--------
Plugin → Server (JSON text frame):
  {
    "requestId":   "<uuid>",
    "playerUuid":  "<uuid>",
    "playerName":  "<name>",
    "instanceId":  "<server-name>",   # optional, used for logging only
    "language":    "en",              # or "" for auto-detect
    "audio":       "<base64 WAV>",
    "flagged":     false              # true = save WAV to recordings/
  }

Server → Plugin (JSON text frame):
  {
    "requestId":  "<uuid>",
    "playerUuid": "<uuid>",
    "text":       "<transcript>"
  }

Error frame:
  {"requestId": "<uuid>", "error": "<message>"}

Environment variables
---------------------
  WHISPER_HOST              bind address for WS server    (default: 0.0.0.0)
  WHISPER_PORT              WebSocket port                (default: 8765)
  WHISPER_MODEL             model size                    (default: base)
                            tiny | base | small | medium | large-v3
  WHISPER_DEVICE            cpu | cuda                    (default: cpu)
  WHISPER_WORKERS           transcription threads         (default: 2)
  WHISPER_MAX_QUEUE         max buffered jobs             (default: WORKERS * 4)
  WHISPER_MAX_CONNECTIONS   global concurrent WS cap      (default: 20)
  WHISPER_MAX_CONN_PER_IP   connections allowed per IP    (default: 3)
  WHISPER_RATE_LIMIT        requests per minute per conn  (default: 30)
  WHISPER_STATUS_PORT       plain HTTP status page port   (default: 8766)
  WHISPER_ABUSE_WEBHOOK     Discord webhook URL for abuse alerts (optional)
                            Leave unset to disable alerts.
  WHISPER_ABUSE_RATE_THRESH  rate-limit hits before alerting    (default: 10)
                            How many rate-limit violations in a session
                            before an alert fires for that IP.
  WHISPER_STATS_FILE        path to per-IP JSON stats file
                            (default: ip_stats.json)
"""

from __future__ import annotations

import asyncio
import base64
import json
import logging
import os
import tempfile
import time
import urllib.request
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime, timezone
from pathlib import Path

import websockets
from websockets.server import WebSocketServerProtocol
from faster_whisper import WhisperModel

# ── Logging ───────────────────────────────────────────────────────────────────

logging.basicConfig(
    level=logging.INFO,
    format="[%(asctime)s] [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("vcm-whisper")

# ── Config ────────────────────────────────────────────────────────────────────

HOST             = os.environ.get("WHISPER_HOST",             "0.0.0.0").strip() or "0.0.0.0"
PORT             = int(os.environ.get("WHISPER_PORT",             "8765"))
MODEL            = os.environ.get("WHISPER_MODEL",            "base").strip()    or "base"
DEVICE           = os.environ.get("WHISPER_DEVICE",           "cpu").strip()     or "cpu"
WORKERS          = max(1, int(os.environ.get("WHISPER_WORKERS",          "2")))
MAX_QUEUE        = int(os.environ.get("WHISPER_MAX_QUEUE",        str(WORKERS * 4)))
MAX_CONNECTIONS  = int(os.environ.get("WHISPER_MAX_CONNECTIONS",  "20"))
MAX_CONN_PER_IP  = int(os.environ.get("WHISPER_MAX_CONN_PER_IP",  "3"))
RATE_LIMIT       = int(os.environ.get("WHISPER_RATE_LIMIT",       "30"))   # req/min
STATUS_PORT      = int(os.environ.get("WHISPER_STATUS_PORT",      "8766"))
ABUSE_WEBHOOK    = os.environ.get("WHISPER_ABUSE_WEBHOOK",    "").strip()
ABUSE_RATE_THRESH = int(os.environ.get("WHISPER_ABUSE_RATE_THRESH", "10"))
STATS_FILE       = Path(os.environ.get("WHISPER_STATS_FILE", "ip_stats.json"))

RECORDINGS_DIR = Path("recordings")

# ── Per-IP stats (persisted to STATS_FILE) ────────────────────────────────────
#
# Structure: { "1.2.3.4": { "requests": int, "rate_limit_hits": int,
#   "conn_rejections": int, "first_seen": iso-str, "last_seen": iso-str,
#   "sessions": int, "instances": [str, ...] } }

_ip_stats: dict[str, dict] = {}

# ── Last-alert epoch per IP — avoids spamming the webhook for the same IP ─────
_last_alert: dict[str, float] = {}
ALERT_COOLDOWN_SEC = 600   # at most one alert per IP per 10 minutes

# ── Connection tracking (all access is on the event-loop thread) ──────────────

_active_connections: set[WebSocketServerProtocol] = set()
_connections_by_ip: dict[str, int] = defaultdict(int)

# ── Model load ────────────────────────────────────────────────────────────────

log.info(f"Loading faster-whisper model '{MODEL}' on device '{DEVICE}' ...")
log.info("(First run downloads model weights — this may take a minute.)")

_compute_type = "int8" if DEVICE == "cpu" else "float16"
_model = WhisperModel(MODEL, device=DEVICE, compute_type=_compute_type)

log.info("Model ready.")

# ── Thread pool ───────────────────────────────────────────────────────────────

_thread_pool = ThreadPoolExecutor(
    max_workers=WORKERS,
    thread_name_prefix="vcm-transcribe",
)

# ── Processing queue ──────────────────────────────────────────────────────────

_queue: asyncio.Queue  # initialised in main()


# ── Per-IP stats helpers ──────────────────────────────────────────────────────

def _ensure_ip(ip: str) -> dict:
    """Return (creating if needed) the stats dict for an IP."""
    if ip not in _ip_stats:
        _ip_stats[ip] = {
            "requests":        0,
            "rate_limit_hits": 0,
            "conn_rejections": 0,
            "sessions":        0,
            "first_seen":      _now_iso(),
            "last_seen":       _now_iso(),
            "instances":       [],
        }
    return _ip_stats[ip]


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _touch(ip: str) -> None:
    _ensure_ip(ip)["last_seen"] = _now_iso()


def _save_stats() -> None:
    """Atomically write stats to disk."""
    try:
        tmp = STATS_FILE.with_suffix(".tmp")
        tmp.write_text(json.dumps(_ip_stats, indent=2), encoding="utf-8")
        tmp.replace(STATS_FILE)
    except OSError as e:
        log.warning(f"[Stats] Failed to save {STATS_FILE}: {e}")


def _load_stats() -> None:
    """Load existing stats from disk on startup."""
    global _ip_stats
    if STATS_FILE.exists():
        try:
            _ip_stats = json.loads(STATS_FILE.read_text(encoding="utf-8"))
            log.info(f"[Stats] Loaded stats for {len(_ip_stats)} IP(s) from {STATS_FILE}")
        except (OSError, json.JSONDecodeError) as e:
            log.warning(f"[Stats] Could not load {STATS_FILE}: {e}")


async def _stats_saver_loop() -> None:
    """Flush stats to disk every 60 seconds."""
    while True:
        await asyncio.sleep(60)
        _save_stats()


# ── Abuse webhook ─────────────────────────────────────────────────────────────

def _send_abuse_alert(ip: str, reason: str, stats: dict) -> None:
    """POST a Discord embed to ABUSE_WEBHOOK in a thread (non-blocking)."""
    if not ABUSE_WEBHOOK:
        return
    now = time.monotonic()
    if now - _last_alert.get(ip, 0) < ALERT_COOLDOWN_SEC:
        return
    _last_alert[ip] = now

    instances = ", ".join(stats.get("instances", [])) or "unknown"
    embed = {
        "embeds": [{
            "title": "VCM Whisper — Abuse Alert",
            "color": 0xE74C3C,
            "fields": [
                {"name": "IP",              "value": f"`{ip}`",             "inline": True},
                {"name": "Reason",          "value": reason,                "inline": True},
                {"name": "Instance IDs",    "value": instances,             "inline": False},
                {"name": "Total requests",  "value": str(stats.get("requests", 0)),        "inline": True},
                {"name": "Rate-limit hits", "value": str(stats.get("rate_limit_hits", 0)),"inline": True},
                {"name": "Conn rejections", "value": str(stats.get("conn_rejections", 0)),"inline": True},
                {"name": "Sessions",        "value": str(stats.get("sessions", 0)),        "inline": True},
                {"name": "First seen",      "value": stats.get("first_seen", "?"),         "inline": True},
                {"name": "Last seen",       "value": stats.get("last_seen",  "?"),         "inline": True},
            ],
            "footer": {"text": "VoicechatModerator Whisper service"},
            "timestamp": _now_iso(),
        }]
    }
    payload = json.dumps(embed).encode()

    def _post() -> None:
        try:
            req = urllib.request.Request(
                ABUSE_WEBHOOK,
                data=payload,
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with urllib.request.urlopen(req, timeout=10):
                pass
        except Exception as exc:
            log.warning(f"[Abuse] Webhook POST failed: {exc}")

    import threading
    threading.Thread(target=_post, daemon=True).start()


# ── Transcription ─────────────────────────────────────────────────────────────

def _transcribe_sync(wav_path: str, language: str | None) -> str:
    segments, _ = _model.transcribe(
        wav_path,
        language=language or None,
        beam_size=5,
        vad_filter=True,
        vad_parameters={"min_silence_duration_ms": 500},
    )
    return " ".join(s.text for s in segments).strip()


async def _worker(worker_id: int) -> None:
    loop = asyncio.get_running_loop()
    log.info(f"Worker {worker_id} started.")
    while True:
        ws, req, instance, ip = await _queue.get()

        request_id  = req.get("requestId",  "")
        player_uuid = req.get("playerUuid", "")
        player_name = req.get("playerName", "unknown")
        language    = req.get("language",   "") or None
        flagged     = bool(req.get("flagged", False))
        audio_b64   = req.get("audio", "")
        prefix      = f"[{instance}] [{player_name}]" if instance else f"[{player_name}]"

        # ── Track request count ────────────────────────────────────────────
        s = _ensure_ip(ip)
        s["requests"] += 1
        _touch(ip)

        if not audio_b64:
            await _send(ws, {"requestId": request_id, "error": "empty audio"})
            _queue.task_done()
            continue

        try:
            wav_bytes = base64.b64decode(audio_b64)
        except Exception as exc:
            await _send(ws, {"requestId": request_id, "error": f"base64 decode: {exc}"})
            _queue.task_done()
            continue

        if flagged and wav_bytes:
            _save_recording(player_name, wav_bytes)

        tmp_path = None
        try:
            with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
                tmp.write(wav_bytes)
                tmp_path = tmp.name

            text = await loop.run_in_executor(_thread_pool, _transcribe_sync, tmp_path, language)
            log.info(f"{prefix} → {text!r}")
            await _send(ws, {
                "requestId":  request_id,
                "playerUuid": player_uuid,
                "text":       text,
            })
        except Exception as exc:
            log.error(f"Transcription error for {prefix}: {exc}")
            await _send(ws, {"requestId": request_id, "error": str(exc)})
        finally:
            if tmp_path:
                try:
                    Path(tmp_path).unlink(missing_ok=True)
                except OSError:
                    pass
            _queue.task_done()


def _save_recording(player_name: str, wav_bytes: bytes) -> None:
    try:
        day_dir = RECORDINGS_DIR / str(date.today())
        day_dir.mkdir(parents=True, exist_ok=True)
        out = day_dir / f"{player_name}-{int(time.time())}.wav"
        out.write_bytes(wav_bytes)
        log.info(f"Saved flagged recording: {out}")
    except OSError as exc:
        log.warning(f"Could not save recording: {exc}")


async def _send(ws: WebSocketServerProtocol, data: dict) -> None:
    try:
        await ws.send(json.dumps(data))
    except websockets.exceptions.ConnectionClosed:
        pass


# ── Rate limiter ──────────────────────────────────────────────────────────────

class _RateLimiter:
    """Sliding 60-second window counter, one per connection."""

    __slots__ = ("_count", "_window_start")

    def __init__(self) -> None:
        self._count        = 0
        self._window_start = time.monotonic()

    def allow(self) -> bool:
        now = time.monotonic()
        if now - self._window_start >= 60.0:
            self._count        = 0
            self._window_start = now
        self._count += 1
        return self._count <= RATE_LIMIT


# ── WebSocket handlers ────────────────────────────────────────────────────────

async def _handler(ws: WebSocketServerProtocol, _path: str) -> None:
    ip = ws.remote_address[0] if ws.remote_address else "unknown"

    # ── Global connection cap ──────────────────────────────────────────────
    if len(_active_connections) >= MAX_CONNECTIONS:
        log.warning(f"Global connection cap ({MAX_CONNECTIONS}) reached — rejecting {ip}")
        s = _ensure_ip(ip)
        s["conn_rejections"] += 1
        _touch(ip)
        _send_abuse_alert(ip, f"Global cap ({MAX_CONNECTIONS}) reached", s)
        await _send(ws, {"error": "server connection limit reached — try again later"})
        await ws.close()
        return

    # ── Per-IP connection limit ────────────────────────────────────────────
    if _connections_by_ip[ip] >= MAX_CONN_PER_IP:
        log.warning(f"Per-IP cap ({MAX_CONN_PER_IP}) reached for {ip} — rejecting")
        s = _ensure_ip(ip)
        s["conn_rejections"] += 1
        _touch(ip)
        _send_abuse_alert(ip, f"Per-IP connection cap ({MAX_CONN_PER_IP}) exceeded", s)
        await _send(ws, {"error": f"too many connections from your IP (max {MAX_CONN_PER_IP})"})
        await ws.close()
        return

    _active_connections.add(ws)
    _connections_by_ip[ip] += 1
    limiter    = _RateLimiter()
    rl_strikes = 0         # rate-limit violations this session
    instance   = ""        # set on first request that carries instanceId

    s = _ensure_ip(ip)
    s["sessions"] += 1
    _touch(ip)

    log.info(
        f"Connected: {ip} "
        f"(total={len(_active_connections)}, from_ip={_connections_by_ip[ip]})"
    )

    try:
        async for raw in ws:
            if not isinstance(raw, str):
                continue

            # ── Rate limiting ──────────────────────────────────────────────
            if not limiter.allow():
                req_id = ""
                try:
                    req_id = json.loads(raw).get("requestId", "")
                except Exception:
                    pass
                s = _ensure_ip(ip)
                s["rate_limit_hits"] += 1
                rl_strikes += 1
                _touch(ip)

                # Alert once the session crosses the threshold
                if rl_strikes == ABUSE_RATE_THRESH:
                    _send_abuse_alert(
                        ip,
                        f"Rate-limit hit {rl_strikes}× in one session "
                        f"(limit: {RATE_LIMIT} req/min)",
                        s,
                    )

                await _send(ws, {
                    "requestId": req_id,
                    "error": f"rate limit exceeded ({RATE_LIMIT} req/min) — slow down",
                })
                continue

            try:
                req = json.loads(raw)
            except json.JSONDecodeError as exc:
                await _send(ws, {"error": f"invalid JSON: {exc}"})
                continue

            # Track instanceId for stats/alerts
            req_instance = req.get("instanceId", "")
            if req_instance and req_instance != instance:
                instance = req_instance
                s = _ensure_ip(ip)
                if req_instance not in s.get("instances", []):
                    s.setdefault("instances", []).append(req_instance)

            # ── Queue backpressure ─────────────────────────────────────────
            if _queue.full():
                log.warning(
                    f"Queue full ({MAX_QUEUE}) — rejecting job from "
                    f"{instance or ip}"
                )
                await _send(ws, {
                    "requestId": req.get("requestId", ""),
                    "error": "server queue full — retry later",
                })
                continue

            # Pass ip into queue so the worker can update request stats
            await _queue.put((ws, req, instance, ip))

    except websockets.exceptions.ConnectionClosed:
        pass
    finally:
        _active_connections.discard(ws)
        _connections_by_ip[ip] = max(0, _connections_by_ip[ip] - 1)
        if _connections_by_ip[ip] == 0:
            del _connections_by_ip[ip]
        log.info(
            f"Disconnected: {ip} "
            f"(total={len(_active_connections)}, from_ip={_connections_by_ip.get(ip, 0)})"
        )


async def _health_handler(ws: WebSocketServerProtocol, _path: str) -> None:
    """WebSocket /health — quick machine-readable check for the plugin."""
    await _send(ws, {
        "status":          "ok",
        "model":           MODEL,
        "device":          DEVICE,
        "workers":         WORKERS,
        "max_queue":       MAX_QUEUE,
        "queue_len":       _queue.qsize(),
        "connections":     len(_active_connections),
        "max_connections": MAX_CONNECTIONS,
    })
    await ws.close()


# ── Plain HTTP status page ────────────────────────────────────────────────────

async def _http_status_handler(reader: asyncio.StreamReader,
                               writer: asyncio.StreamWriter) -> None:
    """Tiny HTTP/1.0 handler — responds to any GET with a JSON status blob."""
    try:
        await reader.readline()
        while True:
            line = await reader.readline()
            if not line or line == b"\r\n":
                break

        body = json.dumps({
            "status":          "ok",
            "model":           MODEL,
            "device":          DEVICE,
            "workers":         WORKERS,
            "max_queue":       MAX_QUEUE,
            "queue_len":       _queue.qsize(),
            "connections":     len(_active_connections),
            "max_connections": MAX_CONNECTIONS,
            "rate_limit_rpm":  RATE_LIMIT,
            "tracked_ips":     len(_ip_stats),
        }, indent=2).encode()

        response = (
            b"HTTP/1.0 200 OK\r\n"
            b"Content-Type: application/json\r\n"
            b"Access-Control-Allow-Origin: *\r\n"
            + b"Content-Length: " + str(len(body)).encode() + b"\r\n"
            + b"Connection: close\r\n\r\n"
            + body
        )
        writer.write(response)
        await writer.drain()
    except Exception:
        pass
    finally:
        writer.close()


# ── Router ────────────────────────────────────────────────────────────────────

async def _ws_router(ws: WebSocketServerProtocol, path: str) -> None:
    if path == "/health":
        await _health_handler(ws, path)
    else:
        await _handler(ws, path)


# ── Main ──────────────────────────────────────────────────────────────────────

async def main() -> None:
    global _queue
    _load_stats()
    _queue = asyncio.Queue(maxsize=MAX_QUEUE)

    for i in range(WORKERS):
        asyncio.create_task(_worker(i + 1))

    asyncio.create_task(_stats_saver_loop())

    log.info(
        f"Starting WebSocket server on {HOST}:{PORT} | "
        f"model={MODEL} device={DEVICE} workers={WORKERS} "
        f"max_queue={MAX_QUEUE} max_conn={MAX_CONNECTIONS} "
        f"max_conn_per_ip={MAX_CONN_PER_IP} rate={RATE_LIMIT}rpm"
    )
    if ABUSE_WEBHOOK:
        log.info(f"Abuse alerts → Discord webhook (threshold: {ABUSE_RATE_THRESH} rate-limit hits)")
    else:
        log.info("Abuse alerts disabled — set WHISPER_ABUSE_WEBHOOK to enable")
    log.info(f"IP stats file: {STATS_FILE.resolve()}")

    async with websockets.serve(_ws_router, HOST, PORT):
        if STATUS_PORT > 0:
            log.info(f"HTTP status page on http://{HOST}:{STATUS_PORT}/")
            async with await asyncio.start_server(
                _http_status_handler, HOST, STATUS_PORT
            ) as _status_srv:
                log.info("Server ready.")
                await asyncio.Future()
        else:
            log.info("Server ready. (HTTP status disabled — set WHISPER_STATUS_PORT)")
            await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())

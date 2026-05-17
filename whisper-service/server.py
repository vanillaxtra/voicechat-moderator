"""
VoicechatModerator — faster-whisper WebSocket transcription service.

Designed to run publicly on a VPS and serve multiple Minecraft servers
simultaneously.  No authentication is required; abuse protection is
provided by connection caps and per-connection rate limiting.

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
                            Jobs beyond this are rejected immediately.
  WHISPER_MAX_CONNECTIONS   global concurrent WS cap      (default: 20)
                            Excess connections are closed with an error.
  WHISPER_MAX_CONN_PER_IP   connections allowed per IP    (default: 3)
                            Protects against one host taking all slots.
  WHISPER_RATE_LIMIT        requests per minute per conn  (default: 30)
                            Excess requests get an error frame; conn stays open.
  WHISPER_STATUS_PORT       plain HTTP status page port   (default: 8766)
                            Returns JSON; 0 = disable status server.
"""

from __future__ import annotations

import asyncio
import base64
import json
import logging
import os
import tempfile
import time
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from datetime import date
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
RATE_LIMIT       = int(os.environ.get("WHISPER_RATE_LIMIT",       "30"))  # per minute
STATUS_PORT      = int(os.environ.get("WHISPER_STATUS_PORT",      "8766"))

RECORDINGS_DIR = Path("recordings")

# ── Connection tracking (all access is on the event-loop thread) ──────────────

_active_connections: set[WebSocketServerProtocol] = set()
_connections_by_ip: dict[str, int] = defaultdict(int)   # ip → active count

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
        ws, req, instance = await _queue.get()

        request_id  = req.get("requestId",  "")
        player_uuid = req.get("playerUuid", "")
        player_name = req.get("playerName", "unknown")
        language    = req.get("language",   "") or None
        flagged     = bool(req.get("flagged", False))
        audio_b64   = req.get("audio", "")
        prefix      = f"[{instance}] [{player_name}]" if instance else f"[{player_name}]"

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
        await _send(ws, {"error": "server connection limit reached — try again later"})
        await ws.close()
        return

    # ── Per-IP connection limit ────────────────────────────────────────────
    if _connections_by_ip[ip] >= MAX_CONN_PER_IP:
        log.warning(f"Per-IP cap ({MAX_CONN_PER_IP}) reached for {ip} — rejecting")
        await _send(ws, {"error": f"too many connections from your IP (max {MAX_CONN_PER_IP})"})
        await ws.close()
        return

    _active_connections.add(ws)
    _connections_by_ip[ip] += 1
    limiter = _RateLimiter()

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

            instance = req.get("instanceId", "")

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

            await _queue.put((ws, req, instance))

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
        await reader.readline()  # consume request line
        while True:              # drain headers
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
    _queue = asyncio.Queue(maxsize=MAX_QUEUE)

    for i in range(WORKERS):
        asyncio.create_task(_worker(i + 1))

    log.info(
        f"Starting WebSocket server on {HOST}:{PORT} | "
        f"model={MODEL} device={DEVICE} workers={WORKERS} "
        f"max_queue={MAX_QUEUE} max_conn={MAX_CONNECTIONS} "
        f"max_conn_per_ip={MAX_CONN_PER_IP} rate={RATE_LIMIT}rpm"
    )

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

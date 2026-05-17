package dev.voicechat.moderator.whisper;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.voicechat.moderator.VoicechatModeratorPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persistent WebSocket client that connects to the faster-whisper VPS service.
 *
 * One instance per Minecraft server.  Multiple Minecraft servers may connect to
 * the same VPS service simultaneously — each gets its own WebSocket connection
 * and its requests are correlated by requestId.
 *
 * Inflight limiting: at most {@code whisper.max_inflight} requests may be in
 * flight at once (i.e. sent but not yet answered).  Extra chunks are silently
 * dropped so the VPS queue never overflows and game-thread pressure stays low.
 *
 * Auto-reconnects with exponential back-off when the connection drops.
 */
public final class WhisperWsClient {

    /** Fired on the WebSocket listener thread — callers must dispatch to game thread if needed. */
    public interface TranscriptCallback {
        void onResult(String text);
    }

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final VoicechatModeratorPlugin plugin;
    private final Logger                   logger;

    /** requestId → callback awaiting result. */
    private final Map<String, TranscriptCallback> pending = new ConcurrentHashMap<>();

    /** Accumulates fragmented WebSocket text frames. */
    private final StringBuilder frameAccumulator = new StringBuilder();

    /** Number of requests currently in-flight (sent, awaiting response). */
    private final AtomicInteger inflight = new AtomicInteger(0);

    private volatile WebSocket ws      = null;
    private volatile boolean   running = false;

    private static final long MIN_RECONNECT_MS = 1_000;
    private static final long MAX_RECONNECT_MS = 30_000;
    private long reconnectDelayMs = MIN_RECONNECT_MS;

    public WhisperWsClient(VoicechatModeratorPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void start() {
        running = true;
        Thread t = new Thread(this::connectionLoop, "vcm-ws-reconnect");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        WebSocket current = ws;
        if (current != null) {
            current.sendClose(WebSocket.NORMAL_CLOSURE, "Plugin shutting down")
                   .exceptionally(e -> null);
        }
        pending.forEach((id, cb) -> cb.onResult(null));
        pending.clear();
        inflight.set(0);
    }

    public boolean isConnected() {
        WebSocket current = ws;
        return current != null && !current.isInputClosed() && !current.isOutputClosed();
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    /**
     * Sends an audio chunk for transcription.
     * Returns false and invokes the callback immediately with {@code null} if:
     * <ul>
     *   <li>the WebSocket is not connected, or</li>
     *   <li>the inflight cap is reached (chunk is dropped to avoid VPS queue overflow).</li>
     * </ul>
     */
    public boolean send(UUID playerUuid, String playerName, String language,
                        byte[] wav, boolean flagged, TranscriptCallback callback) {

        if (!isConnected()) {
            callback.onResult(null);
            return false;
        }

        int maxInflight = plugin.getConfig().getInt("whisper.max_inflight", 8);
        if (inflight.get() >= maxInflight) {
            // VPS queue would overflow — silently drop this chunk
            return false;
        }

        String requestId = UUID.randomUUID().toString();
        String instanceId = plugin.getConfig().getString("whisper.instance_id", "");

        JsonObject msg = new JsonObject();
        msg.addProperty("requestId",  requestId);
        msg.addProperty("playerUuid", playerUuid.toString());
        msg.addProperty("playerName", playerName);
        if (instanceId != null && !instanceId.isBlank()) {
            msg.addProperty("instanceId", instanceId);
        }
        msg.addProperty("language", language == null ? "" : language);
        msg.addProperty("audio",    Base64.getEncoder().encodeToString(wav));
        msg.addProperty("flagged",  flagged);

        inflight.incrementAndGet();
        pending.put(requestId, callback);

        ws.sendText(msg.toString(), true).exceptionally(e -> {
            logger.warning("[WS] Send failed for " + playerName + ": " + e.getMessage());
            pending.remove(requestId);
            inflight.decrementAndGet();
            callback.onResult(null);
            return null;
        });
        return true;
    }

    // ── Connection loop ───────────────────────────────────────────────────────

    private void connectionLoop() {
        while (running) {
            try {
                connectBlocking();
                reconnectDelayMs = MIN_RECONNECT_MS;
                synchronized (this) { wait(); }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (running) {
                    logger.warning("[WS] Connection failed: " + e.getMessage()
                            + " — retrying in " + (reconnectDelayMs / 1000) + "s");
                }
            }
            if (!running) break;
            try { Thread.sleep(reconnectDelayMs); } catch (InterruptedException ie) { break; }
            reconnectDelayMs = Math.min(reconnectDelayMs * 2, MAX_RECONNECT_MS);
        }
    }

    private void connectBlocking() throws Exception {
        String host = plugin.getConfig().getString("whisper.host", "127.0.0.1");
        int    port = plugin.getConfig().getInt("whisper.port", 8765);
        URI    uri  = URI.create("ws://" + host + ":" + port + "/ws");

        if (host.equals("127.0.0.1") || host.equalsIgnoreCase("localhost")) {
            logger.warning("[WS] whisper.host is set to " + host
                    + " — this only works if the Whisper service is running on THIS machine. "
                    + "Set whisper.host to your VPS public IP in plugins/VoicechatModerator/config.yml if needed.");
        }

        logger.info("[WS] Connecting to " + uri + " ...");

        ws = HTTP.newWebSocketBuilder()
                 .buildAsync(uri, new Listener())
                 .join();

        logger.info("[WS] Connected to Whisper service.");
    }

    // ── WebSocket.Listener ────────────────────────────────────────────────────

    private final class Listener implements WebSocket.Listener {

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            frameAccumulator.append(data);
            if (last) {
                String raw = frameAccumulator.toString();
                frameAccumulator.setLength(0);
                handleMessage(raw);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            logger.warning("[WS] Connection closed (code " + statusCode + ": " + reason
                    + "). Reconnecting...");
            ws = null;
            inflight.set(0);
            pending.forEach((id, cb) -> cb.onResult(null));
            pending.clear();
            synchronized (WhisperWsClient.this) { WhisperWsClient.this.notifyAll(); }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            logger.log(Level.WARNING, "[WS] WebSocket error", error);
        }
    }

    // ── Message dispatch ──────────────────────────────────────────────────────

    private void handleMessage(String raw) {
        try {
            JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
            String requestId = json.has("requestId") ? json.get("requestId").getAsString() : null;
            if (requestId == null) return;

            TranscriptCallback cb = pending.remove(requestId);
            if (cb == null) return;

            inflight.decrementAndGet();

            if (json.has("error")) {
                logger.warning("[WS] Whisper error: " + json.get("error").getAsString());
                cb.onResult(null);
            } else if (json.has("text")) {
                String text = json.get("text").getAsString().strip();
                cb.onResult(text.isEmpty() ? null : text);
            } else {
                cb.onResult(null);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "[WS] Failed to parse response: " + raw, e);
        }
    }
}

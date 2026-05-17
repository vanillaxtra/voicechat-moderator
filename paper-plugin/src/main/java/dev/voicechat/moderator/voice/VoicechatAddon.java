package dev.voicechat.moderator.voice;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import dev.voicechat.moderator.VoicechatModeratorPlugin;
import dev.voicechat.moderator.database.DatabaseManager;
import dev.voicechat.moderator.database.MuteRecord;
import dev.voicechat.moderator.matching.CooldownTracker;
import dev.voicechat.moderator.matching.MatchResult;
import dev.voicechat.moderator.matching.WordListDownloader;
import dev.voicechat.moderator.matching.WordMatcher;
import dev.voicechat.moderator.moderation.ModerationActionExecutor;
import dev.voicechat.moderator.util.FoliaUtil;
import dev.voicechat.moderator.whisper.WhisperWsClient;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class VoicechatAddon implements VoicechatPlugin {

    private final VoicechatModeratorPlugin plugin;
    private VoicechatApi api;

    private final Map<UUID, OpusDecoder>      decoders       = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerAudioBuffer> buffers       = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerClipBuffer>  clipBuffers   = new ConcurrentHashMap<>();
    private final Map<UUID, TranscriptBuffer>  transcripts   = new ConcurrentHashMap<>();

    private volatile WordMatcher     matcher          = WordMatcher.build(null);
    private final    CooldownTracker cooldowns        = new CooldownTracker();
    private volatile boolean         moderationActive = true;

    /** Shared WebSocket client — lifecycle managed by VoicechatModeratorPlugin. */
    private final WhisperWsClient wsClient;

    public VoicechatAddon(VoicechatModeratorPlugin plugin, WhisperWsClient wsClient) {
        this.plugin   = plugin;
        this.wsClient = wsClient;
    }

    // ── VoicechatPlugin ───────────────────────────────────────────────────────

    @Override
    public String getPluginId() { return "voicechat-moderator"; }

    @Override
    public void initialize(VoicechatApi api) { this.api = api; }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket, 0);
    }

    // ── Runtime control ───────────────────────────────────────────────────────

    public void setModerationActive(boolean active) { this.moderationActive = active; }
    public boolean isModerationActive()             { return moderationActive; }

    public void reloadMatcher() { reloadMatcher(null); }

    public void reloadMatcher(WordListDownloader downloader) {
        matcher = WordMatcher.build(
                plugin.getConfig().getConfigurationSection("word_groups"), downloader);
        cooldowns.clear();
    }

    // ── Transcript buffer access (used by report GUI) ─────────────────────────

    /** Returns the transcript buffer for the given player, or null if none yet. */
    public TranscriptBuffer getTranscriptBuffer(UUID uuid) {
        return transcripts.get(uuid);
    }

    // ── Packet handler ────────────────────────────────────────────────────────

    private void onMicrophonePacket(MicrophonePacketEvent event) {
        VoicechatConnection sender = event.getSenderConnection();
        if (sender == null) return;

        Object rawPlayer = sender.getPlayer().getPlayer();
        if (!(rawPlayer instanceof Player player)) return;

        UUID uuid = player.getUniqueId();
        String name = player.getName();

        // DB mute check — cancel packet, then notify player on main thread
        DatabaseManager db = plugin.getDatabaseManager();
        if (db != null && db.isVoiceMuted(uuid)) {
            event.cancel();
            FoliaUtil.runOnMain(plugin, () -> {
                Player online = plugin.getServer().getPlayer(uuid);
                if (online != null && plugin.getMuteNotifier() != null) {
                    MuteRecord rec = db.getActiveMute(uuid).orElse(null);
                    plugin.getMuteNotifier().notify(online, rec);
                }
            });
            return;
        }

        if (!moderationActive) return;

        byte[] opusData = event.getPacket().getOpusEncodedData();
        if (opusData == null || opusData.length == 0) return;

        OpusDecoder decoder = decoders.computeIfAbsent(uuid, id -> api.createDecoder());

        short[] pcm;
        try {
            pcm = decoder.decode(opusData);
        } catch (Exception e) {
            plugin.getLogger().warning("[VC] Failed to decode Opus for " + name + ": " + e.getMessage());
            return;
        }
        if (pcm == null || pcm.length == 0) return;

        int sampleRate   = plugin.getConfig().getInt("audio.sample_rate", 48000);
        int chunkSeconds = plugin.getConfig().getInt("audio.chunk_seconds", 3);
        int maxSeconds   = plugin.getConfig().getInt("audio.max_buffer_seconds", 8);
        int clipSeconds  = plugin.getConfig().getInt("discord.clip.clip_seconds", 10);

        PlayerAudioBuffer buf = buffers.computeIfAbsent(uuid, id -> new PlayerAudioBuffer(sampleRate));
        buf.append(pcm);

        PlayerClipBuffer clip = clipBuffers.computeIfAbsent(uuid,
                id -> new PlayerClipBuffer(sampleRate, clipSeconds));
        clip.append(pcm);

        if (buf.durationSeconds() >= chunkSeconds || buf.durationSeconds() >= maxSeconds) {
            byte[] wav     = buf.buildAndReset();
            byte[] clipWav = clip.snapshot();
            dispatchToWhisper(uuid, name, wav, clipWav);
        }
    }

    // ── On-disconnect flush ───────────────────────────────────────────────────

    public void flushAndCleanup(UUID uuid, String name) {
        PlayerAudioBuffer buf = buffers.remove(uuid);
        if (buf != null && buf.durationSeconds() > 0.5) {
            PlayerClipBuffer clip = clipBuffers.get(uuid);
            byte[] clipWav = clip != null ? clip.snapshot() : new byte[0];
            byte[] wav     = buf.buildAndReset();
            dispatchToWhisper(uuid, name, wav, clipWav);
        }
        clipBuffers.remove(uuid);
        // Keep transcriptBuffer alive briefly so a pending report can still be filed;
        // clean up after 10 minutes via natural eviction.
        OpusDecoder decoder = decoders.remove(uuid);
        if (decoder != null) {
            try { decoder.close(); } catch (Exception ignored) {}
        }
        cooldowns.clearPlayer(uuid);
    }

    // ── Whisper dispatch ──────────────────────────────────────────────────────

    private void dispatchToWhisper(UUID uuid, String playerName, byte[] wav, byte[] clipWav) {
        String lang              = plugin.getConfig().getString("audio.language", "");
        WordMatcher currentMatcher   = this.matcher;
        boolean moderationEnabled    = plugin.getConfig().getBoolean("moderation.enabled", true);

        // Send over WebSocket; callback fires on the WS listener thread
        wsClient.send(uuid, playerName, lang, wav, false, text -> {
            if (text == null || text.isBlank()) return;

            // Add to rolling transcript buffer regardless of match
            transcripts.computeIfAbsent(uuid, id -> new TranscriptBuffer()).add(text);

            Instant timestamp = Instant.now();

            List<MatchResult> hits = (moderationEnabled && !currentMatcher.isEmpty())
                    ? currentMatcher.match(text, uuid, cooldowns)
                    : List.of();

            FoliaUtil.runOnMain(plugin, () -> {
                plugin.getLogger().info("[VC] " + playerName + ": " + text);

                // Transcript chat broadcast (opt-in spy mode)
                if (plugin.getConfig().getBoolean("transcript_broadcast.enabled", false)) {
                    Component spyMsg = Component.text("[VCM] ", NamedTextColor.GRAY)
                            .append(Component.text(playerName, NamedTextColor.YELLOW))
                            .append(Component.text(": ", NamedTextColor.GRAY))
                            .append(Component.text(text, NamedTextColor.WHITE))
                            .decoration(TextDecoration.ITALIC, false);
                    for (Player p : plugin.getServer().getOnlinePlayers()) {
                        if (p.isOp() || p.hasPermission("voicechatmoderator.spy")) {
                            p.sendMessage(spyMsg);
                        }
                    }
                }

                if (!hits.isEmpty()) {
                    Player player = plugin.getServer().getPlayer(uuid);
                    ModerationActionExecutor.execute(
                            plugin, player, uuid, playerName, text, timestamp, hits, clipWav);
                }
            });
        });
    }

    // ── Misc ──────────────────────────────────────────────────────────────────

    public VoicechatServerApi getServerApi() {
        return (api instanceof VoicechatServerApi sa) ? sa : null;
    }

    public void shutdown() {
        decoders.forEach((uuid, decoder) -> {
            try { decoder.close(); } catch (Exception ignored) {}
        });
        decoders.clear();
        buffers.clear();
        clipBuffers.clear();
        transcripts.clear();
    }
}

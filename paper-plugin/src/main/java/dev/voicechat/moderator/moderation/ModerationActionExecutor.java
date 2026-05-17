package dev.voicechat.moderator.moderation;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import dev.voicechat.moderator.VoicechatModeratorPlugin;
import dev.voicechat.moderator.database.DatabaseManager;
import dev.voicechat.moderator.discord.DiscordWebhookClient;
import dev.voicechat.moderator.matching.MatchResult;
import dev.voicechat.moderator.matching.WordGroupConfig;
import dev.voicechat.moderator.mute.MuteLadderService;
import dev.voicechat.moderator.util.FoliaUtil;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * Runs all moderation actions on the Bukkit main thread.
 * Webhook I/O is offloaded to a separate executor.
 */
public final class ModerationActionExecutor {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());

    private static final ExecutorService WEBHOOK_EXEC = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "vc-discord-webhook");
        t.setDaemon(true);
        return t;
    });

    // Tracks active mute attachments so we can remove them on expiry
    private static final Map<UUID, PermissionAttachment> MUTE_ATTACHMENTS = new HashMap<>();

    private ModerationActionExecutor() {}

    /**
     * Must be called on the Bukkit main thread.
     *
     * @param player  null if the player has already left
     * @param clipWav rolling 10-second WAV clip to attach to Discord alerts
     */
    public static void execute(
            VoicechatModeratorPlugin plugin,
            Player player,
            UUID uuid,
            String playerName,
            String transcript,
            Instant timestamp,
            List<MatchResult> hits,
            byte[] clipWav) {

        String timeStr = TIME_FMT.format(timestamp);

        for (MatchResult hit : hits) {
            WordGroupConfig cfg = hit.group;

            Map<String, String> ph = new HashMap<>();
            ph.put("%player%",  playerName);
            ph.put("%uuid%",    uuid.toString());
            ph.put("%word%",    hit.matchedWord);
            ph.put("%message%", transcript);
            ph.put("%time%",    timeStr);
            ph.put("%group%",   cfg.id);

            plugin.getLogger().info(
                    "[Moderation] Group '" + cfg.id + "' triggered for " + playerName
                    + " — word: \"" + hit.matchedWord + "\"");

            // ── Console commands ───────────────────────────────────────────
            for (String cmd : cfg.commands) {
                String resolved = applyPlaceholders(cmd, ph).trim();
                if (!resolved.isEmpty()) {
                    plugin.getServer().dispatchCommand(
                            plugin.getServer().getConsoleSender(), resolved);
                }
            }

            // ── Voice mute (ladder or fixed duration) ─────────────────────
            if (cfg.muteEnabled && player != null) {
                MuteLadderService ladder = plugin.getMuteLadderService();
                String muteReason = "Voice chat violation: " + hit.matchedWord;

                if (ladder != null && ladder.isEnabled()) {
                    // Ladder path: LP applies the timed node; we only need the
                    // permission attachment for immediate in-session enforcement.
                    long durationSec = ladder.applyLadderMute(uuid, playerName, muteReason);
                    if (durationSec > 0) applyMute(plugin, player, uuid, (int) Math.min(durationSec, Integer.MAX_VALUE));
                } else {
                    // Standard path: fixed duration from word-group config
                    long expiryTime = cfg.muteDurationSeconds > 0
                            ? System.currentTimeMillis() + (long) cfg.muteDurationSeconds * 1000
                            : -1L;
                    DatabaseManager db = plugin.getDatabaseManager();
                    if (db != null) {
                        db.addMute(uuid, playerName, muteReason, "VoicechatModerator", expiryTime);
                    }
                    applyMute(plugin, player, uuid, cfg.muteDurationSeconds);
                }
            }

            // ── Ban ────────────────────────────────────────────────────────
            if (cfg.banEnabled) {
                String reason   = applyPlaceholders(cfg.banReason, ph);
                long expiryTime = DatabaseManager.parseExpiry(cfg.banDuration);

                DatabaseManager db = plugin.getDatabaseManager();
                if (db != null) {
                    db.addBan(uuid, playerName, reason, "VoicechatModerator", expiryTime);
                }

                plugin.getBanHookManager().ban(player, playerName, reason, cfg.banDuration);

                if (player != null && player.isOnline()) {
                    plugin.getBanScreenLoader().kick(player, cfg.banScreenKey, reason, expiryTime, "VoicechatModerator");
                }
            }

            // ── Save flagged WAV clip to disk ──────────────────────────────
            if (clipWav != null && clipWav.length > 0
                    && plugin.getConfig().getBoolean("recordings.save_flagged", false)) {
                saveClip(plugin, playerName, clipWav);
            }

            // ── Discord webhook (async) ────────────────────────────────────
            String webhookKey = cfg.webhookKey;
            if (webhookKey != null && !webhookKey.isBlank()
                    && plugin.getConfig().getBoolean("discord.enabled", false)) {

                DiscordWebhookClient client = plugin.getWebhookClient(webhookKey);
                if (client != null && client.isValid()) {
                    final byte[] clip = clipWav != null ? clipWav : new byte[0];
                    WEBHOOK_EXEC.submit(() -> client.send(
                            playerName, uuid, hit, transcript, timeStr, clip));
                } else {
                    plugin.getLogger().warning(
                            "[Discord] Webhook '" + webhookKey + "' is not validated — "
                            + "run /vcm reload after fixing the URL.");
                }
            }
        }
    }

    // ── Mute helpers ─────────────────────────────────────────────────────────

    private static void applyMute(
            VoicechatModeratorPlugin plugin, Player player, UUID uuid, int durationSeconds) {

        removeMute(player);

        PermissionAttachment att = player.addAttachment(plugin);
        att.setPermission("voicechat.speak", false);
        MUTE_ATTACHMENTS.put(uuid, att);

        plugin.getLogger().info("[Moderation] Muted " + player.getName()
                + " from voice chat for " + durationSeconds + " s");

        if (plugin.getConfig().getBoolean("moderation.use_api_mute_fallback", false)) {
            VoicechatServerApi serverApi = plugin.getAddon() == null
                    ? null : plugin.getAddon().getServerApi();
            if (serverApi != null) {
                var conn = serverApi.getConnectionOf(uuid);
                if (conn != null) conn.setDisabled(true);
            }
        }

        if (durationSeconds > 0) {
            FoliaUtil.runLater(plugin, () -> {
                Player online = plugin.getServer().getPlayer(uuid);
                if (online != null) removeMute(online);
                MUTE_ATTACHMENTS.remove(uuid);

                DatabaseManager db = plugin.getDatabaseManager();
                if (db != null) db.removeMute(uuid);

                if (plugin.getConfig().getBoolean("moderation.use_api_mute_fallback", false)) {
                    VoicechatServerApi serverApi = plugin.getAddon() == null
                            ? null : plugin.getAddon().getServerApi();
                    if (serverApi != null) {
                        var conn = serverApi.getConnectionOf(uuid);
                        if (conn != null) conn.setDisabled(false);
                    }
                }
            }, durationSeconds * 20L);
        }
    }

    public static void removeMute(Player player) {
        PermissionAttachment att = MUTE_ATTACHMENTS.remove(player.getUniqueId());
        if (att != null) {
            try { player.removeAttachment(att); } catch (IllegalArgumentException ignored) {}
        }
    }

    /** Also clears any LuckPerms temp node when the ladder is active. */
    public static void removeMuteFull(VoicechatModeratorPlugin plugin, Player player) {
        removeMute(player);
        MuteLadderService ladder = plugin.getMuteLadderService();
        if (ladder != null && ladder.isEnabled()) {
            ladder.removeLuckPermsMute(player.getUniqueId());
        }
    }

    // ── Clip save ─────────────────────────────────────────────────────────────

    private static void saveClip(VoicechatModeratorPlugin plugin, String playerName, byte[] wav) {
        try {
            Path dir = plugin.getDataFolder().toPath()
                    .resolve("recordings")
                    .resolve(LocalDate.now().toString());
            Files.createDirectories(dir);
            Path out = dir.resolve(playerName + "-" + Instant.now().getEpochSecond() + ".wav");
            Files.write(out, wav);
            plugin.getLogger().info("[Recording] Saved flagged clip: " + out.getFileName());
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "[Recording] Failed to save clip for " + playerName, e);
        }
    }

    // ── Placeholder ──────────────────────────────────────────────────────────

    static String applyPlaceholders(String template, Map<String, String> placeholders) {
        String result = template;
        for (var entry : placeholders.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }
}

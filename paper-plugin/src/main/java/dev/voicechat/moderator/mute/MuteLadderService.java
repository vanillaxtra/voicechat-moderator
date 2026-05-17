package dev.voicechat.moderator.mute;

import dev.voicechat.moderator.VoicechatModeratorPlugin;
import dev.voicechat.moderator.database.DatabaseManager;
import dev.voicechat.moderator.database.DatabaseManager.MuteSource;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.PermissionNode;
import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Manages the mute ladder feature.
 *
 * When {@code mute_ladder.enabled: true} in config, each automated voice mute
 * (sourced from a word-group match) escalates through configurable tier durations.
 * Manual mutes bypass the ladder entirely.
 *
 * Requires LuckPerms to be installed.  If it is absent when the ladder is enabled,
 * this service disables itself and logs a clear warning — the plugin continues
 * to function with standard permission-attachment mutes.
 */
public final class MuteLadderService {

    private static final String VOICECHAT_SPEAK = "voicechat.speak";

    private final VoicechatModeratorPlugin plugin;
    private final Logger                   logger;
    private final LuckPerms                lp;

    private final boolean        enabled;
    private final TreeMap<Integer, Long> tierDurations = new TreeMap<>(); // tier → millis
    private final long           maxDurationMs;
    private final int            resetAfterDays;

    public MuteLadderService(VoicechatModeratorPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();

        ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("mute_ladder");
        boolean wantsEnabled = cfg != null && cfg.getBoolean("enabled", false);

        // Resolve LuckPerms
        LuckPerms resolved = null;
        if (wantsEnabled) {
            try {
                resolved = org.bukkit.Bukkit.getServicesManager()
                        .load(LuckPerms.class);
            } catch (Exception ignored) {}
            if (resolved == null) {
                logger.warning("[MuteLadder] mute_ladder.enabled=true but LuckPerms is not installed. "
                        + "The ladder has been disabled. Install LuckPerms and restart to enable it.");
            }
        }

        this.lp      = resolved;
        this.enabled = wantsEnabled && resolved != null;

        if (this.enabled && cfg != null) {
            ConfigurationSection tiers = cfg.getConfigurationSection("tiers");
            if (tiers != null) {
                for (String key : tiers.getKeys(false)) {
                    try {
                        int tier = Integer.parseInt(key);
                        long ms  = parseDurationMs(tiers.getString(key, "5m"));
                        tierDurations.put(tier, ms);
                    } catch (NumberFormatException ignored) {}
                }
            }
            this.maxDurationMs = parseDurationMs(cfg.getString("max_tier_duration", "24h"));
            this.resetAfterDays = cfg.getInt("reset_after_days", 30);
            logger.info("[MuteLadder] Enabled with " + tierDurations.size()
                    + " tier(s), max=" + cfg.getString("max_tier_duration", "24h")
                    + ", reset_after=" + resetAfterDays + "d");
        } else {
            this.maxDurationMs  = 0;
            this.resetAfterDays = 30;
        }
    }

    /** True if the ladder is enabled and LuckPerms is available. */
    public boolean isEnabled() { return enabled; }

    /**
     * Applies an escalating mute to the player via LuckPerms.
     *
     * Advances the DB tier, computes the capped duration, applies a timed
     * LuckPerms permission denial for {@code voicechat.speak}, and records
     * the mute in the VCM database with source AUTO_LADDER.
     *
     * Must be called on the main thread (LuckPerms user data modification).
     *
     * @return the duration in seconds that was applied, or -1 if skipped
     */
    public long applyLadderMute(UUID uuid, String playerName, String reason) {
        if (!enabled) return -1;

        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null) return -1;

        // Check reset + advance tier
        int currentTier = db.getLadderTier(uuid, resetAfterDays);
        int newTier     = db.advanceLadderTier(uuid);

        long durationMs = computeDuration(newTier);
        long expiryMs   = System.currentTimeMillis() + durationMs;
        long durationSec = durationMs / 1000;

        // Apply timed LP denial
        applyLuckPermsNode(uuid, durationMs);

        // Record in VCM DB
        db.addMute(uuid, playerName, reason, "VoicechatModerator [Ladder T" + newTier + "]",
                expiryMs, MuteSource.AUTO_LADDER);

        logger.info("[MuteLadder] " + playerName + " → tier " + newTier
                + " (" + formatDuration(durationMs) + ")");
        return durationSec;
    }

    /**
     * Removes the LuckPerms voicechat.speak denial (e.g. on manual unmute or expiry).
     * Safe to call even if LP is unavailable.
     */
    public void removeLuckPermsMute(UUID uuid) {
        if (lp == null) return;
        lp.getUserManager().modifyUser(uuid, user -> {
            user.data().clear(node -> node.getKey().equals(VOICECHAT_SPEAK));
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private long computeDuration(int tier) {
        if (tierDurations.isEmpty()) return Math.min(300_000L, maxDurationMs);
        // Use the highest defined tier if the player's tier exceeds the ladder
        Map.Entry<Integer, Long> entry = tierDurations.floorEntry(tier);
        if (entry == null) entry = tierDurations.firstEntry();
        return Math.min(entry.getValue(), maxDurationMs);
    }

    private void applyLuckPermsNode(UUID uuid, long durationMs) {
        long expiryEpochSec = Instant.now().getEpochSecond() + durationMs / 1000;
        PermissionNode node = PermissionNode.builder(VOICECHAT_SPEAK)
                .value(false)
                .expiry(expiryEpochSec)
                .build();
        lp.getUserManager().modifyUser(uuid, user -> {
            user.data().clear(n -> n.getKey().equals(VOICECHAT_SPEAK));
            user.data().add(node);
        });
    }

    /** Parses duration strings like "5m", "1h", "30s", "1d". */
    public static long parseDurationMs(String s) {
        if (s == null || s.isBlank()) return 0;
        s = s.trim().toLowerCase();
        try {
            long total = 0;
            int  i     = 0;
            while (i < s.length()) {
                int j = i;
                while (j < s.length() && Character.isDigit(s.charAt(j))) j++;
                if (j == i) break;
                long val  = Long.parseLong(s.substring(i, j));
                char unit = j < s.length() ? s.charAt(j) : 's';
                total += switch (unit) {
                    case 's'       -> val * 1_000L;
                    case 'm'       -> val * 60_000L;
                    case 'h'       -> val * 3_600_000L;
                    case 'd'       -> val * 86_400_000L;
                    default        -> val * 1_000L;
                };
                i = j + 1;
            }
            return total;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String formatDuration(long ms) {
        long s = ms / 1000;
        if (s < 60)   return s + "s";
        if (s < 3600) return (s / 60) + "m";
        if (s < 86400) return (s / 3600) + "h";
        return (s / 86400) + "d";
    }
}

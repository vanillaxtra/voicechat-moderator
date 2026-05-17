package dev.voicechat.moderator.mute;

import dev.voicechat.moderator.VoicechatModeratorPlugin;
import dev.voicechat.moderator.database.DatabaseManager;
import dev.voicechat.moderator.database.DatabaseManager.MuteSource;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Manages the mute ladder feature.
 *
 * When {@code mute_ladder.enabled: true} in config, each automated voice mute
 * escalates through configurable tier durations.  Manual mutes bypass the ladder.
 *
 * Optionally uses LuckPerms for timed permission nodes.  If LuckPerms is absent,
 * the ladder falls back to standard PermissionAttachment mutes and the plugin
 * continues to function normally.
 */
public final class MuteLadderService {

    private final VoicechatModeratorPlugin plugin;
    private final Logger                   logger;

    // Only non-null when LP is present AND ladder is enabled
    private final LuckPermsHook lpHook;

    private final boolean              enabled;
    private final TreeMap<Integer, Long> tierDurations = new TreeMap<>();
    private final long                 maxDurationMs;
    private final int                  resetAfterDays;

    public MuteLadderService(VoicechatModeratorPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();

        ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("mute_ladder");
        boolean wantsEnabled = cfg != null && cfg.getBoolean("enabled", false);

        LuckPermsHook hook = null;
        if (wantsEnabled) {
            hook = tryLoadLuckPerms();
            if (hook == null) {
                logger.warning("[MuteLadder] mute_ladder.enabled=true but LuckPerms is not installed. "
                        + "The ladder has been disabled. Install LuckPerms and restart to enable it.");
            }
        }

        this.lpHook  = hook;
        this.enabled = wantsEnabled && hook != null;

        if (this.enabled && cfg != null) {
            ConfigurationSection tiers = cfg.getConfigurationSection("tiers");
            if (tiers != null) {
                for (String key : tiers.getKeys(false)) {
                    try {
                        int  tier = Integer.parseInt(key);
                        long ms   = parseDurationMs(tiers.getString(key, "5m"));
                        tierDurations.put(tier, ms);
                    } catch (NumberFormatException ignored) {}
                }
            }
            this.maxDurationMs  = parseDurationMs(cfg.getString("max_tier_duration", "24h"));
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
     * Applies an escalating mute via LuckPerms.
     *
     * @return duration in seconds applied, or -1 if skipped
     */
    public long applyLadderMute(UUID uuid, String playerName, String reason) {
        if (!enabled) return -1;

        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null) return -1;

        db.getLadderTier(uuid, resetAfterDays); // trigger reset if window elapsed
        int newTier = db.advanceLadderTier(uuid);

        long durationMs  = computeDuration(newTier);
        long expiryMs    = System.currentTimeMillis() + durationMs;
        long durationSec = durationMs / 1000;

        lpHook.applyNode(uuid, durationMs);

        db.addMute(uuid, playerName, reason,
                "VoicechatModerator [Ladder T" + newTier + "]",
                expiryMs, MuteSource.AUTO_LADDER);

        logger.info("[MuteLadder] " + playerName + " → tier " + newTier
                + " (" + formatDuration(durationMs) + ")");
        return durationSec;
    }

    /** Removes the LP voicechat.speak denial. Safe to call when LP is absent. */
    public void removeLuckPermsMute(UUID uuid) {
        if (lpHook != null) lpHook.removeNode(uuid);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Attempts to resolve LuckPerms and create the hook.
     * Catches any linkage/class-not-found errors so the caller gets null safely.
     */
    private static LuckPermsHook tryLoadLuckPerms() {
        try {
            net.luckperms.api.LuckPerms lp =
                    org.bukkit.Bukkit.getServicesManager().load(net.luckperms.api.LuckPerms.class);
            if (lp == null) return null;
            return new LuckPermsHook(lp);
        } catch (NoClassDefFoundError | Exception e) {
            return null;
        }
    }

    private long computeDuration(int tier) {
        if (tierDurations.isEmpty()) return Math.min(300_000L, maxDurationMs);
        Map.Entry<Integer, Long> entry = tierDurations.floorEntry(tier);
        if (entry == null) entry = tierDurations.firstEntry();
        return Math.min(entry.getValue(), maxDurationMs);
    }

    /** Parses duration strings like "5m", "1h30m", "30s", "1d". */
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
                    case 's'  -> val * 1_000L;
                    case 'm'  -> val * 60_000L;
                    case 'h'  -> val * 3_600_000L;
                    case 'd'  -> val * 86_400_000L;
                    default   -> val * 1_000L;
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
        if (s < 60)    return s + "s";
        if (s < 3600)  return (s / 60) + "m";
        if (s < 86400) return (s / 3600) + "h";
        return (s / 86400) + "d";
    }
}

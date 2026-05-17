package dev.voicechat.moderator.notify;

import dev.voicechat.moderator.VoicechatModeratorPlugin;
import dev.voicechat.moderator.database.DatabaseManager;
import dev.voicechat.moderator.database.MuteRecord;
import dev.voicechat.moderator.util.FoliaUtil;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Sends configured mute notifications to players when their mic packet is cancelled.
 * Must be called on the main server thread.
 */
public final class MuteNotifier {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Map<UUID, Long> lastNotified = new ConcurrentHashMap<>();

    // Loaded from mute.yml (messages, cooldown, styles)
    private int cooldownSeconds = 5;

    private boolean actionbarEnabled = true;
    private String  actionbarMessage = "<red>You are voice muted. Expires: <white>%expiry%";

    private boolean chatEnabled = false;
    private String  chatMessage = "<red>You are voice muted. Expires: <white>%expiry%";

    private boolean titleEnabled = false;
    private String  titleTitle    = "<red><bold>Voice Muted";
    private String  titleSubtitle = "<gray>Expires: <white>%expiry%";
    private int fadeIn = 10, stay = 60, fadeOut = 20;

    private boolean bossbarEnabled  = false;
    private String  bossbarMessage  = "<red>Voice muted — expires <white>%expiry%";
    private BossBar.Color   bossbarColor   = BossBar.Color.RED;
    private BossBar.Overlay bossbarOverlay = BossBar.Overlay.PROGRESS;
    private int bossbarTicks = 100;

    private final Logger logger;
    private final VoicechatModeratorPlugin plugin;

    public MuteNotifier(VoicechatModeratorPlugin plugin) {
        this.logger = plugin.getLogger();
        this.plugin = plugin;
        reload(plugin);
    }

    public void reload(VoicechatModeratorPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "mute.yml");
        if (!file.exists()) {
            plugin.saveResource("mute.yml", false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        var sec = cfg.getConfigurationSection("mute_notification");
        if (sec == null) {
            logger.warning("[MuteNotifier] mute.yml has no mute_notification section, using defaults.");
            return;
        }

        cooldownSeconds = sec.getInt("cooldown_seconds", 5);

        var ab = sec.getConfigurationSection("actionbar");
        if (ab != null) {
            actionbarEnabled = ab.getBoolean("enabled", true);
            actionbarMessage = ab.getString("message", actionbarMessage);
        }

        var ch = sec.getConfigurationSection("chat");
        if (ch != null) {
            chatEnabled = ch.getBoolean("enabled", false);
            chatMessage = ch.getString("message", chatMessage);
        }

        var ti = sec.getConfigurationSection("title");
        if (ti != null) {
            titleEnabled   = ti.getBoolean("enabled", false);
            titleTitle     = ti.getString("title", titleTitle);
            titleSubtitle  = ti.getString("subtitle", titleSubtitle);
            fadeIn  = ti.getInt("fade_in", 10);
            stay    = ti.getInt("stay", 60);
            fadeOut = ti.getInt("fade_out", 20);
        }

        var bb = sec.getConfigurationSection("bossbar");
        if (bb != null) {
            bossbarEnabled = bb.getBoolean("enabled", false);
            bossbarMessage = bb.getString("message", bossbarMessage);
            bossbarTicks   = bb.getInt("duration_ticks", 100);
            try {
                bossbarColor = BossBar.Color.valueOf(bb.getString("color", "RED").toUpperCase());
            } catch (IllegalArgumentException ignored) {}
            try {
                bossbarOverlay = switch (bb.getString("style", "SOLID").toUpperCase()) {
                    case "SEGMENTED_6"  -> BossBar.Overlay.NOTCHED_6;
                    case "SEGMENTED_10" -> BossBar.Overlay.NOTCHED_10;
                    case "SEGMENTED_12" -> BossBar.Overlay.NOTCHED_12;
                    case "SEGMENTED_20" -> BossBar.Overlay.NOTCHED_20;
                    default             -> BossBar.Overlay.PROGRESS;
                };
            } catch (IllegalArgumentException ignored) {}
        }

        // Override channel flags with the single selector from config.yml, if set.
        // e.g. mute.mute_notification: "chat"  → only chat fires; others are silenced.
        String channelOverride = plugin.getConfig().getString("mute.mute_notification", "").strip().toLowerCase();
        if (!channelOverride.isEmpty()) {
            actionbarEnabled = channelOverride.equals("actionbar");
            chatEnabled      = channelOverride.equals("chat");
            titleEnabled     = channelOverride.equals("title");
            bossbarEnabled   = channelOverride.equals("bossbar");
        }

        logger.info("[MuteNotifier] Loaded mute notification config (channel: "
                + (channelOverride.isEmpty() ? "mute.yml flags" : channelOverride) + ").");
    }

    /**
     * Notifies {@code player} they are muted. Rate-limited by cooldown.
     *
     * @param rec active mute record — may be null (permanent / no record)
     */
    public void notify(Player player, MuteRecord rec) {
        UUID uuid = player.getUniqueId();
        long now  = System.currentTimeMillis();
        long last = lastNotified.getOrDefault(uuid, 0L);
        if (now - last < (long) cooldownSeconds * 1000) return;
        lastNotified.put(uuid, now);

        String expiry  = rec == null ? "Permanent" : DatabaseManager.formatRemaining(rec.expiryTime);
        String reason  = rec == null ? "N/A" : (rec.reason  == null ? "N/A"     : rec.reason);
        String mutedBy = rec == null ? "Unknown" : (rec.mutedBy == null ? "Unknown" : rec.mutedBy);

        if (actionbarEnabled) {
            player.sendActionBar(MM.deserialize(fill(actionbarMessage, player.getName(), expiry, reason, mutedBy)));
        }

        if (chatEnabled) {
            player.sendMessage(MM.deserialize(fill(chatMessage, player.getName(), expiry, reason, mutedBy)));
        }

        if (titleEnabled) {
            Component t  = MM.deserialize(fill(titleTitle,    player.getName(), expiry, reason, mutedBy));
            Component st = MM.deserialize(fill(titleSubtitle, player.getName(), expiry, reason, mutedBy));
            player.showTitle(Title.title(t, st,
                    Title.Times.times(
                            Duration.ofMillis(fadeIn  * 50L),
                            Duration.ofMillis(stay    * 50L),
                            Duration.ofMillis(fadeOut * 50L))));
        }

        if (bossbarEnabled) {
            Component msg = MM.deserialize(fill(bossbarMessage, player.getName(), expiry, reason, mutedBy));
            BossBar bar = BossBar.bossBar(msg, 1.0f, bossbarColor, bossbarOverlay);
            player.showBossBar(bar);
            FoliaUtil.runLater(plugin, () -> player.hideBossBar(bar), bossbarTicks);
        }
    }

    private String fill(String template, String player, String expiry, String reason, String mutedBy) {
        return template
                .replace("%player%", player)
                .replace("%expiry%", expiry)
                .replace("%reason%", reason)
                .replace("%muted_by%", mutedBy);
    }

    public void clearPlayer(UUID uuid) {
        lastNotified.remove(uuid);
    }
}

package dev.voicechat.moderator.hooks;

import dev.voicechat.moderator.VoicechatModeratorPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/** Loads ban.yml and formats MiniMessage kick messages for banned players. */
public final class BanScreenLoader {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final BanScreenConfig FALLBACK =
            new BanScreenConfig("<red><bold>You are banned", "<gray>Reason: <white>%reason%");

    private final Map<String, BanScreenConfig> screens = new HashMap<>();
    private final Logger logger;

    public BanScreenLoader(VoicechatModeratorPlugin plugin) {
        this.logger = plugin.getLogger();
        reload(plugin);
    }

    public void reload(VoicechatModeratorPlugin plugin) {
        screens.clear();
        File file = new File(plugin.getDataFolder(), "ban.yml");
        if (!file.exists()) {
            plugin.saveResource("ban.yml", false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        var screensSec = cfg.getConfigurationSection("ban_screens");
        if (screensSec == null) {
            logger.warning("[BanScreen] ban.yml has no ban_screens section.");
            return;
        }
        for (String key : screensSec.getKeys(false)) {
            var sec = screensSec.getConfigurationSection(key);
            if (sec == null) continue;
            screens.put(key, new BanScreenConfig(
                    sec.getString("title"),
                    sec.getString("message")));
        }
        logger.info("[BanScreen] Loaded " + screens.size() + " ban screen(s).");
    }

    /**
     * Kicks a player using the named ban screen.
     *
     * @param screenKey key in ban.yml, or null/"" for "default"
     */
    public void kick(Player player, String screenKey, String reason,
                     long expiryTime, String bannedBy) {
        String key = (screenKey == null || screenKey.isBlank()) ? "default" : screenKey;
        BanScreenConfig cfg = screens.getOrDefault(key, screens.getOrDefault("default", FALLBACK));

        String expiry = expiryTime < 0 ? "Permanent"
                : new SimpleDateFormat("yyyy-MM-dd HH:mm z").format(new Date(expiryTime));

        String filled = cfg.title + "\n" + cfg.message;
        filled = filled
                .replace("%player%", player.getName())
                .replace("%reason%", reason == null ? "N/A" : reason)
                .replace("%expiry%", expiry)
                .replace("%banned_by%", bannedBy == null ? "VoicechatModerator" : bannedBy);

        Component kickMsg = MM.deserialize(filled);
        player.kick(kickMsg);
    }
}

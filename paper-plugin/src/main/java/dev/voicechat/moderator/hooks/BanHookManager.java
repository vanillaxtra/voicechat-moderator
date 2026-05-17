package dev.voicechat.moderator.hooks;

import dev.voicechat.moderator.VoicechatModeratorPlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;

import java.util.logging.Logger;

/**
 * Routes ban commands to AdvancedBanX, LiteBans, or vanilla /ban.
 * Must be called on the main server thread.
 */
public final class BanHookManager {

    public enum Provider { ADVANCEDBANX, LITEBANS, VANILLA }

    private final Provider provider;
    private final String customBanCommand;
    private final String customTempBanCommand;
    private final Logger logger;

    public BanHookManager(VoicechatModeratorPlugin plugin) {
        this.logger = plugin.getLogger();
        FileConfiguration cfg = plugin.getConfig();

        String providerCfg = cfg.getString("ban_hooks.provider", "auto");
        customBanCommand     = cfg.getString("ban_hooks.custom_ban_command", "").strip();
        customTempBanCommand = cfg.getString("ban_hooks.custom_tempban_command", "").strip();

        if ("auto".equalsIgnoreCase(providerCfg)) {
            PluginManager pm = Bukkit.getPluginManager();
            if (pm.isPluginEnabled("AdvancedBan")) {
                provider = Provider.ADVANCEDBANX;
            } else if (pm.isPluginEnabled("LiteBans")) {
                provider = Provider.LITEBANS;
            } else {
                provider = Provider.VANILLA;
            }
        } else {
            provider = switch (providerCfg.toLowerCase()) {
                case "advancedbanx", "advancedban" -> Provider.ADVANCEDBANX;
                case "litebans" -> Provider.LITEBANS;
                default -> Provider.VANILLA;
            };
        }
        logger.info("[BanHook] Using ban provider: " + provider);
    }

    /**
     * Issues a ban for the given player.
     *
     * @param player     target player (may be null if offline)
     * @param playerName target name (used when player is null)
     * @param reason     ban reason
     * @param duration   duration string (e.g. "7d") or empty for permanent
     */
    public void ban(Player player, String playerName, String reason, String duration) {
        String name = player != null ? player.getName() : playerName;
        boolean temp = duration != null && !duration.isBlank();

        String command;
        if (temp && !customTempBanCommand.isBlank()) {
            command = customTempBanCommand
                    .replace("%player%", name)
                    .replace("%duration%", duration)
                    .replace("%reason%", reason);
        } else if (!temp && !customBanCommand.isBlank()) {
            command = customBanCommand
                    .replace("%player%", name)
                    .replace("%reason%", reason);
        } else {
            command = buildDefaultCommand(name, reason, duration, temp);
        }

        logger.info("[BanHook] Dispatching: " + command);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    private String buildDefaultCommand(String name, String reason, String duration, boolean temp) {
        return switch (provider) {
            case ADVANCEDBANX -> temp
                    ? "tempban " + name + " " + duration + " " + reason
                    : "ban " + name + " " + reason;
            case LITEBANS -> temp
                    ? "tempban " + name + " " + duration + " " + reason
                    : "ban " + name + " " + reason;
            case VANILLA -> "ban " + name + " " + reason;
        };
    }

    public Provider getProvider() {
        return provider;
    }
}

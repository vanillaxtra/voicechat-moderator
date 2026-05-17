package dev.voicechat.moderator.placeholder;

import dev.voicechat.moderator.VoicechatModeratorPlugin;
import dev.voicechat.moderator.database.BanRecord;
import dev.voicechat.moderator.database.DatabaseManager;
import dev.voicechat.moderator.database.MuteRecord;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * PlaceholderAPI expansion for VoicechatModerator.
 *
 * Available placeholders:
 *   %vcm_is_muted%         → true / false
 *   %vcm_mute_remaining%   → "1h 23m" | "Permanent" | "Not muted"
 *   %vcm_mute_reason%      → reason string or empty
 *   %vcm_is_banned%        → true / false
 *   %vcm_ban_remaining%    → "1h 23m" | "Permanent" | "Not banned"
 *   %vcm_ban_reason%       → reason string or empty
 */
public final class VcmPlaceholderExpansion extends PlaceholderExpansion {

    private final VoicechatModeratorPlugin plugin;

    public VcmPlaceholderExpansion(VoicechatModeratorPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public @NotNull String getIdentifier() { return "vcm"; }
    @Override public @NotNull String getAuthor()     { return "VoicechatModerator"; }
    @Override public @NotNull String getVersion()    { return plugin.getDescription().getVersion(); }
    @Override public boolean persist()               { return true; }
    @Override public boolean canRegister()           { return true; }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "";
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null) return "";

        return switch (params.toLowerCase()) {
            case "is_muted" -> {
                Optional<MuteRecord> mute = db.getActiveMute(player.getUniqueId());
                yield mute.isPresent() ? "true" : "false";
            }
            case "mute_remaining" -> {
                Optional<MuteRecord> mute = db.getActiveMute(player.getUniqueId());
                yield mute.map(r -> DatabaseManager.formatRemaining(r.expiryTime))
                          .orElse("Not muted");
            }
            case "mute_reason" -> {
                Optional<MuteRecord> mute = db.getActiveMute(player.getUniqueId());
                yield mute.map(r -> r.reason == null ? "" : r.reason).orElse("");
            }
            case "is_banned" -> {
                Optional<BanRecord> ban = db.getActiveBan(player.getUniqueId());
                yield ban.isPresent() ? "true" : "false";
            }
            case "ban_remaining" -> {
                Optional<BanRecord> ban = db.getActiveBan(player.getUniqueId());
                yield ban.map(r -> DatabaseManager.formatRemaining(r.expiryTime))
                         .orElse("Not banned");
            }
            case "ban_reason" -> {
                Optional<BanRecord> ban = db.getActiveBan(player.getUniqueId());
                yield ban.map(r -> r.reason == null ? "" : r.reason).orElse("");
            }
            default -> null;
        };
    }
}

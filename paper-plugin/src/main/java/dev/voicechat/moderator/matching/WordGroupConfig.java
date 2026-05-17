package dev.voicechat.moderator.matching;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Collections;
import java.util.List;

/** Immutable snapshot of one word-group entry from config.yml. */
public final class WordGroupConfig {

    public enum MatchMode { WORD_BOUNDARY, CONTAINS }

    public final String id;
    public final boolean enabled;
    public final MatchMode matchMode;
    public final int cooldownSeconds;
    public final List<String> words;        // inline words
    public final List<String> wordListUrls; // URLs to download
    public final List<String> commands;
    public final boolean muteEnabled;
    public final int muteDurationSeconds;
    public final String webhookKey;

    // Ban fields
    public final boolean banEnabled;
    public final String banReason;
    public final String banScreenKey;
    public final String banDuration; // e.g. "7d", "" = permanent

    private WordGroupConfig(
            String id, boolean enabled, MatchMode matchMode, int cooldownSeconds,
            List<String> words, List<String> wordListUrls, List<String> commands,
            boolean muteEnabled, int muteDurationSeconds, String webhookKey,
            boolean banEnabled, String banReason, String banScreenKey, String banDuration) {
        this.id = id;
        this.enabled = enabled;
        this.matchMode = matchMode;
        this.cooldownSeconds = cooldownSeconds;
        this.words = Collections.unmodifiableList(words);
        this.wordListUrls = Collections.unmodifiableList(wordListUrls);
        this.commands = Collections.unmodifiableList(commands);
        this.muteEnabled = muteEnabled;
        this.muteDurationSeconds = muteDurationSeconds;
        this.webhookKey = webhookKey;
        this.banEnabled = banEnabled;
        this.banReason = banReason;
        this.banScreenKey = banScreenKey;
        this.banDuration = banDuration;
    }

    public static WordGroupConfig from(String id, ConfigurationSection sec) {
        boolean enabled = sec.getBoolean("enabled", false);

        MatchMode mode;
        try {
            mode = MatchMode.valueOf(sec.getString("match_mode", "WORD_BOUNDARY").toUpperCase());
        } catch (IllegalArgumentException e) {
            mode = MatchMode.WORD_BOUNDARY;
        }

        int cooldown = sec.getInt("cooldown_seconds", 60);

        List<String> words = new java.util.ArrayList<>(sec.getStringList("words"));
        List<String> wordListUrls = sec.getStringList("word_list_urls");

        List<String> cmds = new java.util.ArrayList<>(sec.getStringList("run_commands_as_console"));
        cmds.removeIf(s -> s == null || s.isBlank());

        boolean muteEnabled = false;
        int muteDuration = 300;
        ConfigurationSection muteSec = sec.getConfigurationSection("voice_mute");
        if (muteSec != null) {
            muteEnabled = muteSec.getBoolean("enabled", false);
            muteDuration = muteSec.getInt("duration_seconds", 300);
        }

        String webhookKey = "";
        ConfigurationSection webhookSec = sec.getConfigurationSection("webhook");
        if (webhookSec != null) {
            webhookKey = webhookSec.getString("key", "");
        }

        boolean banEnabled = false;
        String banReason = "Voice chat violation: %word%";
        String banScreenKey = "default";
        String banDuration = "";
        ConfigurationSection banSec = sec.getConfigurationSection("ban");
        if (banSec != null) {
            banEnabled = banSec.getBoolean("enabled", false);
            banReason = banSec.getString("reason", "Voice chat violation: %word%");
            banScreenKey = banSec.getString("ban_screen_key", "default");
            banDuration = banSec.getString("duration", "");
        }

        return new WordGroupConfig(id, enabled, mode, cooldown,
                words, wordListUrls, cmds, muteEnabled, muteDuration,
                webhookKey == null ? "" : webhookKey,
                banEnabled, banReason,
                banScreenKey == null ? "default" : banScreenKey,
                banDuration == null ? "" : banDuration);
    }
}

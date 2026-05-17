package dev.voicechat.moderator.hooks;

/** Parsed ban screen definition from ban.yml. */
public final class BanScreenConfig {

    public final String title;
    public final String message;

    public BanScreenConfig(String title, String message) {
        this.title = title != null ? title : "<red>You are banned";
        this.message = message != null ? message : "";
    }
}

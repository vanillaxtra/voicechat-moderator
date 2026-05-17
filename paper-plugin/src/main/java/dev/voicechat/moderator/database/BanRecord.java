package dev.voicechat.moderator.database;

import java.util.UUID;

/** Immutable snapshot of a vcm_bans row. */
public final class BanRecord {

    public final int     id;
    public final UUID    uuid;
    public final String  playerName;
    public final String  reason;
    public final String  bannedBy;
    public final long    banTime;    // epoch millis
    public final long    expiryTime; // epoch millis, -1 = permanent
    public final boolean active;

    public BanRecord(int id, UUID uuid, String playerName, String reason,
                     String bannedBy, long banTime, long expiryTime, boolean active) {
        this.id = id;
        this.uuid = uuid;
        this.playerName = playerName;
        this.reason = reason;
        this.bannedBy = bannedBy;
        this.banTime = banTime;
        this.expiryTime = expiryTime;
        this.active = active;
    }

    public boolean isPermanent() {
        return expiryTime < 0;
    }

    public boolean isExpired() {
        return !isPermanent() && System.currentTimeMillis() > expiryTime;
    }
}

package dev.voicechat.moderator.database;

import java.util.UUID;

/** Immutable snapshot of a vcm_mutes row. */
public final class MuteRecord {

    public final int     id;
    public final UUID    uuid;
    public final String  playerName;
    public final String  reason;
    public final String  mutedBy;
    public final long    muteTime;   // epoch millis
    public final long    expiryTime; // epoch millis, -1 = permanent
    public final boolean active;

    public MuteRecord(int id, UUID uuid, String playerName, String reason,
                      String mutedBy, long muteTime, long expiryTime, boolean active) {
        this.id = id;
        this.uuid = uuid;
        this.playerName = playerName;
        this.reason = reason;
        this.mutedBy = mutedBy;
        this.muteTime = muteTime;
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

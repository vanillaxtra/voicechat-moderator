package dev.voicechat.moderator.matching;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe cooldown tracker keyed on (player UUID, group id). */
public final class CooldownTracker {

    // key: "uuid:groupId" → expiry epoch millis
    private final Map<String, Long> expiry = new ConcurrentHashMap<>();

    public boolean isOnCooldown(UUID player, String groupId) {
        Long exp = expiry.get(key(player, groupId));
        return exp != null && System.currentTimeMillis() < exp;
    }

    public void record(UUID player, String groupId, int durationSeconds) {
        expiry.put(key(player, groupId), System.currentTimeMillis() + durationSeconds * 1000L);
    }

    /** Clear all cooldowns for a player (e.g. on disconnect). */
    public void clearPlayer(UUID player) {
        String prefix = player.toString() + ":";
        expiry.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** Clear all state. */
    public void clear() {
        expiry.clear();
    }

    private static String key(UUID player, String groupId) {
        return player.toString() + ":" + groupId;
    }
}

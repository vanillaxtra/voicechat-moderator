package dev.voicechat.moderator.mute;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.node.types.PermissionNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Isolated class that holds all LuckPerms API references.
 * Only instantiated when LuckPerms is confirmed to be present,
 * preventing NoClassDefFoundError on servers without LP.
 */
final class LuckPermsHook {

    private static final String VOICECHAT_SPEAK = "voicechat.speak";

    private final LuckPerms lp;

    LuckPermsHook(LuckPerms lp) {
        this.lp = lp;
    }

    void applyNode(UUID uuid, long durationMs) {
        long expiry = Instant.now().getEpochSecond() + durationMs / 1000;
        PermissionNode node = PermissionNode.builder(VOICECHAT_SPEAK)
                .value(false)
                .expiry(expiry)
                .build();
        lp.getUserManager().modifyUser(uuid, user -> {
            user.data().clear(n -> n.getKey().equals(VOICECHAT_SPEAK));
            user.data().add(node);
        });
    }

    void removeNode(UUID uuid) {
        lp.getUserManager().modifyUser(uuid, user ->
                user.data().clear(n -> n.getKey().equals(VOICECHAT_SPEAK)));
    }
}

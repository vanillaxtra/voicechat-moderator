package dev.voicechat.moderator.events;

import dev.voicechat.moderator.voice.VoicechatAddon;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Flushes any buffered audio and releases the per-player OpusDecoder when a
 * player disconnects, so no audio is silently dropped and no native memory leaks.
 */
public class PlayerQuitListener implements Listener {

    private final VoicechatAddon addon;

    public PlayerQuitListener(VoicechatAddon addon) {
        this.addon = addon;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        addon.flushAndCleanup(
                event.getPlayer().getUniqueId(),
                event.getPlayer().getName()
        );
    }
}

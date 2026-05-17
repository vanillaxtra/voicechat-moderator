package dev.voicechat.moderator.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Scheduler abstraction that works on both Paper and Folia.
 *
 * On Folia the global region scheduler is used for tasks that are not
 * tied to a specific region (e.g. tasks dispatched from network threads
 * like the SVC Netty thread or the Whisper WebSocket callback thread).
 */
public final class FoliaUtil {

    /** True if the server is running Folia. */
    public static final boolean FOLIA;

    static {
        boolean f;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            f = true;
        } catch (ClassNotFoundException e) {
            f = false;
        }
        FOLIA = f;
    }

    private FoliaUtil() {}

    /**
     * Run {@code task} on the main thread (Paper) or the global region
     * thread (Folia), as soon as possible.
     */
    public static void runOnMain(Plugin plugin, Runnable task) {
        if (FOLIA) {
            Bukkit.getGlobalRegionScheduler().run(plugin, scheduledTask -> task.run());
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * Run {@code task} after {@code delayTicks} ticks on the main/global thread.
     */
    public static void runLater(Plugin plugin, Runnable task, long delayTicks) {
        if (FOLIA) {
            Bukkit.getGlobalRegionScheduler().runDelayed(
                    plugin, scheduledTask -> task.run(), delayTicks);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        }
    }
}

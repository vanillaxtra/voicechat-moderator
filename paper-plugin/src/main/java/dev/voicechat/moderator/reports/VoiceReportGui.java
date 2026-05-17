package dev.voicechat.moderator.reports;

import dev.voicechat.moderator.VoicechatModeratorPlugin;
import dev.voicechat.moderator.voice.TranscriptBuffer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;
import java.util.UUID;

/**
 * 3-row chest GUI for filing a voice report.
 *
 * Layout (27 slots, 0-indexed):
 *   Row 0 (slots 0-8):   glass pane border + player skull at slot 4
 *   Row 1 (slots 9-17):  category buttons at slots 10, 12, 14, 16 + cancel at 9
 *   Row 2 (slots 18-26): category button at slot 22 (OTHER) + glass pane fill
 */
public final class VoiceReportGui implements Listener {

    private static final Component GLASS_NAME = Component.empty();
    private static final Material  GLASS      = Material.GRAY_STAINED_GLASS_PANE;

    private final VoicechatModeratorPlugin plugin;
    private final Player reporter;
    private final UUID   targetUuid;
    private final String targetName;
    private final String transcriptSnapshot;
    private final Inventory inv;

    private boolean submitted = false;

    public VoiceReportGui(VoicechatModeratorPlugin plugin,
                          Player reporter,
                          UUID targetUuid, String targetName,
                          TranscriptBuffer buffer) {
        this.plugin    = plugin;
        this.reporter  = reporter;
        this.targetUuid = targetUuid;
        this.targetName = targetName;

        int minutes = plugin.getConfig().getInt("voice_reports.transcript_minutes", 10);
        this.transcriptSnapshot = buffer != null ? buffer.formatForReport(minutes)
                                                 : "(no transcript available)";

        this.inv = Bukkit.createInventory(null, 27,
                Component.text("Report — " + targetName, NamedTextColor.DARK_RED)
                         .decoration(TextDecoration.ITALIC, false));

        populate();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /** Opens the inventory for the reporter player. */
    public void open() {
        reporter.openInventory(inv);
    }

    // ── Inventory population ──────────────────────────────────────────────────

    private void populate() {
        // Fill border with glass
        ItemStack glass = makeItem(GLASS, GLASS_NAME, List.of());
        for (int i = 0; i < 27; i++) {
            inv.setItem(i, glass);
        }

        // Player skull at slot 4
        inv.setItem(4, makeSkull(targetName,
                Component.text(targetName, NamedTextColor.YELLOW)
                         .decoration(TextDecoration.ITALIC, false),
                List.of(Component.text("Click a category below to report.", NamedTextColor.GRAY)
                                 .decoration(TextDecoration.ITALIC, false))));

        // Category buttons
        for (ReportCategory cat : ReportCategory.values()) {
            inv.setItem(cat.slot, makeCategoryItem(cat));
        }

        // Cancel button at slot 18
        inv.setItem(18, makeItem(Material.BARRIER,
                Component.text("Cancel", NamedTextColor.RED)
                         .decoration(TextDecoration.ITALIC, false),
                List.of(Component.text("Close without reporting.", NamedTextColor.GRAY)
                                 .decoration(TextDecoration.ITALIC, false))));
    }

    private ItemStack makeCategoryItem(ReportCategory cat) {
        return makeItem(cat.material,
                Component.text(cat.displayName, NamedTextColor.WHITE)
                         .decoration(TextDecoration.BOLD, true)
                         .decoration(TextDecoration.ITALIC, false),
                List.of(Component.text(cat.description, NamedTextColor.GRAY)
                                 .decoration(TextDecoration.ITALIC, false),
                        Component.empty(),
                        Component.text("Click to submit report.", NamedTextColor.GREEN)
                                 .decoration(TextDecoration.ITALIC, false)));
    }

    private static ItemStack makeItem(Material mat, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    @SuppressWarnings("deprecation")
    private static ItemStack makeSkull(String playerName, Component name, List<Component> lore) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(playerName));
        meta.displayName(name);
        meta.lore(lore);
        skull.setItemMeta(meta);
        return skull;
    }

    // ── Event handlers ────────────────────────────────────────────────────────

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!event.getInventory().equals(inv)) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player clicker)) return;
        if (!clicker.equals(reporter)) return;

        int slot = event.getRawSlot();

        // Cancel button
        if (slot == 18) {
            reporter.closeInventory();
            return;
        }

        for (ReportCategory cat : ReportCategory.values()) {
            if (cat.slot == slot) {
                submit(cat);
                reporter.closeInventory();
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!event.getInventory().equals(inv)) return;
        HandlerList.unregisterAll(this);
    }

    // ── Submission ────────────────────────────────────────────────────────────

    private void submit(ReportCategory category) {
        if (submitted) return;
        submitted = true;

        plugin.getDatabaseManager().addReport(
                reporter.getUniqueId(), reporter.getName(),
                targetUuid, targetName,
                category.name(), transcriptSnapshot);

        reporter.sendMessage(
                Component.text("Report submitted against ", NamedTextColor.GREEN)
                         .append(Component.text(targetName, NamedTextColor.YELLOW))
                         .append(Component.text(" for: " + category.displayName + ".", NamedTextColor.GREEN))
                         .decoration(TextDecoration.ITALIC, false));

        if (plugin.getConfig().getBoolean("voice_reports.notify_staff", true)) {
            notifyStaff(category);
        }
    }

    private void notifyStaff(ReportCategory category) {
        Component msg = Component.text("[VCM] ", NamedTextColor.RED)
                .append(Component.text(reporter.getName(), NamedTextColor.YELLOW))
                .append(Component.text(" reported ", NamedTextColor.GRAY))
                .append(Component.text(targetName, NamedTextColor.YELLOW))
                .append(Component.text(" for ", NamedTextColor.GRAY))
                .append(Component.text(category.displayName, NamedTextColor.WHITE))
                .append(Component.text(". Use /vcm reviews to view.", NamedTextColor.GRAY))
                .decoration(TextDecoration.ITALIC, false);

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isOp() || p.hasPermission("voicechatmoderator.review")) {
                p.sendMessage(msg);
            }
        }
    }
}

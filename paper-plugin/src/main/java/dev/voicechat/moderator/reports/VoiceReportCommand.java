package dev.voicechat.moderator.reports;

import dev.voicechat.moderator.VoicechatModeratorPlugin;
import dev.voicechat.moderator.database.DatabaseManager;
import dev.voicechat.moderator.voice.TranscriptBuffer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Handles the /reportvoice command (player-facing) and the
 * /vcm reviews sub-command (staff-facing, delegated from VoicechatModeratorPlugin).
 */
public final class VoiceReportCommand implements CommandExecutor, TabCompleter {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final VoicechatModeratorPlugin plugin;

    public VoiceReportCommand(VoicechatModeratorPlugin plugin) {
        this.plugin = plugin;
    }

    // ── /reportvoice <player> ─────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player reporter)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }

        if (!reporter.hasPermission("voicechatmoderator.report")) {
            reporter.sendMessage("§cYou do not have permission to file voice reports.");
            return true;
        }

        if (args.length == 0) {
            reporter.sendMessage("§eUsage: /reportvoice <player>");
            return true;
        }

        String targetName = args[0];

        // Resolve UUID — check online players first, then OfflinePlayer
        UUID targetUuid = null;
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.getName().equalsIgnoreCase(targetName)) {
                targetUuid = p.getUniqueId();
                targetName = p.getName();
                break;
            }
        }
        if (targetUuid == null) {
            reporter.sendMessage(Component.text(targetName + " is not online or has no recent voice activity.", NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false));
            return true;
        }

        // Check transcript buffer has something
        TranscriptBuffer buf = plugin.getAddon() != null
                ? plugin.getAddon().getTranscriptBuffer(targetUuid)
                : null;

        int minutes = plugin.getConfig().getInt("voice_reports.transcript_minutes", 10);
        if (buf == null || !buf.hasActivity(minutes)) {
            reporter.sendMessage(Component.text(targetName
                    + " has no recent voice activity to report (last " + minutes + " min).", NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false));
            return true;
        }

        if (reporter.getUniqueId().equals(targetUuid)) {
            reporter.sendMessage(Component.text("You cannot report yourself.", NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false));
            return true;
        }

        new VoiceReportGui(plugin, reporter, targetUuid, targetName, buf).open();
        return true;
    }

    // ── /vcm reviews (called from VoicechatModeratorPlugin.onCommand) ─────────

    public void handleReviews(CommandSender sender) {
        if (!sender.hasPermission("voicechatmoderator.review")) {
            sender.sendMessage("§cNo permission.");
            return;
        }

        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null) {
            sender.sendMessage("§cDatabase not available.");
            return;
        }

        List<ReportRecord> reports = db.getPendingReports();
        if (reports.isEmpty()) {
            sender.sendMessage(Component.text("No unreviewed voice reports.", NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false));
            return;
        }

        sender.sendMessage(Component.text("── Pending Voice Reports ──", NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false));

        for (ReportRecord r : reports) {
            String time = FMT.format(Instant.ofEpochMilli(r.timestamp));
            Component line = Component.text("[" + time + "] ", NamedTextColor.GRAY)
                    .append(Component.text(r.reporterName, NamedTextColor.YELLOW))
                    .append(Component.text(" → ", NamedTextColor.WHITE))
                    .append(Component.text(r.targetName, NamedTextColor.RED))
                    .append(Component.text(" (" + r.category + ") ", NamedTextColor.AQUA))
                    .append(Component.text("[View]", NamedTextColor.GREEN)
                            .clickEvent(ClickEvent.runCommand("/vcm viewreport " + r.id))
                            .decoration(TextDecoration.UNDERLINED, true))
                    .append(Component.text(" ", NamedTextColor.WHITE))
                    .append(Component.text("[Reviewed]", NamedTextColor.GOLD)
                            .clickEvent(ClickEvent.runCommand("/vcm markreviewd " + r.id))
                            .decoration(TextDecoration.UNDERLINED, true))
                    .decoration(TextDecoration.ITALIC, false);
            sender.sendMessage(line);
        }
    }

    /** Shows the full transcript for a report. */
    public void handleViewReport(CommandSender sender, int reportId) {
        if (!sender.hasPermission("voicechatmoderator.review")) {
            sender.sendMessage("§cNo permission.");
            return;
        }
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null) { sender.sendMessage("§cDatabase not available."); return; }

        db.getPendingReports().stream()
                .filter(r -> r.id == reportId)
                .findFirst()
                .ifPresentOrElse(r -> {
                    sender.sendMessage(Component.text("── Report #" + r.id + " ──", NamedTextColor.GOLD)
                            .decoration(TextDecoration.BOLD, true)
                            .decoration(TextDecoration.ITALIC, false));
                    sender.sendMessage(Component.text("Target: " + r.targetName, NamedTextColor.YELLOW)
                            .decoration(TextDecoration.ITALIC, false));
                    sender.sendMessage(Component.text("Category: " + r.category, NamedTextColor.AQUA)
                            .decoration(TextDecoration.ITALIC, false));
                    sender.sendMessage(Component.text("Filed by: " + r.reporterName, NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false));
                    sender.sendMessage(Component.text("Transcript:", NamedTextColor.WHITE)
                            .decoration(TextDecoration.ITALIC, false));
                    if (r.transcript != null) {
                        for (String line : r.transcript.split("\n")) {
                            sender.sendMessage(Component.text("  " + line, NamedTextColor.GRAY)
                                    .decoration(TextDecoration.ITALIC, false));
                        }
                    }
                }, () -> sender.sendMessage("§cReport #" + reportId + " not found."));
    }

    /** Marks a report as reviewed. */
    public void handleMarkReviewed(CommandSender sender, int reportId) {
        if (!sender.hasPermission("voicechatmoderator.review")) {
            sender.sendMessage("§cNo permission.");
            return;
        }
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null) { sender.sendMessage("§cDatabase not available."); return; }
        db.markReviewed(reportId);
        sender.sendMessage(Component.text("Report #" + reportId + " marked as reviewed.", NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (args.length == 1) {
            return plugin.getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}

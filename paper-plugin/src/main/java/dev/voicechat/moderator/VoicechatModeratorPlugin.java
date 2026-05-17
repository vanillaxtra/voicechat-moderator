package dev.voicechat.moderator;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import dev.voicechat.moderator.database.DatabaseManager;
import dev.voicechat.moderator.discord.DiscordWebhookClient;
import dev.voicechat.moderator.events.PlayerQuitListener;
import dev.voicechat.moderator.hooks.BanHookManager;
import dev.voicechat.moderator.hooks.BanScreenLoader;
import dev.voicechat.moderator.matching.WordListDownloader;
import dev.voicechat.moderator.mute.MuteLadderService;
import dev.voicechat.moderator.notify.MuteNotifier;
import dev.voicechat.moderator.placeholder.VcmPlaceholderExpansion;
import dev.voicechat.moderator.reports.VoiceReportCommand;
import dev.voicechat.moderator.voice.VoicechatAddon;
import dev.voicechat.moderator.whisper.WhisperWsClient;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

public class VoicechatModeratorPlugin extends JavaPlugin {

    private VoicechatAddon     addon;
    private WhisperWsClient    wsClient;
    private VoiceReportCommand reportCommand;

    private DatabaseManager    databaseManager;
    private WordListDownloader wordListDownloader;
    private BanHookManager     banHookManager;
    private BanScreenLoader    banScreenLoader;
    private MuteNotifier       muteNotifier;
    private MuteLadderService  muteLadderService;

    // key → validated client; rebuilt on each reload
    private final Map<String, DiscordWebhookClient> webhookClients = new HashMap<>();

    // Validation / IO tasks run off the main thread
    private final ExecutorService bgExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vc-plugin-bg");
        t.setDaemon(true);
        return t;
    });

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveDefaultFile("ban.yml");
        saveDefaultFile("mute.yml");

        // Database
        try {
            databaseManager = new DatabaseManager(this);
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to initialise database — plugin cannot start.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Word list downloader + helpers
        wordListDownloader = new WordListDownloader(getDataFolder(), getLogger());
        banHookManager     = new BanHookManager(this);
        banScreenLoader    = new BanScreenLoader(this);
        muteNotifier       = new MuteNotifier(this);
        muteLadderService  = new MuteLadderService(this);

        // WebSocket client — connect before registering the SVC addon
        wsClient = new WhisperWsClient(this);
        wsClient.start();

        // Simple Voice Chat
        BukkitVoicechatService service =
                getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) {
            getLogger().severe("Simple Voice Chat is not loaded. Install it and restart.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        addon = new VoicechatAddon(this, wsClient);
        service.registerPlugin(addon);
        getServer().getPluginManager().registerEvents(new PlayerQuitListener(addon), this);

        // Initial matcher + webhook build (async)
        bgExec.submit(() -> {
            addon.reloadMatcher(wordListDownloader);
            rebuildWebhookClients();
        });

        // /reportvoice command
        reportCommand = new VoiceReportCommand(this);
        var reportvoiceCmd = getCommand("reportvoice");
        if (reportvoiceCmd != null) {
            reportvoiceCmd.setExecutor(reportCommand);
            reportvoiceCmd.setTabCompleter(reportCommand);
        }

        // PlaceholderAPI — soft-depend
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new VcmPlaceholderExpansion(this).register();
            getLogger().info("[PAPI] PlaceholderAPI expansion registered.");
        }

        getLogger().info("VoicechatModerator enabled.");
    }

    @Override
    public void onDisable() {
        if (addon != null) addon.shutdown();
        if (wsClient != null) wsClient.stop();
        if (databaseManager != null) databaseManager.close();
        webhookClients.clear();
        bgExec.shutdownNow();
    }

    // ── /vcm commands ─────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("voicechatmoderator")) return false;

        String sub = args.length > 0 ? args[0].toLowerCase() : "help";

        switch (sub) {
            case "reload" -> {
                if (!sender.hasPermission("voicechatmoderator.reload")) {
                    sender.sendMessage("§cNo permission."); return true;
                }
                reloadConfig();
                banScreenLoader.reload(this);
                muteNotifier.reload(this);
                bgExec.submit(() -> {
                    addon.reloadMatcher(wordListDownloader);
                    rebuildWebhookClients();
                });
                sender.sendMessage("§aVoicechatModerator reloaded.");
            }
            case "disable" -> {
                if (!sender.hasPermission("voicechatmoderator.toggle")) {
                    sender.sendMessage("§cNo permission."); return true;
                }
                if (!addon.isModerationActive()) {
                    sender.sendMessage("§eVoicechatModerator is already disabled."); return true;
                }
                addon.setModerationActive(false);
                getLogger().info("VoicechatModerator disabled by " + sender.getName());
                sender.sendMessage("§cVoicechatModerator disabled. Transcription paused.");
            }
            case "enable" -> {
                if (!sender.hasPermission("voicechatmoderator.toggle")) {
                    sender.sendMessage("§cNo permission."); return true;
                }
                if (addon.isModerationActive()) {
                    sender.sendMessage("§eVoicechatModerator is already enabled."); return true;
                }
                addon.setModerationActive(true);
                getLogger().info("VoicechatModerator enabled by " + sender.getName());
                sender.sendMessage("§aVoicechatModerator enabled.");
            }
            case "status" -> {
                if (!sender.hasPermission("voicechatmoderator.reload")) {
                    sender.sendMessage("§cNo permission."); return true;
                }
                boolean active = addon.isModerationActive();
                boolean connected = wsClient != null && wsClient.isConnected();
                sender.sendMessage("§eVoicechatModerator status: "
                        + (active ? "§aENABLED" : "§cDISABLED")
                        + "§e | WebSocket: " + (connected ? "§aCONNECTED" : "§cDISCONNECTED"));
            }
            case "reviews" -> {
                if (reportCommand != null) reportCommand.handleReviews(sender);
            }
            case "viewreport" -> {
                if (args.length < 2) { sender.sendMessage("§eUsage: /vcm viewreport <id>"); return true; }
                try { if (reportCommand != null) reportCommand.handleViewReport(sender, Integer.parseInt(args[1])); }
                catch (NumberFormatException e) { sender.sendMessage("§cInvalid report ID."); }
            }
            case "markreviewd", "markreviewed" -> {
                if (args.length < 2) { sender.sendMessage("§eUsage: /vcm markreviewed <id>"); return true; }
                try { if (reportCommand != null) reportCommand.handleMarkReviewed(sender, Integer.parseInt(args[1])); }
                catch (NumberFormatException e) { sender.sendMessage("§cInvalid report ID."); }
            }
            default -> sender.sendMessage(
                    "§eUsage: /vcm <reload|enable|disable|status|reviews|viewreport|markreviewed>");
        }
        return true;
    }

    // ── Reload helpers ────────────────────────────────────────────────────────

    private void rebuildWebhookClients() {
        synchronized (webhookClients) { webhookClients.clear(); }

        if (!getConfig().getBoolean("discord.enabled", false)) {
            getLogger().info("[Discord] Disabled in config — skipping webhook validation.");
            return;
        }

        var sec = getConfig().getConfigurationSection("discord.webhooks");
        if (sec == null) return;

        for (String key : sec.getKeys(false)) {
            String url      = getConfig().getString("discord.webhooks." + key + ".url", "");
            String username = getConfig().getString("discord.webhooks." + key + ".username", "VoiceModerator");
            DiscordWebhookClient client = new DiscordWebhookClient(key, url, username, getLogger());
            client.validate();
            synchronized (webhookClients) { webhookClients.put(key, client); }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void saveDefaultFile(String name) {
        java.io.File f = new java.io.File(getDataFolder(), name);
        if (!f.exists()) saveResource(name, false);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public DiscordWebhookClient getWebhookClient(String key) {
        synchronized (webhookClients) { return webhookClients.get(key); }
    }

    public DatabaseManager   getDatabaseManager()  { return databaseManager;  }
    public BanHookManager    getBanHookManager()   { return banHookManager;   }
    public BanScreenLoader   getBanScreenLoader()  { return banScreenLoader;  }
    public MuteNotifier      getMuteNotifier()     { return muteNotifier;     }
    public MuteLadderService getMuteLadderService(){ return muteLadderService; }
    public VoicechatAddon    getAddon()            { return addon;            }
}

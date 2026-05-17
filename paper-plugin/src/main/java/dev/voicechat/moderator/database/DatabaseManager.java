package dev.voicechat.moderator.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.voicechat.moderator.VoicechatModeratorPlugin;

import dev.voicechat.moderator.reports.ReportRecord;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages persistent bans and mutes using SQLite (default) or MySQL.
 * Thread-safe — all public methods may be called from any thread.
 */
public final class DatabaseManager {

    private final HikariDataSource pool;
    private final Logger logger;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public DatabaseManager(VoicechatModeratorPlugin plugin) throws SQLException {
        this.logger = plugin.getLogger();
        String type = plugin.getConfig().getString("database.type", "sqlite");
        HikariConfig cfg = new HikariConfig();

        if ("mysql".equalsIgnoreCase(type)) {
            String host = plugin.getConfig().getString("database.host", "localhost");
            int    port = plugin.getConfig().getInt("database.port", 3306);
            String db   = plugin.getConfig().getString("database.database", "vcm");
            String user = plugin.getConfig().getString("database.username", "root");
            String pass = plugin.getConfig().getString("database.password", "");
            cfg.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + db
                    + "?useSSL=false&characterEncoding=utf8&autoReconnect=true");
            cfg.setUsername(user);
            cfg.setPassword(pass);
            cfg.setDriverClassName("com.mysql.cj.jdbc.Driver");
        } else {
            File dbFile = new File(plugin.getDataFolder(), "data.db");
            cfg.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
            cfg.setDriverClassName("org.sqlite.JDBC");
            cfg.setMaximumPoolSize(1); // SQLite is single-writer
        }

        cfg.setPoolName("VCM-DB");
        cfg.setConnectionTimeout(10_000);
        cfg.setMaximumPoolSize(cfg.getMaximumPoolSize() == -1 ? 5 : cfg.getMaximumPoolSize());

        pool = new HikariDataSource(cfg);
        createTables();
        logger.info("[DB] Database connected (" + type + ").");
    }

    private void createTables() throws SQLException {
        String createMutes = """
            CREATE TABLE IF NOT EXISTS vcm_mutes (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              uuid TEXT NOT NULL,
              player_name TEXT NOT NULL,
              reason TEXT,
              muted_by TEXT,
              mute_time INTEGER NOT NULL,
              expiry_time INTEGER,
              active INTEGER NOT NULL DEFAULT 1,
              mute_source TEXT NOT NULL DEFAULT 'MANUAL'
            )""";
        // Migrate: add mute_source column if upgrading from an older schema
        String alterMutes = "ALTER TABLE vcm_mutes ADD COLUMN mute_source TEXT NOT NULL DEFAULT 'MANUAL'";
        String createLadder = """
            CREATE TABLE IF NOT EXISTS vcm_mute_ladder (
              uuid TEXT PRIMARY KEY,
              current_tier INTEGER NOT NULL DEFAULT 0,
              last_strike_time INTEGER NOT NULL
            )""";
        String createBans = """
            CREATE TABLE IF NOT EXISTS vcm_bans (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              uuid TEXT NOT NULL,
              player_name TEXT NOT NULL,
              reason TEXT,
              banned_by TEXT,
              ban_time INTEGER NOT NULL,
              expiry_time INTEGER,
              active INTEGER NOT NULL DEFAULT 1
            )""";
        String createReports = """
            CREATE TABLE IF NOT EXISTS vcm_reports (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              reporter_uuid TEXT NOT NULL,
              reporter_name TEXT NOT NULL,
              target_uuid TEXT NOT NULL,
              target_name TEXT NOT NULL,
              category TEXT NOT NULL,
              transcript TEXT,
              timestamp INTEGER NOT NULL,
              reviewed INTEGER NOT NULL DEFAULT 0
            )""";
        try (Connection con = pool.getConnection();
             Statement st = con.createStatement()) {
            st.execute(createMutes);
            st.execute(createBans);
            st.execute(createReports);
            st.execute(createLadder);
            // Safe migration — ignore error if column already exists
            try { st.execute(alterMutes); } catch (SQLException ignored) {}
        }
    }

    public void close() {
        if (pool != null && !pool.isClosed()) {
            pool.close();
        }
    }

    // ── Mutes ─────────────────────────────────────────────────────────────────

    /** Mute source — AUTO_LADDER strikes escalate tiers; MANUAL bypasses them. */
    public enum MuteSource { MANUAL, AUTO_LADDER }

    /**
     * Inserts a voice mute. Deactivates any existing active mute first.
     *
     * @param expiryTime epoch millis, or -1 for permanent
     * @param source     MANUAL or AUTO_LADDER
     */
    public void addMute(UUID uuid, String playerName, String reason,
                        String mutedBy, long expiryTime, MuteSource source) {
        try (Connection con = pool.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE vcm_mutes SET active=0 WHERE uuid=? AND active=1")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO vcm_mutes (uuid,player_name,reason,muted_by,mute_time,expiry_time,active,mute_source) " +
                    "VALUES (?,?,?,?,?,?,1,?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, playerName);
                ps.setString(3, reason);
                ps.setString(4, mutedBy);
                ps.setLong(5, System.currentTimeMillis());
                if (expiryTime < 0) ps.setNull(6, Types.INTEGER);
                else ps.setLong(6, expiryTime);
                ps.setString(7, source.name());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to add mute for " + playerName, e);
        }
    }

    /** Convenience overload — MANUAL source (used by non-ladder code paths). */
    public void addMute(UUID uuid, String playerName, String reason,
                        String mutedBy, long expiryTime) {
        addMute(uuid, playerName, reason, mutedBy, expiryTime, MuteSource.MANUAL);
    }

    // ── Mute ladder ───────────────────────────────────────────────────────────

    /**
     * Returns the player's current tier (1-based), resetting to 0 if the
     * reset window has elapsed.  Returns 0 if no record exists yet.
     */
    public int getLadderTier(UUID uuid, int resetAfterDays) {
        try (Connection con = pool.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT current_tier, last_strike_time FROM vcm_mute_ladder WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return 0;
            int  tier      = rs.getInt("current_tier");
            long lastStrike = rs.getLong("last_strike_time");
            long cutoff    = System.currentTimeMillis() - (long) resetAfterDays * 86_400_000L;
            if (lastStrike < cutoff) {
                resetLadder(uuid);
                return 0;
            }
            return tier;
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to query ladder tier", e);
            return 0;
        }
    }

    /**
     * Advances the player's ladder tier by 1 and records the strike time.
     * Returns the new (post-increment) tier.
     */
    public int advanceLadderTier(UUID uuid) {
        try (Connection con = pool.getConnection()) {
            // Upsert current tier
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO vcm_mute_ladder (uuid, current_tier, last_strike_time) VALUES (?,1,?) " +
                    "ON CONFLICT(uuid) DO UPDATE SET current_tier=current_tier+1, last_strike_time=excluded.last_strike_time")) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, System.currentTimeMillis());
                ps.executeUpdate();
            }
            // Read back
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT current_tier FROM vcm_mute_ladder WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                return rs.next() ? rs.getInt(1) : 1;
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to advance ladder tier", e);
            return 1;
        }
    }

    /** Resets the player's ladder tier to 0. */
    public void resetLadder(UUID uuid) {
        try (Connection con = pool.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "DELETE FROM vcm_mute_ladder WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to reset ladder tier", e);
        }
    }

    public void removeMute(UUID uuid) {
        try (Connection con = pool.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "UPDATE vcm_mutes SET active=0 WHERE uuid=? AND active=1")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to remove mute", e);
        }
    }

    /**
     * Returns the active mute for a player, or empty if not muted.
     * Also auto-expires stale records.
     */
    public Optional<MuteRecord> getActiveMute(UUID uuid) {
        try (Connection con = pool.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT * FROM vcm_mutes WHERE uuid=? AND active=1 LIMIT 1")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();
            MuteRecord rec = muteFromRow(rs);
            if (rec.isExpired()) {
                removeMute(uuid);
                return Optional.empty();
            }
            return Optional.of(rec);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to query mute", e);
            return Optional.empty();
        }
    }

    public boolean isVoiceMuted(UUID uuid) {
        return getActiveMute(uuid).isPresent();
    }

    // ── Bans ──────────────────────────────────────────────────────────────────

    public void addBan(UUID uuid, String playerName, String reason,
                       String bannedBy, long expiryTime) {
        try (Connection con = pool.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE vcm_bans SET active=0 WHERE uuid=? AND active=1")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO vcm_bans (uuid,player_name,reason,banned_by,ban_time,expiry_time,active) " +
                    "VALUES (?,?,?,?,?,?,1)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, playerName);
                ps.setString(3, reason);
                ps.setString(4, bannedBy);
                ps.setLong(5, System.currentTimeMillis());
                if (expiryTime < 0) ps.setNull(6, Types.INTEGER);
                else ps.setLong(6, expiryTime);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to add ban for " + playerName, e);
        }
    }

    public void removeBan(UUID uuid) {
        try (Connection con = pool.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "UPDATE vcm_bans SET active=0 WHERE uuid=? AND active=1")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to remove ban", e);
        }
    }

    public Optional<BanRecord> getActiveBan(UUID uuid) {
        try (Connection con = pool.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT * FROM vcm_bans WHERE uuid=? AND active=1 LIMIT 1")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();
            BanRecord rec = banFromRow(rs);
            if (rec.isExpired()) {
                removeBan(uuid);
                return Optional.empty();
            }
            return Optional.of(rec);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to query ban", e);
            return Optional.empty();
        }
    }

    // ── Row mappers ───────────────────────────────────────────────────────────

    private MuteRecord muteFromRow(ResultSet rs) throws SQLException {
        long exp = rs.getLong("expiry_time");
        if (rs.wasNull()) exp = -1;
        return new MuteRecord(
                rs.getInt("id"),
                UUID.fromString(rs.getString("uuid")),
                rs.getString("player_name"),
                rs.getString("reason"),
                rs.getString("muted_by"),
                rs.getLong("mute_time"),
                exp,
                rs.getInt("active") == 1
        );
    }

    private BanRecord banFromRow(ResultSet rs) throws SQLException {
        long exp = rs.getLong("expiry_time");
        if (rs.wasNull()) exp = -1;
        return new BanRecord(
                rs.getInt("id"),
                UUID.fromString(rs.getString("uuid")),
                rs.getString("player_name"),
                rs.getString("reason"),
                rs.getString("banned_by"),
                rs.getLong("ban_time"),
                exp,
                rs.getInt("active") == 1
        );
    }

    // ── Duration parser ───────────────────────────────────────────────────────

    /**
     * Parses duration strings like "7d", "2h30m", "30m", "1h".
     * Returns epoch millis of expiry, or -1 for empty/null (permanent).
     */
    public static long parseExpiry(String duration) {
        if (duration == null || duration.isBlank()) return -1;
        long ms = 0;
        String d = duration.trim().toLowerCase();
        int idx;
        if ((idx = d.indexOf('d')) != -1) {
            ms += Long.parseLong(d.substring(0, idx).replaceAll("[^0-9]", "")) * 86_400_000L;
            d = d.substring(idx + 1);
        }
        if ((idx = d.indexOf('h')) != -1) {
            String num = d.substring(0, idx).replaceAll("[^0-9]", "");
            if (!num.isEmpty()) ms += Long.parseLong(num) * 3_600_000L;
            d = d.substring(idx + 1);
        }
        if ((idx = d.indexOf('m')) != -1) {
            String num = d.substring(0, idx).replaceAll("[^0-9]", "");
            if (!num.isEmpty()) ms += Long.parseLong(num) * 60_000L;
        }
        return ms == 0 ? -1 : System.currentTimeMillis() + ms;
    }

    // ── Reports ───────────────────────────────────────────────────────────────

    public void addReport(UUID reporterUuid, String reporterName,
                          UUID targetUuid,   String targetName,
                          String category,   String transcript) {
        try (Connection con = pool.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO vcm_reports "
                     + "(reporter_uuid,reporter_name,target_uuid,target_name,category,transcript,timestamp,reviewed) "
                     + "VALUES (?,?,?,?,?,?,?,0)")) {
            ps.setString(1, reporterUuid.toString());
            ps.setString(2, reporterName);
            ps.setString(3, targetUuid.toString());
            ps.setString(4, targetName);
            ps.setString(5, category);
            ps.setString(6, transcript);
            ps.setLong(7, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to save voice report", e);
        }
    }

    public List<ReportRecord> getPendingReports() {
        List<ReportRecord> results = new ArrayList<>();
        try (Connection con = pool.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT * FROM vcm_reports WHERE reviewed=0 ORDER BY timestamp DESC")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                results.add(new ReportRecord(
                        rs.getInt("id"),
                        UUID.fromString(rs.getString("reporter_uuid")),
                        rs.getString("reporter_name"),
                        UUID.fromString(rs.getString("target_uuid")),
                        rs.getString("target_name"),
                        rs.getString("category"),
                        rs.getString("transcript"),
                        rs.getLong("timestamp"),
                        false
                ));
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to query reports", e);
        }
        return results;
    }

    public void markReviewed(int id) {
        try (Connection con = pool.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "UPDATE vcm_reports SET reviewed=1 WHERE id=?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[DB] Failed to mark report reviewed", e);
        }
    }

    // ── Duration parser ───────────────────────────────────────────────────────

    /** Formats remaining time as "2h 30m" or "Permanent". */
    public static String formatRemaining(long expiryTime) {
        if (expiryTime < 0) return "Permanent";
        long remaining = expiryTime - System.currentTimeMillis();
        if (remaining <= 0) return "Expired";
        long hours = remaining / 3_600_000L;
        long mins  = (remaining % 3_600_000L) / 60_000L;
        if (hours > 0) return hours + "h " + mins + "m";
        long secs = (remaining % 60_000L) / 1_000L;
        if (mins > 0) return mins + "m " + secs + "s";
        return secs + "s";
    }
}

package dev.voicechat.moderator.matching;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Downloads word lists from URLs and caches them locally in
 * {@code plugins/VoicechatModerator/wordlists/<groupName>.txt}.
 *
 * Words are one per line; blank lines and lines starting with {@code #} are ignored.
 * The file is only downloaded once — delete the cache file to force re-download.
 */
public final class WordListDownloader {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final File cacheDir;
    private final Logger logger;

    public WordListDownloader(File pluginDataFolder, Logger logger) {
        this.cacheDir = new File(pluginDataFolder, "wordlists");
        this.logger = logger;
        cacheDir.mkdirs();
    }

    /**
     * Downloads (if not already cached) and parses the word list from {@code url}.
     *
     * @param groupName used as the cache file name
     * @param url       HTTP(S) URL to a plain-text word list
     * @return parsed words (never null, may be empty on error)
     */
    public List<String> fetchOrLoad(String groupName, String url) {
        if (url == null || url.isBlank()) return List.of();

        String safe = groupName.replaceAll("[^a-zA-Z0-9_-]", "_");
        File cacheFile = new File(cacheDir, safe + ".txt");

        if (cacheFile.exists()) {
            logger.info("[WordList] Using cached list for group '" + groupName + "': " + cacheFile.getName());
            return parseFile(cacheFile);
        }

        logger.info("[WordList] Downloading word list for group '" + groupName + "' from: " + url);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "VoicechatModerator/1.0")
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                logger.warning("[WordList] HTTP " + resp.statusCode() + " downloading word list for '"
                        + groupName + "' — no words loaded from URL.");
                return List.of();
            }
            Files.writeString(cacheFile.toPath(), resp.body());
            logger.info("[WordList] Saved word list for group '" + groupName + "' ("
                    + resp.body().lines().count() + " lines).");
            return parseLines(resp.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            logger.log(Level.WARNING, "[WordList] Failed to download word list for group '"
                    + groupName + "': " + e.getMessage());
            return List.of();
        }
    }

    private List<String> parseFile(File file) {
        try {
            return parseLines(Files.readString(file.toPath()));
        } catch (IOException e) {
            logger.warning("[WordList] Cannot read cache file " + file.getName() + ": " + e.getMessage());
            return List.of();
        }
    }

    private List<String> parseLines(String content) {
        List<String> words = new ArrayList<>();
        for (String line : content.lines().toList()) {
            String w = line.strip();
            if (!w.isEmpty() && !w.startsWith("#")) {
                words.add(w);
            }
        }
        return Collections.unmodifiableList(words);
    }
}

package dev.voicechat.moderator.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.voicechat.moderator.matching.MatchResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends moderation alerts to a Discord webhook as multipart/form-data:
 *   - embed with player skin thumbnail, matched word, and transcript
 *   - WAV audio clip attachment
 *
 * Thread-safe — all methods are stateless aside from {@link #isValid()}.
 */
public final class DiscordWebhookClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final String BOUNDARY = "VcmWebhookBoundary1234567890";
    private static final String CRLF = "\r\n";

    private final String webhookKey;
    private final String url;
    private final String username;
    private final Logger logger;
    private volatile boolean valid = false;

    public DiscordWebhookClient(String webhookKey, String url, String username, Logger logger) {
        this.webhookKey = webhookKey;
        this.url = url;
        this.username = (username != null && !username.isBlank()) ? username : "VoiceModerator";
        this.logger = logger;
    }

    public boolean isValid() {
        return valid;
    }

    /** Validates the webhook by sending a GET to the Discord API. */
    public void validate() {
        if (url == null || url.isBlank() || url.contains("YOUR_ID")) {
            logger.warning("[Discord] Webhook '" + webhookKey + "' has a placeholder URL — skipping.");
            valid = false;
            return;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(8))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                valid = true;
                String name = webhookKey;
                try {
                    JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
                    if (json.has("name")) name = json.get("name").getAsString();
                } catch (Exception ignored) {}
                logger.info("[Discord] Webhook '" + webhookKey + "' validated OK (Discord name: " + name + ")");
            } else {
                valid = false;
                logger.warning("[Discord] Webhook '" + webhookKey + "' validation failed: HTTP "
                        + resp.statusCode() + " — fix the URL and run /vcm reload.");
            }
        } catch (Exception e) {
            valid = false;
            logger.log(Level.WARNING,
                    "[Discord] Webhook '" + webhookKey + "' validation error: " + e.getMessage());
        }
    }

    /**
     * Sends the moderation alert. Blocking — run off the main thread.
     *
     * @param wav the WAV audio clip bytes to attach
     */
    public void send(
            String playerName, UUID uuid,
            MatchResult hit,
            String transcript,
            String timeStr,
            byte[] wav) {

        if (!valid) return;

        byte[] clip = (wav != null) ? wav : new byte[0];
        if (clip.length == 0) return;

        String payloadJson = buildEmbed(playerName, uuid, hit, transcript, timeStr);
        String fileName = playerName + "-" + Instant.now().getEpochSecond() + ".wav";

        try {
            byte[] body = buildMultipart(payloadJson, fileName, clip);

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                logger.warning("[Discord] Webhook '" + webhookKey + "' returned HTTP "
                        + resp.statusCode() + ": " + resp.body());
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "[Discord] Failed to send to webhook '" + webhookKey + "'", e);
        }
    }

    // ── Payload builders ─────────────────────────────────────────────────────

    private String buildEmbed(
            String playerName, UUID uuid,
            MatchResult hit, String transcript, String timeStr) {

        JsonObject embed = new JsonObject();
        embed.addProperty("title", "\uD83D\uDEA8 Moderation Alert — " + hit.group.id);
        embed.addProperty("color", 0xE74C3C);

        String snippet = transcript.length() > 900
                ? transcript.substring(0, 900) + "…"
                : transcript;
        embed.addProperty("description",
                "**Matched word:** `" + hit.matchedWord + "`\n"
                + "**Transcript:** " + snippet);

        JsonObject thumbnail = new JsonObject();
        thumbnail.addProperty("url", "https://mc-heads.net/avatar/" + playerName);
        embed.add("thumbnail", thumbnail);

        JsonArray fields = new JsonArray();
        fields.add(field("Player", playerName, true));
        fields.add(field("UUID", uuid.toString(), true));
        fields.add(field("Time", timeStr, false));
        embed.add("fields", fields);

        JsonObject footer = new JsonObject();
        footer.addProperty("text", "VoicechatModerator • clip attached as WAV");
        embed.add("footer", footer);

        JsonObject payload = new JsonObject();
        payload.addProperty("username", username);
        payload.addProperty("avatar_url", "https://mc-heads.net/avatar/" + playerName);
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        payload.add("embeds", embeds);

        return payload.toString();
    }

    private static JsonObject field(String name, String value, boolean inline) {
        JsonObject f = new JsonObject();
        f.addProperty("name", name);
        f.addProperty("value", value);
        f.addProperty("inline", inline);
        return f;
    }

    /** Builds a multipart/form-data body with payload_json + file parts. */
    private static byte[] buildMultipart(String payloadJson, String fileName, byte[] fileBytes) {
        String mimeType = "audio/wav";

        StringBuilder header = new StringBuilder();
        header.append("--").append(BOUNDARY).append(CRLF);
        header.append("Content-Disposition: form-data; name=\"payload_json\"").append(CRLF);
        header.append("Content-Type: application/json").append(CRLF);
        header.append(CRLF);
        header.append(payloadJson).append(CRLF);

        header.append("--").append(BOUNDARY).append(CRLF);
        header.append("Content-Disposition: form-data; name=\"files[0]\"; filename=\"")
              .append(fileName).append("\"").append(CRLF);
        header.append("Content-Type: ").append(mimeType).append(CRLF);
        header.append(CRLF);

        byte[] headerBytes = header.toString().getBytes(StandardCharsets.UTF_8);
        String footer = CRLF + "--" + BOUNDARY + "--" + CRLF;
        byte[] footerBytes = footer.getBytes(StandardCharsets.UTF_8);

        byte[] result = new byte[headerBytes.length + fileBytes.length + footerBytes.length];
        System.arraycopy(headerBytes, 0, result, 0, headerBytes.length);
        System.arraycopy(fileBytes, 0, result, headerBytes.length, fileBytes.length);
        System.arraycopy(footerBytes, 0, result, headerBytes.length + fileBytes.length, footerBytes.length);
        return result;
    }
}

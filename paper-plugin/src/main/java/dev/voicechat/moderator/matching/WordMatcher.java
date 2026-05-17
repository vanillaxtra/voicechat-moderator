package dev.voicechat.moderator.matching;

import org.bukkit.configuration.ConfigurationSection;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Immutable compiled snapshot of all enabled word groups.
 * Build a new instance via {@link #build(ConfigurationSection, WordListDownloader)} on every reload.
 */
public final class WordMatcher {

    private record CompiledGroup(WordGroupConfig config, List<Pattern> patterns) {}

    private final List<CompiledGroup> groups;

    private WordMatcher(List<CompiledGroup> groups) {
        this.groups = Collections.unmodifiableList(groups);
    }

    /**
     * Builds a matcher from the {@code word_groups} config section.
     * If {@code downloader} is not null, URL-based word lists are fetched (cached locally).
     */
    public static WordMatcher build(ConfigurationSection wordGroupsSection,
                                    WordListDownloader downloader) {
        List<CompiledGroup> compiled = new ArrayList<>();
        if (wordGroupsSection == null) return new WordMatcher(compiled);

        for (String key : wordGroupsSection.getKeys(false)) {
            ConfigurationSection sec = wordGroupsSection.getConfigurationSection(key);
            if (sec == null) continue;
            WordGroupConfig cfg = WordGroupConfig.from(key, sec);
            if (!cfg.enabled) continue;

            List<String> allWords = new ArrayList<>(cfg.words);
            if (downloader != null) {
                for (String url : cfg.wordListUrls) {
                    allWords.addAll(downloader.fetchOrLoad(key, url));
                }
            }
            if (allWords.isEmpty()) continue;

            List<Pattern> patterns = new ArrayList<>();
            for (String word : allWords) {
                if (word == null || word.isBlank()) continue;
                String escaped = Pattern.quote(word.toLowerCase().strip());
                String regex = cfg.matchMode == WordGroupConfig.MatchMode.WORD_BOUNDARY
                        ? "\\b" + escaped + "\\b"
                        : escaped;
                patterns.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
            }
            if (!patterns.isEmpty()) {
                compiled.add(new CompiledGroup(cfg, Collections.unmodifiableList(patterns)));
            }
        }
        return new WordMatcher(compiled);
    }

    /** Convenience overload without downloader (no URL word lists). */
    public static WordMatcher build(ConfigurationSection wordGroupsSection) {
        return build(wordGroupsSection, null);
    }

    public List<MatchResult> match(String transcript, UUID playerUuid, CooldownTracker cooldowns) {
        if (transcript == null || transcript.isBlank()) return List.of();
        String lower = transcript.toLowerCase();

        List<MatchResult> results = new ArrayList<>();
        for (CompiledGroup cg : groups) {
            if (cooldowns.isOnCooldown(playerUuid, cg.config.id)) continue;
            for (Pattern p : cg.patterns) {
                var matcher = p.matcher(lower);
                if (matcher.find()) {
                    results.add(new MatchResult(cg.config, matcher.group()));
                    cooldowns.record(playerUuid, cg.config.id, cg.config.cooldownSeconds);
                    break;
                }
            }
        }
        return results;
    }

    public boolean isEmpty() {
        return groups.isEmpty();
    }
}

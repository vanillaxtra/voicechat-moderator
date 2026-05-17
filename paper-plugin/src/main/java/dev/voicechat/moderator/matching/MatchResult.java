package dev.voicechat.moderator.matching;

/** A word-group match returned by {@link WordMatcher}. */
public final class MatchResult {

    public final WordGroupConfig group;
    /** The specific word/phrase that triggered this match. */
    public final String matchedWord;

    public MatchResult(WordGroupConfig group, String matchedWord) {
        this.group = group;
        this.matchedWord = matchedWord;
    }
}

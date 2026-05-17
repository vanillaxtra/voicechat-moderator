package dev.voicechat.moderator.voice;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Rolling in-memory transcript buffer per player.
 *
 * Entries older than {@link #MAX_MINUTES} minutes are evicted automatically.
 * All methods are synchronized — entries arrive from the WebSocket callback
 * thread and may be read from the game thread (report GUI).
 */
public final class TranscriptBuffer {

    public record Entry(String text, Instant time) {}

    /** Maximum age of entries kept in the buffer. */
    private static final int MAX_MINUTES = 10;

    private final Deque<Entry> entries = new ArrayDeque<>();

    /** Add a new transcript line and evict stale entries. */
    public synchronized void add(String text) {
        entries.addLast(new Entry(text, Instant.now()));
        evict();
    }

    /**
     * Returns all entries no older than {@code minutes} minutes, in order.
     * A defensive copy is returned; the caller may iterate safely.
     */
    public synchronized List<Entry> getRecent(int minutes) {
        evict();
        Instant cutoff = Instant.now().minusSeconds((long) minutes * 60);
        List<Entry> result = new ArrayList<>();
        for (Entry e : entries) {
            if (!e.time().isBefore(cutoff)) result.add(e);
        }
        return result;
    }

    /**
     * Returns a plain-text snapshot for the last {@code minutes} minutes,
     * one transcript line per line, prefixed with the time offset.
     */
    public synchronized String formatForReport(int minutes) {
        List<Entry> recent = getRecent(minutes);
        if (recent.isEmpty()) return "(no recent voice activity)";
        StringBuilder sb = new StringBuilder();
        long now = Instant.now().getEpochSecond();
        for (Entry e : recent) {
            long secsAgo = now - e.time().getEpochSecond();
            sb.append("[").append(secsAgo).append("s ago] ").append(e.text()).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    /** True if there are any entries within the last {@code minutes} minutes. */
    public synchronized boolean hasActivity(int minutes) {
        return !getRecent(minutes).isEmpty();
    }

    public synchronized void clear() {
        entries.clear();
    }

    private void evict() {
        Instant cutoff = Instant.now().minusSeconds((long) MAX_MINUTES * 60);
        while (!entries.isEmpty() && entries.peekFirst().time().isBefore(cutoff)) {
            entries.pollFirst();
        }
    }
}

package dev.voicechat.moderator.voice;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A rolling PCM buffer that keeps at most {@code maxSeconds} of audio.
 * Older frames are dropped from the front as new ones are added.
 *
 * Thread-safe — frames arrive on the SVC Netty thread; snapshots may be taken
 * from the HTTP executor thread.
 */
public final class PlayerClipBuffer {

    private final int sampleRate;
    private final int maxSeconds;

    private final Deque<short[]> frames = new ArrayDeque<>();
    private long totalSamples = 0;

    public PlayerClipBuffer(int sampleRate, int maxSeconds) {
        this.sampleRate = sampleRate;
        this.maxSeconds = maxSeconds;
    }

    public synchronized void append(short[] pcm) {
        frames.addLast(pcm);
        totalSamples += pcm.length;

        long maxSamples = (long) sampleRate * maxSeconds;
        while (totalSamples > maxSamples && !frames.isEmpty()) {
            short[] oldest = frames.removeFirst();
            totalSamples -= oldest.length;
        }
    }

    /**
     * Returns a WAV snapshot of all current buffered audio without clearing.
     * The snapshot is independent; future appends don't affect it.
     */
    public synchronized byte[] snapshot() {
        if (frames.isEmpty()) return new byte[0];
        short[][] data = frames.toArray(new short[0][]);
        return WavWriter.build(data, sampleRate);
    }

    public synchronized void clear() {
        frames.clear();
        totalSamples = 0;
    }
}

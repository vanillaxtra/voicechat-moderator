package dev.voicechat.moderator.voice;

import java.util.ArrayList;
import java.util.List;

/**
 * Accumulates decoded 16-bit PCM frames for a single player until they are
 * ready to be flushed as a WAV chunk to the Whisper service.
 *
 * All public methods are synchronized because frames arrive on a Netty thread
 * while flush checks may also come from the same thread or a scheduler thread.
 */
public class PlayerAudioBuffer {

    private final int sampleRate;
    private final List<short[]> frames = new ArrayList<>();
    private long totalSamples = 0;

    public PlayerAudioBuffer(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    public synchronized void append(short[] pcm) {
        frames.add(pcm);
        totalSamples += pcm.length;
    }

    /** Returns the accumulated duration in seconds. */
    public synchronized double durationSeconds() {
        return (double) totalSamples / sampleRate;
    }

    /**
     * Serialises all accumulated PCM frames to a WAV byte array, then clears
     * the buffer so it is ready for the next accumulation window.
     */
    public synchronized byte[] buildAndReset() {
        short[][] data = frames.toArray(new short[0][]);
        frames.clear();
        totalSamples = 0;
        return WavWriter.build(data, sampleRate);
    }
}

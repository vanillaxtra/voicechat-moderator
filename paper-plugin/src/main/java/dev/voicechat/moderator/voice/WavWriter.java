package dev.voicechat.moderator.voice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Builds a minimal PCM WAV byte array from an array of decoded Opus frames. */
public final class WavWriter {

    private WavWriter() {}

    /**
     * @param frames     Array of 16-bit mono PCM frames (each frame is a short[])
     * @param sampleRate Sample rate in Hz (must match what the Opus decoder produces)
     * @return           Byte array containing a well-formed WAV file
     */
    public static byte[] build(short[][] frames, int sampleRate) {
        int totalSamples = 0;
        for (short[] frame : frames) totalSamples += frame.length;

        int dataBytes = totalSamples * 2; // 16-bit = 2 bytes per sample
        int riffSize  = 36 + dataBytes;   // everything after the first 8 RIFF bytes

        ByteBuffer buf = ByteBuffer.allocate(44 + dataBytes)
                .order(ByteOrder.LITTLE_ENDIAN);

        // RIFF chunk descriptor
        buf.put(new byte[]{'R', 'I', 'F', 'F'});
        buf.putInt(riffSize);
        buf.put(new byte[]{'W', 'A', 'V', 'E'});

        // fmt sub-chunk (PCM, 16 bytes)
        buf.put(new byte[]{'f', 'm', 't', ' '});
        buf.putInt(16);
        buf.putShort((short) 1); // AudioFormat = PCM
        buf.putShort((short) 1); // NumChannels = 1 (mono)
        buf.putInt(sampleRate);
        buf.putInt(sampleRate * 2); // ByteRate
        buf.putShort((short) 2);    // BlockAlign
        buf.putShort((short) 16);   // BitsPerSample

        // data sub-chunk
        buf.put(new byte[]{'d', 'a', 't', 'a'});
        buf.putInt(dataBytes);
        for (short[] frame : frames) {
            for (short s : frame) {
                buf.putShort(s);
            }
        }

        return buf.array();
    }
}

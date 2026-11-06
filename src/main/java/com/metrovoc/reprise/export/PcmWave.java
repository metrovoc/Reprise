package com.metrovoc.reprise.export;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** PCM16 stereo. RF64 keeps the sample count and data length intact on exports larger than 4 GiB. */
final class PcmWave {
    private PcmWave() {}

    static byte[] header(long frames) {
        int stride = AudioCapture.CHANNELS * Short.BYTES;
        long dataSize = frames * stride;
        boolean large = dataSize + 36 > 0xFFFFFFFFL;
        ByteBuffer out = ByteBuffer.allocate(large ? 80 : 44).order(ByteOrder.LITTLE_ENDIAN);
        tag(out, large ? "RF64" : "RIFF");
        out.putInt(large ? -1 : (int) (dataSize + 36));
        tag(out, "WAVE");
        if (large) {
            tag(out, "ds64");
            out.putInt(28).putLong(dataSize + 72).putLong(dataSize).putLong(frames).putInt(0);
        }
        tag(out, "fmt ");
        out.putInt(16).putShort((short) 1).putShort((short) AudioCapture.CHANNELS);
        out.putInt(AudioCapture.RATE).putInt(AudioCapture.RATE * stride).putShort((short) stride).putShort((short) 16);
        tag(out, "data");
        out.putInt(large ? -1 : (int) dataSize);
        return out.array();
    }

    static short sample(float value) {
        return (short) Math.clamp(Math.round(value * 32768f), -32768, 32767);
    }

    private static void tag(ByteBuffer out, String tag) {
        out.put(tag.getBytes(StandardCharsets.US_ASCII));
    }
}

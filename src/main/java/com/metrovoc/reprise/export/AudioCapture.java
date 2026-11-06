package com.metrovoc.reprise.export;

import com.metrovoc.reprise.editor.FrameRate;
import com.metrovoc.reprise.Reprise;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import org.lwjgl.openal.SOFTLoopback;
import org.lwjgl.system.MemoryUtil;

/**
 * Records the game's sound during export. The sound engine is restarted on an OpenAL loopback device, which plays
 * nothing aloud and hands over its mix on request: after each video frame, exactly that frame's share of samples.
 */
public final class AudioCapture {
    public static final int RATE = 48000;
    public static final int CHANNELS = 2;

    private static boolean loopback;

    private final Path file;
    private final OutputStream out;
    private final int numerator;
    private final long samplesPerFrameNumerator;
    private final FloatBuffer samples;
    private final ByteBuffer bytes;
    private long device;
    private final boolean wave;
    private final long expectedSamples;
    private long remainder;
    private long written;

    AudioCapture(Path file, FrameRate rate, int frames, boolean wave) throws IOException {
        this.file = file;
        numerator = rate.numerator();
        samplesPerFrameNumerator = (long) RATE * rate.denominator();
        this.wave = wave;
        // Divide first so a long shot at a fractional rate cannot overflow the intermediate product.
        this.expectedSamples = frames * (samplesPerFrameNumerator / numerator)
            + frames * (samplesPerFrameNumerator % numerator) / numerator;
        this.out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16);
        int max = (int) (samplesPerFrameNumerator / numerator) + 2;
        this.samples = MemoryUtil.memAllocFloat(max * CHANNELS);
        this.bytes = ByteBuffer.allocate(max * CHANNELS * 4).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** True while the sound engine should open a loopback device instead of the speakers. */
    public static boolean loopback() {
        return loopback;
    }

    void start() {
        loopback = true;
        Minecraft mc = Minecraft.getInstance();
        mc.getSoundManager().reload();
        this.device = mc.getSoundManager().soundEngine.library.currentDevice;
        Reprise.LOGGER.info("Capturing audio from loopback device {}", this.device);
    }

    /** Takes one video frame's worth of sound. */
    void frame() throws IOException {
        if (this.device == 0) {
            throw new IOException("No audio loopback device");
        }
        if (this.wave && this.written == 0) {
            this.out.write(PcmWave.header(this.expectedSamples));
        }
        // Sounds start on the sound thread. Export runs faster than real time, so let that thread catch up with
        // this frame's sounds first; each round also runs the tasks the previous round queued.
        var executor = Minecraft.getInstance().getSoundManager().soundEngine.executor;
        for (int i = 0; i < 3; i++) {
            executor.submit(() -> {}).join();
        }
        this.remainder += samplesPerFrameNumerator;
        int count = (int) (this.remainder / numerator);
        this.remainder %= numerator;
        this.samples.clear();
        SOFTLoopback.alcRenderSamplesSOFT(this.device, this.samples, count);
        this.bytes.clear();
        for (int i = 0; i < count * CHANNELS; i++) {
            float value = this.samples.get(i);
            if (this.wave) {
                this.bytes.putShort(PcmWave.sample(value));
            } else {
                this.bytes.putFloat(value);
            }
        }
        this.out.write(this.bytes.array(), 0, this.bytes.position());
        this.written += count;
    }

    /** Back to the speakers. */
    boolean stop() {
        boolean closed = true;
        try {
            this.out.close();
        } catch (IOException e) {
            closed = false;
            Reprise.LOGGER.warn("Could not close the audio capture", e);
        }
        MemoryUtil.memFree(this.samples);
        loopback = false;
        Minecraft.getInstance().getSoundManager().reload();
        return closed && this.written == this.expectedSamples;
    }

    Path file() {
        return this.file;
    }
}

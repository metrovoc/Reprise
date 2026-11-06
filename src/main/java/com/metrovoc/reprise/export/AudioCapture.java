package com.metrovoc.reprise.export;

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
    private final int fps;
    private final FloatBuffer samples;
    private final ByteBuffer bytes;
    private long device;
    private double owed;
    private long written;

    AudioCapture(Path file, int fps) throws IOException {
        this.file = file;
        this.fps = fps;
        this.out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16);
        int max = RATE / Math.max(1, fps) + 2;
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
            return;
        }
        // Sounds start on the sound thread. Export runs faster than real time, so let that thread catch up with
        // this frame's sounds first; each round also runs the tasks the previous round queued.
        var executor = Minecraft.getInstance().getSoundManager().soundEngine.executor;
        for (int i = 0; i < 3; i++) {
            executor.submit(() -> {}).join();
        }
        this.owed += (double) RATE / this.fps;
        int count = (int) Math.floor(this.owed);
        this.owed -= count;
        this.samples.clear();
        SOFTLoopback.alcRenderSamplesSOFT(this.device, this.samples, count);
        this.bytes.clear();
        for (int i = 0; i < count * CHANNELS; i++) {
            this.bytes.putFloat(this.samples.get(i));
        }
        this.out.write(this.bytes.array(), 0, this.bytes.position());
        this.written += count;
    }

    /** Back to the speakers. */
    void stop() {
        try {
            this.out.close();
        } catch (IOException e) {
            Reprise.LOGGER.warn("Could not close the audio capture", e);
        }
        MemoryUtil.memFree(this.samples);
        loopback = false;
        Minecraft.getInstance().getSoundManager().reload();
    }

    Path file() {
        return this.file;
    }

    long written() {
        return this.written;
    }
}

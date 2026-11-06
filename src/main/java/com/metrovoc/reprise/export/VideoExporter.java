package com.metrovoc.reprise.export;

import com.metrovoc.reprise.Reprise;
import com.metrovoc.reprise.ReprisePaths;
import com.metrovoc.reprise.editor.ExportSettings;
import com.metrovoc.reprise.playback.CameraPath;
import com.metrovoc.reprise.playback.ReplaySession;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;

/**
 * Renders the replay to video at a fixed time step. Every frame the client renders during export is one video frame:
 * the game timer is told to run exactly the ticks that bring the replay to that frame's moment, with the matching
 * partial tick, so the result does not depend on how fast the computer is. Frames are read back from the GPU and
 * handed to a writer thread that feeds ffmpeg.
 */
public final class VideoExporter {
    private static final int WARMUP_FRAMES = 30;
    private static final int MAX_WAIT_FRAMES = 200;
    private static final int PROGRESS_EVERY = 20;

    private static VideoExporter running;

    private final Minecraft mc = Minecraft.getInstance();
    private final ReplaySession session;
    private final ExportSettings settings;
    private final int start, end, width, height;
    private final double[] frameTicks;
    private final int totalFrames;
    private final Path output;
    private final Screen returnTo;
    private final FrameSink sink;
    private final BlockingQueue<byte[]> frames = new ArrayBlockingQueue<>(6);
    private final Thread writer;
    private volatile Throwable writeError;
    private volatile boolean writerDone;

    private int frame;
    private int warmup = WARMUP_FRAMES;
    private int waited;
    private int stableFrames;
    private int neededTick;
    private int lastVisible = -1;
    private boolean captureThisFrame;
    private boolean progressFrame;
    private int pendingReadbacks;
    private boolean finished;
    private final long startedAt = System.currentTimeMillis();

    private final boolean savedHideGui;
    private final int savedFramerate;
    private final boolean savedVsync;
    private final double savedVolume;
    private final double savedFade;

    private VideoExporter(ReplaySession session, ExportSettings settings, Screen returnTo) throws Exception {
        this.session = session;
        this.settings = settings;
        this.returnTo = returnTo;
        this.start = session.project().start();
        this.end = session.project().end(session.duration());
        this.width = settings.width & ~1;
        this.height = settings.height & ~1;
        this.frameTicks = session.project().frameTicks(this.start, this.end, settings.fps);
        this.totalFrames = this.frameTicks.length;

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        String base = session.file().path().getFileName().toString().replaceFirst("\\.reprise$", "") + "_" + stamp;
        Path dir = ReprisePaths.exports();
        if (settings.format == ExportSettings.Format.PNG_SEQUENCE) {
            this.output = dir.resolve(base);
            this.sink = new PngSink(this.output, this.width, this.height);
        } else {
            String ffmpeg = FfmpegSink.find(settings.ffmpeg);
            if (ffmpeg == null) {
                throw new IllegalStateException(Component.translatable("reprise.export.no_ffmpeg").getString());
            }
            this.output = dir.resolve(base + "." + settings.format.extension);
            this.sink = new FfmpegSink(ffmpeg, settings, this.width, this.height, this.output, dir.resolve(base + ".log"));
        }
        this.writer = new Thread(this::write, "Reprise Export Writer");
        this.writer.setDaemon(true);
        this.writer.start();

        var options = this.mc.options;
        this.savedHideGui = options.hideGui;
        this.savedFramerate = options.framerateLimit().get();
        this.savedVsync = options.enableVsync().get();
        this.savedVolume = options.getSoundSourceOptionInstance(SoundSource.MASTER).get();
        this.savedFade = options.chunkSectionFadeInTime().get();
        options.hideGui = true;
        options.framerateLimit().set(260);
        options.enableVsync().set(false);
        options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
        options.chunkSectionFadeInTime().set(0.0);
    }

    public static boolean isRunning() {
        return running != null;
    }

    public static VideoExporter running() {
        return running;
    }

    public static void start(ReplaySession session, ExportSettings settings, Screen returnTo) throws Exception {
        if (running != null) {
            return;
        }
        VideoExporter exporter = new VideoExporter(session, settings, returnTo);
        running = exporter;
        session.setPlaying(false);
        session.seek(exporter.start);
        CameraPath.setForced(true);
        exporter.mc.setScreen(new ExportProgressScreen(exporter));
        exporter.mc.resizeGui();
        Reprise.LOGGER.info("Exporting ticks {}..{} as {} frames at {}x{} to {}", exporter.start, exporter.end, exporter.totalFrames,
            exporter.width, exporter.height, exporter.output);
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    public int frame() {
        return this.frame;
    }

    public int totalFrames() {
        return this.totalFrames;
    }

    public long elapsedMillis() {
        return System.currentTimeMillis() - this.startedAt;
    }

    public boolean capturing() {
        return this.captureThisFrame;
    }

    /**
     * Called instead of the game timer at the start of each frame: how many client ticks to run, and (through
     * {@code partial}) the partial tick to render at.
     */
    public int advance(float[] partial) {
        this.captureThisFrame = false;
        partial[0] = 0;
        if (this.finished) {
            return 0;
        }
        if (this.writeError != null) {
            stop(false, Component.translatable("reprise.export.failed", String.valueOf(this.writeError.getMessage())));
            return 0;
        }
        if (this.progressFrame) {
            // A frame that only shows the progress screen; the replay does not move.
            this.progressFrame = false;
            partial[0] = currentPartial();
            return 0;
        }
        if (this.warmup > 0) {
            this.warmup--;
            partial[0] = 1;
            return 0;
        }
        if (this.frame >= this.totalFrames) {
            stop(true, null);
            return 0;
        }
        if (!this.session.isPlaying()) {
            this.session.setPlaying(true);
        }
        double target = this.frameTicks[this.frame];
        int needTick = (int) Math.floor(target) + 1;
        int ticks = Math.max(0, needTick - this.session.tick());
        partial[0] = (float) (target - Math.floor(target));
        this.neededTick = needTick;
        this.captureThisFrame = true;
        return ticks;
    }

    /** True once every section in view is built and uploaded, so the frame shows the whole world. */
    private boolean worldReady() {
        var renderer = this.mc.levelRenderer;
        var dispatcher = renderer.getSectionRenderDispatcher();
        if (dispatcher == null) {
            return true;
        }
        var graph = renderer.sectionOcclusionGraph;
        if (!dispatcher.isQueueEmpty() || graph.needsFullUpdate || graph.fullUpdateTask != null && !graph.fullUpdateTask.isDone()) {
            this.stableFrames = 0;
            return false;
        }
        for (var section : renderer.visibleSections) {
            // Sections at the edge of the loaded area wait for neighbours that never come; vanilla skips them too.
            if (section.isDirty() && (section.getSectionMesh() != net.minecraft.client.renderer.chunk.CompiledSectionMesh.UNCOMPILED || section.hasAllNeighbors())) {
                this.stableFrames = 0;
                return false;
            }
        }
        // The occlusion graph also grows a little each frame as chunks get their neighbours; wait until it settles.
        int visible = renderer.visibleSections.size();
        if (visible != this.lastVisible) {
            this.lastVisible = visible;
            this.stableFrames = 0;
            return false;
        }
        return ++this.stableFrames >= 2;
    }

    private float currentPartial() {
        double target = this.frameTicks[Math.max(0, Math.min(this.totalFrames - 1, this.frame - 1))];
        return (float) (target - Math.floor(target));
    }

    /** Called after the frame is rendered. */
    public void afterRender() {
        if (!this.captureThisFrame || this.finished) {
            return;
        }
        this.captureThisFrame = false;
        if (this.session.tick() < this.neededTick && this.session.tick() < this.session.duration()) {
            // The game runs at most 10 ticks per frame; a fast timelapse needs more frames to catch up.
            return;
        }
        if (!worldReady() && this.waited < MAX_WAIT_FRAMES) {
            // Render this moment again once the chunks in view are built.
            this.waited++;
            return;
        }
        this.waited = 0;
        readBack();
        this.frame++;
        if (this.frame % PROGRESS_EVERY == 0) {
            this.progressFrame = true;
        }
    }

    private void readBack() {
        RenderTarget target = this.mc.getMainRenderTarget();
        GpuTexture texture = target.getColorTexture();
        int w = target.width, h = target.height;
        if (w != this.width || h != this.height) {
            Reprise.LOGGER.warn("Frame is {}x{}, expected {}x{}", w, h, this.width, this.height);
        }
        int pixelSize = texture.getFormat().pixelSize();
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "Reprise export frame",
            GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) w * h * pixelSize);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        this.pendingReadbacks++;
        encoder.copyTextureToBuffer(texture, buffer, 0L, () -> {
            try (GpuBuffer.MappedView view = encoder.mapBuffer(buffer, true, false)) {
                byte[] bytes = new byte[w * h * 4];
                view.data().get(bytes);
                this.frames.put(bytes);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                buffer.close();
                this.pendingReadbacks--;
            }
        }, 0);
    }

    private void write() {
        try {
            while (true) {
                byte[] bytes = this.frames.take();
                if (bytes.length == 0) {
                    break;
                }
                this.sink.frame(bytes);
            }
        } catch (Throwable t) {
            this.writeError = t;
            Reprise.LOGGER.error("Writing the video failed", t);
        } finally {
            this.writerDone = true;
        }
    }

    public void cancel() {
        stop(false, Component.translatable("reprise.export.cancelled"));
    }

    private void stop(boolean success, Component failure) {
        if (this.finished) {
            return;
        }
        this.finished = true;
        // Pending GPU readbacks complete on the render thread; give them a few frames.
        for (int i = 0; i < 50 && this.pendingReadbacks > 0; i++) {
            RenderSystem.executePendingTasks();
            try {
                Thread.sleep(5);
            } catch (InterruptedException ignored) {
            }
        }
        boolean ok = success;
        try {
            if (success) {
                this.frames.put(new byte[0]);
                this.writer.join();
                ok = this.writeError == null && this.sink.finish();
            } else {
                this.writer.interrupt();
                this.sink.abort();
            }
        } catch (Exception e) {
            Reprise.LOGGER.error("Finishing the video failed", e);
            ok = false;
        }
        restore();
        running = null;
        Component message = ok ? Component.translatable("reprise.export.done", this.output.getFileName().toString())
            : failure != null ? failure : Component.translatable("reprise.export.failed", "ffmpeg");
        Reprise.LOGGER.info("Export {} after {} frames in {} s: {}", ok ? "finished" : "stopped", this.frame, elapsedMillis() / 1000, this.output);
        this.mc.setScreen(new ExportDoneScreen(this.returnTo, ok, message, this.output));
    }

    private void restore() {
        var options = this.mc.options;
        options.hideGui = this.savedHideGui;
        options.framerateLimit().set(this.savedFramerate);
        options.enableVsync().set(this.savedVsync);
        options.getSoundSourceOptionInstance(SoundSource.MASTER).set(this.savedVolume);
        options.chunkSectionFadeInTime().set(this.savedFade);
        CameraPath.setForced(false);
        this.session.setPlaying(false);
        this.mc.resizeGui();
    }
}

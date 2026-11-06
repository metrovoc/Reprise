package com.metrovoc.reprise.export;

import com.metrovoc.reprise.editor.FrameRate;
import com.metrovoc.reprise.Reprise;
import com.metrovoc.reprise.ReprisePaths;
import com.metrovoc.reprise.editor.ExportSettings;
import com.metrovoc.reprise.editor.FrameSchedule;
import com.metrovoc.reprise.playback.CameraPath;
import com.metrovoc.reprise.playback.ReplaySession;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.server.ServerPackManager.ActivationStatus;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * Renders the replay at a fixed time step. All views and compositing passes for one output frame share its clock:
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
    private final boolean sodium = net.neoforged.fml.ModList.get().isLoaded("sodium");
    private final ExportSettings settings;
    private final int start, end, width, height;
    private final FrameSchedule schedule;
    private final int totalFrames;
    private final ExportFiles files;
    private final Screen returnTo;
    private final FrameSink sink;
    private final AudioCapture audio;
    private record Frame(byte[] color, byte[] depth, float m22, float m32) {}
    private static final Frame END = new Frame(null, null, 0, 0);
    private final boolean zeroToOne = RenderSystem.getDevice().isZZeroToOne();
    private final BlockingQueue<Frame> frames = new ArrayBlockingQueue<>(6);
    private final Thread writer;
    private volatile Throwable writeError;
    private volatile boolean writerDone;

    private int frame;
    private int warmup = WARMUP_FRAMES;
    private int waited;
    private int stableFrames;
    private int neededTick;
    private final java.util.NavigableSet<Double> worldCuts = new java.util.TreeSet<>();
    private int lastVisible = -1;
    private boolean captureThisFrame;
    private boolean progressFrame;
    private int pendingReadbacks;
    private final int renderWidth, renderHeight;
    private final Panorama panorama;
    private final boolean stereo;
    private final boolean ods;
    private final FrameRate frameRate;
    private boolean rightEye;
    private Vec3 stereoCenter;
    private static final Panorama.Face[] FACES = Panorama.Face.values();
    private final List<Panorama.Face> captureFaces;
    private int faceIndex;
    private final int captureWidth, captureHeight;
    private TextureTarget downsampled;
    private final boolean withDepth;
    private DepthPass depthPass;
    private boolean depthCaptured;
    private final boolean transparent;
    private boolean whitePass;
    private boolean finished;
    private final long startedAt = System.currentTimeMillis();

    private final boolean savedHideGui;
    private final int savedFramerate;
    private final boolean savedVsync;
    private final double savedVolume;
    private final double savedFade;
    private final double savedMusic;

    private VideoExporter(ReplaySession session, ExportSettings settings, Screen returnTo) throws Exception {
        this.session = session;
        this.settings = settings;
        this.frameRate = settings.frameRate();
        this.ods = settings.projection() == ExportSettings.Projection.ODS;
        if (ods && (sodium || ShaderPacks.active())) {
            throw new IllegalStateException(Component.translatable("reprise.export.ods_renderer").getString());
        }
        this.stereo = settings.projection() == ExportSettings.Projection.STEREO;
        this.withDepth = settings.depthMap && settings.format == ExportSettings.Format.PNG_SEQUENCE;
        this.transparent = settings.transparentSky && settings.supportsAlpha();
        if (this.transparent && ShaderPacks.active()) {
            throw new IllegalStateException(Component.translatable("reprise.export.alpha_shaders").getString());
        }
        this.returnTo = returnTo;
        for (int tick : session.meta().worldSwitchTicks) worldCuts.add(session.project().shotTick(tick));
        for (var segment : session.meta().segments) {
            if (segment.reset) worldCuts.add(session.project().shotTick(segment.startTick));
        }
        this.start = session.project().start();
        this.end = session.project().end(session.duration());
        this.width = settings.outputWidth();
        this.height = settings.outputHeight();
        int scale = settings.supersample ? 2 : 1;
        int max = RenderSystem.getDevice().getMaxTextureSize();
        this.captureWidth = settings.captureWidth();
        this.captureHeight = settings.captureHeight();
        if (!settings.fitsDevice(max)) {
            throw new IllegalArgumentException(Component.translatable("reprise.export.size_limit", (long) this.width * scale,
                (long) this.height * scale, max).getString());
        }
        this.renderWidth = captureWidth * scale;
        this.renderHeight = captureHeight * scale;
        this.panorama = settings.spherical() ? new Panorama(width, ods ? height / 2 : height, captureWidth, withDepth) : null;
        this.captureFaces = panorama != null ? panorama.captureFaces : settings.cubeMap ? List.of(FACES) : List.of();
        this.schedule = session.project().frameSchedule(this.start, this.end, frameRate.value());
        if (this.schedule.frameCount() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("This range exceeds the supported video frame count");
        }
        this.totalFrames = (int) this.schedule.frameCount();
        if (this.totalFrames == 0) {
            throw new IllegalArgumentException("The export range contains no frames");
        }

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        String base = session.file().path().getFileName().toString().replaceFirst("\\.reprise$", "") + "_" + stamp;
        if (returnTo instanceof ExportQueueScreen queue && !queue.shotName().isBlank()) {
            base = net.minecraft.util.FileUtil.sanitizeName(queue.shotName()) + "_" + stamp;
        }
        Path dir = ReprisePaths.exports();
        boolean sequence = settings.format == ExportSettings.Format.PNG_SEQUENCE;
        long firstSample = SoundtrackMix.sampleAt(session.project().previewClock(session.duration()), this.start);
        SoundtrackMix soundtrack = settings.audio && !session.project().audioCues.isEmpty()
            ? session.soundtrack.exportMix(session, firstSample, AudioCapture.sampleCount(frameRate, totalFrames)) : null;
        this.files = new ExportFiles(dir, base, sequence ? "" : "." + settings.format.extension);
        FrameSink opened = null;
        try {
            if (sequence) {
                Files.createDirectory(this.files.media);
                opened = new PngSink(this.files.media, this.width, this.height, this.transparent);
            } else {
                String ffmpeg = FfmpegSink.find(settings.ffmpeg);
                if (ffmpeg == null) {
                    throw new IllegalStateException(Component.translatable("reprise.export.no_ffmpeg").getString());
                }
                opened = new FfmpegSink(ffmpeg, settings, this.width, this.height, this.files.media, this.files.work.resolve("encode.log"));
            }
            this.audio = settings.audio ? new AudioCapture(this.files.work.resolve(sequence ? "audio.wav" : "audio.f32"),
                frameRate, this.totalFrames, sequence, soundtrack, firstSample) : null;
            this.sink = opened;
        } catch (Exception e) {
            if (opened != null) opened.abort();
            cleanupFiles(true);
            throw e;
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
        options.hideGui = settings.hideHud || !captureFaces.isEmpty();
        options.framerateLimit().set(260);
        options.enableVsync().set(false);
        this.savedMusic = options.getSoundSourceOptionInstance(SoundSource.MUSIC).get();
        if (this.audio == null) {
            options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
        } else {
            // The replay's own sounds only: music is the viewer's, not the recording's.
            options.getSoundSourceOptionInstance(SoundSource.MUSIC).set(0.0);
            if (this.savedVolume == 0) {
                options.getSoundSourceOptionInstance(SoundSource.MASTER).set(1.0);
            }
        }
        options.chunkSectionFadeInTime().set(0.0);
    }

    /** RGB background for a transparency pass, or -1 to render the project background. */
    public static int matteColor() {
        return running == null || !running.transparent ? -1 : running.whitePass ? 0xFFFFFF : 0;
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
        try {
            if (exporter.audio != null) {
                exporter.audio.start();
            }
            session.setPlaying(false);
            double startWorld = session.project().worldTick(exporter.start);
            // A shot may start midway through a world tick (a fractional freeze). Rebuild from
            // its preceding whole tick so its future terrain has not been applied by the seek.
            int seekTick = startWorld == Math.floor(startWorld) ? exporter.start
                : (int) session.project().shotTick(Math.floor(startWorld));
            session.seek(seekTick);
            CameraPath.setForced(true);
            exporter.mc.setScreen(new ExportProgressScreen(exporter));
            exporter.mc.resizeGui();
            FlawlessFrames.setEnabled(true);
        } catch (RuntimeException e) {
            exporter.stop(false, Component.translatable("reprise.export.failed", String.valueOf(e.getMessage())));
            throw e;
        }
        Reprise.LOGGER.info("Exporting ticks {}..{} as {} frames at {}x{}", exporter.start, exporter.end, exporter.totalFrames,
            exporter.width, exporter.height);
    }

    public Panorama.Face panoramaFace() { return captureFaces.isEmpty() ? null : captureFaces.get(faceIndex); }

    public boolean capturingOds() { return ods && captureThisFrame; }
    public boolean rightEye() { return rightEye; }

    public float eyeOffset() { return stereo ? (rightEye ? 0.1f : -0.1f) : 0; }

    public void setStereoCenter(Vec3 center) { stereoCenter = center; }
    public Vec3 stereoCenter() { return stereoCenter; }

    public boolean includesHud() {
        return captureFaces.isEmpty() && !this.settings.hideHud;
    }

    public boolean handsOnly() {
        return captureFaces.isEmpty() && this.settings.hideHud && this.settings.handsOnly;
    }

    public int outputWidth() { return this.width; }
    public int hudWidth() { return stereo ? captureWidth : outputWidth(); }
    public int outputHeight() { return this.height; }

    public int renderWidth() {
        return this.renderWidth;
    }

    public int renderHeight() {
        return this.renderHeight;
    }

    public int frame() {
        return this.frame;
    }

    public double shotTick() {
        return this.schedule.tickAt(Math.min(this.frame, this.totalFrames - 1));
    }

    public float shaderTimeSeconds() {
        return (float) ((session.project().worldTick(shotTick()) / 20.0) % 3600.0);
    }

    public float frameDurationSeconds() {
        return (float) (1.0 / frameRate.value());
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
        this.depthCaptured = false;
        partial[0] = 0;
        if (this.finished) {
            return 0;
        }
        if (this.writeError != null) {
            stop(false, Component.translatable("reprise.export.failed", String.valueOf(this.writeError.getMessage())));
            return 0;
        }
        if (mc.getOverlay() instanceof LoadingOverlay overlay) {
            // The export clock freezes world ticks here. The overlay still needs its tick
            // and render callbacks to finish applying the pack and dismiss itself.
            overlay.tick();
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
            if (this.pendingReadbacks == 0) {
                stop(true, null);
            }
            return 0;
        }
        if (!this.session.isPlaying()) {
            this.session.setPlaying(true);
        }
        double target = shotTick();
        if (session.advanceExportTerrain((int) Math.floor(session.project().worldTick(target)))) {
            this.stableFrames = 0;
            this.waited = 0;
        }
        int needTick = target >= this.session.duration() ? this.session.duration() : (int) target + 1;
        // Vanilla interpolates toward the next tick. Its entities and terrain belong to a
        // different world at a hard cut, so hold the outgoing world's final pose until then.
        Double cut = worldCuts.higher(target);
        if (cut != null && cut <= needTick) needTick = (int) target;
        this.session.skipFrozenTicks(needTick);
        int ticks = Math.max(0, needTick - this.session.tick());
        if (ticks > 1) this.stableFrames = 0; // Skipped ticks can apply terrain directly.
        partial[0] = (float) Math.min(1, target - needTick + 1);
        this.neededTick = needTick;
        this.captureThisFrame = true;
        return ticks;
    }

    /** True once every section in view is built and uploaded, so the frame shows the whole world. */
    private boolean worldReady() {
        var renderer = this.mc.levelRenderer;
        if (this.sodium) {
            // Sodium owns the terrain queues and implements this query against its chunk builder.
            if (!renderer.hasRenderedAllSections()) {
                this.stableFrames = 0;
                return false;
            }
            return ++this.stableFrames >= 2;
        }
        var dispatcher = renderer.getSectionRenderDispatcher();
        if (dispatcher == null) {
            return true;
        }
        var graph = renderer.sectionOcclusionGraph;
        // Workers remove tasks from the queue before compiling them. A borrowed buffer
        // means a mesh can still change after this render.
        boolean compiling = mc.renderBuffers().sectionBufferPool().freeBuffers.remainingCapacity() > 0;
        if (compiling || !dispatcher.isQueueEmpty() || graph.needsFullUpdate || graph.fullUpdateTask != null && !graph.fullUpdateTask.isDone()) {
            this.stableFrames = 0;
            return false;
        }
        for (var section : renderer.visibleSections) {
            // Sections at the edge of the loaded area wait for neighbours that never come; vanilla skips them too.
            // A queued rebuild clears the dirty flag before a worker compiles or uploads its first mesh.
            boolean uncompiled = section.getSectionMesh() == net.minecraft.client.renderer.chunk.CompiledSectionMesh.UNCOMPILED;
            if (uncompiled ? section.hasAllNeighbors() : section.isDirty()) {
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
        double target = this.schedule.tickAt(Math.max(0, Math.min(this.totalFrames - 1, this.frame - 1)));
        return target >= this.session.duration() ? 1 : (float) (target - Math.floor(target));
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
        // A recorded pack change can schedule its reload during the very tick being rendered.
        // Keep this output frame pending until Minecraft has applied the new textures.
        if (mc.getOverlay() instanceof LoadingOverlay) return;
        for (var pack : mc.getDownloadedPackSource().manager.packs) {
            if (pack.path == null) continue;
            boolean shouldBeActive = !pack.isRemoved();
            if (pack.activationStatus == ActivationStatus.PENDING
                || (pack.activationStatus == ActivationStatus.ACTIVE) != shouldBeActive) return;
        }
        if (!worldReady() && this.waited < MAX_WAIT_FRAMES) {
            // Render this moment again once the chunks in view are built.
            this.waited++;
            return;
        }
        this.waited = 0;
        try {
            readBack();
        } catch (RuntimeException e) {
            this.writeError = e;
            return;
        }
        if (this.transparent) {
            this.whitePass = !this.whitePass;
            if (this.whitePass) {
                return; // Render the same replay tick and partial tick on white.
            }
        }
        if (!captureFaces.isEmpty()) {
            stableFrames = 0;
            if (++faceIndex < captureFaces.size()) return;
            faceIndex = 0;
        }
        if (stereo || ods) {
            rightEye = !rightEye;
            if (rightEye) return;
        }
        if (this.audio != null) {
            try {
                if (!captureFaces.isEmpty() || stereo) mc.getSoundManager().updateSource(mc.gameRenderer.getMainCamera());
                this.audio.frame();
            } catch (java.io.IOException e) {
                this.writeError = e;
            }
        }
        this.frame++;
        if (this.frame % PROGRESS_EVERY == 0) {
            this.progressFrame = true;
        }
    }

    public void captureWorldDepth() {
        if (!captureThisFrame || whitePass || !withDepth) return;
        try {
            if (depthPass == null) depthPass = new DepthPass(captureWidth, captureHeight);
            depthPass.capture(mc.getMainRenderTarget());
            depthCaptured = true;
        } catch (RuntimeException e) {
            writeError = e;
        }
    }

    private void readBack() {
        RenderTarget target = this.mc.getMainRenderTarget();
        if (target.width != this.renderWidth || target.height != this.renderHeight) {
            throw new IllegalStateException("Unexpected render target size " + target.width + "×" + target.height);
        }
        if (this.renderWidth != this.captureWidth) {
            if (this.downsampled == null) {
                this.downsampled = new TextureTarget("Reprise supersampling", captureWidth, captureHeight, false);
            }
            // A centered linear sample of an exactly 2x source averages the four covered texels.
            try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Reprise downsample", this.downsampled.getColorTextureView(), OptionalInt.empty())) {
                pass.setPipeline(RenderPipelines.TRACY_BLIT);
                pass.bindTexture("InSampler", target.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                pass.draw(0, 3);
            }
            target = this.downsampled;
        }
        GpuTexture texture = target.getColorTexture();
        int w = target.width, h = target.height;
        int pixelSize = texture.getFormat().pixelSize();
        boolean readDepth = withDepth && !whitePass;
        if (readDepth && !depthCaptured) throw new IllegalStateException("World depth was unavailable for this frame");
        var projection = mc.gameRenderer.getGameRenderState().levelRenderState.cameraRenderState.projectionMatrix;
        float m22 = projection.m22(), m32 = projection.m32();
        long colorBytes = (long) w * h * pixelSize;
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "Reprise export frame",
            GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, colorBytes * (readDepth ? 2 : 1));
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        this.pendingReadbacks++;
        try {
            if (readDepth) encoder.copyTextureToBuffer(depthPass.target.getColorTexture(), buffer, colorBytes, () -> {}, 0);
            encoder.copyTextureToBuffer(texture, buffer, 0L, () -> {
                // OpenGL can enqueue the fence before reporting that the copy failed.
                if (buffer.isClosed()) {
                    return;
                }
                try (GpuBuffer.MappedView view = encoder.mapBuffer(buffer, true, false)) {
                    byte[] bytes = new byte[w * h * 4];
                    var mapped = view.data();
                    mapped.get(bytes);
                    byte[] depth = null;
                    if (readDepth) {
                        depth = new byte[bytes.length];
                        mapped.get(depth);
                    }
                    if (!this.finished) {
                        enqueue(new Frame(bytes, depth, m22, m32));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    this.writeError = e;
                } finally {
                    buffer.close();
                    this.pendingReadbacks--;
                }
            }, 0);
        } catch (RuntimeException e) {
            if (!buffer.isClosed()) {
                buffer.close();
                this.pendingReadbacks--;
            }
            throw e;
        }
    }

    private void enqueue(Frame frame) throws InterruptedException {
        while (!this.writerDone && this.writeError == null) {
            if (this.frames.offer(frame, 100, TimeUnit.MILLISECONDS)) {
                return;
            }
        }
    }

    private static byte[] topBottom(byte[] left, byte[] right) {
        // GPU rows run bottom-up. The left eye occupies the upper half after the output flip.
        byte[] packed = java.util.Arrays.copyOf(right, Math.addExact(left.length, right.length));
        System.arraycopy(left, 0, packed, right.length, left.length);
        return packed;
    }

    private void write() {
        try {
            int written = 0, face = 0;
            byte[] leftColor = null, leftDepth = null;
            byte[][] colors = captureFaces.isEmpty() ? null : new byte[FACES.length][];
            byte[][] depths = captureFaces.isEmpty() || !withDepth ? null : new byte[FACES.length][];
            while (true) {
                Frame captured = this.frames.take();
                if (captured == END) {
                    if (written != this.totalFrames) {
                        throw new IOException("Incomplete video: " + written + " frames written");
                    }
                    break;
                }
                byte[] bytes = captured.color;
                if (this.transparent) {
                    byte[] white = this.frames.take().color;
                    if (white == null || white.length != bytes.length) {
                        throw new IOException("Missing white transparency pass");
                    }
                    AlphaMatte.combine(bytes, white);
                }
                byte[] depth = captured.depth;
                if (!captureFaces.isEmpty()) {
                    int slot = captureFaces.get(face).ordinal();
                    colors[slot] = bytes;
                    if (withDepth) depths[slot] = depth;
                    if (++face < captureFaces.size()) continue;
                    face = 0;
                    bytes = panorama == null ? Panorama.cross(colors, captureWidth, false) : panorama.project(colors);
                    if (withDepth) depth = panorama == null ? Panorama.cross(depths, captureWidth, true) : panorama.project(depths);
                }
                if (stereo || ods) {
                    if (leftColor == null) {
                        leftColor = bytes;
                        leftDepth = depth;
                        continue;
                    }
                    bytes = ods ? topBottom(leftColor, bytes) : sideBySide(leftColor, bytes, height);
                    if (depth != null) depth = ods ? topBottom(leftDepth, depth) : sideBySide(leftDepth, depth, height);
                    leftColor = null;
                    leftDepth = null;
                }
                if (depth != null) {
                    DepthExr.write(files.media.resolve(String.format("frame_%06d.depth.exr", written)), width, height, depth,
                        captured.m22, captured.m32, zeroToOne, panorama == null ? null : panorama.radialScale());
                }
                this.sink.frame(bytes);
                written++;
            }
        } catch (InterruptedException | java.nio.channels.ClosedByInterruptException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            this.writeError = t;
            Reprise.LOGGER.error("Writing the video failed", t);
        } finally {
            this.writerDone = true;
        }
    }

    private static byte[] sideBySide(byte[] left, byte[] right, int height) {
        int row = left.length / height;
        byte[] pair = new byte[Math.multiplyExact(2, left.length)];
        for (int y = 0; y < height; y++) {
            System.arraycopy(left, y * row, pair, y * 2 * row, row);
            System.arraycopy(right, y * row, pair, (y * 2 + 1) * row, row);
        }
        return pair;
    }

    public void cancel() {
        stop(false, Component.translatable("reprise.export.cancelled"));
    }

    public static void sessionClosed(ReplaySession session) {
        if (running != null && running.session == session) running.stop(false, null, false);
    }

    private void stop(boolean success, Component failure) {
        stop(success, failure, true);
    }

    private void stop(boolean success, Component failure, boolean showResult) {
        if (this.finished) {
            return;
        }
        this.finished = true;
        FlawlessFrames.setEnabled(false);
        boolean ok = success;
        Path output = null;
        try {
            if (success) {
                enqueue(END);
                this.writer.join();
                ok = this.writeError == null && this.sink.finish();
                if (!ok) {
                    this.sink.abort();
                }
                if (ok && (panorama != null || stereo) && settings.format == ExportSettings.Format.WEBM_VP9) {
                    SpatialVideoMetadata.tag(files.media, width, height, captureWidth * 4, settings.projection());
                    if (audio == null) {
                        Path webm = files.work.resolve("spherical.webm");
                        ok = FfmpegSink.run(FfmpegSink.find(settings.ffmpeg), java.util.List.of("-i", files.media.toString(),
                            "-c", "copy", webm.toString()), files.work.resolve("mux.log"));
                        if (ok) java.nio.file.Files.move(webm, files.media, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                if (ok && this.audio != null) {
                    ok = this.audio.stop() && addAudio();
                    if (!ok) {
                        failure = Component.translatable("reprise.export.audio_failed");
                    }
                }
                if (ok && (panorama != null || stereo) && settings.format != ExportSettings.Format.PNG_SEQUENCE
                    && settings.format != ExportSettings.Format.WEBM_VP9) SpatialVideoMetadata.tag(files.media, width, height, captureWidth * 4, settings.projection());
                if (ok) output = this.files.publish();
            } else {
                abortWriter();
            }
        } catch (Exception e) {
            abortWriter();
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Reprise.LOGGER.error("Finishing the video failed", e);
            ok = false;
        }
        if (this.audio != null && AudioCapture.loopback()) {
            this.audio.stop();
        }
        cleanupFiles(!ok && (success || this.writeError != null));
        if (this.depthPass != null) this.depthPass.close();
        if (this.downsampled != null) {
            this.downsampled.destroyBuffers();
        }
        running = null;
        if (showResult) this.session.advanceExportTerrain(Integer.MAX_VALUE);
        restore();
        Component message = ok ? Component.translatable("reprise.export.done", output.getFileName().toString())
            : failure != null ? failure : Component.translatable("reprise.export.failed", "ffmpeg");
        if (ok) {
            Reprise.LOGGER.info("Export finished after {} frames in {} s: {}", this.frame, elapsedMillis() / 1000, output);
        } else {
            Reprise.LOGGER.info("Export stopped after {} frames", this.frame);
        }
        if (this.returnTo instanceof ExportQueueScreen queue) {
            queue.completed(ok, message, showResult);
        } else if (showResult) this.mc.setScreen(new ExportDoneScreen(this.returnTo, ok, message, output));
    }

    private void abortWriter() {
        this.writer.interrupt();
        this.sink.abort();
        boolean interrupted = false;
        while (this.writer.isAlive()) {
            try {
                this.writer.join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void cleanupFiles(boolean reportEncoderError) {
        if (reportEncoderError) {
            FfmpegSink.reportLog(this.files.work.resolve("encode.log"));
            FfmpegSink.reportLog(this.files.work.resolve("mux.log"));
        }
        try {
            this.files.close();
        } catch (IOException e) {
            Reprise.LOGGER.error("Could not clean export folder {}", this.files.work, e);
        }
    }

    /** Muxes the captured sound into the video, or writes it next to a PNG sequence. */
    private boolean addAudio() {
        try {
            if (this.settings.format == ExportSettings.Format.PNG_SEQUENCE) {
                java.nio.file.Files.move(this.audio.file(), this.files.media.resolve("audio.wav"));
                return true;
            }
            String ffmpeg = FfmpegSink.find(this.settings.ffmpeg);
            if (ffmpeg == null) {
                return false;
            }
            Path muxed = this.files.work.resolve("muxed." + this.settings.format.extension);
            String codec = switch (this.settings.format) {
                case WEBM_VP9 -> "libopus";
                case MOV_PRORES -> "pcm_s16le";
                default -> "aac";
            };
            boolean ok = FfmpegSink.run(ffmpeg, java.util.List.of("-i", this.files.media.toString(),
                "-f", "f32le", "-ar", String.valueOf(AudioCapture.RATE), "-ac", "2", "-i", this.audio.file().toString(),
                "-map", "0:v", "-map", "1:a", "-c:v", "copy", "-c:a", codec, "-b:a", "256k", "-shortest", muxed.toString()),
                this.files.work.resolve("mux.log"));
            if (ok) {
                java.nio.file.Files.move(muxed, this.files.media, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return ok;
        } catch (Exception e) {
            Reprise.LOGGER.error("Adding the sound failed", e);
            return false;
        }
    }

    private void restore() {
        var options = this.mc.options;
        options.hideGui = this.savedHideGui;
        options.framerateLimit().set(this.savedFramerate);
        options.enableVsync().set(this.savedVsync);
        options.getSoundSourceOptionInstance(SoundSource.MASTER).set(this.savedVolume);
        options.chunkSectionFadeInTime().set(this.savedFade);
        options.getSoundSourceOptionInstance(SoundSource.MUSIC).set(this.savedMusic);
        if (!captureFaces.isEmpty()) mc.gameRenderer.getMainCamera().disablePanoramicMode();
        CameraPath.setForced(false);
        this.session.setPlaying(false);
        this.mc.resizeGui();
    }
}

package com.metrovoc.reprise.export;

import com.metrovoc.reprise.Reprise;
import com.metrovoc.reprise.editor.FrameSchedule;
import com.metrovoc.reprise.playback.ReplaySession;
import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.Library;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.network.chat.Component;

/** Session-owned decoders and the non-positional preview channel. Called from the client thread. */
public final class Soundtrack implements AutoCloseable {
    private static final class Load {
        final CompletableFuture<SoundtrackSource> result = new CompletableFuture<>();
        volatile Process process;
        Thread worker;
        boolean reported;
    }
    private final Map<String, Load> sources = new HashMap<>();
    private FrameSchedule mixClock;
    private SoundtrackMix mix;
    private ChannelAccess.ChannelHandle channel;
    private PreviewStream stream;
    private long lastNanos;
    private double estimatedSample;
    private float pitch;
    private final java.util.concurrent.atomic.AtomicLong revision = new java.util.concurrent.atomic.AtomicLong();
    private long mixRevision;

    public CompletableFuture<SoundtrackSource> load(String file, String ffmpeg, boolean retry) {
        var old = sources.get(file);
        if (old != null && !(retry && old.result.isCompletedExceptionally())) return old.result;
        var load = new Load();
        sources.put(file, load);
        load.worker = Thread.startVirtualThread(() -> {
            try {
                load.result.complete(SoundtrackSource.decode(Path.of(file), ffmpeg, p -> load.process = p));
            } catch (Throwable e) {
                load.result.completeExceptionally(e);
            } finally {
                load.process = null;
                revision.incrementAndGet();
            }
        });
        return load.result;
    }

    public SoundtrackSource ready(String file) {
        var load = sources.get(file);
        return load == null || load.result.isCompletedExceptionally() ? null : load.result.getNow(null);
    }

    private static boolean intersects(com.metrovoc.reprise.editor.AudioCue cue, FrameSchedule clock, long first, long count) {
        long start = SoundtrackMix.sampleAt(clock, cue.tick());
        return cue.gain() > 0 && start < first + count && start + cue.playableFrames(cue.frames()) > first;
    }

    record Preparation(CompletableFuture<Void> ready, Runnable cancel) {}

    Preparation prepareExport(ReplaySession session, com.metrovoc.reprise.editor.ExportSettings settings) throws IOException {
        var project = session.project();
        int start = project.start(), duration = session.duration();
        long frames = project.frameSchedule(start, project.end(duration), settings.frameRate().value()).frameCount();
        long count = AudioCapture.sampleCount(settings.frameRate(), Math.toIntExact(frames));
        var clock = project.previewClock(duration);
        long first = SoundtrackMix.sampleAt(clock, start);
        var pending = new HashMap<String, Load>();
        for (var cue : project.audioCues) {
            if (!intersects(cue, clock, first, count)) continue;
            load(cue.file(), settings.ffmpeg, true);
            pending.put(cue.file(), sources.get(cue.file()));
        }
        var ready = CompletableFuture.allOf(pending.values().stream().map(l -> l.result).toArray(CompletableFuture[]::new));
        return new Preparation(ready, () -> pending.forEach((file, load) -> {
            if (!load.result.isDone() && sources.remove(file, load)) {
                closeLoad(load);
                mix = null;
            }
        }));
    }

    SoundtrackMix exportMix(ReplaySession session, long first, long count) throws IOException {
        var project = session.project();
        var clock = project.previewClock(session.duration());
        var clips = new ArrayList<SoundtrackMix.Clip>();
        for (var cue : project.audioCues) {
            if (!intersects(cue, clock, first, count)) continue;
            try {
                var source = load(cue.file(), project.export.ffmpeg, true).join();
                if (Math.round(cue.offset() * SoundtrackSource.RATE) >= source.frames()) {
                    throw new IOException("Source offset exceeds the duration of " + cue.file());
                }
                clips.add(SoundtrackMix.clip(clock, cue, source));
            } catch (CompletionException e) {
                throw new IOException("Cannot load " + cue.file() + ": " + e.getCause().getMessage(), e.getCause());
            }
        }
        return clips.isEmpty() ? null : new SoundtrackMix(clips);
    }

    private SoundtrackMix previewMix(ReplaySession session) {
        var project = session.project();
        var clock = project.previewClock(session.duration());
        long current = revision.get();
        if (clock == mixClock && current == mixRevision && mix != null) return mix;
        stopPreview();
        var clips = new ArrayList<SoundtrackMix.Clip>();
        for (var cue : project.audioCues) {
            if (cue.gain() == 0) continue;
            try {
                var source = load(cue.file(), project.export.ffmpeg, false).getNow(null);
                if (source != null) clips.add(SoundtrackMix.clip(clock, cue, source));
            } catch (CompletionException e) {
                var failed = sources.get(cue.file());
                if (!failed.reported) {
                    failed.reported = true;
                    Reprise.LOGGER.warn("Cannot preview audio cue {}", cue.file(), e.getCause());
                    Minecraft.getInstance().gui.setOverlayMessage(Component.literal(e.getCause().getMessage()), false);
                }
            }
        }
        mixClock = clock;
        mixRevision = current;
        return mix = new SoundtrackMix(clips);
    }

    public void preview(ReplaySession session, float partial) {
        if (!session.isPlaying() || session.project().audioCues.isEmpty() || VideoExporter.isRunning()) {
            stopPreview();
            return;
        }
        var track = previewMix(session);
        double tick = session.visualTick(partial);
        long sample = SoundtrackMix.sampleAt(mixClock, tick);
        if (!track.audible(sample)) {
            stopPreview();
            return;
        }
        long now = System.nanoTime();
        float rate = (float)Math.clamp(50.0 / (session.millisPerTick() * session.project().speedAt(tick)), .01, 16);
        estimatedSample += (now - lastNanos) * 1e-9 * SoundtrackSource.RATE * pitch;
        lastNanos = now;
        if (channel == null || channel.isStopped() || Math.abs(estimatedSample - sample) > SoundtrackSource.RATE / 10) {
            stopPreview();
            var engine = Minecraft.getInstance().getSoundManager().soundEngine;
            if (!engine.loaded) return;
            channel = engine.channelAccess.createHandle(Library.Pool.STREAMING).join();
            if (channel == null) return;
            estimatedSample = sample;
            pitch = rate;
            var opened = new PreviewStream(track, sample);
            stream = opened;
            channel.execute(c -> {
                c.setRelative(true);
                c.disableAttenuation();
                c.setVolume(1);
                c.setPitch(rate);
                c.attachBufferStream(opened);
                c.play();
            });
        } else if (rate != pitch) {
            pitch = rate;
            channel.execute(c -> c.setPitch(rate));
        }
    }

    public void stopPreview() {
        if (channel != null) {
            stream.close();
            stream = null;
            channel.execute(Channel::stop);
            channel = null;
        }
    }

    private static final class PreviewStream implements AudioStream {
        private final SoundtrackMix.Reader reader;
        private boolean closed;
        private long cursor;
        private float[] samples = new float[0];
        private ByteBuffer bytes = ByteBuffer.allocateDirect(0);
        PreviewStream(SoundtrackMix mix, long start) { reader = new SoundtrackMix.Reader(mix); cursor = start; }
        @Override public AudioFormat getFormat() { return SoundtrackSource.FORMAT; }
        @Override public synchronized ByteBuffer read(int size) throws IOException {
            if (closed) return null;
            if (bytes.capacity() < size) {
                bytes = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN);
                samples = new float[size / 2];
            }
            Arrays.fill(samples, 0, size / 2, 0f);
            reader.add(samples, cursor, size / 4);
            cursor += size / 4;
            bytes.clear();
            for (int i = 0; i < size / 2; i++) bytes.putShort(PcmWave.sample(samples[i]));
            return bytes.flip();
        }
        @Override public synchronized void close() { closed = true; }
    }

    public void discardUnused(ReplaySession session) {
        var keep = new java.util.HashSet<String>();
        for (var cue : session.project().audioCues) keep.add(cue.file());
        var it = sources.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (keep.contains(entry.getKey())) continue;
            stopPreview();
            closeLoad(entry.getValue());
            it.remove();
            mix = null;
        }
    }

    private static void closeLoad(Load load) {
        load.worker.interrupt();
        var process = load.process;
        if (process != null && process.isAlive()) process.destroyForcibly();
        try {
            load.result.join().close();
        } catch (CompletionException ignored) {
            // The decoder already closed its partial output.
        } catch (IOException e) {
            Reprise.LOGGER.warn("Closing soundtrack", e);
        }
    }

    @Override public void close() {
        stopPreview();
        sources.values().forEach(Soundtrack::closeLoad);
        sources.clear();
    }
}

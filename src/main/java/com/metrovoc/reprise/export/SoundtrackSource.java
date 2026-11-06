package com.metrovoc.reprise.export;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

/** Seekable PCM shared by realtime playback and offline rendering. Its file closes with the replay. */
public final class SoundtrackSource implements AutoCloseable {
    static final int RATE = 48000;
    static final AudioFormat FORMAT = new AudioFormat(RATE, 16, 2, true, false);
    private final FileChannel pcm;
    private long frames;
    private byte[] peaks = new byte[256];
    private byte[][] envelope;
    private static final int BUCKET = 960;

    private SoundtrackSource(FileChannel pcm) { this.pcm = pcm; }
    public long frames() { return frames; }

    static SoundtrackSource decode(Path file, String configured, java.util.function.Consumer<Process> started) throws IOException {
        if (!Files.isRegularFile(file)) throw new IOException("Missing audio file: " + file);
        Path temp = Files.createTempFile("reprise-audio-", ".pcm");
        FileChannel cache;
        try {
            cache = FileChannel.open(temp, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.DELETE_ON_CLOSE);
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        var result = new SoundtrackSource(cache);
        try {
            try (var input = AudioSystem.getAudioInputStream(file.toFile());
                 var converted = AudioSystem.getAudioInputStream(FORMAT, input)) {
                result.copy(converted);
            } catch (UnsupportedAudioFileException | IllegalArgumentException unsupported) {
                String ffmpeg = FfmpegSink.find(configured);
                if (ffmpeg == null) throw new IOException("This audio format requires ffmpeg");
                var process = new ProcessBuilder(ffmpeg, "-nostdin", "-v", "error", "-protocol_whitelist", "file,pipe",
                    "-i", file.toString(), "-map", "0:a:0", "-f", "s16le", "-ar", "48000", "-ac", "2", "pipe:1")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                started.accept(process);
                try (var stream = process.getInputStream()) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Audio import cancelled");
                    result.copy(stream);
                    if (process.waitFor() != 0) throw new IOException("Could not decode " + file.getFileName());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Audio import interrupted", e);
                } finally {
                    if (process.isAlive()) process.destroyForcibly().onExit().join();
                }
            }
            if (result.frames == 0) throw new IOException("Empty audio file: " + file.getFileName());
            result.buildEnvelope();
            return result;
        } catch (Throwable e) {
            result.close();
            throw e;
        }
    }

    private void copy(InputStream input) throws IOException {
        byte[] bytes = new byte[65536];
        for (int n; (n = input.readNBytes(bytes, 0, bytes.length)) != 0;) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Audio import cancelled");
            if (n % 4 != 0) throw new IOException("Incomplete stereo sample");
            ByteBuffer buf = ByteBuffer.wrap(bytes, 0, n).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < n; i += 4) {
                int bucket = Math.toIntExact(frames++ / BUCKET);
                if (bucket == peaks.length) peaks = Arrays.copyOf(peaks, Math.multiplyExact(peaks.length, 2));
                int amplitude = Math.max(Math.abs((int)buf.getShort(i)), Math.abs((int)buf.getShort(i + 2)));
                peaks[bucket] = (byte)Math.max(peaks[bucket], Math.min(127, amplitude >> 8));
            }
            while (buf.hasRemaining()) pcm.write(buf);
        }
    }

    private void buildEnvelope() {
        int count = Math.toIntExact((frames - 1) / BUCKET + 1);
        envelope = new byte[1 + (32 - Integer.numberOfLeadingZeros(count - 1))][];
        envelope[0] = Arrays.copyOf(peaks, count);
        peaks = null;
        for (int i = 1; i < envelope.length; i++) {
            byte[] prior = envelope[i - 1], next = new byte[(prior.length + 1) / 2];
            for (int j = 0; j < next.length; j++) next[j] = (byte)Math.max(prior[2*j], prior[Math.min(2*j+1, prior.length-1)]);
            envelope[i] = next;
        }
    }

    /** Maximum stereo amplitude across a timeline pixel, including when zoomed out. */
    public float peak(double fromSeconds, double toSeconds) {
        int left = Math.clamp((long)Math.floor(fromSeconds * RATE / BUCKET), 0, envelope[0].length);
        int right = Math.clamp((long)Math.ceil(toSeconds * RATE / BUCKET), left, envelope[0].length);
        int max = 0;
        for (int level = 0; left < right; level++) {
            if ((left & 1) != 0) max = Math.max(max, envelope[level][left++]);
            if ((right & 1) != 0) max = Math.max(max, envelope[level][--right]);
            left /= 2;
            right /= 2;
        }
        return max / 127f;
    }

    void add(float[] mix, int destination, long source, int count, double gain, ByteBuffer buffer) throws IOException {
        buffer.clear().limit(count * 4);
        long position = source * 4;
        while (buffer.hasRemaining()) {
            int read = pcm.read(buffer, position);
            if (read < 0) throw new IOException("Unexpected end of soundtrack");
            position += read;
        }
        buffer.flip();
        for (int i = 0; i < count * 2; i++) mix[2 * destination + i] += (float)(buffer.getShort() * (gain / 32768.0));
    }

    @Override public void close() throws IOException { pcm.close(); }
}

package com.metrovoc.reprise.export;

import com.metrovoc.reprise.Reprise;
import com.metrovoc.reprise.editor.ExportSettings;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Pipes raw frames into an ffmpeg process, which encodes them. */
final class FfmpegSink implements FrameSink {
    private final Process process;
    private final OutputStream stdin;
    private final Path log;

    FfmpegSink(String ffmpeg, ExportSettings settings, int width, int height, Path output, Path log) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-y",
            "-f", "rawvideo", "-pix_fmt", "rgba", "-s", width + "x" + height, "-r", String.valueOf(settings.fps), "-i", "-",
            "-vf", "vflip"));
        String q = String.valueOf(settings.quality);
        switch (settings.format) {
            case MP4_H264 -> cmd.addAll(List.of("-c:v", "libx264", "-preset", "slow", "-crf", q, "-pix_fmt", "yuv420p", "-movflags", "+faststart"));
            case MP4_H265 -> cmd.addAll(List.of("-c:v", "libx265", "-preset", "medium", "-crf", q, "-pix_fmt", "yuv420p", "-tag:v", "hvc1", "-movflags", "+faststart"));
            case WEBM_VP9 -> cmd.addAll(List.of("-c:v", "libvpx-vp9", "-crf", q, "-b:v", "0", "-row-mt", "1", "-pix_fmt", "yuv420p"));
            case MOV_PRORES -> cmd.addAll(List.of("-c:v", "prores_ks", "-profile:v", "4", "-pix_fmt", "yuva444p10le"));
            case PNG_SEQUENCE -> throw new IllegalArgumentException("PNG sequences are written without ffmpeg");
        }
        cmd.add(output.toString());
        Reprise.LOGGER.info("Starting {}", String.join(" ", cmd));
        this.log = log;
        this.process = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        this.stdin = this.process.getOutputStream();
    }

    @Override
    public void frame(byte[] rgba) throws IOException {
        this.stdin.write(rgba);
    }

    @Override
    public boolean finish() throws IOException {
        this.stdin.close();
        try {
            if (!this.process.waitFor(10, TimeUnit.MINUTES)) {
                this.process.destroyForcibly();
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (this.process.exitValue() != 0) {
            Reprise.LOGGER.error("ffmpeg failed ({}); see {}", this.process.exitValue(), this.log);
            return false;
        }
        Files.deleteIfExists(this.log);
        return true;
    }

    @Override
    public void abort() {
        this.process.destroyForcibly();
    }

    /** Runs ffmpeg once with these arguments; true if it succeeded. */
    static boolean run(String ffmpeg, List<String> args, Path log) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-y"));
        cmd.addAll(args);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!p.waitFor(10, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            return false;
        }
        if (p.exitValue() == 0) {
            Files.deleteIfExists(log);
            return true;
        }
        Reprise.LOGGER.error("ffmpeg failed ({}); see {}", p.exitValue(), log);
        return false;
    }

    /** The ffmpeg to use: the configured path, then the game folder, then PATH and the usual install folders. */
    static String find(String configured) {
        List<String> candidates = new ArrayList<>();
        if (configured != null && !configured.isBlank()) {
            candidates.add(configured);
        }
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        String exe = windows ? "ffmpeg.exe" : "ffmpeg";
        candidates.add(com.metrovoc.reprise.ReprisePaths.root().resolve("ffmpeg").resolve(exe).toString());
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                candidates.add(Path.of(dir, exe).toString());
            }
        }
        candidates.addAll(List.of("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg"));
        for (String c : candidates) {
            Path p = Path.of(c);
            if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                return p.toString();
            }
        }
        return null;
    }
}

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
            "-vf", settings.format == ExportSettings.Format.MOV_PRORES && !settings.transparentSky ? "vflip,format=rgb24" : "vflip"));
        if (settings.supportsHardware() && settings.hardwareEncoder) {
            cmd.addAll(hardwareEncoder(ffmpeg, settings, width, height, log));
            if (settings.format == ExportSettings.Format.MP4_H265) {
                cmd.addAll(List.of("-tag:v", "hvc1"));
            }
        } else {
            String q = String.valueOf(settings.quality);
            switch (settings.format) {
                case MP4_H264 -> cmd.addAll(List.of("-c:v", "libx264", "-preset", "slow", "-crf", q, "-pix_fmt", "yuv420p"));
                case MP4_H265 -> cmd.addAll(List.of("-c:v", "libx265", "-preset", "medium", "-crf", q, "-pix_fmt", "yuv420p", "-tag:v", "hvc1"));
                case WEBM_VP9 -> cmd.addAll(List.of("-c:v", "libvpx-vp9", "-crf", q, "-b:v", "0", "-row-mt", "1", "-pix_fmt", "yuv420p"));
                case MOV_PRORES -> cmd.addAll(List.of("-c:v", "prores_ks", "-profile:v", "4", "-pix_fmt", "yuva444p10le"));
                case PNG_SEQUENCE -> throw new IllegalArgumentException("PNG sequences are written without ffmpeg");
            }
        }
        if (settings.supportsHardware() && !settings.panorama && !settings.stereo) cmd.addAll(List.of("-movflags", "+faststart"));
        if ((settings.panorama || settings.stereo) && settings.format == ExportSettings.Format.WEBM_VP9) {
            // Stage VP9 in ISO BMFF so FFmpeg can carry the spherical side data into WebM on remux.
            cmd.addAll(List.of("-f", "mp4"));
        }
        cmd.add(output.toString());
        Reprise.LOGGER.info("Starting {}", String.join(" ", cmd));
        this.log = log;
        this.process = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        this.stdin = this.process.getOutputStream();
    }

    private static List<String> hardwareEncoder(String ffmpeg, ExportSettings settings, int width, int height, Path log) throws IOException {
        String codec = settings.format == ExportSettings.Format.MP4_H264 ? "h264_" : "hevc_";
        boolean mac = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("mac");
        List<String> candidates = mac ? List.of("videotoolbox") : List.of("nvenc", "qsv", "amf");
        // A listed encoder can still lack a usable driver or reject this resolution.
        for (String candidate : candidates) {
            List<String> options = new ArrayList<>(List.of("-c:v", codec + candidate, "-pix_fmt", "nv12",
                "-b:v", settings.bitrateMbps + "M"));
            if (candidate.equals("videotoolbox")) {
                options.addAll(List.of("-allow_sw", "0"));
            }
            List<String> probe = new ArrayList<>(List.of(ffmpeg, "-nostdin", "-v", "error", "-f", "lavfi",
                "-i", "color=s=" + width + "x" + height + ":r=" + settings.fps, "-frames:v", "1", "-vf", "format=rgba,vflip"));
            probe.addAll(options);
            probe.addAll(List.of("-f", "null", "-"));
            Process process = new ProcessBuilder(probe).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) {
                    return options;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Hardware encoder check interrupted", e);
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly().onExit().join();
                }
            }
        }
        throw new IOException(net.minecraft.client.resources.language.I18n.get("reprise.export.no_hardware"));
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
                this.process.destroyForcibly().onExit().join();
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (this.process.exitValue() != 0) {
            Reprise.LOGGER.error("ffmpeg failed ({})", this.process.exitValue());
            return false;
        }
        Files.deleteIfExists(this.log);
        return true;
    }

    @Override
    public void abort() {
        this.process.destroyForcibly().onExit().join();
    }

    /** Runs ffmpeg once with these arguments; true if it succeeded. */
    static boolean run(String ffmpeg, List<String> args, Path log) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-y"));
        cmd.addAll(args);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            if (!p.waitFor(10, TimeUnit.MINUTES)) return false;
        } finally {
            if (p.isAlive()) p.destroyForcibly().onExit().join();
        }
        if (p.exitValue() == 0) {
            Files.deleteIfExists(log);
            return true;
        }
        Reprise.LOGGER.error("ffmpeg failed ({})", p.exitValue());
        return false;
    }

    static void reportLog(Path log) {
        if (!Files.isRegularFile(log)) return;
        try (var input = new java.io.RandomAccessFile(log.toFile(), "r")) {
            int count = (int) Math.min(8192, input.length());
            input.seek(input.length() - count);
            byte[] tail = new byte[count];
            input.readFully(tail);
            Reprise.LOGGER.error("ffmpeg output: {}", new String(tail, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            Reprise.LOGGER.warn("Could not read encoder output", e);
        }
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

package driver;

import com.metrovoc.reprise.ReprisePaths;
import com.metrovoc.reprise.playback.ReplaySession;
import com.mojang.blaze3d.platform.NativeImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;

/** Check the encoded pixels, after game rendering and GPU readback. */
final class FrameChecks {
    private FrameChecks() {}

    static void verify(Minecraft mc, String arg) throws Exception {
        String[] args = arg.split(" ");
        String replay = ReplaySession.active().file().path().getFileName().toString();
        String prefix = replay.replaceFirst("\\.reprise$", "") + "_";
        List<Path> sequences;
        try (var files = Files.list(ReprisePaths.exports())) {
            sequences = files.filter(Files::isDirectory).filter(p -> p.getFileName().toString().startsWith(prefix)).sorted(Comparator.reverseOrder()).limit(3).toList();
        }
        if (args[0].equals("soundtrack")) {
            checkSoundtrack(sequences.getFirst(), Integer.parseInt(args[1]));
            return;
        }
        String frame = String.format("frame_%06d.png", Integer.parseInt(args[1]));
        try (var file = Files.newInputStream(sequences.getFirst().resolve(frame)); var image = NativeImage.read(file)) {
            if (args[0].equals("supersample")) {
                try (var low = Files.newInputStream(sequences.get(1).resolve(frame)); var resolved = NativeImage.read(low);
                     var single = Files.newInputStream(sequences.get(2).resolve(frame)); var nativeSize = NativeImage.read(single)) {
                    checkSupersampling(image, resolved, nativeSize);
                }
                return;
            }
            if (args[0].equals("solid")) {
                int expected = Integer.parseUnsignedInt(args[2], 16);
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        if (image.getPixel(x, y) != expected) {
                            throw new IllegalStateException("Unexpected pixel at " + x + ", " + y);
                        }
                    }
                }
                return;
            }
            if (!args[0].equals("composite")) {
                throw new IllegalArgumentException(arg);
            }
            try (var previous = Files.newInputStream(sequences.get(1).resolve(frame)); var green = NativeImage.read(previous)) {
                int clear = 0, partial = 0, opaque = 0;
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        int rgba = image.getPixel(x, y), alpha = rgba >>> 24;
                        if (alpha == 0) clear++;
                        else if (alpha == 255) opaque++;
                        else partial++;
                        int reference = green.getPixel(x, y);
                        for (int shift = 0; shift <= 16; shift += 8) {
                            int bg = shift == 8 ? 255 : 0;
                            int channel = ((rgba >>> shift & 255) * alpha + bg * (255 - alpha) + 127) / 255;
                            if (Math.abs(channel - (reference >>> shift & 255)) > 2) {
                                throw new IllegalStateException("Composite differs at " + x + ", " + y);
                            }
                        }
                    }
                }
                if (clear == 0 || opaque == 0 || args.length > 2 && args[2].equals("glass") && partial == 0) {
                    throw new IllegalStateException("Missing expected alpha coverage in " + frame);
                }
            }
        }
    }

    private static void checkSupersampling(NativeImage reference, NativeImage resolved, NativeImage nativeSize) {
        int width = resolved.getWidth(), height = resolved.getHeight();
        if (reference.getWidth() != width * 2 || reference.getHeight() != height * 2
            || nativeSize.getWidth() != width || nativeSize.getHeight() != height) {
            throw new IllegalStateException("Unexpected frame dimensions");
        }
        long nativeError = 0, resolvedError = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int a = reference.getPixel(x * 2, y * 2), b = reference.getPixel(x * 2 + 1, y * 2);
                int c = reference.getPixel(x * 2, y * 2 + 1), d = reference.getPixel(x * 2 + 1, y * 2 + 1);
                int result = resolved.getPixel(x, y), original = nativeSize.getPixel(x, y);
                for (int shift = 0; shift <= 16; shift += 8) {
                    int avg = ((a >>> shift & 255) + (b >>> shift & 255) + (c >>> shift & 255) + (d >>> shift & 255) + 2) / 4;
                    nativeError += Math.abs(avg - (original >>> shift & 255));
                    resolvedError += Math.abs(avg - (result >>> shift & 255));
                }
            }
        }
        if (resolvedError > 3L * width * height || resolvedError >= nativeError) {
            throw new IllegalStateException("Supersampling error " + resolvedError + ", native error " + nativeError);
        }
    }

    private static void checkSoundtrack(Path sequence, int fps) throws Exception {
        long frames;
        try (var files = Files.list(sequence)) {
            frames = files.filter(p -> p.toString().endsWith(".png")).count();
        }
        try (var wav = javax.sound.sampled.AudioSystem.getAudioInputStream(sequence.resolve("audio.wav").toFile())) {
            var format = wav.getFormat();
            if (format.getChannels() != 2 || format.getSampleRate() != 48000 || format.getSampleSizeInBits() != 16
                || wav.getFrameLength() != frames * 48000 / fps) {
                throw new IllegalStateException("Unexpected WAV format or duration: " + format);
            }
            int peak = 0;
            byte[] buffer = new byte[8192];
            for (int n; (n = wav.read(buffer)) != -1;) {
                for (int i = 0; i < n; i += 2) {
                    peak = Math.max(peak, Math.abs((short) ((buffer[i] & 255) | buffer[i + 1] << 8)));
                }
            }
            if (peak < 128) {
                throw new IllegalStateException("Expected audible block placements, got peak " + peak);
            }
        }
    }
}

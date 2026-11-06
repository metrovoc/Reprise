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
            sequences = files.filter(Files::isDirectory).filter(p -> p.getFileName().toString().startsWith(prefix)).sorted(Comparator.reverseOrder()).limit(2).toList();
        }
        String frame = String.format("frame_%06d.png", Integer.parseInt(args[1]));
        try (var file = Files.newInputStream(sequences.getFirst().resolve(frame)); var image = NativeImage.read(file)) {
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
}

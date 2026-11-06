package com.metrovoc.reprise;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.neoforged.fml.loading.FMLPaths;

public final class ReprisePaths {
    public static Path root() {
        return FMLPaths.GAMEDIR.get().resolve("reprise");
    }

    /** Finished replays, one {@code .reprise} file each. */
    public static Path replays() {
        return dir(root().resolve("replays"));
    }

    /** Recordings in progress, one folder each; a folder left behind is a recording that can be recovered. */
    public static Path recordings() {
        return dir(root().resolve("recordings"));
    }

    /** Editor projects (keyframes, settings) by replay uuid. */
    public static Path projects() {
        return dir(root().resolve("projects"));
    }

    public static Path trash() {
        return dir(root().resolve("trash"));
    }

    public static Path exports() {
        return dir(root().resolve("exports"));
    }

    private static Path dir(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return path;
    }

    private ReprisePaths() {}
}

package com.metrovoc.reprise.export;

import com.metrovoc.reprise.Reprise;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/** NeoForge entry point for renderers implementing the FREX Flawless Frames protocol. */
public final class FlawlessFrames {
    private static final List<Consumer<Boolean>> controllers = new CopyOnWriteArrayList<>();

    private FlawlessFrames() {}

    public static void acceptController(Function<String, Consumer<Boolean>> provider) {
        controllers.add(provider.apply(Reprise.MOD_ID));
    }

    static void setEnabled(boolean enabled) {
        for (var controller : controllers) {
            controller.accept(enabled);
        }
    }
}

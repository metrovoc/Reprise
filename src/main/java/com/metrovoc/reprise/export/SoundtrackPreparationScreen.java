package com.metrovoc.reprise.export;

import com.metrovoc.reprise.editor.ExportSettings;
import com.metrovoc.reprise.playback.ReplaySession;
import java.io.IOException;
import java.util.concurrent.CompletionException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Keep the client responsive when a reopened shot needs to decode a long soundtrack. */
final class SoundtrackPreparationScreen extends Screen {
    private final ReplaySession session;
    private final ExportSettings settings;
    private final Screen returnTo, origin;
    private final Soundtrack.Preparation preparation;

    static void start(ReplaySession session, ExportSettings settings, Screen returnTo) throws Exception {
        if (settings.audio && !session.project().audioCues.isEmpty()) {
            var preparation = session.soundtrack.prepareExport(session, settings);
            if (!preparation.ready().isDone()) {
                Minecraft.getInstance().setScreen(new SoundtrackPreparationScreen(session, settings, returnTo, preparation));
                return;
            }
            check(preparation);
        }
        VideoExporter.start(session, settings, returnTo);
    }

    private SoundtrackPreparationScreen(ReplaySession session, ExportSettings settings, Screen returnTo, Soundtrack.Preparation preparation) {
        super(Component.translatable("reprise.audio.loading"));
        this.session = session;
        this.settings = settings;
        this.returnTo = returnTo;
        this.origin = Minecraft.getInstance().screen;
        this.preparation = preparation;
    }

    private static void check(Soundtrack.Preparation preparation) throws IOException {
        try {
            preparation.ready().join();
        } catch (CompletionException e) {
            throw new IOException(e.getCause().getMessage(), e.getCause());
        }
    }

    @Override protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
            .bounds(width / 2 - 48, height / 2 + 8, 96, 20).build());
    }

    @Override public void tick() {
        if (!preparation.ready().isDone()) return;
        try {
            check(preparation);
            VideoExporter.start(session, settings, returnTo);
        } catch (Exception e) {
            var message = Component.literal(String.valueOf(e.getMessage()));
            if (returnTo instanceof ExportQueueScreen queue) queue.completed(false, message, true);
            else minecraft.setScreen(new AlertScreen(() -> minecraft.setScreen(origin), title, message));
        }
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
        super.extractRenderState(g, mx, my, partial);
        g.centeredText(font, title, width / 2, height / 2 - 16, -1);
    }

    @Override public void onClose() {
        preparation.cancel().run();
        if (returnTo instanceof ExportQueueScreen queue) queue.completed(false, Component.translatable("reprise.export.cancelled"), true);
        else minecraft.setScreen(origin);
    }
}

package com.metrovoc.reprise.export;

import com.metrovoc.reprise.Reprise;
import com.metrovoc.reprise.editor.ExportSettings;
import com.metrovoc.reprise.editor.ReplayProject;
import com.metrovoc.reprise.gui.Timecode;
import com.metrovoc.reprise.playback.ReplaySession;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Export settings: format, resolution, frame rate and quality. */
public final class ExportScreen extends Screen {
    private static final int[][] RESOLUTIONS = {{1280, 720}, {1920, 1080}, {2560, 1440}, {3840, 2160}, {1080, 1920}, {1080, 1080}};
    private static final int[] FRAME_RATES = {24, 30, 60, 120};
    private static final int[] QUALITIES = {12, 16, 18, 23, 28};

    private final Screen parent;
    private final ReplaySession session;
    private final ExportSettings settings;
    private String error;
    private String ffmpeg;

    public ExportScreen(Screen parent, ReplaySession session) {
        super(Component.translatable("reprise.export.title"));
        this.parent = parent;
        this.session = session;
        this.settings = session.project().export;
    }

    @Override
    protected void init() {
        this.ffmpeg = FfmpegSink.find(this.settings.ffmpeg);
        int w = 200;
        int x = this.width / 2 - w / 2;
        int y = this.height / 2 - 70;
        cycle(x, y, w, () -> Component.translatable("reprise.export.format", this.settings.format.label), () -> {
            ExportSettings.Format[] all = ExportSettings.Format.values();
            this.settings.format = all[(this.settings.format.ordinal() + 1) % all.length];
        });
        cycle(x, y + 24, w, () -> Component.translatable("reprise.export.resolution", this.settings.width + "×" + this.settings.height), () -> {
            int i = 0;
            for (int j = 0; j < RESOLUTIONS.length; j++) {
                if (RESOLUTIONS[j][0] == this.settings.width && RESOLUTIONS[j][1] == this.settings.height) {
                    i = j + 1;
                }
            }
            int[] r = RESOLUTIONS[i % RESOLUTIONS.length];
            this.settings.width = r[0];
            this.settings.height = r[1];
        });
        cycle(x, y + 48, w, () -> Component.translatable("reprise.export.fps", this.settings.fps), () -> this.settings.fps = next(FRAME_RATES, this.settings.fps));
        cycle(x, y + 72, w, () -> Component.translatable("reprise.export.quality", this.settings.quality), () -> this.settings.quality = next(QUALITIES, this.settings.quality));

        addRenderableWidget(Button.builder(Component.translatable("reprise.export.start"), b -> startExport()).bounds(x, y + 120, w / 2 - 2, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(x + w / 2 + 2, y + 120, w / 2 - 2, 20).build());
    }

    private void cycle(int x, int y, int w, Supplier<Component> label, Runnable next) {
        Button button = Button.builder(label.get(), b -> {
            next.run();
            b.setMessage(label.get());
            this.session.project().save();
        }).bounds(x, y, w, 20).build();
        addRenderableWidget(button);
    }

    private static int next(int[] values, int current) {
        for (int v : values) {
            if (v > current) {
                return v;
            }
        }
        return values[0];
    }

    private void startExport() {
        try {
            this.session.project().save();
            VideoExporter.start(this.session, this.settings, this.parent);
        } catch (Exception e) {
            Reprise.LOGGER.error("Could not start the export", e);
            this.error = String.valueOf(e.getMessage());
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        g.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 96, 0xFFFFFFFF);
        ReplayProject project = this.session.project();
        int start = project.start(), end = project.end(this.session.duration());
        int frames = project.frameTicks(start, end, this.settings.fps).length;
        String range = Component.translatable("reprise.export.range", Timecode.precise(start), Timecode.precise(end), frames).getString();
        g.centeredText(this.font, range, this.width / 2, this.height / 2 + 28, 0xFFB0B0B8);
        String camera = project.keyframes.isEmpty() ? Component.translatable("reprise.export.no_keyframes").getString()
            : Component.translatable("reprise.export.keyframes", project.keyframes.size()).getString();
        g.centeredText(this.font, camera, this.width / 2, this.height / 2 + 38, 0xFFB0B0B8);
        if (this.settings.format != ExportSettings.Format.PNG_SEQUENCE && this.ffmpeg == null) {
            g.centeredText(this.font, Component.translatable("reprise.export.no_ffmpeg"), this.width / 2, this.height / 2 + 76, 0xFFFF6060);
        }
        if (this.error != null) {
            g.centeredText(this.font, this.error, this.width / 2, this.height / 2 + 88, 0xFFFF6060);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}

package com.metrovoc.reprise.export;

import com.metrovoc.reprise.Reprise;
import com.metrovoc.reprise.editor.ExportSettings;
import com.metrovoc.reprise.editor.ReplayProject;
import com.metrovoc.reprise.gui.Timecode;
import com.metrovoc.reprise.playback.ReplaySession;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Export settings: format, resolution, frame rate and quality. */
public final class ExportScreen extends Screen {
    private static final int[][] RESOLUTIONS = {{1280, 720}, {1920, 1080}, {2560, 1440}, {3840, 2160}, {1080, 1920}, {1080, 1080}};
    private static final int[][] STEREO_RESOLUTIONS = {{1920, 1080}, {2560, 720}, {3840, 1080}, {5120, 1440}, {7680, 2160}};
    private static final int[][] PANORAMAS = {{1024, 512}, {2048, 1024}, {4096, 2048}, {6144, 3072}, {8192, 4096}};
    private static final int[] FRAME_RATES = {24, 30, 60, 120};
    private static final int[] QUALITIES = {12, 16, 18, 23, 28};

    private static final int[] BITRATES = {5, 10, 20, 35, 50, 80, 120};

    private final Screen parent;
    private final ReplaySession session;
    private final ExportSettings settings;
    private String error;
    private String ffmpeg;
    private Button alphaButton;
    private Button depthButton;
    private Button encoderButton;
    private Button qualityButton;
    private long frameCount;
    private boolean shaders;

    public ExportScreen(Screen parent, ReplaySession session) {
        super(Component.translatable("reprise.export.title"));
        this.parent = parent;
        this.session = session;
        this.settings = session.project().export;
    }

    @Override
    protected void init() {
        updateFrameCount();
        this.shaders = ShaderPacks.active();
        this.ffmpeg = FfmpegSink.find(this.settings.ffmpeg);
        int w = 200;
        int x = this.width / 2 - w / 2;
        int y = this.height / 2 - 92;
        cycle(x, y, w - 76, () -> Component.translatable("reprise.export.format", this.settings.format.label), () -> {
            ExportSettings.Format[] all = ExportSettings.Format.values();
            this.settings.format = all[(this.settings.format.ordinal() + 1) % all.length];
            updateEncoder();
            updatePasses();
        });
        cycle(x + w - 72, y, 72, () -> Component.translatable(settings.panorama ? "reprise.export.panorama" : settings.stereo ? "reprise.export.stereo" : "reprise.export.flat"), () -> {
            boolean flat = !settings.panorama && !settings.stereo;
            settings.stereo = settings.panorama;
            settings.panorama = flat;
            rebuildWidgets();
        });
        var resolution = cycle(x, y + 24, w - 68, this::resolutionLabel, () -> {
            var sizes = settings.panorama ? PANORAMAS : settings.stereo ? STEREO_RESOLUTIONS : RESOLUTIONS;
            int i = 0;
            for (int j = 0; j < sizes.length; j++) {
                if (sizes[j][0] == this.settings.width && (settings.panorama || sizes[j][1] == this.settings.height)) {
                    i = j + 1;
                }
            }
            int[] r = sizes[i % sizes.length];
            this.settings.width = r[0];
            this.settings.height = r[1];
        });
        cycle(x, y + 48, w / 2 - 2, () -> Component.translatable("reprise.export.fps", this.settings.fps), () -> {
            this.settings.fps = next(FRAME_RATES, this.settings.fps);
            updateFrameCount();
        });
        addRenderableWidget(Button.builder(Component.translatable("reprise.queue.open", this.session.project().renderQueue.size()),
            b -> this.minecraft.setScreen(new ExportQueueScreen(this, this.session))).bounds(x + w / 2 + 2, y + 48, w / 2 - 2, 20).build());
        cycle(x, y + 96, w / 2 - 2, () -> Component.translatable(this.settings.audio ? "reprise.export.audio.on" : "reprise.export.audio.off"),
            () -> this.settings.audio = !this.settings.audio);
        this.qualityButton = cycle(x, y + 72, w / 2 - 2, this::qualityLabel, () -> {
            if (hardware()) {
                this.settings.bitrateMbps = next(BITRATES, this.settings.bitrateMbps);
            } else {
                this.settings.quality = next(QUALITIES, this.settings.quality);
            }
        });
        this.encoderButton = cycle(x + w / 2 + 2, y + 96, w / 2 - 2, this::encoderLabel, () -> {
            this.settings.hardwareEncoder = !this.settings.hardwareEncoder;
            updateEncoder();
        });
        updateEncoder();

        cycle(x + w / 2 + 2, y + 72, w / 2 - 2,
            () -> Component.translatable(this.settings.supersample ? "reprise.export.ssaa.on" : "reprise.export.ssaa.off"),
            () -> this.settings.supersample = !this.settings.supersample)
            .setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("reprise.export.ssaa.tooltip")));
        this.alphaButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            this.session.project().checkpoint();
            this.settings.transparentSky = !this.settings.transparentSky;
            this.session.project().save();
            updatePasses();
        }).bounds(x, y + 120, 113, 20).build());
        this.depthButton = cycle(x + 117, y + 120, w - 117,
            this::depthLabel, () -> this.settings.depthMap = !this.settings.depthMap);
        this.depthButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("reprise.export.depth.tooltip")));
        if (settings.panorama) {
            addRenderableWidget(new AbstractSliderButton(x + w - 64, y + 24, 64, 20, angleLabel(), (settings.panoramaFov - 120) / 240.0) {
                @Override protected void applyValue() {
                    int fov = 120 + (int) Math.round(value * 48) * 5;
                    if (fov != settings.panoramaFov) {
                        if (!dragging) session.project().checkpoint();
                        settings.panoramaFov = fov;
                        if (!dragging) session.project().save();
                    }
                }
                @Override protected void updateMessage() {
                    setMessage(angleLabel());
                    resolution.setMessage(resolutionLabel());
                }
                @Override public void onClick(MouseButtonEvent event, boolean doubleClick) {
                    session.project().checkpoint();
                    super.onClick(event, doubleClick);
                }
                @Override public void onRelease(MouseButtonEvent event) {
                    super.onRelease(event);
                    session.project().save();
                }
            }).setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("reprise.export.coverage.tooltip")));
        } else {
            var hud = cycle(x + w - 64, y + 24, 64,
                () -> Component.translatable(!this.settings.hideHud ? "reprise.export.hud.on" : this.settings.handsOnly ? "reprise.export.hud.hands" : "reprise.export.hud.off"),
                () -> {
                    if (!this.settings.hideHud) {
                        this.settings.hideHud = true;
                        this.settings.handsOnly = true;
                    } else if (this.settings.handsOnly) {
                        this.settings.handsOnly = false;
                    } else {
                        this.settings.hideHud = false;
                    }
                });
            hud.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("reprise.export.hud.tooltip")));
        }
        updatePasses();

        addRenderableWidget(Button.builder(Component.translatable("reprise.export.start"), b -> startExport()).bounds(x, y + 147, w / 2 - 2, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(x + w / 2 + 2, y + 147, w / 2 - 2, 20).build());
    }

    private Component resolutionLabel() {
        return Component.translatable("reprise.export.resolution", settings.outputWidth() + "×" + settings.outputHeight());
    }

    private Component angleLabel() {
        return Component.translatable("reprise.export.coverage", settings.panoramaFov);
    }

    private void updateFrameCount() {
        var project = this.session.project();
        this.frameCount = project.frameSchedule(project.start(), project.end(this.session.duration()), this.settings.fps).frameCount();
    }

    private boolean hardware() {
        return this.settings.supportsHardware() && this.settings.hardwareEncoder;
    }

    private Component encoderLabel() {
        return Component.translatable(hardware() ? "reprise.export.encoder.gpu" : "reprise.export.encoder.cpu");
    }

    private Component qualityLabel() {
        return hardware() ? Component.translatable("reprise.export.bitrate", this.settings.bitrateMbps)
            : Component.translatable("reprise.export.quality", this.settings.quality);
    }

    private void updateEncoder() {
        this.encoderButton.active = this.settings.supportsHardware();
        this.encoderButton.setMessage(encoderLabel());
        this.qualityButton.active = this.settings.supportsHardware() || this.settings.format == ExportSettings.Format.WEBM_VP9;
        this.qualityButton.setMessage(qualityLabel());
    }

    private Component depthLabel() {
        boolean enabled = this.settings.format == ExportSettings.Format.PNG_SEQUENCE && this.settings.depthMap;
        return Component.translatable("reprise.export.depth", Component.translatable(enabled ? "options.on" : "options.off"));
    }

    private void updatePasses() {
        this.depthButton.active = this.settings.format == ExportSettings.Format.PNG_SEQUENCE;
        this.depthButton.setMessage(depthLabel());
        this.alphaButton.active = this.settings.supportsAlpha() && (!this.shaders || this.settings.transparentSky);
        this.alphaButton.setTooltip(this.shaders && this.settings.supportsAlpha()
            ? net.minecraft.client.gui.components.Tooltip.create(Component.translatable("reprise.export.alpha_shaders")) : null);
        this.alphaButton.setMessage(Component.translatable("reprise.export.transparent_sky",
            Component.translatable(this.alphaButton.active && this.settings.transparentSky ? "options.on" : "options.off")));
    }

    private Button cycle(int x, int y, int w, Supplier<Component> label, Runnable next) {
        Button button = Button.builder(label.get(), b -> {
            this.session.project().checkpoint();
            next.run();
            b.setMessage(label.get());
            this.session.project().save();
        }).bounds(x, y, w, 20).build();
        return addRenderableWidget(button);
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
        g.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 107, 0xFFFFFFFF);
        ReplayProject project = this.session.project();
        int start = project.start(), end = project.end(this.session.duration());
        String range = Component.translatable("reprise.export.range", Timecode.precise(start), Timecode.precise(end), this.frameCount).getString();
        g.centeredText(this.font, range, this.width / 2, this.height / 2 + 84, 0xFFB0B0B8);
        String camera = project.keyframes.isEmpty() ? Component.translatable("reprise.export.no_keyframes").getString()
            : Component.translatable("reprise.export.keyframes", project.keyframes.size()).getString();
        g.centeredText(this.font, camera, this.width / 2, this.height / 2 + 94, 0xFFB0B0B8);
        if (this.settings.format != ExportSettings.Format.PNG_SEQUENCE && this.ffmpeg == null) {
            g.centeredText(this.font, Component.translatable("reprise.export.no_ffmpeg"), this.width / 2, this.height / 2 + 108, 0xFFFF6060);
        }
        if (this.error != null) {
            g.centeredText(this.font, this.error, this.width / 2, this.height / 2 + 120, 0xFFFF6060);
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

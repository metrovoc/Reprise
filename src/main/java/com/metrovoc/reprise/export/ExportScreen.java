package com.metrovoc.reprise.export;

import com.metrovoc.reprise.editor.FrameRate;
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
    private Component outputSummary;
    private boolean shaders;

    public ExportScreen(Screen parent, ReplaySession session) {
        super(Component.translatable("reprise.export.title"));
        this.parent = parent;
        this.session = session;
        this.settings = session.project().export;
    }

    /** Rare options stay folded away until someone opens them; remembered for the session. */
    private static boolean advanced;
    private int labelX;
    private final java.util.List<Object[]> labels = new java.util.ArrayList<>();
    private int summaryY;

    @Override
    protected void init() {
        error = null;
        labels.clear();
        updateOutputSummary();
        this.shaders = ShaderPacks.active();
        this.ffmpeg = FfmpegSink.find(this.settings.ffmpeg);
        int formWidth = 300;
        int left = this.width / 2 - formWidth / 2;
        int controlX = left + 110, controlW = formWidth - 110;
        labelX = left;
        int rows = advanced ? 5 : 6;
        int y = Math.max(30, this.height / 2 - (rows * 22 + 70) / 2);

        Button resolution = null;
        if (!advanced) {
        row(y, "reprise.export.row.format");
        cycle(controlX, y, controlW, () -> Component.literal(this.settings.format.label), () -> {
            ExportSettings.Format[] all = ExportSettings.Format.values();
            this.settings.format = all[(this.settings.format.ordinal() + 1) % all.length];
            updateEncoder();
            updatePasses();
        });
        y += 22;
        row(y, "reprise.export.row.size");
        resolution = cycle(controlX, y, controlW - 64, this::resolutionLabel, () -> {
            var sizes = switch (settings.projection()) {
                case ODS, PANORAMA, CUBE -> PANORAMAS;
                case STEREO -> STEREO_RESOLUTIONS;
                case FLAT -> RESOLUTIONS;
            };
            int i = 0;
            for (int j = 0; j < sizes.length; j++) {
                if (sizes[j][0] == this.settings.width && (settings.spherical() || settings.cubeMap || sizes[j][1] == this.settings.height)) {
                    i = j + 1;
                }
            }
            int[] r = sizes[i % sizes.length];
            this.settings.width = r[0];
            this.settings.height = r[1];
        });
        addRenderableWidget(Button.builder(Component.translatable("reprise.export.custom"),
            b -> minecraft.setScreen(new CustomVideoScreen(this, session.project(), session.duration())))
            .bounds(controlX + controlW - 60, y, 60, 20)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("reprise.export.custom.tooltip"))).build());
        y += 22;
        row(y, "reprise.export.row.fps");
        cycle(controlX, y, controlW, () -> Component.translatable("reprise.export.fps_value", this.settings.frameRate().toString()), () -> {
            this.settings.setFrameRate(new FrameRate(next(FRAME_RATES, (int) settings.frameRate().value()), 1));
            updateOutputSummary();
        });
        y += 22;
        row(y, "reprise.export.row.quality");
        this.qualityButton = cycle(controlX, y, controlW, this::qualityLabel, () -> {
            if (hardware()) {
                this.settings.bitrateMbps = next(BITRATES, this.settings.bitrateMbps);
            } else {
                this.settings.quality = next(QUALITIES, this.settings.quality);
            }
        });
        y += 22;
        row(y, "reprise.export.row.sound");
        cycle(controlX, y, controlW, () -> Component.translatable(this.settings.audio ? "reprise.export.sound.on" : "reprise.export.sound.off"),
            () -> this.settings.audio = !this.settings.audio);
        y += 22;
        row(y, "reprise.export.row.overlay");
        var hud = cycle(controlX, y, controlW,
            () -> Component.translatable(settings.cubeMap ? "reprise.export.hud.off" : !this.settings.hideHud ? "reprise.export.hud.on" : this.settings.handsOnly ? "reprise.export.hud.hands" : "reprise.export.hud.off"),
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
        hud.active = !settings.cubeMap;
        hud.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("reprise.export.hud.tooltip")));
        y += 26;

        }

        this.encoderButton = Button.builder(Component.empty(), b -> {}).build();
        this.alphaButton = Button.builder(Component.empty(), b -> {}).build();
        this.depthButton = Button.builder(Component.empty(), b -> {}).build();
        if (advanced) {
            row(y, "reprise.export.row.projection");
            cycle(controlX, y, settings.spherical() ? controlW - 68 : controlW, () -> Component.translatable(settings.projection().labelKey), () -> {
                var modes = ExportSettings.Projection.values();
                settings.setProjection(modes[(settings.projection().ordinal() + 1) % modes.length]);
                rebuildWidgets();
            });
            if (settings.spherical()) {
                addRenderableWidget(new AbstractSliderButton(controlX + controlW - 64, y, 64, 20, angleLabel(), (settings.panoramaFov - 120) / 240.0) {
                    @Override protected void applyValue() {
                        int fov = 120 + (int) Math.round(value * 48) * 5;
                        if (fov != settings.panoramaFov) {
                            if (!dragging) session.project().checkpoint();
                            error = null;
                            settings.panoramaFov = fov;
                            if (!dragging) session.project().save();
                        }
                    }
                    @Override protected void updateMessage() {
                        setMessage(angleLabel());
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
            }
            y += 22;
            row(y, "reprise.export.row.encoder");
            this.encoderButton = cycle(controlX, y, controlW, this::encoderLabel, () -> {
                this.settings.hardwareEncoder = !this.settings.hardwareEncoder;
                updateEncoder();
            });
            y += 22;
            row(y, "reprise.export.row.ssaa");
            cycle(controlX, y, controlW,
                () -> Component.translatable(this.settings.supersample ? "options.on" : "options.off"),
                () -> this.settings.supersample = !this.settings.supersample)
                .setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("reprise.export.ssaa.tooltip")));
            y += 22;
            row(y, "reprise.export.row.alpha");
            this.alphaButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
                this.session.project().checkpoint();
                error = null;
                this.settings.transparentSky = !this.settings.transparentSky;
                this.session.project().save();
                updatePasses();
            }).bounds(controlX, y, controlW, 20).build());
            y += 22;
            row(y, "reprise.export.row.depth");
            this.depthButton = cycle(controlX, y, controlW, this::depthLabel, () -> this.settings.depthMap = !this.settings.depthMap);
            this.depthButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("reprise.export.depth.tooltip")));
            y += 22;
        }
        updateEncoder();
        updatePasses();

        addRenderableWidget(Button.builder(Component.translatable(advanced ? "reprise.export.fewer" : "reprise.export.more"), b -> {
            advanced = !advanced;
            rebuildWidgets();
        }).bounds(left, y + 2, formWidth, 16).build());
        y += 22;
        summaryY = y + 4;
        int by = summaryY + 26;
        addRenderableWidget(Button.builder(Component.translatable("reprise.queue.open", this.session.project().renderQueue.size()),
            b -> this.minecraft.setScreen(new ExportQueueScreen(this, this.session))).bounds(left, by, 96, 20)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("reprise.export.queue.tooltip"))).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(left + formWidth - 196, by, 96, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("reprise.export.start"), b -> startExport()).bounds(left + formWidth - 96, by, 96, 20).build());
    }

    private void row(int y, String key) {
        labels.add(new Object[] {y, Component.translatable(key)});
    }

    private Component resolutionLabel() {
        return Component.translatable("reprise.export.resolution", settings.outputWidth() + "×" + settings.outputHeight());
    }

    private Component angleLabel() {
        return Component.translatable("reprise.export.coverage", settings.panoramaFov);
    }

    private void updateOutputSummary() {
        outputSummary = outputSummary(session.project(), session.meta().durationTicks);
    }

    static Component outputSummary(ReplayProject project, int recordedTicks) {
        double fps = project.export.frameRate().value();
        int end = project.end(project.shotDuration(recordedTicks));
        return outputSummary(project, project.start(), end, fps);
    }

    static Component outputSummary(ReplayProject project, int start, int end, double fps) {
        long frames = project.frameSchedule(start, end, fps).frameCount();
        return Component.translatable(frames == 1 ? "reprise.export.output_duration_one" : "reprise.export.output_duration",
            Timecode.outputDuration(frames, fps), frames);
    }

    private boolean hardware() {
        return this.settings.supportsHardware() && this.settings.hardwareEncoder;
    }

    private Component encoderLabel() {
        return Component.translatable(hardware() ? "reprise.export.encoder.gpu" : "reprise.export.encoder.cpu");
    }

    private Component qualityLabel() {
        if (hardware()) return Component.translatable("reprise.export.bitrate", this.settings.bitrateMbps);
        String name = switch (this.settings.quality) {
            case 12 -> "best";
            case 16 -> "very_high";
            case 18 -> "high";
            case 23 -> "good";
            case 28 -> "small";
            default -> null;
        };
        return name == null ? Component.translatable("reprise.export.quality", this.settings.quality)
            : Component.translatable("reprise.export.quality." + name);
    }

    private void updateEncoder() {
        this.encoderButton.active = this.settings.supportsHardware();
        this.encoderButton.setMessage(encoderLabel());
        this.qualityButton.active = this.settings.supportsHardware() || this.settings.format == ExportSettings.Format.WEBM_VP9;
        this.qualityButton.setMessage(qualityLabel());
    }

    private Component depthLabel() {
        boolean enabled = this.settings.format == ExportSettings.Format.PNG_SEQUENCE && this.settings.depthMap;
        return Component.translatable(enabled ? "options.on" : "options.off");
    }

    private void updatePasses() {
        this.depthButton.active = this.settings.format == ExportSettings.Format.PNG_SEQUENCE;
        this.depthButton.setMessage(depthLabel());
        this.alphaButton.active = this.settings.supportsAlpha() && (!this.shaders || this.settings.transparentSky);
        this.alphaButton.setTooltip(this.shaders && this.settings.supportsAlpha()
            ? net.minecraft.client.gui.components.Tooltip.create(Component.translatable("reprise.export.alpha_shaders")) : null);
        this.alphaButton.setMessage(Component.translatable(this.alphaButton.active && this.settings.transparentSky ? "options.on" : "options.off"));
    }

    private Button cycle(int x, int y, int w, Supplier<Component> label, Runnable next) {
        Button button = Button.builder(label.get(), b -> {
            this.session.project().checkpoint();
            error = null;
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
        error = null;
        try {
            this.session.project().save();
            SoundtrackPreparationScreen.start(this.session, this.settings, this.parent);
        } catch (Exception e) {
            Reprise.LOGGER.error("Could not start the export", e);
            this.error = String.valueOf(e.getMessage());
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        int top = labels.isEmpty() ? 30 : (int) labels.getFirst()[0];
        g.centeredText(this.font, this.title, this.width / 2, top - 18, 0xFFFFFFFF);
        for (Object[] label : labels) {
            g.text(font, (Component) label[1], labelX, (int) label[0] + 6, 0xFFB8B8C2);
        }
        Component problem = error == null ? null : Component.literal(error);
        if (problem == null && settings.format != ExportSettings.Format.PNG_SEQUENCE && ffmpeg == null) {
            problem = Component.translatable("reprise.export.no_ffmpeg");
        }
        if (problem != null) {
            int y = summaryY;
            for (var line : font.split(problem, 300)) {
                g.centeredText(font, line, width / 2, y, 0xFFFF6060);
                y += font.lineHeight;
            }
            return;
        }
        ReplayProject project = this.session.project();
        int start = project.start(), end = project.end(this.session.duration());
        String range = Component.translatable("reprise.export.range", Timecode.precise(start), Timecode.precise(end)).getString();
        String camera = project.keyframes.isEmpty() ? Component.translatable("reprise.export.no_keyframes").getString()
            : Component.translatable("reprise.export.keyframes", project.keyframes.size()).getString();
        g.centeredText(font, outputSummary, width / 2, summaryY, 0xFFFFFFFF);
        g.centeredText(this.font, range + "  ·  " + camera, this.width / 2, summaryY + 11, 0xFF9A9AA6);
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

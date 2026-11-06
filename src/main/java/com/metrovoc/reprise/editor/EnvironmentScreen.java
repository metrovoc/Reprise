package com.metrovoc.reprise.editor;

import com.metrovoc.reprise.editor.EnvironmentSettings.Clock;
import com.metrovoc.reprise.editor.EnvironmentSettings.Weather;
import com.metrovoc.reprise.playback.ReplaySession;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Time of day and weather for the shot. Time is one of three choices: as it was recorded, fixed at a chosen hour, or
 * changing over the shot through keys on the timeline. The hour is set on a clock slider or with a preset.
 */
final class EnvironmentScreen extends Screen {
    private static final int WIDTH = 300;
    /** Sunrise, morning, noon, sunset, night and midnight in game ticks. */
    private static final int[] PRESETS = {23000, 1000, 6000, 12000, 14000, 18000};
    private static final String[] PRESET_NAMES = {"sunrise", "morning", "noon", "sunset", "night", "midnight"};

    private final EditorScreen parent;
    private final ReplaySession session;
    private final List<Object[]> labels = new ArrayList<>();
    /** The hour being edited: the fixed time, or the time for a key at the playhead. */
    private int hour;
    private int left;

    EnvironmentScreen(EditorScreen parent, ReplaySession session) {
        super(Component.translatable("reprise.scene.title"));
        this.parent = parent;
        this.session = session;
        session.setPlaying(false);
    }

    private EnvironmentSettings settings() {
        return session.project().environment;
    }

    @Override
    protected void init() {
        labels.clear();
        left = width / 2 - WIDTH / 2;
        var s = settings();
        var key = s.keyAt(cursor());
        hour = s.clock == Clock.FIXED ? s.fixedTime : key != null ? key.time()
            : Math.floorMod(minecraft.level.getOverworldClockTime(), EnvironmentSettings.DAY);
        int rows = s.clock == Clock.RECORDED ? 5 : s.clock == Clock.FIXED ? 7 : 9;
        int y = Math.max(28, height / 2 - (rows * 22 + 40) / 2);

        label(y, "reprise.scene.time");
        y += 12;
        int third = (WIDTH - 4) / 3;
        choice(left, y, third, "reprise.scene.time.recorded", s.clock == Clock.RECORDED, () -> s.clock = Clock.RECORDED);
        choice(left + third + 2, y, third, "reprise.scene.time.fixed", s.clock == Clock.FIXED, () -> {
            s.fixedTime = hour;
            s.clock = Clock.FIXED;
        });
        choice(left + 2 * third + 4, y, third, "reprise.scene.time.changing", s.clock == Clock.KEYFRAMED, () -> {
            if (s.keys.isEmpty()) s.key(cursor(), hour);
            s.clock = Clock.KEYFRAMED;
        });
        y += 24;

        if (s.clock != Clock.RECORDED) {
            addRenderableWidget(new AbstractSliderButton(left, y, WIDTH, 20, clockLabel(hour), hour / (double) EnvironmentSettings.DAY) {
                @Override protected void updateMessage() {
                    setMessage(clockLabel(sliderHour(value)));
                }

                @Override protected void applyValue() {
                    hour = sliderHour(value);
                    if (s.clock == Clock.FIXED) {
                        s.fixedTime = hour;
                        session.refreshEnvironment();
                    }
                }

                @Override public void onRelease(net.minecraft.client.input.MouseButtonEvent event) {
                    super.onRelease(event);
                    if (s.clock == Clock.FIXED) edit(() -> s.fixedTime = hour);
                }
            });
            y += 22;
            int sixth = (WIDTH - 10) / 6;
            for (int i = 0; i < PRESETS.length; i++) {
                int time = PRESETS[i];
                addRenderableWidget(Button.builder(Component.translatable("reprise.scene.preset." + PRESET_NAMES[i]), b -> {
                    hour = time;
                    if (s.clock == Clock.FIXED) edit(() -> s.fixedTime = time);
                    else rebuildWidgets();
                }).bounds(left + i * (sixth + 2), y, sixth, 18).build());
            }
            y += 24;
        }
        if (s.clock == Clock.KEYFRAMED) {
            int half = (WIDTH - 2) / 2;
            addRenderableWidget(Button.builder(Component.translatable(key == null ? "reprise.scene.key.add" : "reprise.scene.key.update"),
                b -> edit(() -> s.key(cursor(), hour, key == null ? EnvironmentSettings.TimeCurve.FORWARD : key.curve()))).bounds(left, y, half, 20)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("reprise.scene.keys_hint"))).build());
            var remove = addRenderableWidget(Button.builder(Component.translatable("reprise.scene.key.delete"), b -> edit(() -> {
                s.keys.removeIf(k -> k.tick() == cursor());
                if (s.keys.isEmpty()) s.clock = Clock.RECORDED;
            })).bounds(left + half + 2, y, half, 20).build());
            remove.active = key != null;
            y += 22;
            var previous = addRenderableWidget(Button.builder(Component.translatable("reprise.scene.key.previous"), b -> jump(-1))
                .bounds(left, y, half, 20).build());
            var next = addRenderableWidget(Button.builder(Component.translatable("reprise.scene.key.next"), b -> jump(1))
                .bounds(left + half + 2, y, half, 20).build());
            double tick = cursor();
            previous.active = s.keys.stream().anyMatch(k -> k.tick() < tick);
            next.active = s.keys.stream().anyMatch(k -> k.tick() > tick);
            y += 22;
            var curve = addRenderableWidget(Button.builder(Component.translatable("reprise.scene.curve",
                Component.translatable("reprise.environment.curve." + (key == null ? "forward" : key.curve().name().toLowerCase(Locale.ROOT)))),
                b -> edit(() -> {
                    var curves = EnvironmentSettings.TimeCurve.values();
                    s.key(key.tick(), key.time(), curves[(key.curve().ordinal() + 1) % curves.length]);
                })).bounds(left, y, WIDTH, 20).build());
            curve.active = key != null;
            y += 26;
        }

        label(y, "reprise.environment.weather");
        y += 12;
        for (Weather weather : Weather.values()) {
            int i = weather.ordinal();
            choice(left + i % 3 * (third + 2), y + i / 3 * 22, third, "reprise.environment.weather." + weather.name().toLowerCase(Locale.ROOT),
                s.weather == weather, () -> s.weather = weather);
        }
        y += ((Weather.values().length + 2) / 3) * 22 + 6;

        int half = (WIDTH - 2) / 2;
        addRenderableWidget(Button.builder(Component.translatable("reprise.scene.layers"), b ->
            minecraft.setScreen(new SceneLayersScreen(this, session))).bounds(left, y, half, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
            .bounds(left + half + 2, y, half, 20).build());
    }

    private static int sliderHour(double value) {
        // Snap to quarter hours (250 ticks); fine enough for lighting, easy to hit.
        return Math.min(EnvironmentSettings.DAY - 250, (int) Math.round(value * EnvironmentSettings.DAY / 250.0) * 250);
    }

    static Component clockLabel(int ticks) {
        int minutes = (int) ((Math.floorMod(ticks, EnvironmentSettings.DAY) / 1000.0 + 6) * 60) % (24 * 60);
        return Component.translatable("reprise.scene.clock", String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60));
    }

    private void choice(int x, int y, int w, String key, boolean selected, Runnable action) {
        Component label = selected ? Component.literal("● ").append(Component.translatable(key)) : Component.translatable(key);
        addRenderableWidget(Button.builder(label, b -> {
            if (!selected) edit(action);
        }).bounds(x, y, w, 20).build());
    }

    private void label(int y, String key) {
        labels.add(new Object[] {y, Component.translatable(key)});
    }

    private double cursor() {
        return session.project().worldTick(session.visualTick(1));
    }

    private void edit(Runnable action) {
        session.project().checkpoint();
        action.run();
        session.project().save();
        session.refreshEnvironment();
        rebuildWidgets();
    }

    private void jump(int direction) {
        double tick = cursor();
        var candidates = settings().keys.stream().mapToDouble(EnvironmentSettings.TimeKey::tick);
        double target = direction < 0 ? candidates.filter(t -> t < tick).max().orElse(tick)
            : candidates.filter(t -> t > tick).min().orElse(tick);
        session.seekEditorCursor(session.project().shotTick(target));
        rebuildWidgets();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        // A dim panel instead of the blurred world, so changes to the sky and weather show while editing.
        g.fill(0, 0, width, height, 0x60000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        super.extractRenderState(g, mouseX, mouseY, partial);
        int top = labels.isEmpty() ? 30 : (int) labels.getFirst()[0];
        g.centeredText(font, title, width / 2, top - 16, 0xFFFFFFFF);
        for (Object[] label : labels) g.text(font, (Component) label[1], left, (int) label[0], 0xFFB8B8C2);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}

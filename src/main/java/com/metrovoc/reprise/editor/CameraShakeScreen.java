package com.metrovoc.reprise.editor;

import com.metrovoc.reprise.playback.ReplaySession;
import java.util.Locale;
import java.util.function.DoubleFunction;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Handheld camera shake from the playhead on. Pick a strength and it takes effect at once; Custom opens sliders for
 * how far and how fast the view sways side to side and up and down. Keys on the timeline's Scene lane blend between
 * strengths.
 */
final class CameraShakeScreen extends Screen {
    private static final int WIDTH = 300;
    private record Preset(String name, double hHz, double hDeg, double vHz, double vDeg) {}
    private static final Preset[] PRESETS = {
        new Preset("none", 0, 0, 0, 0),
        new Preset("subtle", 0.4, 0.4, 0.5, 0.3),
        new Preset("handheld", 0.9, 1.2, 1.1, 0.8),
        new Preset("strong", 1.8, 3.5, 2.2, 2.5),
    };

    private final EditorScreen parent;
    private final ReplaySession session;
    private final double tick;
    private static boolean custom;
    private int left, top;

    CameraShakeScreen(EditorScreen parent, ReplaySession session) {
        super(Component.translatable("reprise.shake.title"));
        this.parent = parent;
        this.session = session;
        tick = session.visualTick(1);
        if (session.isPlaying()) session.seekEditorCursor(tick);
    }

    private CameraShake.Key current() {
        return session.project().shake(session.duration()).settingsAt(tick);
    }

    private CameraShake.Key keyHere() {
        return session.project().shakeKeys.stream().filter(k -> k.tick() == tick).findFirst().orElse(null);
    }

    @Override
    protected void init() {
        var project = session.project();
        var value = current();
        boolean hasKeys = !project.shakeKeys.isEmpty();
        left = width / 2 - WIDTH / 2;
        int rows = 4 + (custom ? 4 : 0);
        top = Math.max(28, height / 2 - (rows * 22 + 30) / 2);
        int y = top + 12;

        int quarter = (WIDTH - 6) / 4;
        for (int i = 0; i < PRESETS.length; i++) {
            Preset p = PRESETS[i];
            boolean selected = hasKeys && !custom && matches(value, p);
            Component label = Component.translatable("reprise.shake.preset." + p.name);
            if (selected) label = Component.literal("● ").append(label);
            addRenderableWidget(Button.builder(label, b -> {
                custom = false;
                put(new CameraShake.Key(tick, p.hHz, p.hDeg, p.vHz, p.vDeg, value.curve()));
            }).bounds(left + i * (quarter + 2), y, quarter, 20).build());
        }
        y += 22;
        addRenderableWidget(Button.builder(Component.translatable(custom ? "reprise.shake.custom.hide" : "reprise.shake.custom.show"), b -> {
            custom = !custom;
            rebuildWidgets();
        }).bounds(left, y, WIDTH, 16).build());
        y += 20;
        if (custom) {
            y = slider(y, "reprise.shake.side_amount", value.horizontalDegrees(), 10, v -> String.format(Locale.ROOT, "%.1f°", v),
                v -> new CameraShake.Key(tick, current().horizontalHz(), v, current().verticalHz(), current().verticalDegrees(), current().curve()));
            y = slider(y, "reprise.shake.side_speed", value.horizontalHz(), 5, v -> String.format(Locale.ROOT, "%.1f /s", v),
                v -> new CameraShake.Key(tick, v, current().horizontalDegrees(), current().verticalHz(), current().verticalDegrees(), current().curve()));
            y = slider(y, "reprise.shake.up_amount", value.verticalDegrees(), 10, v -> String.format(Locale.ROOT, "%.1f°", v),
                v -> new CameraShake.Key(tick, current().horizontalHz(), current().horizontalDegrees(), current().verticalHz(), v, current().curve()));
            y = slider(y, "reprise.shake.up_speed", value.verticalHz(), 5, v -> String.format(Locale.ROOT, "%.1f /s", v),
                v -> new CameraShake.Key(tick, current().horizontalHz(), current().horizontalDegrees(), v, current().verticalDegrees(), current().curve()));
        }
        var curve = addRenderableWidget(Button.builder(Component.translatable("reprise.shake.curve",
            Component.translatable("reprise.shake.curve." + value.curve().name().toLowerCase(Locale.ROOT))), b -> {
            var curves = CameraShake.Curve.values();
            var v = current();
            put(new CameraShake.Key(tick, v.horizontalHz(), v.horizontalDegrees(), v.verticalHz(), v.verticalDegrees(),
                curves[(v.curve().ordinal() + 1) % curves.length]));
        }).bounds(left, y, WIDTH, 20).build());
        curve.active = keyHere() != null;
        y += 26;
        int third = (WIDTH - 4) / 3;
        var mute = addRenderableWidget(Button.builder(Component.translatable(project.shakeEnabled ? "reprise.shake.mute" : "reprise.shake.unmute"), b -> {
            project.checkpoint();
            project.shakeEnabled = !project.shakeEnabled;
            project.save();
            rebuildWidgets();
        }).bounds(left, y, third, 20).build());
        mute.active = hasKeys;
        var remove = addRenderableWidget(Button.builder(Component.translatable("reprise.shake.remove"), b -> {
            project.checkpoint();
            project.shakeKeys.removeIf(k -> k.tick() == tick);
            project.save();
            rebuildWidgets();
        }).bounds(left + third + 2, y, third, 20).build());
        remove.active = keyHere() != null;
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(left + 2 * third + 4, y, third, 20).build());
    }

    private static boolean matches(CameraShake.Key v, Preset p) {
        return Math.abs(v.horizontalHz() - p.hHz) < 1e-6 && Math.abs(v.horizontalDegrees() - p.hDeg) < 1e-6
            && Math.abs(v.verticalHz() - p.vHz) < 1e-6 && Math.abs(v.verticalDegrees() - p.vDeg) < 1e-6;
    }

    private int slider(int y, String key, double initial, double max, DoubleFunction<String> format, DoubleFunction<CameraShake.Key> make) {
        Component name = Component.translatable(key);
        addRenderableWidget(new AbstractSliderButton(left, y, WIDTH, 20, Component.empty(), Math.clamp(initial / max, 0, 1)) {
            {
                updateMessage();
            }

            @Override protected void updateMessage() {
                setMessage(Component.translatable("reprise.shake.value", name, format.apply(value * max)));
            }

            @Override protected void applyValue() {}

            @Override public void onRelease(net.minecraft.client.input.MouseButtonEvent event) {
                super.onRelease(event);
                put(make.apply(Math.round(value * max * 10) / 10.0));
            }
        });
        return y + 22;
    }

    private void put(CameraShake.Key key) {
        var project = session.project();
        project.checkpoint();
        project.shakeKeys.removeIf(k -> k.tick() == tick);
        project.shakeKeys.add(key);
        project.shakeEnabled = true;
        project.sort();
        project.save();
        rebuildWidgets();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, width, height, 0x60000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
        super.extractRenderState(g, mx, my, partial);
        g.centeredText(font, title, width / 2, top - 14, -1);
        g.text(font, Component.translatable("reprise.shake.from_here", com.metrovoc.reprise.gui.Timecode.precise(tick)), left, top, 0xFFB8B8C2);
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

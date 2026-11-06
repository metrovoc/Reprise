package com.metrovoc.reprise.editor;

import com.metrovoc.reprise.playback.ReplaySession;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class CameraShakeScreen extends Screen {
    private static final String[] LABELS = {"horizontal_hz", "horizontal_degrees", "vertical_hz", "vertical_degrees"};
    private final EditBox[] fields = new EditBox[4];
    private final EditorScreen parent;
    private final ReplaySession session;
    private final double tick;
    private CameraShake.Curve curve;
    private Button save;
    private EditBox time;
    private boolean occupied;

    CameraShakeScreen(EditorScreen parent, ReplaySession session) {
        super(Component.translatable("reprise.shake.title"));
        this.parent = parent;
        this.session = session;
        tick = session.visualTick(1);
        if (session.isPlaying()) session.seekEditorCursor(tick);
    }

    @Override protected void init() {
        var keys = session.project().shakeKeys;
        var key = keys.stream().filter(k -> k.tick() == tick).findFirst().orElse(null);
        var values = session.project().shake(session.duration()).settingsAt(tick);
        double[] defaults = {values.horizontalHz(), values.horizontalDegrees(), values.verticalHz(), values.verticalDegrees()};
        if (fields[0] == null) curve = values.curve();
        int x = width / 2 - 140, y = height / 2 - 89;
        for (int i = 0; i < fields.length; i++) {
            String text = fields[i] == null ? Double.toString(defaults[i]) : fields[i].getValue();
            var field = addRenderableWidget(new EditBox(font, x + (i % 2) * 144, y + 24 + (i / 2) * 36,
                136, 20, Component.translatable("reprise.shake." + LABELS[i])));
            field.setValue(text);
            fields[i] = field;
        }
        String previousTime = time == null ? Double.toString(tick) : time.getValue();
        time = addRenderableWidget(new EditBox(font, x, y + 96, 136, 20, Component.translatable("reprise.key.tick")));
        time.setValue(previousTime);
        addRenderableWidget(Button.builder(curveLabel(), b -> {
            var curves = CameraShake.Curve.values();
            curve = curves[(curve.ordinal() + 1) % curves.length];
            b.setMessage(curveLabel());
        }).bounds(x + 144, y + 96, 136, 20).build());
        addRenderableWidget(Button.builder(enabledLabel(), b -> {
            var project = session.project();
            project.checkpoint();
            project.shakeEnabled = !project.shakeEnabled;
            project.save();
            onClose();
        }).bounds(x, y + 121, 280, 20).build());
        save = addRenderableWidget(Button.builder(Component.translatable("reprise.shake.save"), b -> apply(false))
            .bounds(x, y + 146, 106, 20).build());
        var remove = addRenderableWidget(Button.builder(Component.translatable("reprise.shake.remove"), b -> apply(true))
            .bounds(x + 110, y + 146, 98, 20).build());
        remove.active = key != null;
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(x + 212, y + 146, 68, 20).build());
        for (var field : fields) field.setResponder(text -> save.active = readKey() != null);
        time.setResponder(text -> save.active = readKey() != null);
        save.active = readKey() != null;
    }

    private Component enabledLabel() {
        return Component.translatable(session.project().shakeEnabled ? "reprise.shake.on" : "reprise.shake.off");
    }

    private Component curveLabel() { return Component.translatable("reprise.shake." + curve.name().toLowerCase(java.util.Locale.ROOT)); }

    private CameraShake.Key readKey() {
        occupied = false;
        double target;
        double[] v = new double[4];
        try {
            target = Double.parseDouble(time.getValue());
            if (!Double.isFinite(target) || target < 0 || target > session.duration()) return null;
            occupied = target != tick && session.project().shakeKeys.stream().anyMatch(k -> k.tick() == target);
            if (occupied) return null;
            for (int i = 0; i < v.length; i++) {
                v[i] = Double.parseDouble(fields[i].getValue());
                if (!Double.isFinite(v[i]) || v[i] < 0 || v[i] > (i % 2 == 0 ? 100 : 180)) return null;
            }
        } catch (NumberFormatException invalid) {
            return null;
        }
        return new CameraShake.Key(target, v[0], v[1], v[2], v[3], curve);
    }

    private void apply(boolean remove) {
        var project = session.project();
        var key = readKey();
        if (!remove && key == null) return;
        project.checkpoint();
        project.shakeKeys.removeIf(k -> k.tick() == tick);
        if (!remove) {
            project.shakeKeys.add(key);
            project.sort();
        }
        project.save();
        if (!remove && key.tick() != tick) session.seekEditorCursor(key.tick());
        onClose();
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
        super.extractRenderState(g, mx, my, partial);
        g.centeredText(font, title, width / 2, height / 2 - 96, -1);
        g.text(font, occupied ? Component.translatable("reprise.shake.occupied") : time.getMessage(), time.getX(), time.getY() - 10, occupied ? 0xFFFF8888 : -1);
        for (var field : fields) g.text(font, field.getMessage(), field.getX(), field.getY() - 10, -1);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
}

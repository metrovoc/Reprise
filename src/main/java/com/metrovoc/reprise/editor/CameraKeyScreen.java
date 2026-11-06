package com.metrovoc.reprise.editor;

import com.metrovoc.reprise.playback.ReplaySession;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Edit an existing camera key without replacing its tracking or imported curve. */
final class CameraKeyScreen extends Screen {
    private static final String[] LABELS = {"x", "y", "z", "yaw", "pitch", "roll", "tick", "fov"};
    private final EditBox[] fields = new EditBox[LABELS.length];
    private final EditorScreen parent;
    private final ReplaySession session;
    private final Keyframe key;
    private Button save;
    private Component error;

    CameraKeyScreen(EditorScreen parent, ReplaySession session, Keyframe key) {
        super(Component.translatable("reprise.editor.edit_key"));
        this.parent = parent;
        this.session = session;
        this.key = key;
        session.setPlaying(false);
    }

    @Override
    protected void init() {
        String[] values = {Double.toString(key.x), Double.toString(key.y), Double.toString(key.z),
            Float.toString(key.yaw), Float.toString(key.pitch), Float.toString(key.roll), Double.toString(key.tick), Float.toString(key.fov)};
        int left = width / 2 - 135, top = height / 2 - 52;
        for (int i = 0; i < fields.length; i++) {
            String text = fields[i] == null ? values[i] : fields[i].getValue();
            var field = addRenderableWidget(new EditBox(font, left + (i % 3) * 91, top + (i / 3) * 36, 87, 20,
                Component.translatable("reprise.key." + LABELS[i])));
            field.setValue(text);
            fields[i] = field;
        }
        save = addRenderableWidget(Button.builder(Component.translatable("reprise.key.save"), b -> apply())
            .bounds(left, top + 112, 132, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
            .bounds(left + 137, top + 112, 132, 20).build());
        for (var field : fields) field.setResponder(text -> validateFields());
        validateFields();
    }

    private double[] validateFields() {
        double[] values = new double[fields.length];
        String problem = null;
        try {
            for (int i = 0; i < fields.length; i++) {
                values[i] = Double.parseDouble(fields[i].getValue());
                if (!Double.isFinite(values[i]) || (i >= 3 && i <= 5 && !Float.isFinite((float) values[i]))) {
                    throw new NumberFormatException();
                }
            }
            if (values[6] < 0 || values[6] > session.duration()) problem = "time_range";
            else if ((float) values[7] <= 0 || (float) values[7] >= 180) problem = "fov_range";
            else if (session.project().keyframes.stream().anyMatch(k -> k != key && k.tick == values[6])) problem = "occupied";
        } catch (NumberFormatException e) {
            problem = "number";
        }
        error = problem == null ? null : Component.translatable("reprise.key." + problem);
        save.active = error == null;
        return save.active ? values : null;
    }

    private void apply() {
        double[] v = validateFields();
        if (v == null) return;
        var project = session.project();
        project.checkpoint();
        key.x = v[0];
        key.y = v[1];
        key.z = v[2];
        key.yaw = (float) v[3];
        key.pitch = (float) v[4];
        key.roll = (float) v[5];
        key.tick = v[6];
        key.fov = (float) v[7];
        project.sort();
        project.save();
        onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
        super.extractRenderState(g, mx, my, partial);
        g.centeredText(font, title, width / 2, height / 2 - 80, 0xFFFFFFFF);
        for (var field : fields) g.text(font, field.getMessage(), field.getX(), field.getY() - 10, 0xFFFFFFFF);
        if (error != null) g.centeredText(font, error, width / 2, height / 2 + 44, 0xFFFF8888);
    }

    @Override
    public void onClose() { minecraft.setScreen(parent); }
}

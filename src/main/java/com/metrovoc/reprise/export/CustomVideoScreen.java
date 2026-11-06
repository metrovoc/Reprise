package com.metrovoc.reprise.export;

import com.metrovoc.reprise.editor.ExportSettings;
import com.metrovoc.reprise.editor.FrameRate;
import com.metrovoc.reprise.editor.ReplayProject;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class CustomVideoScreen extends Screen {
    private final Screen parent;
    private final ReplayProject project;
    private final ExportSettings draft = new ExportSettings();
    private final int duration;
    private EditBox horizontal, vertical, rate, start, end;
    private int startTick, endTick;
    private Component runningTime;
    private Button apply;
    private Component feedback;

    CustomVideoScreen(Screen parent, ReplayProject project, int duration) {
        super(Component.translatable("reprise.export.custom_title"));
        this.parent = parent;
        this.duration = duration;
        this.project = project;
        draft.setProjection(project.export.projection());
        draft.panoramaFov = project.export.panoramaFov;
        draft.supersample = project.export.supersample;
    }

    @Override protected void init() {
        var saved = project.export;
        int x = width / 2 - 128, y = height / 2 - 65;
        boolean fixedAspect = draft.spherical() || draft.cubeMap;
        String label = draft.cubeMap ? "face_size" : draft.spherical() ? "eye_height" : "width";
        int size = draft.cubeMap ? saved.captureWidth() : draft.spherical() ? (saved.width & ~3) / 2 : saved.outputWidth();
        horizontal = field(horizontal, label, Integer.toString(size), x, y, fixedAspect ? 160 : 78);
        if (!fixedAspect) vertical = field(vertical, "height", Integer.toString(saved.outputHeight()), x + 82, y, 78);
        rate = field(rate, "rate", saved.frameRate().toString(), x + 164, y, 92);
        start = field(start, "start", project.exportStart < 0 ? "" : Integer.toString(project.exportStart), x, y + 53, 126);
        end = field(end, "end", project.exportEnd < 0 ? "" : Integer.toString(project.end(duration)), x + 130, y + 53, 126);
        start.setHint(Component.literal("0"));
        end.setHint(Component.literal(Integer.toString(duration)));
        apply = addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> {
            if (!validateFields()) return;
            project.checkpoint();
            saved.width = draft.width;
            if (!fixedAspect) saved.height = draft.height;
            saved.setFrameRate(draft.frameRate());
            project.exportStart = startTick;
            project.exportEnd = endTick;
            project.save();
            onClose();
        }).bounds(x, y + 124, 126, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(x + 130, y + 124, 126, 20).build());
        horizontal.setResponder(text -> validateFields());
        if (vertical != null) vertical.setResponder(text -> validateFields());
        start.setResponder(text -> validateFields());
        end.setResponder(text -> validateFields());
        rate.setResponder(text -> validateFields());
        validateFields();
    }

    private EditBox field(EditBox previous, String label, String initial, int x, int y, int w) {
        var field = addRenderableWidget(new EditBox(font, x, y, w, 20, Component.translatable("reprise.export.custom_" + label)));
        field.setMaxLength(24);
        field.setValue(previous == null ? initial : previous.getValue());
        return field;
    }

    private boolean validateFields() {
        try {
            int size = Integer.parseInt(horizontal.getValue());
            int h = vertical == null ? size : Integer.parseInt(vertical.getValue());
            if (size < 2 || h < 2 || size % 2 != 0 || h % 2 != 0) throw new NumberFormatException();
            draft.width = Math.multiplyExact(size, draft.cubeMap ? 4 : draft.spherical() ? 2 : 1);
            draft.height = h;
            if (!draft.fitsDevice(RenderSystem.getDevice().getMaxTextureSize())) throw new ArithmeticException();
        } catch (NumberFormatException | ArithmeticException e) {
            feedback = Component.translatable("reprise.export.custom_size_error");
            return apply.active = false;
        }
        try {
            draft.setFrameRate(FrameRate.parse(rate.getValue()));
        } catch (IllegalArgumentException | ArithmeticException e) {
            feedback = Component.translatable("reprise.export.custom_rate_error");
            return apply.active = false;
        }
        try {
            startTick = boundary(start);
            endTick = boundary(end);
            int first = startTick < 0 ? 0 : startTick;
            int last = endTick < 0 ? duration : endTick;
            if (last < first) throw new NumberFormatException();
            runningTime = ExportScreen.outputSummary(project, first, last, draft.frameRate().value());
        } catch (NumberFormatException e) {
            feedback = Component.translatable("reprise.export.custom_range_error", duration);
            return apply.active = false;
        }
        feedback = Component.translatable("reprise.export.custom_preview", draft.outputWidth(), draft.outputHeight(), draft.frameRate().toString());
        return apply.active = true;
    }

    private int boundary(EditBox box) {
        String text = box.getValue().trim();
        if (text.isEmpty()) return -1;
        int tick = Integer.parseInt(text);
        if (tick < 0 || tick > duration) throw new NumberFormatException();
        return tick;
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
        super.extractRenderState(g, mx, my, partial);
        g.centeredText(font, title, width / 2, horizontal.getY() - 38, 0xFFFFFFFF);
        drawLabel(g, horizontal);
        if (vertical != null) drawLabel(g, vertical);
        drawLabel(g, rate);
        drawLabel(g, start);
        drawLabel(g, end);
        g.centeredText(font, Component.translatable("reprise.export.custom_rate_hint"), width / 2, rate.getBottom() + 7, 0xFFB0B0B8);
        g.centeredText(font, Component.translatable("reprise.export.custom_range_hint"), width / 2, end.getBottom() + 5, 0xFFB0B0B8);
        int y = end.getBottom() + 18;
        for (var line : font.split(feedback, width - 16)) {
            g.centeredText(font, line, width / 2, y, apply.active ? 0xFFFFFFFF : 0xFFFF8888);
            y += font.lineHeight;
        }
        if (apply.active) g.centeredText(font, runningTime, width / 2, y + 2, 0xFFFFFFFF);
    }

    private void drawLabel(GuiGraphicsExtractor g, EditBox field) {
        g.text(font, field.getMessage(), field.getX(), field.getY() - 11, 0xFFFFFFFF);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
}

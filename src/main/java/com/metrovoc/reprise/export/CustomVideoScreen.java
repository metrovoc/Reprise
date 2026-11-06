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
    private EditBox horizontal, vertical, rate;
    private Button apply;
    private Component feedback;

    CustomVideoScreen(Screen parent, ReplayProject project) {
        super(Component.translatable("reprise.export.custom_title"));
        this.parent = parent;
        this.project = project;
        draft.setProjection(project.export.projection());
        draft.panoramaFov = project.export.panoramaFov;
        draft.supersample = project.export.supersample;
    }

    @Override protected void init() {
        var saved = project.export;
        int x = width / 2 - 128, y = height / 2 - 28;
        boolean fixedAspect = draft.spherical() || draft.cubeMap;
        String label = draft.cubeMap ? "face_size" : draft.spherical() ? "eye_height" : "width";
        int size = draft.cubeMap ? saved.captureWidth() : draft.spherical() ? (saved.width & ~3) / 2 : saved.outputWidth();
        horizontal = field(horizontal, label, Integer.toString(size), x, y, fixedAspect ? 160 : 78);
        if (!fixedAspect) vertical = field(vertical, "height", Integer.toString(saved.outputHeight()), x + 82, y, 78);
        rate = field(rate, "rate", saved.frameRate().toString(), x + 164, y, 92);
        apply = addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> {
            if (!validateFields()) return;
            project.checkpoint();
            saved.width = draft.width;
            if (!fixedAspect) saved.height = draft.height;
            saved.setFrameRate(draft.frameRate());
            project.save();
            onClose();
        }).bounds(x, y + 68, 126, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(x + 130, y + 68, 126, 20).build());
        horizontal.setResponder(text -> validateFields());
        if (vertical != null) vertical.setResponder(text -> validateFields());
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
        feedback = Component.translatable("reprise.export.custom_preview", draft.outputWidth(), draft.outputHeight(), draft.frameRate().toString());
        return apply.active = true;
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
        super.extractRenderState(g, mx, my, partial);
        g.centeredText(font, title, width / 2, horizontal.getY() - 38, 0xFFFFFFFF);
        drawLabel(g, horizontal);
        if (vertical != null) drawLabel(g, vertical);
        drawLabel(g, rate);
        g.centeredText(font, Component.translatable("reprise.export.custom_rate_hint"), width / 2, rate.getBottom() + 7, 0xFFB0B0B8);
        int y = rate.getBottom() + 21;
        for (var line : font.split(feedback, width - 16)) {
            g.centeredText(font, line, width / 2, y, apply.active ? 0xFFFFFFFF : 0xFFFF8888);
            y += font.lineHeight;
        }
    }

    private void drawLabel(GuiGraphicsExtractor g, EditBox field) {
        g.text(font, field.getMessage(), field.getX(), field.getY() - 11, 0xFFFFFFFF);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
}

package com.metrovoc.reprise.editor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.metrovoc.reprise.playback.CameraPath;
import com.metrovoc.reprise.playback.ReplaySession;
import java.io.IOException;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class ReplayModPathsScreen extends Screen {
    private final EditorScreen parent;
    private final ReplaySession session;
    private final JsonObject paths;
    private final List<String> names;
    private int page;

    ReplayModPathsScreen(EditorScreen parent, ReplaySession session) throws IOException {
        super(Component.translatable("reprise.editor.replaymod_paths"));
        this.parent = parent;
        this.session = session;
        paths = JsonParser.parseString(session.file().readCameraPaths()).getAsJsonObject();
        names = paths.keySet().stream().sorted().toList();
    }

    @Override
    protected void init() {
        int rows = Math.max(1, (height - 94) / 24);
        int pages = Math.max(1, Math.ceilDiv(names.size(), rows));
        page = Math.min(page, pages - 1);
        int x = width / 2 - 112;
        for (int i = 0; i < rows && page * rows + i < names.size(); i++) {
            String name = names.get(page * rows + i);
            Component label = name.isEmpty() ? Component.translatable("reprise.editor.replaymod_current") : Component.literal(name);
            addRenderableWidget(Button.builder(label, b -> load(name)).bounds(x, 48 + i * 24, 224, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            page--;
            rebuildWidgets();
        }).bounds(x, height - 32, 40, 20).build()).active = page > 0;
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
            .bounds(x + 44, height - 32, 136, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            page++;
            rebuildWidgets();
        }).bounds(x + 184, height - 32, 40, 20).build()).active = page + 1 < pages;
    }

    private void load(String name) {
        ReplayModPath path;
        try {
            var camera = minecraft.gameRenderer.getMainCamera();
            var entity = minecraft.getCameraEntity();
            var current = CameraPath.current();
            float roll = current == null ? com.metrovoc.reprise.playback.CameraController.roll() : current.roll();
            var initial = new CameraPose(entity.getX(), entity.getY(), entity.getZ(), camera.yRot(), camera.xRot(),
                roll, minecraft.options.fov().get());
            path = new ReplayModPath(paths.getAsJsonArray(name), session.meta().durationTicks, initial);
        } catch (RuntimeException e) {
            minecraft.setScreen(new AlertScreen(() -> minecraft.setScreen(this),
                Component.translatable("reprise.editor.path_failed"), Component.literal(String.valueOf(e.getMessage()))));
            return;
        }
        var project = session.project();
        project.checkpoint();
        project.freezes = path.freezes;
        project.keyframes = path.keys;
        project.speedKeys = path.speeds;
        project.exportStart = path.start;
        project.exportEnd = path.end;
        project.sort();
        project.save();
        session.setPlaying(false);
        session.stopSpectating();
        session.seek(path.start);
        CameraPath.setPreview(true);
        minecraft.setScreen(new EditorScreen(session));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mx, int my, float partial) {
        super.extractRenderState(graphics, mx, my, partial);
        graphics.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
        graphics.centeredText(font, Component.translatable(names.isEmpty() ? "reprise.editor.no_saved_paths" : "reprise.editor.path_selection"),
            width / 2, 30, 0xFFA0A0A0);
    }

    @Override
    public void onClose() { minecraft.setScreen(parent); }
}

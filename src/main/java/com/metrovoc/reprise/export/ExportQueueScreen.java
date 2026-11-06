package com.metrovoc.reprise.export;

import com.metrovoc.reprise.Reprise;
import com.metrovoc.reprise.editor.CameraPose;
import com.metrovoc.reprise.editor.QueuedShot;
import com.metrovoc.reprise.editor.ReplayProject;
import com.metrovoc.reprise.gui.Timecode;
import com.metrovoc.reprise.playback.CameraController;
import com.metrovoc.reprise.playback.CameraPath;
import com.metrovoc.reprise.playback.ReplaySession;
import java.util.Collections;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

final class ExportQueueScreen extends Screen {
    private final Screen parent;
    private final ReplaySession session;
    private final ReplayProject editor;
    private ShotList list;
    private Button up, down, remove, render;
    private int selection = -1;
    private double returnTick;
    private CameraPose returnCamera;
    private UUID returnTarget;
    private String draftName = "";
    private Component status = Component.empty();

    ExportQueueScreen(Screen parent, ReplaySession session) {
        super(Component.translatable("reprise.queue.title"));
        this.parent = parent;
        this.session = session;
        this.editor = session.project();
    }

    @Override
    protected void init() {
        Component label = Component.translatable("reprise.queue.name");
        var name = addRenderableWidget(new EditBox(font, width / 2 - 150, 23, 300, 18, label));
        name.setMaxLength(48);
        name.setHint(label);
        name.setValue(draftName);
        name.setResponder(value -> draftName = value);
        list = addRenderableWidget(new ShotList());
        int x = width / 2 - 150, y = height - 52;
        button("add", x, y, 104, () -> {
            editor.queueShot(draftName, camera(), session.spectatedId());
            draftName = "";
            select(editor.renderQueue.size() - 1);
        });
        up = button("up", x + 108, y, 50, () -> move(-1));
        down = button("down", x + 162, y, 50, () -> move(1));
        remove = button("remove", x + 216, y, 84, () -> {
            editor.renderQueue.remove(selection);
            editor.save();
            select(Math.min(selection, editor.renderQueue.size() - 1));
        });
        render = button("render", x, y + 24, 148, () -> {
            returnTick = session.visualTick(1);
            returnCamera = camera();
            returnTarget = session.spectatedId();
            next();
        });
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
            .bounds(x + 152, y + 24, 148, 20).build());
        if (selection >= 0 && selection < list.children().size()) list.setSelected(list.children().get(selection));
        update();
    }

    private Button button(String key, int x, int y, int width, Runnable action) {
        return addRenderableWidget(Button.builder(Component.translatable("reprise.queue." + key), b -> action.run())
            .bounds(x, y, width, 20).build());
    }

    private CameraPose camera() {
        var player = minecraft.player;
        return new CameraPose(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(),
            CameraController.roll(), minecraft.options.fov().get());
    }

    private void apply(CameraPose pose, UUID target) {
        CameraPath.apply(pose);
        CameraController.setRoll(pose.roll());
        minecraft.options.fov().set(Math.round(pose.fov()));
        session.restoreSpectating(target);
    }

    private void move(int offset) {
        Collections.swap(editor.renderQueue, selection, selection + offset);
        editor.save();
        select(selection + offset);
    }

    private void select(int index) {
        selection = index;
        status = Component.empty();
        rebuildWidgets();
        if (list.getSelected() != null) list.center(list.getSelected());
    }

    private void update() {
        boolean idle = returnCamera == null;
        up.active = idle && selection > 0;
        down.active = idle && selection >= 0 && selection + 1 < editor.renderQueue.size();
        remove.active = idle && selection >= 0;
        render.active = idle && !editor.renderQueue.isEmpty();
    }

    String shotName() {
        String name = editor.renderQueue.getFirst().name();
        return name == null ? "" : name;
    }

    private void next() {
        QueuedShot shot = editor.renderQueue.getFirst();
        try {
            session.setPlaying(false);
            session.stopSpectating();
            session.setQueuedProject(shot.project());
            session.seek(shot.project().start());
            apply(shot.camera(), shot.spectating());
            SoundtrackPreparationScreen.start(session, shot.project().export, this);
        } catch (Exception e) {
            Reprise.LOGGER.warn("Could not start queued shot", e);
            if (returnCamera != null) completed(false, Component.literal(String.valueOf(e.getMessage())), true);
        }
    }

    void completed(boolean ok, Component message, boolean showScreen) {
        session.setQueuedProject(null);
        if (ok) {
            editor.renderQueue.removeFirst();
            editor.save();
        }
        selection = editor.renderQueue.isEmpty() ? -1 : 0;
        status = message;
        if (showScreen && ok && !editor.renderQueue.isEmpty()) {
            next();
            return;
        }
        if (showScreen) {
            session.seekEditorCursor(returnTick);
            apply(returnCamera, returnTarget);
        } else {
            minecraft.options.fov().set(Math.round(returnCamera.fov()));
            CameraController.setRoll(returnCamera.roll());
        }
        returnCamera = null;
        if (showScreen) minecraft.setScreen(this);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        super.extractRenderState(g, mouseX, mouseY, partial);
        g.centeredText(font, title, width / 2, 9, 0xFFFFFFFF);
        g.centeredText(font, status, width / 2, height - 66, 0xFFCCCCCC);
    }

    private final class ShotList extends ObjectSelectionList<Row> {
        ShotList() {
            super(ExportQueueScreen.this.minecraft, ExportQueueScreen.this.width, ExportQueueScreen.this.height - 118, 45, 40);
            for (int i = 0; i < editor.renderQueue.size(); i++) addEntry(new Row(i));
        }

        void center(Row row) { scrollToEntry(row); }

        @Override
        public void setSelected(Row row) {
            super.setSelected(row);
            if (list == this && render != null) {
                selection = row == null ? -1 : row.index;
                update();
            }
        }

        @Override
        public int getRowWidth() { return Math.min(300, ExportQueueScreen.this.width - 24); }
    }

    private final class Row extends ObjectSelectionList.Entry<Row> {
        private final int index;
        private final String range, format;
        private final Component output;
        Row(int index) {
            this.index = index;
            var project = editor.renderQueue.get(index).project();
            var settings = project.export;
            String name = editor.renderQueue.get(index).name();
            range = (name == null || name.isBlank() ? "" : name + " · ") + Timecode.precise(project.start()) + " – " + Timecode.precise(project.end(project.shotDuration(session.meta().durationTicks)));
            format = settings.format.label + " · " + settings.outputWidth() + "×" + settings.outputHeight() + " · " + settings.frameRate() + " fps";
            output = ExportScreen.outputSummary(project, session.meta().durationTicks);
        }

        @Override
        public Component getNarration() { return Component.literal(range + ", " + format + ", ").append(output); }

        @Override
        public void extractContent(GuiGraphicsExtractor g, int x, int y, boolean hovered, float partial) {
            g.text(font, font.plainSubstrByWidth(range, getContentWidth() - 4), getContentX() + 2, getContentY(), 0xFFFFFFFF);
            g.text(font, format, getContentX() + 2, getContentY() + 11, 0xFFACACAC);
            g.text(font, output, getContentX() + 2, getContentY() + 22, 0xFFACACAC);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean twice) {
            list.setSelected(this);
            return true;
        }
    }
}

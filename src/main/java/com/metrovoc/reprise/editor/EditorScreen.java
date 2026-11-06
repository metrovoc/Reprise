package com.metrovoc.reprise.editor;

import com.metrovoc.reprise.Reprise;
import com.metrovoc.reprise.RepriseKeys;
import com.metrovoc.reprise.format.ReplayClip;
import com.metrovoc.reprise.export.ExportScreen;
import com.metrovoc.reprise.gui.Timecode;
import com.metrovoc.reprise.gui.Timeline;
import com.metrovoc.reprise.playback.CameraController;
import com.metrovoc.reprise.playback.CameraPath;
import com.metrovoc.reprise.playback.PlaybackControls;
import com.metrovoc.reprise.playback.ReplaySession;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * The replay editor: a timeline over the live world. The mouse is free for the timeline while the movement keys still
 * fly the camera. Tab (or Escape) goes back to looking around with the mouse.
 */
public final class EditorScreen extends Screen {
    private static final int PANEL_HEIGHT = 64;
    private static final int INFO_Y = 26;

    private final ReplaySession session;
    private final Timeline timeline = new Timeline();
    private final java.util.LinkedHashSet<Keyframe> selection = new java.util.LinkedHashSet<>();

    private boolean scrubbing;
    private Keyframe dragging;
    private double dragStart;
    private boolean dragCheckpoint;
    private boolean panning;
    private double scrubTick = -1;
    private long lastScrubSeek;
    private Button playButton;
    private Button speedButton;
    private Button pathButton;
    private Button interpolationButton;
    private Button namesButton;
    private Button fewerTurns;
    private Button moreTurns;
    private Button saveClip;
    private Button editKey;
    private Button undoButton;
    private Button redoButton;

    private Keyframe singleKey() {
        return selection.size() == 1 ? selection.getFirst() : null;
    }

    private void selectOnly(Keyframe key) {
        selection.clear();
        if (key != null) selection.add(key);
    }

    void addTargetKey(Keyframe.Target target) {
        this.session.setPlaying(false);
        selectOnly(PlaybackControls.addKeyframe(this.session, target));
        CameraPath.apply(CameraPath.evaluate(project().keyframes, this.session.visualTick(0), 1));
    }

    private void changeTurns(int change) {
        var key = singleKey();
        if (key == null || key.target == null || key.target.mode() != Keyframe.Tracking.ORBIT) return;
        project().checkpoint();
        key.turns += change;
        project().save();
    }

    public EditorScreen(ReplaySession session) {
        super(Component.translatable("reprise.editor.title"));
        this.session = session;
        this.timeline.restore(session.project().timelineView, session.duration());
    }

    public boolean flying() {
        return getFocused() == null || !(getFocused() instanceof net.minecraft.client.gui.components.EditBox);
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("reprise.entities.title"), b ->
            this.minecraft.setScreen(new EntityScreen(this, this.session))).bounds(6, 6, 72, 20).build());
        Button orbitPoint = addRenderableWidget(Button.builder(Component.translatable("reprise.editor.orbit_point"), b ->
            this.minecraft.setScreen(new OrbitPointScreen(this, this.session))).bounds(82, 6, 84, 20).build());
        orbitPoint.active = this.minecraft.getCameraEntity() == this.minecraft.player;
        addRenderableWidget(Button.builder(Component.translatable("reprise.environment.title"), b ->
            this.minecraft.setScreen(new EnvironmentScreen(this, this.session))).bounds(170, 6, 94, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("reprise.editor.freeze"), b ->
            minecraft.setScreen(new FreezeScreen(this, session))).bounds(218, 30, 82, 20).build());
        this.undoButton = addRenderableWidget(Button.builder(Component.translatable("reprise.editor.undo"), b -> restoreEdit(false))
            .bounds(6, 30, 60, 20).build());
        this.redoButton = addRenderableWidget(Button.builder(Component.translatable("reprise.editor.redo"), b -> restoreEdit(true))
            .bounds(68, 30, 60, 20).build());
        this.editKey = addRenderableWidget(Button.builder(Component.translatable("reprise.editor.edit_key"), b -> {
            var key = singleKey();
            if (key != null) minecraft.setScreen(new CameraKeyScreen(this, session, key));
        }).bounds(6, 78, 122, 20).build());
        this.fewerTurns = addRenderableWidget(Button.builder(Component.translatable("reprise.editor.fewer_turns"), b -> changeTurns(-1))
            .bounds(6, 54, 60, 20).build());
        this.moreTurns = addRenderableWidget(Button.builder(Component.translatable("reprise.editor.more_turns"), b -> changeTurns(1))
            .bounds(68, 54, 60, 20).build());
        this.saveClip = addRenderableWidget(Button.builder(Component.translatable("reprise.editor.save_clip"), b -> saveClip())
            .bounds(132, 30, 82, 20).tooltip(Tooltip.create(Component.translatable("reprise.editor.save_clip.tooltip"))).build());
        if (session.file().hasCameraPaths()) {
            addRenderableWidget(Button.builder(Component.translatable("reprise.editor.replaymod_paths"), b -> {
                try {
                    minecraft.setScreen(new ReplayModPathsScreen(this, session));
                } catch (java.io.IOException | RuntimeException e) {
                    minecraft.setScreen(new AlertScreen(() -> minecraft.setScreen(this), Component.translatable("reprise.editor.path_failed"),
                        Component.literal(String.valueOf(e.getMessage()))));
                }
            }).bounds(132, 54, 110, 20).build());
        }
        int top = this.height - PANEL_HEIGHT;
        int y = top + 4;
        // Preferred widths; shrunk together when the screen is narrow.
        int[] widths = {20, 14, 40, 14, 70, 70, 50, 80, 16, 16, 60, 66, 50, 64};
        int gaps = 2 * (widths.length + 2) + 12;
        int total = gaps;
        for (int w : widths) {
            total += w;
        }
        double scale = Math.min(1, (this.width - 12.0) / total);
        int[] w = new int[widths.length];
        for (int i = 0; i < widths.length; i++) {
            w[i] = Math.max(14, (int) (widths[i] * scale));
        }
        int x = 6;
        this.playButton = addRenderableWidget(Button.builder(Component.empty(), b -> PlaybackControls.togglePlay(this.session))
            .bounds(x, y, w[0], 20).tooltip(Tooltip.create(Component.translatable("reprise.editor.play"))).build());
        x += w[0] + 2;
        addRenderableWidget(Button.builder(Component.literal("-"), b -> PlaybackControls.changeSpeed(this.session, false)).bounds(x, y, w[1], 20).build());
        x += w[1] + 1;
        this.speedButton = addRenderableWidget(Button.builder(Component.empty(), b -> this.session.setSpeed(1)).bounds(x, y, w[2], 20)
            .tooltip(Tooltip.create(Component.translatable("reprise.editor.speed"))).build());
        x += w[2] + 1;
        addRenderableWidget(Button.builder(Component.literal("+"), b -> PlaybackControls.changeSpeed(this.session, true)).bounds(x, y, w[3], 20).build());
        x += w[3] + 6;
        addRenderableWidget(Button.builder(Component.translatable("reprise.editor.keyframe"), b -> addKeyframe()).bounds(x, y, w[4], 20)
            .tooltip(Tooltip.create(Component.translatable("reprise.editor.keyframe.tooltip"))).build());
        x += w[4] + 2;
        this.interpolationButton = addRenderableWidget(Button.builder(Component.empty(), b -> cycleInterpolation()).bounds(x, y, w[5], 20)
            .tooltip(Tooltip.create(Component.translatable("reprise.editor.interpolation.tooltip"))).build());
        x += w[5] + 2;
        addRenderableWidget(Button.builder(Component.translatable("reprise.editor.delete"), b -> deleteSelected()).bounds(x, y, w[6], 20).build());
        x += w[6] + 2;
        addRenderableWidget(Button.builder(Component.translatable("reprise.editor.speed_key"), b -> addSpeedKey()).bounds(x, y, w[12], 20)
            .tooltip(Tooltip.create(Component.translatable("reprise.editor.speed_key.tooltip"))).build());
        x += w[12] + 2;
        this.pathButton = addRenderableWidget(Button.builder(Component.empty(), b -> CameraPath.setPreview(!CameraPath.preview())).bounds(x, y, w[7], 20)
            .tooltip(Tooltip.create(Component.translatable("reprise.editor.path.tooltip"))).build());
        x += w[7] + 2;
        this.namesButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            project().checkpoint();
            project().hideNames = !project().hideNames;
            project().save();
        })
            .bounds(x, y, w[13], 20).tooltip(Tooltip.create(Component.translatable("reprise.editor.names.tooltip"))).build());
        x += w[13] + 2;
        addRenderableWidget(Button.builder(Component.literal("["), b -> setRange(true)).bounds(x, y, w[8], 20)
            .tooltip(Tooltip.create(Component.translatable("reprise.editor.range_start"))).build());
        x += w[8] + 1;
        addRenderableWidget(Button.builder(Component.literal("]"), b -> setRange(false)).bounds(x, y, w[9], 20)
            .tooltip(Tooltip.create(Component.translatable("reprise.editor.range_end"))).build());

        int right = this.width - 6;
        addRenderableWidget(Button.builder(Component.translatable("reprise.editor.exit"), b -> this.session.exit())
            .bounds(right - w[11], y, w[11], 20).build());
        addRenderableWidget(Button.builder(Component.translatable("reprise.editor.export"), b -> this.minecraft.setScreen(new ExportScreen(this, this.session)))
            .bounds(right - w[11] - 2 - w[10], y, w[10], 20).build());

        this.timeline.setBounds(10, top + 38, this.width - 20, 22);
        updateLabels();
    }

    private void saveClip() {
        int start = (int) Math.floor(project().worldTick(project().start()));
        int end = (int) Math.ceil(project().worldTick(project().end(this.session.duration())));
        this.session.setPlaying(false);
        var source = this.session.file().path();
        var client = this.minecraft;
        var progress = new GenericMessageScreen(Component.translatable("reprise.editor.saving_clip"));
        client.setScreen(progress);
        Thread.startVirtualThread(() -> {
            Component message;
            try {
                var saved = ReplayClip.save(source, start, end);
                message = Component.translatable("reprise.recording.saved", saved.getFileName().toString());
            } catch (Exception e) {
                Reprise.LOGGER.warn("Could not save replay clip", e);
                message = Component.literal(String.valueOf(e.getMessage()));
            }
            Component result = message;
            client.execute(() -> {
                if (client.screen == progress) client.setScreen(new AlertScreen(
                    () -> client.setScreen(this), Component.translatable("reprise.editor.save_clip"), result));
            });
        });
    }

    private void updateLabels() {
        Keyframe selected = singleKey();
        this.editKey.active = selected != null;
        this.undoButton.active = project().canUndo();
        this.redoButton.active = project().canRedo();
        this.saveClip.active = project().worldTick(project().start()) < project().worldTick(project().end(this.session.duration()));
        this.playButton.setMessage(Component.literal(this.session.isPlaying() ? "❚❚" : "▶"));
        this.speedButton.setMessage(Component.literal(PlaybackControls.formatSpeed(this.session.speed())));
        this.pathButton.setMessage(Component.translatable(CameraPath.preview() ? "reprise.editor.path.on" : "reprise.editor.path.off"));
        this.namesButton.setMessage(Component.translatable(project().hideNames ? "reprise.editor.names.off" : "reprise.editor.names.on"));
        this.fewerTurns.visible = this.moreTurns.visible = selected != null && selected.target != null
            && selected.target.mode() == Keyframe.Tracking.ORBIT;
        this.interpolationButton.active = selected != null;
        if (selected != null && selected.spectatorAfterKey != null) {
            this.interpolationButton.setMessage(Component.translatable("reprise.interpolation.spectator"));
        } else {
            this.interpolationButton.setMessage(selected != null && selected.spline != null
                ? Component.translatable("reprise.interpolation.spline") : Component.translatable("reprise.interpolation." +
                (selected == null ? Keyframe.Interpolation.SMOOTH : selected.interpolation).name().toLowerCase()));
        }
    }

    @Override
    public void tick() {
        updateLabels();
    }

    private ReplayProject project() {
        return this.session.project();
    }

    private void addKeyframe() {
        selectOnly(PlaybackControls.addKeyframe(this.session, null));
    }

    private void addSpeedKey() {
        project().checkpoint();
        double tick = session.isPlaying() ? session.tick() : session.visualTick(1);
        project().speedKeys.removeIf(k -> k.tick == tick);
        project().speedKeys.add(new SpeedKey(tick, this.session.speed()));
        project().sort();
        project().save();
        this.session.setSpeed(1);
    }

    private void deleteSelected() {
        if (!selection.isEmpty()) {
            project().checkpoint();
            project().keyframes.removeAll(selection);
            selection.clear();
            project().save();
        }
    }

    private void cycleInterpolation() {
        Keyframe selected = singleKey();
        if (selected != null) {
            project().checkpoint();
            Keyframe.Interpolation[] values = Keyframe.Interpolation.values();
            selected.spline = null;
            if (selected.spectatorAfterKey != null) {
                selected.spectatorAfterKey = null;
                selected.interpolation = Keyframe.Interpolation.LINEAR;
                int index = project().keyframes.indexOf(selected);
                if (index + 1 < project().keyframes.size()) project().keyframes.get(index + 1).spectatorAtKey = null;
                if (index == 0) selected.spectatorAtKey = null;
            } else {
                selected.interpolation = values[(selected.interpolation.ordinal() + 1) % values.length];
            }
            project().save();
        }
    }

    private void setRange(boolean start) {
        project().checkpoint();
        int tick = this.session.tick();
        if (start) {
            project().exportStart = tick;
            if (project().exportEnd >= 0 && project().exportEnd <= tick) {
                project().exportEnd = -1;
            }
        } else {
            project().exportEnd = tick;
            if (project().exportStart >= tick) {
                project().exportStart = -1;
            }
        }
        project().save();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        // The world stays visible behind the editor.
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        int top = this.height - PANEL_HEIGHT;
        g.fill(0, top, this.width, this.height, Timeline.BACKGROUND);
        super.extractRenderState(g, mouseX, mouseY, a);
        double shown = this.scrubTick >= 0 ? this.scrubTick : this.session.visualTick(a);
        this.timeline.draw(g, this.font, this.session, shown, selection, true);

        String position = Timecode.precise(shown);
        if (!session.isPlaying() && scrubTick < 0) {
            String tick = shown == Math.rint(shown) ? Long.toString((long) shown) : Double.toString(shown);
            position = Component.translatable("reprise.editor.timeline_position", position, tick).getString();
        }
        String time = position + " / " + Timecode.format(this.session.duration());
        int timeX = width - 10 - font.width(time);
        g.text(font, time, timeX, top + INFO_Y, 0xFFFFFFFF);
        Keyframe selected = singleKey();
        String info = "";
        if (selection.size() > 1) {
            info = Component.translatable("reprise.editor.group_selected", selection.size()).getString();
        } else if (selected != null) {
            info = Component.translatable("reprise.editor.selected", Timecode.precise(selected.tick)).getString();
            if (selected.target != null) {
                String kind = switch (selected.target.mode()) {
                    case AIM -> "aim";
                    case FOLLOW -> "follow";
                    case ORBIT -> "orbit";
                };
                info = Component.translatable("reprise.editor.target." + kind, selected.target.name(),
                    Timecode.precise(selected.tick), selected.turns).getString();
            }
            if (selected.spectatorAfterKey != null) {
                var actor = minecraft.level.getEntity(selected.spectatorAfterKey.intValue());
                String name = actor == null ? "#" + selected.spectatorAfterKey : actor.getName().getString();
                info = Component.translatable("reprise.editor.target.spectate", name, Timecode.precise(selected.tick)).getString();
            }
        }
        if (!info.isEmpty() && timeX > 20) {
            int available = timeX - 16;
            String label = info;
            if (font.width(info) > available) {
                label = font.plainSubstrByWidth(info, available - font.width("…")) + "…";
                if (mouseX >= 10 && mouseX < timeX && mouseY >= top + INFO_Y && mouseY < top + INFO_Y + font.lineHeight) {
                    g.setTooltipForNextFrame(Component.literal(info), mouseX, mouseY);
                }
            }
            g.text(font, label, 10, top + INFO_Y, 0xFFFFC94D);
        }
        if (this.timeline.contains(mouseX, mouseY)) {
            double hover = this.timeline.tickAt(mouseX, this.session.duration());
            String tip = Component.translatable("reprise.editor.timeline_position", Timecode.precise(hover), Math.round(hover)).getString();
            if (!project().freezes.isEmpty()) tip = Component.translatable("reprise.editor.shot_world_time",
                Timecode.precise(hover), Timecode.precise(project().worldTick(hover))).getString();
            SpeedKey speed = this.timeline.speedKeyAt(this.session, mouseX, mouseY);
            var marker = this.timeline.markerAt(this.session, mouseX);
            if (speed != null) {
                tip = Component.translatable("reprise.editor.speed_key.hover", PlaybackControls.formatSpeed((float) speed.speed),
                    Component.translatable(speed.hold ? "reprise.interpolation.hold" : "reprise.interpolation.linear")).getString();
            } else if (marker != null) {
                tip = Component.translatable("reprise.editor.marker.hover", marker.name.isEmpty() ? Timecode.precise(project().shotTick(marker.tick)) : marker.name).getString();
            }
            g.setTooltipForNextFrame(Component.literal(tip), mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (!this.timeline.contains(x, y)) return super.mouseScrolled(x, y, horizontal, vertical);
        if (this.dragging == null && !this.scrubbing) {
            this.timeline.zoom(x, vertical, this.session.duration());
            this.timeline.pan(horizontal * 32, this.session.duration());
            project().timelineView = this.timeline.view(this.session.duration());
        }
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (!this.timeline.contains(event.x(), event.y())) {
            return false;
        }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            if (doubleClick) {
                this.timeline.fit();
                project().timelineView = null;
            }
            this.panning = !doubleClick;
            return true;
        }
        SpeedKey speedHit = this.timeline.speedKeyAt(this.session, event.x(), event.y());
        if (speedHit != null && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && doubleClick) {
            project().checkpoint();
            speedHit.hold = !speedHit.hold;
            project().save();
            return true;
        }
        if (speedHit != null && event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            project().checkpoint();
            project().speedKeys.remove(speedHit);
            project().save();
            return true;
        }
        Keyframe hit = this.timeline.keyframeAt(this.session, event.x(), event.y());
        if (hit == null && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            var marker = this.timeline.markerAt(this.session, event.x());
            if (marker != null && doubleClick) {
                jumpToCue(project().shotTick(marker.tick));
                return true;
            }
        }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (hit != null) {
                selectOnly(hit);
                cycleInterpolation();
            }
            return true;
        }
        if (hit != null) {
            if (event.hasShiftDown()) {
                if (!selection.remove(hit)) selection.add(hit);
                return true;
            }
            selectOnly(hit);
            if (doubleClick) {
                jumpToCue(hit.tick);
            } else {
                this.dragStart = hit.tick;
                this.dragging = hit;
            }
            return true;
        }
        selection.clear();
        this.scrubbing = true;
        this.scrubTick = this.timeline.tickAt(event.x(), this.session.duration());
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (this.panning) {
            this.timeline.pan(dx, this.session.duration());
            project().timelineView = this.timeline.view(this.session.duration());
            return true;
        }
        if (this.dragging != null) {
            double tick = Math.round(this.timeline.tickAt(event.x(), this.session.duration()));
            if (tick != this.dragging.tick && !dragCheckpoint) {
                project().checkpoint();
                dragCheckpoint = true;
            }
            this.dragging.tick = tick;
            project().sort();
            return true;
        }
        if (this.scrubbing) {
            this.scrubTick = this.timeline.tickAt(event.x(), this.session.duration());
            long now = System.currentTimeMillis();
            if (now - this.lastScrubSeek > 120) {
                this.lastScrubSeek = now;
                this.session.seek((int) this.scrubTick);
            }
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (this.panning) {
            this.panning = false;
            return true;
        }
        if (this.dragging != null) {
            finishDrag();
            return true;
        }
        if (this.scrubbing) {
            this.scrubbing = false;
            this.session.seek((int) Math.round(this.scrubTick));
            this.scrubTick = -1;
            return true;
        }
        return super.mouseReleased(event);
    }

    private void finishDrag() {
        if (this.dragging.tick != this.dragStart) project().putCameraKey(this.dragging);
        this.dragging = null;
        if (dragCheckpoint) project().save();
        dragCheckpoint = false;
    }

    @Override
    public void removed() {
        if (this.dragging != null) finishDrag();
        this.panning = false;
        super.removed();
    }

    private void restoreEdit(boolean redo) {
        if (redo ? project().redo() : project().undo()) {
            selection.clear();
            this.dragging = null;
            this.session.refreshEnvironment();
            updateLabels();
        }
    }

    private void jumpToCue(double tick) {
        session.seekEditorCursor(tick);
        timeline.reveal(tick, session.duration());
        project().timelineView = timeline.view(session.duration());
        selection.clear();
        for (var key : project().keyframes) {
            if (key.tick == tick) {
                selectOnly(key);
                CameraPath.apply(CameraPath.evaluate(project().keyframes, tick, 1));
                CameraController.setRoll(key.roll);
                break;
            }
        }
        updateLabels();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (RepriseKeys.OPEN_EDITOR.matches(event)) {
            onClose();
            return true;
        }
        if (this.dragging != null) finishDrag();
        if (event.hasAltDown()) {
            double cursor = session.visualTick(1);
            switch (event.key()) {
                case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN -> {
                    jumpToCue(project().adjacentCue(cursor, event.key() == GLFW.GLFW_KEY_UP, session.duration(), session.meta().markers));
                    return true;
                }
                case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT -> {
                    int step = event.hasControlDown() || event.hasControlDownWithQuirk() ? 5 : 1;
                    jumpToCue(Math.clamp(cursor + (event.key() == GLFW.GLFW_KEY_RIGHT ? step : -step), 0, session.duration()));
                    return true;
                }
            }
        }
        if (event.isSelectAll()) {
            selection.addAll(project().keyframes);
            return true;
        }
        if (event.isCopy() || event.isCut()) {
            if (!selection.isEmpty()) {
                String payload = CameraKeyClipboard.encode(selection);
                minecraft.keyboardHandler.setClipboard(payload);
                if (event.isCut() && payload.equals(minecraft.keyboardHandler.getClipboard())) deleteSelected();
            }
            return true;
        }
        if (event.isPaste()) {
            var keys = CameraKeyClipboard.decode(minecraft.keyboardHandler.getClipboard(), session.visualTick(1), session.duration());
            if (!keys.isEmpty()) {
                project().pasteCameraKeys(keys);
                selection.clear();
                selection.addAll(keys);
            }
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_DELETE || event.key() == GLFW.GLFW_KEY_BACKSPACE) {
            deleteSelected();
            return true;
        }
        if ((event.hasControlDown() || event.hasControlDownWithQuirk()) && event.key() == GLFW.GLFW_KEY_Z) {
            restoreEdit(event.hasShiftDown());
            return true;
        }
        if ((event.hasControlDown() || event.hasControlDownWithQuirk()) && event.key() == GLFW.GLFW_KEY_Y) {
            restoreEdit(true);
            return true;
        }
        if (PlaybackControls.handleKey(this.session, event)) {
            return true;
        }
        if (RepriseKeys.ADD_KEYFRAME.matches(event)) {
            addKeyframe();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

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
    private Keyframe selected;
    private boolean scrubbing;
    private Keyframe dragging;
    private double dragStart;
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
    private Button undoButton;
    private Button redoButton;

    void addTargetKey(Keyframe.Target target) {
        this.session.setPlaying(false);
        this.selected = PlaybackControls.addKeyframe(this.session, target);
        CameraPath.apply(CameraPath.evaluate(project().keyframes, this.session.tick(), 1));
    }

    private void changeTurns(int change) {
        project().checkpoint();
        this.selected.turns += change;
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
        this.undoButton = addRenderableWidget(Button.builder(Component.translatable("reprise.editor.undo"), b -> restoreEdit(false))
            .bounds(6, 30, 60, 20).build());
        this.redoButton = addRenderableWidget(Button.builder(Component.translatable("reprise.editor.redo"), b -> restoreEdit(true))
            .bounds(68, 30, 60, 20).build());
        this.fewerTurns = addRenderableWidget(Button.builder(Component.translatable("reprise.editor.fewer_turns"), b -> changeTurns(-1))
            .bounds(6, 54, 60, 20).build());
        this.moreTurns = addRenderableWidget(Button.builder(Component.translatable("reprise.editor.more_turns"), b -> changeTurns(1))
            .bounds(68, 54, 60, 20).build());
        this.saveClip = addRenderableWidget(Button.builder(Component.translatable("reprise.editor.save_clip"), b -> saveClip())
            .bounds(132, 30, 82, 20).tooltip(Tooltip.create(Component.translatable("reprise.editor.save_clip.tooltip"))).build());
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
        int start = project().start();
        int end = project().end(this.session.duration());
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
        this.undoButton.active = project().canUndo();
        this.redoButton.active = project().canRedo();
        this.saveClip.active = project().start() < project().end(this.session.duration());
        this.playButton.setMessage(Component.literal(this.session.isPlaying() ? "❚❚" : "▶"));
        this.speedButton.setMessage(Component.literal(PlaybackControls.formatSpeed(this.session.speed())));
        this.pathButton.setMessage(Component.translatable(CameraPath.preview() ? "reprise.editor.path.on" : "reprise.editor.path.off"));
        this.namesButton.setMessage(Component.translatable(project().hideNames ? "reprise.editor.names.off" : "reprise.editor.names.on"));
        this.fewerTurns.visible = this.moreTurns.visible = this.selected != null && this.selected.target != null
            && this.selected.target.mode() == Keyframe.Tracking.ORBIT;
        this.interpolationButton.active = this.selected != null;
        this.interpolationButton.setMessage(Component.translatable("reprise.interpolation." +
            (this.selected == null ? Keyframe.Interpolation.SMOOTH : this.selected.interpolation).name().toLowerCase()));
    }

    @Override
    public void tick() {
        updateLabels();
    }

    private ReplayProject project() {
        return this.session.project();
    }

    private void addKeyframe() {
        PlaybackControls.addKeyframe(this.session);
        double tick = this.session.visualTick(0);
        this.selected = project().keyframes.stream().filter(k -> Math.abs(k.tick - tick) < 0.5).findFirst().orElse(null);
    }

    private void addSpeedKey() {
        project().checkpoint();
        int tick = this.session.tick();
        project().speedKeys.removeIf(k -> Math.abs(k.tick - tick) < 0.5);
        project().speedKeys.add(new SpeedKey(tick, this.session.speed()));
        project().sort();
        project().save();
        this.session.setSpeed(1);
    }

    private void deleteSelected() {
        if (this.selected != null) {
            project().checkpoint();
            project().keyframes.remove(this.selected);
            this.selected = null;
            project().save();
        }
    }

    private void cycleInterpolation() {
        if (this.selected != null) {
            project().checkpoint();
            Keyframe.Interpolation[] values = Keyframe.Interpolation.values();
            this.selected.interpolation = values[(this.selected.interpolation.ordinal() + 1) % values.length];
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
        this.timeline.draw(g, this.font, this.session, shown, this.selected, true);

        String time = Timecode.precise(shown) + " / " + Timecode.format(this.session.duration());
        g.text(this.font, time, this.width - 10 - this.font.width(time), top + INFO_Y, 0xFFFFFFFF);
        if (this.selected != null) {
            String info = Component.translatable("reprise.editor.selected", Timecode.precise(this.selected.tick)).getString();
            if (this.selected.target != null) {
                String kind = switch (this.selected.target.mode()) {
                    case AIM -> "aim";
                    case FOLLOW -> "follow";
                    case ORBIT -> "orbit";
                };
                info = Component.translatable("reprise.editor.target." + kind, this.selected.target.name(),
                    Timecode.precise(this.selected.tick), this.selected.turns).getString();
            }
            g.text(this.font, info, 10, top + INFO_Y, 0xFFFFC94D);
        }
        if (this.timeline.contains(mouseX, mouseY)) {
            double hover = this.timeline.tickAt(mouseX, this.session.duration());
            String tip = Component.translatable("reprise.editor.timeline_position", Timecode.precise(hover), Math.round(hover)).getString();
            SpeedKey speed = this.timeline.speedKeyAt(this.session, mouseX, mouseY);
            var marker = this.timeline.markerAt(this.session, mouseX);
            if (speed != null) {
                tip = Component.translatable("reprise.editor.speed_key.hover", PlaybackControls.formatSpeed(speed.speed)).getString();
            } else if (marker != null) {
                tip = Component.translatable("reprise.editor.marker.hover", marker.name.isEmpty() ? Timecode.precise(marker.tick) : marker.name).getString();
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
                this.session.seek(marker.tick);
                return true;
            }
        }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (hit != null) {
                this.selected = hit;
                cycleInterpolation();
            }
            return true;
        }
        if (hit != null) {
            this.selected = hit;
            if (doubleClick) {
                this.session.seek((int) Math.round(hit.tick));
                CameraPath.apply(CameraPath.evaluate(project().keyframes, hit.tick, 1));
                CameraController.setRoll(hit.roll);
            } else {
                project().checkpoint();
                this.dragStart = hit.tick;
                this.dragging = hit;
            }
            return true;
        }
        this.selected = null;
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
            this.dragging.tick = Math.round(this.timeline.tickAt(event.x(), this.session.duration()));
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
        project().save();
    }

    @Override
    public void removed() {
        if (this.dragging != null) finishDrag();
        this.panning = false;
        super.removed();
    }

    private void restoreEdit(boolean redo) {
        if (redo ? project().redo() : project().undo()) {
            this.selected = null;
            this.dragging = null;
            this.session.refreshEnvironment();
            updateLabels();
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (RepriseKeys.OPEN_EDITOR.matches(event)) {
            onClose();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_DELETE || event.key() == GLFW.GLFW_KEY_BACKSPACE) {
            deleteSelected();
            return true;
        }
        if (event.hasControlDown() && event.key() == GLFW.GLFW_KEY_Z) {
            restoreEdit(event.hasShiftDown());
            return true;
        }
        if (event.hasControlDown() && event.key() == GLFW.GLFW_KEY_Y) {
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

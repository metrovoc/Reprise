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
    private static final int ROW_STEP = 22;
    private static final int TIMELINE_BOTTOM_MARGIN = 4;
    private static final int TIMELINE_HEIGHT = 22;
    private int panelHeight;
    private Button[][] toolbarGroups;

    private final ReplaySession session;
    private final Timeline timeline = new Timeline();
    private final java.util.LinkedHashSet<Keyframe> selection = new java.util.LinkedHashSet<>();

    private boolean scrubbing;
    private boolean choosingRange;
    private final java.util.LinkedHashSet<Keyframe> rangePreview = new java.util.LinkedHashSet<>();
    private boolean rangeDragged;
    private double rangeStartX, rangeEndX;
    private CameraKeyDrag dragging;
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
        var freezeButton = toolbarButton(Component.translatable("reprise.editor.freeze"),
            b -> minecraft.setScreen(new FreezeScreen(this, session)), null);
        undoButton = toolbarButton(Component.translatable("reprise.editor.undo"), b -> restoreEdit(false), null);
        redoButton = toolbarButton(Component.translatable("reprise.editor.redo"), b -> restoreEdit(true), null);
        this.editKey = addRenderableWidget(Button.builder(Component.translatable("reprise.editor.edit_key"), b -> {
            var key = singleKey();
            if (key != null) minecraft.setScreen(new CameraKeyScreen(this, session, key));
        }).bounds(6, 54, 122, 20).build());
        this.fewerTurns = addRenderableWidget(Button.builder(Component.translatable("reprise.editor.fewer_turns"), b -> changeTurns(-1))
            .bounds(6, 78, 60, 20).build());
        this.moreTurns = addRenderableWidget(Button.builder(Component.translatable("reprise.editor.more_turns"), b -> changeTurns(1))
            .bounds(68, 78, 60, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("reprise.shake.title"), b ->
            minecraft.setScreen(new CameraShakeScreen(this, session))).bounds(132, 78, 110, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("reprise.audio.title"), b ->
            minecraft.setScreen(new AudioCueScreen(this, session))).bounds(246, 78, 68, 20).build());
        saveClip = toolbarButton(Component.translatable("reprise.editor.save_clip"), b -> saveClip(), "reprise.editor.save_clip.tooltip");
        int topX = 6;
        for (Button button : new Button[]{undoButton, redoButton, saveClip, freezeButton}) {
            button.setPosition(topX, 30);
            topX += button.getWidth() + 4;
        }
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
        this.playButton = toolbarButton(Component.literal("▶"), b -> PlaybackControls.togglePlay(session), "reprise.editor.play");
        playButton.setWidth(Math.max(playButton.getWidth(), buttonWidth(Component.literal("❚❚"))));
        var slower = toolbarButton(Component.literal("-"), b -> PlaybackControls.changeSpeed(session, false), "key.reprise.slower");
        this.speedButton = toolbarButton(Component.literal("0.25×"), b -> session.setSpeed(1), "reprise.editor.speed");
        var faster = toolbarButton(Component.literal("+"), b -> PlaybackControls.changeSpeed(session, true), "key.reprise.faster");
        var addKey = toolbarButton(Component.translatable("reprise.editor.keyframe"), b -> addKeyframe(), "reprise.editor.keyframe.tooltip");
        interpolationButton = toolbarButton(Component.empty(), b -> cycleInterpolation(), "reprise.editor.interpolation.tooltip");
        var delete = toolbarButton(Component.translatable("reprise.editor.delete"), b -> deleteSelected(), null);
        var speedKey = toolbarButton(Component.translatable("reprise.editor.speed_key"), b -> addSpeedKey(), "reprise.editor.speed_key.tooltip");
        pathButton = toolbarButton(Component.empty(), b -> CameraPath.setPreview(!CameraPath.preview()), "reprise.editor.path.tooltip");
        namesButton = toolbarButton(Component.empty(), b -> {
            project().checkpoint();
            project().hideNames = !project().hideNames;
            project().save();
        }, "reprise.editor.names.tooltip");
        var rangeStart = toolbarButton(Component.literal("["), b -> setRange(true), "reprise.editor.range_start");
        var rangeEnd = toolbarButton(Component.literal("]"), b -> setRange(false), "reprise.editor.range_end");
        var export = toolbarButton(Component.translatable("reprise.editor.export"),
            b -> minecraft.setScreen(new ExportScreen(this, session)), null);
        var exit = toolbarButton(Component.translatable("reprise.editor.exit"), b -> session.exit(), null);

        reserveLabels(pathButton, "reprise.editor.path.on", "reprise.editor.path.off");
        reserveLabels(namesButton, "reprise.editor.names.on", "reprise.editor.names.off");
        reserveLabels(interpolationButton, "reprise.interpolation.smooth", "reprise.interpolation.linear",
            "reprise.interpolation.ease", "reprise.interpolation.hold", "reprise.interpolation.spline", "reprise.interpolation.spectator");
        toolbarGroups = new Button[][] {{playButton, slower, speedButton, faster}, {addKey, interpolationButton, delete},
            {speedKey, pathButton, namesButton}, {rangeStart, rangeEnd}, {export, exit}};
        layoutToolbar();
        updateLabels();
    }

    private int buttonWidth(Component label) {
        return Math.max(16, font.width(label) + (width < 400 ? 8 : 10));
    }

    private Button toolbarButton(Component label, Button.OnPress press, String tooltip) {
        var builder = Button.builder(label, press).size(buttonWidth(label), 20);
        if (tooltip != null) builder.tooltip(Tooltip.create(Component.translatable(tooltip)));
        return addRenderableWidget(builder.build());
    }

    private void reserveLabels(Button button, String... keys) {
        for (String key : keys) button.setWidth(Math.max(button.getWidth(), buttonWidth(Component.translatable(key))));
    }

    /** Wrap whole control groups, then anchor the rows immediately above the fixed timeline. */
    private void layoutToolbar() {
        int x = 6, row = 0;
        for (Button[] group : toolbarGroups) {
            int groupWidth = -2;
            for (Button button : group) groupWidth += button.getWidth() + 2;
            if (x > 6 && x + groupWidth > width - 6) {
                row++;
                x = 6;
            }
            if (group == toolbarGroups[toolbarGroups.length - 1]) x = Math.max(x, width - 6 - groupWidth);
            for (Button button : group) {
                button.setPosition(x, row * ROW_STEP);
                x += button.getWidth() + 2;
            }
            x += width < 400 ? 2 : 4;
        }
        int audioHeight = project().audioCues.isEmpty() ? 0 : Timeline.AUDIO_HEIGHT;
        int timelineHeight = TIMELINE_HEIGHT + audioHeight;
        timeline.setBounds(10, height - timelineHeight - TIMELINE_BOTTOM_MARGIN, width - 20, timelineHeight);
        panelHeight = 64 + row * ROW_STEP + audioHeight;
        for (Button[] group : toolbarGroups) {
            for (Button button : group) button.setY(height - panelHeight + 4 + button.getY());
        }
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
        int speedWidth = buttonWidth(speedButton.getMessage());
        if (speedWidth > speedButton.getWidth()) {
            speedButton.setWidth(speedWidth);
            layoutToolbar();
        }
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
        int top = this.height - panelHeight;
        int infoY = timeline.y - 12;
        g.fill(0, top, this.width, this.height, Timeline.BACKGROUND);
        super.extractRenderState(g, mouseX, mouseY, a);
        double shown = this.scrubTick >= 0 ? this.scrubTick : this.session.visualTick(a);
        var displayedSelection = choosingRange && rangeDragged ? rangePreview : selection;
        this.timeline.draw(g, this.font, this.session, shown, displayedSelection, true, dragging == null ? java.util.Map.of() : dragging.preview);

        if (choosingRange && rangeDragged) {
            int left = (int) Math.floor(Math.min(rangeStartX, rangeEndX));
            int right = (int) Math.ceil(Math.max(rangeStartX, rangeEndX));
            int topOfTrack = timeline.y + 10, bottom = timeline.y + timeline.height;
            g.fill(left, topOfTrack, right + 1, bottom, 0x446DA8FF);
            g.fill(left, topOfTrack, left + 1, bottom, 0xFF9DBFFF);
            g.fill(right, topOfTrack, right + 1, bottom, 0xFF9DBFFF);
        }
        String position = Timecode.precise(shown);
        if (!session.isPlaying() && scrubTick < 0) {
            String tick = shown == Math.rint(shown) ? Long.toString((long) shown) : Double.toString(shown);
            position = Component.translatable("reprise.editor.timeline_position", position, tick).getString();
        }
        String time = position + " / " + Timecode.format(this.session.duration());
        int timeX = width - 10 - font.width(time);
        g.text(font, time, timeX, infoY, 0xFFFFFFFF);
        Keyframe selected = singleKey();
        String info = "";
        if (choosingRange && rangeDragged) {
            info = Component.translatable("reprise.editor.range_selection", displayedSelection.size()).getString();
        } else if (dragging != null && dragging.scaling) {
            info = dragging.collapsed ? Component.translatable("reprise.editor.scale_collapsed").getString()
                : Component.translatable("reprise.editor.scale_percent", Math.round(dragging.scale * 100)).getString();
        } else if (selection.size() > 1) {
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
                if (mouseX >= 10 && mouseX < timeX && mouseY >= infoY && mouseY < infoY + font.lineHeight) {
                    g.setTooltipForNextFrame(Component.literal(info), mouseX, mouseY);
                }
            }
            g.text(font, label, 10, infoY, 0xFFFFC94D);
        }
        if (!choosingRange && this.timeline.contains(mouseX, mouseY)) {
            double hover = this.timeline.tickAt(mouseX, this.session.duration());
            String tip = Component.translatable("reprise.editor.timeline_position", Timecode.precise(hover), Math.round(hover)).getString();
            if (!project().freezes.isEmpty()) tip = Component.translatable("reprise.editor.shot_world_time",
                Timecode.precise(hover), Timecode.precise(project().worldTick(hover))).getString();
            SpeedKey speed = this.timeline.speedKeyAt(this.session, mouseX, mouseY);
            var marker = this.timeline.inAudioLane(session, mouseY) ? null : this.timeline.markerAt(this.session, mouseX);
            var cue = timeline.audioCueAt(session, mouseX, mouseY);
            if (cue != null) {
                tip = Component.translatable("reprise.audio.hover", java.nio.file.Path.of(cue.file()).getFileName().toString(),
                    Double.toString(cue.tick())).getString();
            } else if (speed != null) {
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
        if (this.dragging == null && !this.scrubbing && !choosingRange) {
            this.timeline.zoom(x, vertical, this.session.duration());
            this.timeline.pan(horizontal * 32, this.session.duration());
            project().timelineView = this.timeline.view(this.session.duration());
        }
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (choosingRange) return true;
        if (dragging != null) finishDrag();
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
        var audioCue = timeline.audioCueAt(session, event.x(), event.y());
        if (audioCue != null && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && doubleClick) {
            jumpToCue(audioCue.tick());
            minecraft.setScreen(new AudioCueScreen(this, session));
            return true;
        }
        if (!timeline.inAudioLane(session, event.y()) && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && event.hasShiftDown()) {
            choosingRange = true;
            rangeDragged = false;
            rangeStartX = rangeEndX = Math.clamp(event.x(), timeline.x, timeline.x + timeline.width);
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
        if (hit == null && !timeline.inAudioLane(session, event.y()) && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
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
            if (!selection.contains(hit)) selectOnly(hit);
            if (doubleClick) {
                jumpToCue(hit.tick);
            } else {
                dragging = new CameraKeyDrag(hit, selection, timeline.tickAt(event.x(), session.duration()), session.duration());
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
        if (choosingRange) {
            updateRangePreview(event.x());
            return true;
        }
        if (this.panning) {
            this.timeline.pan(dx, this.session.duration());
            project().timelineView = this.timeline.view(this.session.duration());
            return true;
        }
        if (this.dragging != null) {
            dragging.update(timeline.tickAt(event.x(), session.duration()), event.hasAltDown());
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
        if (choosingRange) {
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                updateRangePreview(event.x());
                if (rangeDragged) {
                    selection.addAll(rangePreview);
                } else {
                    Keyframe hit = timeline.keyframeAt(session, rangeStartX, event.y());
                    if (hit != null && !selection.remove(hit)) selection.add(hit);
                }
                cancelRange();
                updateLabels();
            }
            return true;
        }
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

    private void updateRangePreview(double x) {
        rangeEndX = Math.clamp(x, timeline.x, timeline.x + timeline.width);
        rangeDragged |= Math.abs(rangeEndX - rangeStartX) >= 2;
        if (rangeDragged) {
            rangePreview.clear();
            rangePreview.addAll(selection);
            timeline.selectCameraRange(session, rangeStartX, rangeEndX, rangePreview);
        }
    }

    private void cancelRange() {
        choosingRange = false;
        rangePreview.clear();
    }

    private void finishDrag() {
        if (!dragging.preview.isEmpty()) project().retimeCameraKeys(dragging.preview);
        else if (!dragging.moved) selectOnly(dragging.grabbed);
        dragging = null;
    }

    @Override
    public void removed() {
        cancelRange();
        dragging = null;
        this.panning = false;
        super.removed();
    }

    private void restoreEdit(boolean redo) {
        if (redo ? project().redo() : project().undo()) {
            selection.clear();
            this.dragging = null;
            this.session.refreshEnvironment();
            layoutToolbar();
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
        if (choosingRange) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) cancelRange();
            return true;
        }
        if (dragging != null && event.key() == GLFW.GLFW_KEY_ESCAPE) {
            dragging = null;
            return true;
        }
        if (dragging != null && (event.key() == GLFW.GLFW_KEY_LEFT_ALT || event.key() == GLFW.GLFW_KEY_RIGHT_ALT)) {
            return true;
        }
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

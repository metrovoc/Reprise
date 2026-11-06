package com.metrovoc.reprise.editor;

import com.metrovoc.reprise.Reprise;
import com.metrovoc.reprise.RepriseKeys;
import com.metrovoc.reprise.format.ReplayClip;
import com.metrovoc.reprise.export.ExportScreen;
import com.metrovoc.reprise.gui.PopupMenu;
import com.metrovoc.reprise.gui.Timecode;
import com.metrovoc.reprise.gui.Timeline;
import com.metrovoc.reprise.playback.CameraController;
import com.metrovoc.reprise.playback.CameraPath;
import com.metrovoc.reprise.playback.PlaybackControls;
import com.metrovoc.reprise.playback.ReplaySession;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.KeyMapping;
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
 * The replay editor: a dock along the bottom of the live world. One row of controls (playback, camera keys, then
 * More, Export and Exit), a line of help about what is selected or under the pointer, and the timeline with a lane per
 * kind of key. Everything else opens from More, so nothing covers the shot. The movement keys still fly the camera;
 * Tab (or Escape) goes back to looking around with the mouse.
 */
public final class EditorScreen extends Screen {
    private static final float[] SPEEDS = {0.1F, 0.25F, 0.5F, 1, 2, 4, 8, 16};
    private static final int MARGIN = 6;

    private final ReplaySession session;
    private final Timeline timeline = new Timeline();
    private final java.util.LinkedHashSet<Keyframe> selection = new java.util.LinkedHashSet<>();

    private boolean scrubbing;
    private boolean choosingRange;
    private final java.util.LinkedHashSet<Keyframe> rangePreview = new java.util.LinkedHashSet<>();
    private boolean rangeDragged;
    private double rangeStartX, rangeEndX;
    private CameraKeyDrag dragging;
    private Boolean draggingRangeEdge;
    private boolean panning;
    private double scrubTick = -1;
    private long lastScrubSeek;

    private int dockTop;
    private int statusY;
    private int timeX;
    private PopupMenu menu;

    private Button playButton;
    private Button speedButton;
    private Button addKeyButton;
    private Button curveButton;
    private Button editKeyButton;
    private Button deleteButton;
    private Button fewerTurns;
    private Button moreTurns;
    private Button previewButton;
    private Button moreButton;
    private Button exportButton;
    private Button exitButton;

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

    // Layout

    @Override
    protected void init() {
        menu = null;
        playButton = button(Component.literal("▶"), b -> PlaybackControls.togglePlay(session),
            tip("reprise.editor.play.tooltip", RepriseKeys.PLAY_PAUSE));
        playButton.setWidth(20);
        speedButton = button(Component.literal("1×"), b -> openSpeedMenu(), tip("reprise.editor.speed.tooltip", null));
        speedButton.setWidth(Math.max(speedButton.getWidth(), font.width("0.25×") + 10));
        addKeyButton = button(Component.translatable("reprise.editor.add_key"), b -> addKeyframe(),
            tip("reprise.editor.add_key.tooltip", RepriseKeys.ADD_KEYFRAME));
        curveButton = button(Component.translatable("reprise.interpolation.smooth"), b -> openCurveMenu(), tip("reprise.editor.curve.tooltip", null));
        reserve(curveButton, "reprise.interpolation.smooth", "reprise.interpolation.linear", "reprise.interpolation.ease",
            "reprise.interpolation.hold", "reprise.interpolation.spline", "reprise.interpolation.spectator");
        curveButton.setWidth(curveButton.getWidth() + font.width(" ▴"));
        editKeyButton = button(Component.translatable("reprise.editor.edit_key"), b -> {
            var key = singleKey();
            if (key != null) minecraft.setScreen(new CameraKeyScreen(this, session, key));
        }, tip("reprise.editor.edit_key.tooltip", null));
        deleteButton = button(Component.translatable("reprise.editor.delete"), b -> deleteSelected(), tip("reprise.editor.delete.tooltip", null));
        fewerTurns = button(Component.literal("−⟲"), b -> changeTurns(-1), tip("reprise.editor.fewer_turns", null));
        moreTurns = button(Component.literal("+⟲"), b -> changeTurns(1), tip("reprise.editor.more_turns", null));
        previewButton = button(Component.translatable("reprise.editor.preview.off"), b -> CameraPath.setPreview(!CameraPath.preview()),
            tip("reprise.editor.preview.tooltip", RepriseKeys.PATH_PREVIEW));
        reserve(previewButton, "reprise.editor.preview.on", "reprise.editor.preview.off");
        moreButton = button(Component.translatable("reprise.editor.more"), b -> openMoreMenu(), null);
        exportButton = button(Component.translatable("reprise.editor.export"), b -> minecraft.setScreen(new ExportScreen(this, session)),
            tip("reprise.editor.export.tooltip", null));
        exitButton = button(Component.translatable("reprise.editor.exit"), b -> session.exit(), null);
        layoutDock();
        updateLabels();
    }

    private Tooltip tip(String key, KeyMapping shortcut) {
        Component text = Component.translatable(key);
        if (shortcut != null) text = Component.translatable("reprise.tooltip.shortcut", text, shortcut.getTranslatedKeyMessage());
        return Tooltip.create(text);
    }

    private Button button(Component label, Button.OnPress press, Tooltip tooltip) {
        var builder = Button.builder(label, press).size(Math.max(20, font.width(label) + 10), 20);
        if (tooltip != null) builder.tooltip(tooltip);
        return addRenderableWidget(builder.build());
    }

    private void reserve(Button button, String... keys) {
        for (String key : keys) button.setWidth(Math.max(button.getWidth(), font.width(Component.translatable(key)) + 10));
    }

    /** One row of controls, the help line, then the timeline; the row wraps only on very narrow screens. */
    private void layoutDock() {
        int timelineHeight = Timeline.editorHeight(project());
        int timelineY = height - MARGIN - timelineHeight;
        timeline.setBounds(MARGIN + Timeline.GUTTER, timelineY, width - 2 * MARGIN - Timeline.GUTTER, timelineHeight);
        statusY = timelineY - 13;

        Button[] left = {playButton, speedButton};
        Button[] middle = {addKeyButton, curveButton, editKeyButton, fewerTurns, moreTurns, deleteButton, previewButton};
        Button[] right = {moreButton, exportButton, exitButton};
        int rightWidth = rowWidth(right);
        int x = MARGIN;
        int rowY = statusY - 24;
        for (Button b : left) {
            b.setPosition(x, rowY);
            x += b.getWidth() + 2;
        }
        timeX = x + 4;
        x = timeX + font.width("0:00:00.0 / 0:00:00") + 8;
        int middleWidth = -2;
        for (Button b : middle) if (b.visible) middleWidth += b.getWidth() + 2;
        if (x + middleWidth > width - MARGIN - rightWidth - 8) {
            // Narrow: camera controls get a row of their own above.
            rowY -= 22;
            x = MARGIN;
            for (Button b : left) b.setY(rowY + 22);
        }
        for (Button b : middle) {
            b.setPosition(x, rowY);
            if (b.visible) x += b.getWidth() + 2;
        }
        x = width - MARGIN - rightWidth;
        for (Button b : right) {
            b.setPosition(x, statusY - 24);
            x += b.getWidth() + 2;
        }
        dockTop = rowY - 5;
    }

    private static int rowWidth(Button[] buttons) {
        int w = -2;
        for (Button b : buttons) w += b.getWidth() + 2;
        return w;
    }

    private void updateLabels() {
        Keyframe selected = singleKey();
        boolean any = !selection.isEmpty();
        playButton.setMessage(Component.literal(session.isPlaying() ? "❚❚" : "▶"));
        speedButton.setMessage(Component.literal(PlaybackControls.formatSpeed(session.speed())));
        curveButton.visible = editKeyButton.visible = selected != null;
        deleteButton.visible = any;
        fewerTurns.visible = moreTurns.visible = selected != null && selected.target != null
            && selected.target.mode() == Keyframe.Tracking.ORBIT;
        if (selected != null) {
            Component curve;
            if (selected.spectatorAfterKey != null) curve = Component.translatable("reprise.interpolation.spectator");
            else if (selected.spline != null) curve = Component.translatable("reprise.interpolation.spline");
            else curve = Component.translatable("reprise.interpolation." + selected.interpolation.name().toLowerCase());
            curveButton.setMessage(Component.empty().append(curve).append(" ▴"));
        }
        previewButton.setMessage(Component.translatable(CameraPath.preview() ? "reprise.editor.preview.on" : "reprise.editor.preview.off"));
        layoutDock();
    }

    @Override
    public void tick() {
        updateLabels();
    }

    private ReplayProject project() {
        return this.session.project();
    }

    // Menus

    private void openMenu(PopupMenu next, Button anchor) {
        menu = next.place(font, anchor.getX(), anchor.getY() - 2, width, height);
    }

    private void openSpeedMenu() {
        var m = new PopupMenu();
        for (float speed : SPEEDS) {
            m.check(Component.literal(PlaybackControls.formatSpeed(speed)), Math.abs(session.speed() - speed) < 1e-4, () -> session.setSpeed(speed));
        }
        openMenu(m, speedButton);
    }

    private void openCurveMenu() {
        Keyframe selected = singleKey();
        if (selected == null) return;
        var m = new PopupMenu();
        for (Keyframe.Interpolation type : Keyframe.Interpolation.values()) {
            boolean current = selected.spline == null && selected.spectatorAfterKey == null && selected.interpolation == type;
            m.check(Component.translatable("reprise.interpolation." + type.name().toLowerCase() + ".long"), current, () -> setInterpolation(type));
        }
        openMenu(m, curveButton);
    }

    private void openMoreMenu() {
        var p = project();
        boolean free = minecraft.getCameraEntity() == minecraft.player;
        var m = new PopupMenu()
            .add(Component.translatable("reprise.editor.undo"), "Ctrl+Z", p.canUndo(), () -> restoreEdit(false))
            .add(Component.translatable("reprise.editor.redo"), "Ctrl+Shift+Z", p.canRedo(), () -> restoreEdit(true))
            .separator()
            .add(Component.translatable("reprise.menu.scene"), () -> minecraft.setScreen(new EnvironmentScreen(this, session)))
            .add(Component.translatable("reprise.menu.entities"), RepriseKeys.PLAYERS.getTranslatedKeyMessage().getString(), true,
                () -> minecraft.setScreen(new EntityScreen(this, session)))
            .check(Component.translatable("reprise.menu.names"), !p.hideNames, () -> {
                p.checkpoint();
                p.hideNames = !p.hideNames;
                p.save();
            })
            .separator()
            .add(Component.translatable("reprise.menu.speed_key", PlaybackControls.formatSpeed(session.speed())), this::addSpeedKey)
            .add(Component.translatable("reprise.menu.freeze"), () -> minecraft.setScreen(new FreezeScreen(this, session)))
            .add(Component.translatable("reprise.menu.orbit"), null, free, () -> minecraft.setScreen(new OrbitPointScreen(this, session)))
            .add(Component.translatable("reprise.menu.shake"), () -> minecraft.setScreen(new CameraShakeScreen(this, session)))
            .add(Component.translatable("reprise.menu.audio"), () -> minecraft.setScreen(new AudioCueScreen(this, session)))
            .separator()
            .add(Component.translatable("reprise.menu.range_start"), "[", true, () -> setRange(true))
            .add(Component.translatable("reprise.menu.range_end"), "]", true, () -> setRange(false))
            .add(Component.translatable("reprise.menu.range_clear"), null, p.exportStart >= 0 || p.exportEnd >= 0, this::clearRange)
            .add(Component.translatable("reprise.menu.save_clip"), null, p.worldTick(p.start()) < p.worldTick(p.end(session.duration())), this::saveClip);
        if (session.file().hasCameraPaths()) {
            m.separator().add(Component.translatable("reprise.editor.replaymod_paths"), () -> {
                try {
                    minecraft.setScreen(new ReplayModPathsScreen(this, session));
                } catch (java.io.IOException | RuntimeException e) {
                    minecraft.setScreen(new AlertScreen(() -> minecraft.setScreen(this), Component.translatable("reprise.editor.path_failed"),
                        Component.literal(String.valueOf(e.getMessage()))));
                }
            });
        }
        openMenu(m, moreButton);
    }

    /** Opens the players and mobs list over the editor, from the viewer's key. */
    public static void openPlayers(ReplaySession session) {
        var editor = new EditorScreen(session);
        net.minecraft.client.Minecraft.getInstance().setScreen(new EntityScreen(editor, session));
    }

    /** Chooses an item of the open menu by the start of its label; for scripted tests. */
    public boolean chooseMenuItem(String prefix) {
        var open = menu;
        menu = null;
        return open != null && open.choose(prefix);
    }

    // Edits

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
                message = Component.translatable("reprise.editor.clip_saved", saved.getFileName().toString());
            } catch (Exception e) {
                Reprise.LOGGER.warn("Could not save replay clip", e);
                message = Component.literal(String.valueOf(e.getMessage()));
            }
            Component result = message;
            client.execute(() -> {
                if (client.screen == progress) client.setScreen(new AlertScreen(
                    () -> client.setScreen(this), Component.translatable("reprise.menu.save_clip"), result));
            });
        });
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
        layoutDock();
    }

    private void deleteSelected() {
        if (!selection.isEmpty()) {
            project().checkpoint();
            project().keyframes.removeAll(selection);
            selection.clear();
            project().save();
        }
    }

    private void setInterpolation(Keyframe.Interpolation type) {
        Keyframe selected = singleKey();
        if (selected == null) return;
        project().checkpoint();
        selected.spline = null;
        if (selected.spectatorAfterKey != null) {
            selected.spectatorAfterKey = null;
            int index = project().keyframes.indexOf(selected);
            if (index + 1 < project().keyframes.size()) project().keyframes.get(index + 1).spectatorAtKey = null;
            if (index == 0) selected.spectatorAtKey = null;
        }
        selected.interpolation = type;
        project().save();
    }

    private void cycleInterpolation() {
        Keyframe selected = singleKey();
        if (selected == null) return;
        Keyframe.Interpolation[] values = Keyframe.Interpolation.values();
        setInterpolation(selected.spectatorAfterKey != null ? Keyframe.Interpolation.LINEAR
            : values[(selected.interpolation.ordinal() + 1) % values.length]);
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

    private void clearRange() {
        project().checkpoint();
        project().exportStart = -1;
        project().exportEnd = -1;
        project().save();
    }

    // Drawing

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        // The world stays visible behind the editor.
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        g.fill(0, dockTop, this.width, this.height, Timeline.BACKGROUND);
        g.fill(0, dockTop, this.width, dockTop + 1, 0xFF34343F);
        super.extractRenderState(g, mouseX, mouseY, a);
        double shown = this.scrubTick >= 0 ? this.scrubTick : this.session.visualTick(a);
        var displayedSelection = choosingRange && rangeDragged ? rangePreview : selection;
        this.timeline.draw(g, this.font, this.session, shown, displayedSelection, dragging == null ? java.util.Map.of() : dragging.preview);

        if (choosingRange && rangeDragged) {
            int left = (int) Math.floor(Math.min(rangeStartX, rangeEndX));
            int right = (int) Math.ceil(Math.max(rangeStartX, rangeEndX));
            int top = timeline.y + Timeline.RULER, bottom = timeline.y + timeline.height;
            g.fill(left, top, right + 1, bottom, 0x446DA8FF);
            g.fill(left, top, left + 1, bottom, 0xFF9DBFFF);
            g.fill(right, top, right + 1, bottom, 0xFF9DBFFF);
        }

        // Time, beside the playback controls.
        String time = Timecode.precise(shown) + " / " + Timecode.format(this.session.duration());
        g.text(font, time, timeX, playButton.getY() + 6, 0xFFFFFFFF);

        // Help line: what is selected, or what the pointer is over, or what to try first.
        String info = statusText(displayedSelection);
        int infoColor = selection.isEmpty() && !(choosingRange && rangeDragged) && dragging == null ? 0xFF9A9AA6 : 0xFFFFC94D;
        int right = this.width - MARGIN;
        int problems = session.unreadable() + session.meta().unrecordedCount();
        if (problems > 0) {
            String warn = Component.translatable("reprise.hud.problems", problems).getString();
            int wx = right - font.width(warn);
            g.text(font, warn, wx, statusY + 2, 0xFFFFC14D);
            if (mouseX >= wx && mouseX <= right && mouseY >= statusY && mouseY < statusY + 12) {
                g.setTooltipForNextFrame(font, PlaybackControls.problemDetails(session), java.util.Optional.empty(),
                    net.minecraft.world.item.ItemStack.EMPTY, mouseX, mouseY);
            }
            right = wx - 10;
        }
        int available = right - MARGIN;
        if (font.width(info) > available) info = font.plainSubstrByWidth(info, available - font.width("…")) + "…";
        g.text(font, info, MARGIN, statusY + 2, infoColor);

        if (menu != null) {
            menu.draw(g, font, mouseX, mouseY);
        } else if (!choosingRange && this.timeline.contains(mouseX, mouseY)) {
            String tip = hoverText(mouseX, mouseY);
            if (tip != null) g.setTooltipForNextFrame(Component.literal(tip), mouseX, mouseY);
        }
    }

    private String statusText(java.util.Set<Keyframe> displayedSelection) {
        Keyframe selected = singleKey();
        if (choosingRange && rangeDragged) {
            return Component.translatable("reprise.editor.range_selection", displayedSelection.size()).getString();
        }
        if (dragging != null && dragging.scaling) {
            return dragging.collapsed ? Component.translatable("reprise.editor.scale_collapsed").getString()
                : Component.translatable("reprise.editor.scale_percent", Math.round(dragging.scale * 100)).getString();
        }
        if (selection.size() > 1) {
            return Component.translatable("reprise.editor.group_selected", selection.size()).getString();
        }
        if (selected != null) {
            String info = Component.translatable("reprise.editor.selected", Timecode.precise(selected.tick)).getString();
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
            return info;
        }
        if (project().keyframes.isEmpty()) {
            return Component.translatable("reprise.editor.help.first", RepriseKeys.ADD_KEYFRAME.getTranslatedKeyMessage()).getString();
        }
        return Component.translatable(timeline.zoomed(session.duration()) ? "reprise.editor.help.zoomed" : "reprise.editor.help").getString();
    }

    private String hoverText(int mouseX, int mouseY) {
        double hover = this.timeline.tickAt(mouseX, this.session.duration());
        if (timeline.rangeHandleAt(session, mouseX, mouseY) != null) {
            return Component.translatable("reprise.editor.range_handle").getString();
        }
        var cue = timeline.audioCueAt(session, mouseX, mouseY);
        if (cue != null) {
            return Component.translatable("reprise.audio.hover", java.nio.file.Path.of(cue.file()).getFileName().toString(),
                Double.toString(cue.tick())).getString();
        }
        SpeedKey speed = this.timeline.speedKeyAt(this.session, mouseX, mouseY);
        if (speed != null) {
            return Component.translatable("reprise.editor.speed_key.hover", PlaybackControls.formatSpeed((float) speed.speed),
                Component.translatable(speed.hold ? "reprise.interpolation.hold" : "reprise.interpolation.linear")).getString();
        }
        var marker = this.timeline.markerAt(this.session, mouseX);
        if (marker != null && timeline.onRuler(mouseX, mouseY)) {
            return Component.translatable("reprise.editor.marker.hover",
                marker.name.isEmpty() ? Timecode.precise(project().shotTick(marker.tick)) : marker.name).getString();
        }
        Keyframe key = timeline.keyframeAt(session, mouseX, mouseY);
        if (key != null) {
            return Component.translatable("reprise.editor.key.hover", Timecode.precise(key.tick)).getString();
        }
        if (!project().freezes.isEmpty()) {
            return Component.translatable("reprise.editor.shot_world_time", Timecode.precise(hover), Timecode.precise(project().worldTick(hover))).getString();
        }
        return Timecode.precise(hover);
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
        if (menu != null) {
            // A click anywhere closes the menu; one inside it also chooses.
            var open = menu;
            menu = null;
            if (open.click(event.x(), event.y())) updateLabels();
            return true;
        }
        if (choosingRange) return true;
        if (dragging != null) finishDrag();
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (!this.timeline.contains(event.x(), event.y())) {
            return false;
        }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            Boolean edge = timeline.rangeHandleAt(session, event.x(), event.y());
            if (edge != null) {
                project().checkpoint();
                draggingRangeEdge = edge;
                return true;
            }
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
        if (draggingRangeEdge != null) {
            int tick = (int) Math.round(timeline.tickAt(event.x(), session.duration()));
            if (draggingRangeEdge) project().exportStart = Math.clamp(tick, 0, Math.max(0, project().end(session.duration()) - 1));
            else project().exportEnd = Math.clamp(tick, project().start() + 1, session.duration());
            return true;
        }
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
        if (draggingRangeEdge != null) {
            draggingRangeEdge = null;
            project().save();
            return true;
        }
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
        if (menu != null) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) menu = null;
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_LEFT_BRACKET) {
            setRange(true);
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_RIGHT_BRACKET) {
            setRange(false);
            return true;
        }
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
        if (RepriseKeys.OPEN_EDITOR.matches(event) && !(getFocused() instanceof net.minecraft.client.gui.components.EditBox)) {
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
        if (RepriseKeys.PATH_PREVIEW.matches(event)) {
            CameraPath.setPreview(!CameraPath.preview());
            return true;
        }
        if (RepriseKeys.PLAYERS.matches(event)) {
            minecraft.setScreen(new EntityScreen(this, session));
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_EQUAL || event.key() == GLFW.GLFW_KEY_MINUS
            || event.key() == GLFW.GLFW_KEY_KP_ADD || event.key() == GLFW.GLFW_KEY_KP_SUBTRACT) {
            // Zoom around the playhead, as Flashback does with = and -.
            boolean in = event.key() == GLFW.GLFW_KEY_EQUAL || event.key() == GLFW.GLFW_KEY_KP_ADD;
            timeline.zoom(timeline.xOf(session.visualTick(1), session.duration()), in ? 1 : -1, session.duration());
            project().timelineView = timeline.view(session.duration());
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

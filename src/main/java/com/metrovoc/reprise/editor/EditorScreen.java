package com.metrovoc.reprise.editor;

import com.metrovoc.reprise.RepriseKeys;
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
    private double scrubTick = -1;
    private long lastScrubSeek;
    private Button playButton;
    private Button speedButton;
    private Button pathButton;
    private Button interpolationButton;

    public EditorScreen(ReplaySession session) {
        super(Component.translatable("reprise.editor.title"));
        this.session = session;
    }

    public boolean flying() {
        return getFocused() == null || !(getFocused() instanceof net.minecraft.client.gui.components.EditBox);
    }

    @Override
    protected void init() {
        int top = this.height - PANEL_HEIGHT;
        int y = top + 4;
        // Preferred widths; shrunk together when the screen is narrow.
        int[] widths = {20, 14, 40, 14, 70, 70, 50, 80, 16, 16, 60, 60, 50};
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

    private void updateLabels() {
        this.playButton.setMessage(Component.literal(this.session.isPlaying() ? "❚❚" : "▶"));
        this.speedButton.setMessage(Component.literal(PlaybackControls.formatSpeed(this.session.speed())));
        this.pathButton.setMessage(Component.translatable(CameraPath.preview() ? "reprise.editor.path.on" : "reprise.editor.path.off"));
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
            g.text(this.font, info, 10, top + INFO_Y, 0xFFFFC94D);
        }
        if (this.timeline.contains(mouseX, mouseY)) {
            double hover = this.timeline.tickAt(mouseX, this.session.duration());
            String tip = Timecode.precise(hover);
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
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (!this.timeline.contains(event.x(), event.y())) {
            return false;
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
                CameraPath.apply(new CameraPose(hit.x, hit.y, hit.z, hit.yaw, hit.pitch, hit.roll, hit.fov));
                CameraController.setRoll(hit.roll);
            } else {
                project().checkpoint();
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
        if (this.dragging != null) {
            this.dragging = null;
            project().save();
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
            if (event.hasShiftDown() ? project().redo() : project().undo()) {
                this.selected = null;
            }
            return true;
        }
        if (event.hasControlDown() && event.key() == GLFW.GLFW_KEY_Y) {
            project().redo();
            this.selected = null;
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

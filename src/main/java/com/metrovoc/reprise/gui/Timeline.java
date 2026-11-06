package com.metrovoc.reprise.gui;

import com.metrovoc.reprise.editor.AudioCue;
import com.metrovoc.reprise.editor.Keyframe;
import com.metrovoc.reprise.editor.ReplayProject;
import com.metrovoc.reprise.editor.SpeedKey;
import com.metrovoc.reprise.format.ReplayMeta;
import com.metrovoc.reprise.playback.ReplaySession;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * The replay's timeline. In the editor it is a ruler (time, markers, the export range and its handles) above labelled
 * lanes: camera keys always, then speed, scene and audio lanes only when they have something in them. In the viewer's
 * HUD it is a slim progress bar.
 */
public final class Timeline {
    public static final int BACKGROUND = 0xE0121216;
    public static final int TRACK = 0xFF24242C;
    public static final int TRACK_ALT = 0xFF1E1E25;
    public static final int RANGE = 0x334F8DFF;
    public static final int RANGE_EDGE = 0xFF6FA0FF;
    public static final int PLAYHEAD = 0xFFFFFFFF;
    public static final int KEYFRAME = 0xFFFFC94D;
    public static final int KEYFRAME_TRACKING = 0xFF9DDA80;
    public static final int KEYFRAME_SELECTED = 0xFFFF7A3D;
    public static final int SPEED = 0xFF4DD0E1;
    public static final int SCENE = 0xFFFFAA66;
    public static final int SHAKE = 0xFF69C3B3;
    public static final int PROGRESS = 0xFF5B8CFF;
    /** Width of the lane names left of the timeline. */
    public static final int GUTTER = 44;

    public static final int RULER = 12;
    private static final int CAMERA_LANE = 14;
    private static final int THIN_LANE = 9;
    public static final int AUDIO_LANE = 14;

    public enum Lane {
        CAMERA("reprise.lane.camera", CAMERA_LANE),
        SPEED("reprise.lane.speed", THIN_LANE),
        SCENE("reprise.lane.scene", THIN_LANE),
        AUDIO("reprise.lane.audio", AUDIO_LANE);

        final String key;
        final int height;

        Lane(String key, int height) {
            this.key = key;
            this.height = height;
        }
    }

    private record Row(Lane lane, int top, int bottom) {}

    public int x, y, width, height;
    private double firstTick;
    private double visibleTicks = Double.POSITIVE_INFINITY;
    private final List<Row> rows = new ArrayList<>();

    public record View(double start, double ticks) {}

    // Layout

    /** The lanes this project needs, in order. */
    public static List<Lane> lanes(ReplayProject project) {
        List<Lane> lanes = new ArrayList<>();
        lanes.add(Lane.CAMERA);
        if (!project.speedKeys.isEmpty()) lanes.add(Lane.SPEED);
        if (!project.environment.keys.isEmpty() || !project.shakeKeys.isEmpty()) lanes.add(Lane.SCENE);
        if (!project.audioCues.isEmpty()) lanes.add(Lane.AUDIO);
        return lanes;
    }

    /** Height of the editor timeline: ruler plus the project's lanes. */
    public static int editorHeight(ReplayProject project) {
        int h = RULER;
        for (Lane lane : lanes(project)) h += lane.height + 1;
        return h;
    }

    public void setBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    private void layoutRows(ReplayProject project) {
        rows.clear();
        int top = y + RULER;
        for (Lane lane : lanes(project)) {
            rows.add(new Row(lane, top, top + lane.height));
            top += lane.height + 1;
        }
    }

    private Row row(Lane lane) {
        for (Row r : rows) if (r.lane == lane) return r;
        return null;
    }

    /** The lane under a point, or null over the ruler or outside. */
    public Lane laneAt(ReplaySession session, double my) {
        layoutRows(session.project());
        for (Row r : rows) if (my >= r.top && my < r.bottom + 1) return r.lane;
        return null;
    }

    public boolean onRuler(double mx, double my) {
        return mx >= x - 4 && mx <= x + width + 4 && my >= y && my < y + RULER;
    }

    // Zoom and pan

    public View view(int duration) {
        return span(duration) >= duration ? null : new View(firstTick, visibleTicks);
    }

    public void restore(View view, int duration) {
        if (view == null || duration <= 0) {
            fit();
        } else {
            visibleTicks = Math.clamp(view.ticks(), Math.min(10, duration), duration);
            firstTick = Math.clamp(view.start(), 0, duration - visibleTicks);
        }
    }

    private double span(int duration) {
        return Math.min(visibleTicks, Math.max(1, duration));
    }

    public boolean zoomed(int duration) {
        return span(duration) < Math.max(1, duration);
    }

    public void zoom(double mouseX, double steps, int duration) {
        if (duration <= 0) return;
        double anchor = tickAt(mouseX, duration);
        double fraction = Math.clamp((mouseX - x) / Math.max(1, width), 0, 1);
        visibleTicks = Math.clamp(span(duration) * Math.pow(1.5, -steps), Math.min(10, duration), duration);
        firstTick = Math.clamp(anchor - visibleTicks * fraction, 0, duration - visibleTicks);
    }

    public void pan(double pixels, int duration) {
        firstTick = Math.clamp(firstTick - pixels * span(duration) / Math.max(1, width), 0, Math.max(0, duration - span(duration)));
    }

    public void reveal(double tick, int duration) {
        if (!visible(tick, duration)) firstTick = Math.clamp(tick - span(duration) / 2, 0, Math.max(0, duration - span(duration)));
    }

    public void fit() {
        firstTick = 0;
        visibleTicks = Double.POSITIVE_INFINITY;
    }

    private boolean visible(double tick, int duration) {
        return tick >= firstTick && tick <= firstTick + span(duration);
    }

    public double tickAt(double mouseX, int duration) {
        double f = (mouseX - this.x) / Math.max(1, this.width);
        return Math.min(duration, firstTick + Math.clamp(f, 0, 1) * span(duration));
    }

    public int xOf(double tick, int duration) {
        return this.x + (int) Math.round(Math.clamp((tick - firstTick) / span(duration), -1, 2) * this.width);
    }

    public boolean contains(double mx, double my) {
        return mx >= this.x - 4 && mx <= this.x + this.width + 4 && my >= this.y && my <= this.y + this.height;
    }

    // Drawing

    /** The slim bar in the viewer's HUD: progress, export range and markers. */
    public void drawBar(GuiGraphicsExtractor g, ReplaySession session, double tick) {
        int duration = Math.max(1, session.duration());
        ReplayProject project = session.project();
        int bottom = y + height;
        g.fill(x, y, x + width, bottom, 0xA0101014);
        if (project.exportStart >= 0 || project.exportEnd >= 0) {
            int rs = Math.max(x, xOf(project.start(), duration)), re = Math.min(x + width, xOf(project.end(duration), duration));
            if (rs < re) g.fill(rs, y, re, bottom, RANGE);
        }
        int px = Math.clamp(xOf(tick, duration), x, x + width);
        g.fill(x, y + 1, px, bottom - 1, PROGRESS);
        for (ReplayMeta.Marker marker : session.meta().markers) {
            int mx = xOf(project.shotTick(marker.tick), duration);
            g.fill(mx, y - 2, mx + 1, bottom, 0xFF000000 | marker.color);
        }
        g.fill(px - 1, y - 2, px + 1, bottom + 2, PLAYHEAD);
    }

    /** The editor's timeline. {@code previewTimes} shows keys being dragged at their new times. */
    public void draw(GuiGraphicsExtractor g, Font font, ReplaySession session, double tick, Set<Keyframe> selected,
                     Map<Keyframe, Double> previewTimes) {
        int duration = Math.max(1, session.duration());
        ReplayProject project = session.project();
        layoutRows(project);
        int bottom = y + height;

        // Lane names in the gutter, then lane backgrounds.
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            String name = Component.translatable(r.lane.key).getString();
            int ty = r.top + (r.bottom - r.top - font.lineHeight) / 2 + 1;
            g.text(font, name, x - GUTTER + 2, ty, 0xFF8A8A96, false);
            g.fill(x, r.top, x + width, r.bottom, i % 2 == 0 ? TRACK : TRACK_ALT);
        }
        g.enableScissor(x - 3, y, x + width + 4, bottom + 2);

        // Ruler: a label every "nice" interval.
        int[] steps = {1, 2, 5, 10, 20, 100, 200, 600, 1200, 3600, 6000, 12000, 36000, 72000};
        int step = steps[steps.length - 1];
        for (int s : steps) {
            if ((double) s / span(duration) * width >= 56) {
                step = s;
                break;
            }
        }
        for (long t = (long) Math.ceil(firstTick / step) * step; t <= firstTick + span(duration); t += step) {
            int tx = xOf(t, duration);
            g.verticalLine(tx, y + RULER - 4, y + RULER - 1, 0xFF6A6A76);
            g.text(font, step < 20 ? t + "t" : Timecode.format(t), tx + 2, y + 1, 0xFF8A8A96, false);
        }

        // Export range over the lanes, with handles on the ruler.
        if (project.exportStart >= 0 || project.exportEnd >= 0) {
            int rs = xOf(project.start(), duration), re = xOf(project.end(duration), duration);
            int left = Math.max(x, rs), right = Math.min(x + width, re);
            if (left < right) g.fill(left, y + RULER, right, bottom, RANGE);
            if (project.exportStart >= 0 && visible(project.start(), duration)) handle(g, rs, true);
            if (project.exportEnd >= 0 && visible(project.end(duration), duration)) handle(g, re, false);
        }

        Row camera = row(Lane.CAMERA);
        // Frozen stretches of the shot.
        int added = 0;
        for (var freeze : project.freezes) {
            double start = freeze.worldTick() + added;
            int left = Math.max(x, xOf(start, duration)), right = Math.min(x + width, xOf(start + freeze.length(), duration));
            if (left < right) g.fill(left, camera.top, right, camera.bottom, 0x66879DAD);
            added += freeze.length();
        }

        // Markers: a flag on the ruler and a faint line through the lanes.
        for (ReplayMeta.Marker marker : session.meta().markers) {
            double shot = project.shotTick(marker.tick);
            if (!visible(shot, duration)) continue;
            int mx = xOf(shot, duration);
            int color = 0xFF000000 | marker.color;
            g.fill(mx, y + 2, mx + 1, bottom, (color & 0x00FFFFFF) | 0x60000000);
            g.fill(mx, y + RULER - 7, mx + 4, y + RULER - 3, color);
        }

        // Camera keys.
        int mid = (camera.top + camera.bottom) / 2;
        for (Keyframe k : project.keyframes) {
            if (!selected.contains(k) && visible(k.tick, duration)) {
                diamond(g, xOf(k.tick, duration), mid, k.target == null ? KEYFRAME : KEYFRAME_TRACKING,
                    k.interpolation == Keyframe.Interpolation.HOLD);
            }
        }
        for (Keyframe k : selected) {
            double at = previewTimes.getOrDefault(k, k.tick);
            if (visible(at, duration)) diamond(g, xOf(at, duration), mid, KEYFRAME_SELECTED, k.interpolation == Keyframe.Interpolation.HOLD);
        }

        Row speed = row(Lane.SPEED);
        if (speed != null) {
            int sm = (speed.top + speed.bottom) / 2;
            for (SpeedKey key : project.speedKeys) {
                if (!visible(key.tick, duration)) continue;
                int sx = xOf(key.tick, duration);
                g.fill(sx - 2, sm - 2, sx + 3, sm + 3, SPEED);
            }
        }
        Row scene = row(Lane.SCENE);
        if (scene != null) {
            int sm = (scene.top + scene.bottom) / 2;
            for (var key : project.environment.keys) {
                double shot = project.shotTick(key.tick());
                if (!visible(shot, duration)) continue;
                int sx = xOf(shot, duration);
                g.fill(sx - 2, sm - 2, sx + 3, sm + 3, SCENE);
            }
            for (var key : project.shakeKeys) {
                if (!visible(key.tick(), duration)) continue;
                int sx = xOf(key.tick(), duration);
                g.fill(sx - 1, sm - 3, sx + 2, sm + 4, project.shakeEnabled ? SHAKE : 0xFF627673);
            }
        }
        Row audio = row(Lane.AUDIO);
        if (audio != null) drawAudio(g, session, duration, audio.top, audio.bottom);

        // Playhead: a line through everything with a cap on the ruler.
        if (visible(tick, duration)) {
            int px = xOf(tick, duration);
            g.fill(px, y + 2, px + 1, bottom, PLAYHEAD);
            g.fill(px - 2, y, px + 3, y + 3, PLAYHEAD);
        }
        g.disableScissor();
    }

    private void handle(GuiGraphicsExtractor g, int hx, boolean start) {
        g.fill(hx, y, hx + 1, y + height, RANGE_EDGE);
        for (int i = 0; i < 4; i++) {
            int w = 4 - i;
            if (start) g.fill(hx, y + i, hx + w, y + i + 1, RANGE_EDGE);
            else g.fill(hx - w + 1, y + i, hx + 1, y + i + 1, RANGE_EDGE);
        }
    }

    private void drawAudio(GuiGraphicsExtractor g, ReplaySession session, int duration, int top, int bottom) {
        var project = session.project();
        var clock = project.previewClock(duration);
        for (var cue : project.audioCues) {
            double start = clock.videoTimeAt(cue.tick());
            double end = clock.tickAtVideoTime(start + cue.playableFrames(cue.frames()) / 2400.0);
            int left = Math.max(x, xOf(cue.tick(), duration)), right = Math.min(x + width, xOf(end, duration));
            if (right < left) continue;
            session.soundtrack.load(cue.file(), project.export.ffmpeg, false);
            var source = session.soundtrack.ready(cue.file());
            g.fill(left, top, right + 1, bottom, 0x503F9982);
            if (source != null) {
                double seconds = Math.max(cue.offset(), cue.offset() + (clock.videoTimeAt(tickAt(left, duration)) - start) / 20);
                for (int px = left; px <= right; px++) {
                    double next = Math.min(cue.offset() + cue.playableFrames(source.frames()) / 48000.0,
                        cue.offset() + (clock.videoTimeAt(tickAt(px + 1, duration)) - start) / 20);
                    int h = (int) Math.ceil(Math.min(1, source.peak(seconds, next) * cue.gain()) * (bottom - top - 2) / 2);
                    seconds = next;
                    int middle = (top + bottom) / 2;
                    g.fill(px, middle - h, px + 1, middle + h + 1, 0xAA83CEB9);
                }
            }
        }
    }

    private static void diamond(GuiGraphicsExtractor g, int cx, int cy, int color, boolean square) {
        if (square) {
            g.fill(cx - 3, cy - 3, cx + 4, cy + 4, color);
            return;
        }
        for (int i = 0; i <= 4; i++) {
            g.fill(cx - i, cy - 4 + i, cx + i + 1, cy - 3 + i, color);
            g.fill(cx - i, cy + 4 - i, cx + i + 1, cy + 5 - i, color);
        }
    }

    // Hit testing

    /** Which range handle the pointer is on, if any: true for the start, false for the end. */
    public Boolean rangeHandleAt(ReplaySession session, double mx, double my) {
        if (!onRuler(mx, my)) return null;
        var project = session.project();
        int duration = session.duration();
        if (project.exportStart >= 0 && Math.abs(xOf(project.start(), duration) - mx) <= 4) return true;
        if (project.exportEnd >= 0 && Math.abs(xOf(project.end(duration), duration) - mx) <= 4) return false;
        return null;
    }

    public boolean inAudioLane(ReplaySession session, double my) {
        return laneAt(session, my) == Lane.AUDIO;
    }

    public AudioCue audioCueAt(ReplaySession session, double mx, double my) {
        if (!inAudioLane(session, my)) return null;
        int duration = session.duration();
        var project = session.project();
        var clock = project.previewClock(duration);
        for (var cue : project.audioCues.reversed()) {
            double end = clock.tickAtVideoTime(clock.videoTimeAt(cue.tick()) + cue.playableFrames(cue.frames()) / 2400.0);
            if (mx >= xOf(cue.tick(), duration) && mx <= xOf(end, duration)) return cue;
        }
        return null;
    }

    public SpeedKey speedKeyAt(ReplaySession session, double mx, double my) {
        if (laneAt(session, my) != Lane.SPEED) return null;
        int duration = Math.max(1, session.duration());
        for (var key : session.project().speedKeys) {
            if (visible(key.tick, duration) && Math.abs(xOf(key.tick, duration) - mx) <= 3) return key;
        }
        return null;
    }

    public ReplayMeta.Marker markerAt(ReplaySession session, double mx) {
        int duration = Math.max(1, session.duration());
        for (ReplayMeta.Marker marker : session.meta().markers) {
            double shot = session.project().shotTick(marker.tick);
            if (visible(shot, duration) && Math.abs(xOf(shot, duration) - mx) <= 3) return marker;
        }
        return null;
    }

    /** Add all visible camera keys touched by a horizontal selection, including stacked keys. */
    public void selectCameraRange(ReplaySession session, double fromX, double toX, Set<Keyframe> selection) {
        int duration = session.duration();
        double left = Math.min(fromX, toX), right = Math.max(fromX, toX);
        for (Keyframe key : session.project().keyframes) {
            if (!visible(key.tick, duration)) continue;
            int kx = xOf(key.tick, duration);
            if (left <= kx + 4 && kx - 4 <= right) selection.add(key);
        }
    }

    public Keyframe keyframeAt(ReplaySession session, double mx, double my) {
        if (laneAt(session, my) != Lane.CAMERA) return null;
        int duration = Math.max(1, session.duration());
        Keyframe best = null;
        double bestDist = 5;
        for (Keyframe k : session.project().keyframes) {
            if (!visible(k.tick, duration)) continue;
            double d = Math.abs(xOf(k.tick, duration) - mx);
            if (d <= bestDist) {
                best = k;
                bestDist = d;
            }
        }
        return best;
    }
}

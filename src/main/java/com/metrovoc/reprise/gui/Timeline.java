package com.metrovoc.reprise.gui;

import com.metrovoc.reprise.editor.Keyframe;
import com.metrovoc.reprise.editor.ReplayProject;
import com.metrovoc.reprise.format.ReplayMeta;
import com.metrovoc.reprise.playback.ReplaySession;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Draws the replay's timeline: ruler, export range, markers, keyframes and playhead. */
public final class Timeline {
    public static final int BACKGROUND = 0xC0101014;
    public static final int TRACK = 0xFF2A2A33;
    public static final int RANGE = 0x5560A0FF;
    public static final int PLAYHEAD = 0xFFFFFFFF;
    public static final int KEYFRAME = 0xFFFFC94D;
    public static final int KEYFRAME_SELECTED = 0xFFFF7A3D;
    public static final int SPEED = 0xFF4DD0E1;

    public int x, y, width, height;
    private double firstTick;
    private double visibleTicks = Double.POSITIVE_INFINITY;

    public record View(double start, double ticks) {}

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

    public void setBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
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

    public void draw(GuiGraphicsExtractor g, Font font, ReplaySession session, double tick, java.util.Set<Keyframe> selected, boolean detailed, java.util.Map<Keyframe, Double> previewTimes) {
        int duration = Math.max(1, session.duration());
        ReplayProject project = session.project();
        int trackTop = this.y + (detailed ? 10 : 0);
        int trackBottom = this.y + this.height;
        g.enableScissor(this.x - 3, this.y, this.x + this.width + 4, trackBottom + 2);
        g.fill(this.x, trackTop, this.x + this.width, trackBottom, TRACK);

        if (project.exportStart >= 0 || project.exportEnd >= 0) {
            int rs = Math.max(this.x, xOf(project.start(), duration)), re = Math.min(this.x + this.width, xOf(project.end(duration), duration));
            if (rs < re) g.fill(rs, trackTop, re, trackBottom, RANGE);
        }
        int addedTicks = 0;
        for (var freeze : project.freezes) {
            double start = freeze.worldTick() + addedTicks;
            int left = Math.max(this.x, xOf(start, duration));
            int right = Math.min(this.x + this.width, xOf(start + freeze.length(), duration));
            if (left < right) g.fill(left, trackTop, right, trackBottom, 0x88879DAD);
            addedTicks += freeze.length();
        }
        // Progress
        int px = xOf(tick, duration);
        g.fill(this.x, trackBottom - 2, Math.clamp(px, this.x, this.x + this.width), trackBottom, 0xFF5B8CFF);

        if (detailed) {
            // Ruler: a label every "nice" interval.
            int[] steps = {1, 2, 5, 10, 20, 100, 200, 600, 1200, 3600, 6000, 12000, 36000, 72000};
            int step = steps[steps.length - 1];
            for (int s : steps) {
                if ((double) s / span(duration) * this.width >= 60) {
                    step = s;
                    break;
                }
            }
            for (long t = (long) Math.ceil(firstTick / step) * step; t <= firstTick + span(duration); t += step) {
                int tx = xOf(t, duration);
                g.verticalLine(tx, trackTop - 3, trackTop, 0xFF808080);
                g.text(font, step < 20 ? t + "t" : Timecode.format(t), tx + 2, this.y, 0xFF9A9AA6, false);
            }
        }
        for (ReplayMeta.Marker marker : session.meta().markers) {
            if (!visible(session.project().shotTick(marker.tick), duration)) continue;
            int mx = xOf(session.project().shotTick(marker.tick), duration);
            g.fill(mx, trackTop, mx + 1, trackBottom, 0xFF000000 | marker.color);
        }
        int mid = (trackTop + trackBottom) / 2;
        for (Keyframe k : project.keyframes) {
            if (!selected.contains(k) && visible(k.tick, duration)) {
                int color = k.target == null ? KEYFRAME : 0xFF9DDA80;
                diamond(g, xOf(k.tick, duration), mid, color, k.interpolation == Keyframe.Interpolation.HOLD);
            }
        }
        for (Keyframe k : selected) {
            double preview = previewTimes.getOrDefault(k, k.tick);
            if (visible(preview, duration)) {
                diamond(g, xOf(preview, duration), mid, KEYFRAME_SELECTED, k.interpolation == Keyframe.Interpolation.HOLD);
            }
        }
        for (var key : project.environment.keys) {
            double shot = project.shotTick(key.tick());
            if (!visible(shot, duration)) continue;
            int tx = xOf(shot, duration);
            int color = project.environment.clock == com.metrovoc.reprise.editor.EnvironmentSettings.Clock.KEYFRAMED ? 0xFFFFAA66 : 0xFF90745F;
            g.fill(tx - 2, trackTop, tx + 3, trackTop + 2, color);
        }
        for (com.metrovoc.reprise.editor.SpeedKey key : project.speedKeys) {
            if (!visible(key.tick, duration)) continue;
            int sx = xOf(key.tick, duration);
            g.fill(sx - 2, trackBottom - 4, sx + 3, trackBottom - 3, SPEED);
            g.fill(sx - 1, trackBottom - 3, sx + 2, trackBottom - 2, SPEED);
            g.fill(sx, trackBottom - 2, sx + 1, trackBottom - 1, SPEED);
        }
        if (visible(tick, duration)) g.fill(px, trackTop - 1, px + 1, trackBottom + 1, PLAYHEAD);
        g.disableScissor();
    }

    private static void diamond(GuiGraphicsExtractor g, int cx, int cy, int color, boolean square) {
        if (square) {
            g.fill(cx - 2, cy - 2, cx + 3, cy + 3, color);
            return;
        }
        for (int i = 0; i <= 3; i++) {
            g.fill(cx - i, cy - 3 + i, cx + i + 1, cy - 2 + i, color);
            g.fill(cx - i, cy + 3 - i, cx + i + 1, cy + 4 - i, color);
        }
    }

    public com.metrovoc.reprise.editor.SpeedKey speedKeyAt(ReplaySession session, double mx, double my) {
        int duration = Math.max(1, session.duration());
        if (my < this.y + this.height - 6) {
            return null;
        }
        for (var key : session.project().speedKeys) {
            if (!visible(key.tick, duration)) continue;
            if (Math.abs(xOf(key.tick, duration) - mx) <= 3) {
                return key;
            }
        }
        return null;
    }

    public ReplayMeta.Marker markerAt(ReplaySession session, double mx) {
        int duration = Math.max(1, session.duration());
        for (ReplayMeta.Marker marker : session.meta().markers) {
            if (!visible(session.project().shotTick(marker.tick), duration)) continue;
            if (Math.abs(xOf(session.project().shotTick(marker.tick), duration) - mx) <= 2) {
                return marker;
            }
        }
        return null;
    }

    /** Add all visible camera diamonds touched by a horizontal selection, including stacked keys. */
    public void selectCameraRange(ReplaySession session, double fromX, double toX, java.util.Set<Keyframe> selection) {
        int duration = session.duration();
        double left = Math.min(fromX, toX), right = Math.max(fromX, toX);
        for (Keyframe key : session.project().keyframes) {
            if (!visible(key.tick, duration)) continue;
            int x = xOf(key.tick, duration);
            int radius = key.interpolation == Keyframe.Interpolation.HOLD ? 2 : 3;
            if (left <= x + radius && x - radius <= right) selection.add(key);
        }
    }

    public Keyframe keyframeAt(ReplaySession session, double mx, double my) {
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

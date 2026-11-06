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

    public void setBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public double tickAt(double mouseX, int duration) {
        double f = (mouseX - this.x) / Math.max(1, this.width);
        return Math.max(0, Math.min(1, f)) * duration;
    }

    public int xOf(double tick, int duration) {
        return this.x + (int) Math.round(tick / Math.max(1, duration) * this.width);
    }

    public boolean contains(double mx, double my) {
        return mx >= this.x - 4 && mx <= this.x + this.width + 4 && my >= this.y && my <= this.y + this.height;
    }

    public void draw(GuiGraphicsExtractor g, Font font, ReplaySession session, double tick, Keyframe selected, boolean detailed) {
        int duration = Math.max(1, session.duration());
        ReplayProject project = session.project();
        int trackTop = this.y + (detailed ? 10 : 0);
        int trackBottom = this.y + this.height;
        g.fill(this.x, trackTop, this.x + this.width, trackBottom, TRACK);

        if (project.exportStart >= 0 || project.exportEnd >= 0) {
            int rs = xOf(project.start(), duration), re = xOf(project.end(duration), duration);
            g.fill(rs, trackTop, re, trackBottom, RANGE);
        }
        // Progress
        int px = xOf(tick, duration);
        g.fill(this.x, trackBottom - 2, px, trackBottom, 0xFF5B8CFF);

        if (detailed) {
            // Ruler: a label every "nice" interval.
            int[] steps = {20, 100, 200, 600, 1200, 3600, 6000, 12000, 36000, 72000};
            int step = steps[steps.length - 1];
            for (int s : steps) {
                if ((double) s / duration * this.width >= 60) {
                    step = s;
                    break;
                }
            }
            for (int t = 0; t <= duration; t += step) {
                int tx = xOf(t, duration);
                g.verticalLine(tx, trackTop - 3, trackTop, 0xFF808080);
                g.text(font, Timecode.format(t), tx + 2, this.y, 0xFF9A9AA6, false);
            }
        }
        for (ReplayMeta.Marker marker : session.meta().markers) {
            int mx = xOf(marker.tick, duration);
            g.fill(mx, trackTop, mx + 1, trackBottom, 0xFF000000 | marker.color);
        }
        int mid = (trackTop + trackBottom) / 2;
        for (Keyframe k : project.keyframes) {
            int kx = xOf(k.tick, duration);
            int color = k.target == null ? KEYFRAME : 0xFF9DDA80;
            diamond(g, kx, mid, k == selected ? KEYFRAME_SELECTED : color, k.interpolation == Keyframe.Interpolation.HOLD);
        }
        for (var key : project.environment.keys) {
            int tx = xOf(key.tick(), duration);
            int color = project.environment.clock == com.metrovoc.reprise.editor.EnvironmentSettings.Clock.KEYFRAMED ? 0xFFFFAA66 : 0xFF90745F;
            g.fill(tx - 2, trackTop, tx + 3, trackTop + 2, color);
        }
        for (com.metrovoc.reprise.editor.SpeedKey key : project.speedKeys) {
            int sx = xOf(key.tick, duration);
            g.fill(sx - 2, trackBottom - 4, sx + 3, trackBottom - 3, SPEED);
            g.fill(sx - 1, trackBottom - 3, sx + 2, trackBottom - 2, SPEED);
            g.fill(sx, trackBottom - 2, sx + 1, trackBottom - 1, SPEED);
        }
        g.fill(px, trackTop - 1, px + 1, trackBottom + 1, PLAYHEAD);
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
            if (Math.abs(xOf(key.tick, duration) - mx) <= 3) {
                return key;
            }
        }
        return null;
    }

    public ReplayMeta.Marker markerAt(ReplaySession session, double mx) {
        int duration = Math.max(1, session.duration());
        for (ReplayMeta.Marker marker : session.meta().markers) {
            if (Math.abs(xOf(marker.tick, duration) - mx) <= 2) {
                return marker;
            }
        }
        return null;
    }

    public Keyframe keyframeAt(ReplaySession session, double mx, double my) {
        int duration = Math.max(1, session.duration());
        Keyframe best = null;
        double bestDist = 5;
        for (Keyframe k : session.project().keyframes) {
            double d = Math.abs(xOf(k.tick, duration) - mx);
            if (d <= bestDist) {
                best = k;
                bestDist = d;
            }
        }
        return best;
    }
}

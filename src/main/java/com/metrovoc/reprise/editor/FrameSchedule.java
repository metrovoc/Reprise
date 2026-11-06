package com.metrovoc.reprise.editor;

import java.util.ArrayList;
import java.util.List;

/** Maps video frame numbers to replay ticks on a piecewise-linear replay speed curve. */
public final class FrameSchedule {
    private static final double MIN_SPEED = 0.001;
    private record Span(double tick, double limit, double speed, double slope, double videoStart, double videoEnd) {}

    private final List<Span> spans = new ArrayList<>();
    private final int start, end;
    private final double fps;
    private final long count;

    public FrameSchedule(List<SpeedKey> keys, int start, int end, double fps) {
        if (!Double.isFinite(fps) || fps <= 0) throw new IllegalArgumentException("Frame rate must be positive");
        this.start = start;
        this.end = end;
        this.fps = fps;
        if (end < start) {
            this.count = 0;
            return;
        }
        if (keys.isEmpty()) {
            add(start, end, 1, 1);
        } else {
            SpeedKey first = keys.getFirst();
            add(start, Math.min(end, first.tick), first.speed, first.speed);
            for (int i = 1; i < keys.size(); i++) {
                SpeedKey a = keys.get(i - 1), b = keys.get(i);
                double left = Math.max(start, a.tick), right = Math.min(end, b.tick);
                if (right <= left) continue;
                double slope = a.hold ? 0 : (b.speed - a.speed) / (b.tick - a.tick);
                add(left, right, a.speed + slope * (left - a.tick), a.speed + slope * (right - a.tick));
            }
            SpeedKey last = keys.getLast();
            add(Math.max(start, last.tick), end, last.speed, last.speed);
        }
        double intervals = spans.isEmpty() ? 0 : spans.getLast().videoEnd * fps / 20;
        // Include an on-grid endpoint even when accumulated floating-point error puts it just below an integer.
        this.count = Math.addExact((long) Math.floor(intervals + 4 * Math.ulp(intervals)), 1);
    }

    private void add(double from, double to, double v0, double v1) {
        if (to <= from) return;
        if (!Double.isFinite(v0) || !Double.isFinite(v1)) throw new IllegalArgumentException("Invalid replay speed");
        if ((v0 < MIN_SPEED && v1 > MIN_SPEED) || (v0 > MIN_SPEED && v1 < MIN_SPEED)) {
            double crossing = from + (to - from) * (MIN_SPEED - v0) / (v1 - v0);
            add(from, crossing, v0, MIN_SPEED);
            add(crossing, to, MIN_SPEED, v1);
            return;
        }
        v0 = Math.max(MIN_SPEED, v0);
        v1 = Math.max(MIN_SPEED, v1);
        double slope = (v1 - v0) / (to - from);
        double duration = elapsedTicks(to - from, v0, v1, slope);
        double videoStart = spans.isEmpty() ? 0 : spans.getLast().videoEnd;
        spans.add(new Span(from, to, v0, slope, videoStart, videoStart + duration));
    }

    private static double elapsedTicks(double distance, double initialSpeed, double finalSpeed, double slope) {
        if (slope == 0) return distance / initialSpeed;
        double ratio = finalSpeed / initialSpeed;
        return (ratio < 0.5 ? Math.log(ratio) : Math.log1p(slope * distance / initialSpeed)) / slope;
    }

    /** Elapsed video time in twentieths of a second, for the preview's client tick clock. */
    public double videoTimeAt(double tick) {
        if (tick <= start || spans.isEmpty()) return 0;
        if (tick >= end) return spans.getLast().videoEnd;
        int low = 0, high = spans.size() - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (tick >= spans.get(mid).limit) low = mid + 1;
            else high = mid;
        }
        Span span = spans.get(low);
        double distance = tick - span.tick;
        return span.videoStart + elapsedTicks(distance, span.speed, span.speed + distance * span.slope, span.slope);
    }

    public long frameCount() { return count; }

    public double tickAt(long frame) {
        if (frame < 0 || frame >= count) throw new IndexOutOfBoundsException(frame);
        if (frame == 0) return start;
        double time = frame * 20.0 / fps;
        int low = 0, high = spans.size() - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (time >= spans.get(mid).videoEnd) low = mid + 1;
            else high = mid;
        }
        Span span = spans.get(low);
        double elapsed = time - span.videoStart;
        // Invert the integral of 1/speed from the start of this span.
        double distance = span.slope == 0 ? elapsed * span.speed
            : span.speed * Math.expm1(span.slope * elapsed) / span.slope;
        return Math.clamp(span.tick + distance, span.tick, Math.min(end, span.limit));
    }
}

package com.metrovoc.reprise.gui;

public final class Timecode {
    /** Ticks as m:ss, or h:mm:ss for an hour or more. */
    public static String format(double ticks) {
        long total = (long) Math.floor(Math.max(0, ticks) / 20.0);
        long h = total / 3600, m = (total / 60) % 60, s = total % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    /** Ticks as m:ss.t with tenths. */
    public static String precise(double ticks) {
        long tenths = Math.round(Math.max(0, ticks) / 2);
        return String.format("%d:%02d.%d", tenths / 600, tenths / 10 % 60, tenths % 10);
    }

    /** Encoded running time includes the display interval of the last frame. */
    public static String outputDuration(long frames, double fps) {
        long millis = Math.round(frames * (1000.0 / fps));
        long seconds = millis / 1000;
        if (seconds >= 3600) {
            return String.format("%d:%02d:%02d.%03d", seconds / 3600, seconds / 60 % 60, seconds % 60, millis % 1000);
        }
        return String.format("%d:%02d.%03d", seconds / 60, seconds % 60, millis % 1000);
    }

    private Timecode() {}
}

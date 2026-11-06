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
        double seconds = Math.max(0, ticks) / 20.0;
        long m = (long) (seconds / 60);
        return String.format("%d:%04.1f", m, seconds - m * 60);
    }

    private Timecode() {}
}

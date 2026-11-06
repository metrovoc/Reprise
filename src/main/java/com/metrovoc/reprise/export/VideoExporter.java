package com.metrovoc.reprise.export;

public final class VideoExporter {
    private static VideoExporter running;

    public static boolean isRunning() {
        return running != null;
    }
}

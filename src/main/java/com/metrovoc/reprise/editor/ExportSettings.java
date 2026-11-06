package com.metrovoc.reprise.editor;

public final class ExportSettings {
    public enum Format {
        MP4_H264("mp4", "H.264 MP4"),
        MP4_H265("mp4", "H.265 MP4"),
        WEBM_VP9("webm", "VP9 WebM"),
        MOV_PRORES("mov", "ProRes 4444 MOV"),
        PNG_SEQUENCE("png", "PNG sequence");

        public final String extension;
        public final String label;

        Format(String extension, String label) {
            this.extension = extension;
            this.label = label;
        }
    }

    public int width = 1920;
    public int height = 1080;
    public int fps = 60;
    /** Constant-quality factor for the H.264/H.265/VP9 encoders: lower is better; 18 looks lossless to most eyes. */
    public int quality = 18;
    public Format format = Format.MP4_H264;
    public boolean hideHud = true;
    public boolean handsOnly;
    public boolean audio = false;
    public boolean transparentSky;
    public boolean supersample;
    public boolean depthMap;
    public boolean panorama;
    public boolean stereo;
    public int panoramaFov = 360;

    public boolean hardwareEncoder;
    public int bitrateMbps = 20;

    /** Full sphere width at the requested vertical resolution, with square cube faces. */
    public int sphereWidth() {
        int verticalDegrees = Math.clamp(panoramaFov, 120, 180);
        double pixelsPerDegree = ((width & ~3) / 2.0) / verticalDegrees;
        return Math.toIntExact(4 * Math.round(90 * pixelsPerDegree));
    }

    public int outputWidth() {
        if (!panorama) return width & ~1;
        double crop = sphereWidth() * (Math.clamp(panoramaFov, 120, 360) / 360.0);
        return Math.toIntExact(2 * Math.round(crop / 2));
    }

    public int outputHeight() {
        return panorama ? (width & ~3) / 2 : height & ~1;
    }

    public boolean supportsHardware() {
        return format == Format.MP4_H264 || format == Format.MP4_H265;
    }

    public boolean supportsAlpha() {
        return format == Format.PNG_SEQUENCE || format == Format.MOV_PRORES;
    }

    public String ffmpeg = "";
}

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

    public boolean hardwareEncoder;
    public int bitrateMbps = 20;

    public boolean supportsHardware() {
        return format == Format.MP4_H264 || format == Format.MP4_H265;
    }

    public boolean supportsAlpha() {
        return format == Format.PNG_SEQUENCE || format == Format.MOV_PRORES;
    }

    public String ffmpeg = "";
}

package com.metrovoc.reprise.editor;

public final class ExportSettings {
    public enum Projection {
        FLAT("flat"), PANORAMA("panorama"), STEREO("stereo"), CUBE("cube"), ODS("ods");

        public final String labelKey;
        Projection(String name) { labelKey = "reprise.export." + name; }
    }

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
    /** Rate numerator; projects saved with integer frame rates use the default denominator of one. */
    public int fps = 60;
    public int fpsDenominator = 1;

    public FrameRate frameRate() { return new FrameRate(fps, fpsDenominator); }

    public void setFrameRate(FrameRate rate) {
        fps = rate.numerator();
        fpsDenominator = rate.denominator();
    }

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
    public boolean cubeMap;
    public boolean omnidirectionalStereo;
    public int panoramaFov = 360;

    public boolean hardwareEncoder;
    public int bitrateMbps = 20;

    /** Keep the flags used in saved projects and queued shots from earlier releases. */
    public Projection projection() {
        if (omnidirectionalStereo) return Projection.ODS;
        if (cubeMap) return Projection.CUBE;
        if (panorama) return Projection.PANORAMA;
        if (stereo) return Projection.STEREO;
        return Projection.FLAT;
    }

    public void setProjection(Projection projection) {
        cubeMap = projection == Projection.CUBE;
        omnidirectionalStereo = projection == Projection.ODS;
        panorama = projection == Projection.PANORAMA;
        stereo = projection == Projection.STEREO;
    }

    public boolean spherical() {
        return panorama || omnidirectionalStereo;
    }

    /** Full sphere width at the requested vertical resolution, with square cube faces. */
    public int sphereWidth() {
        int verticalDegrees = Math.clamp(panoramaFov, 120, 180);
        double pixelsPerDegree = ((width & ~3) / 2.0) / verticalDegrees;
        return Math.toIntExact(4 * Math.round(90 * pixelsPerDegree));
    }

    public int outputWidth() {
        if (cubeMap) return width & ~7;
        if (!spherical()) return width & ~1;
        double crop = sphereWidth() * (Math.clamp(panoramaFov, 120, 360) / 360.0);
        return Math.toIntExact(2 * Math.round(crop / 2));
    }

    public int outputHeight() {
        if (cubeMap) return outputWidth() / 4 * 3;
        if (omnidirectionalStereo) return width & ~3;
        return panorama ? (width & ~3) / 2 : height & ~1;
    }

    public int captureWidth() {
        return switch (projection()) {
            case ODS, PANORAMA -> sphereWidth() / 4;
            case CUBE -> outputWidth() / 4;
            case STEREO -> outputWidth() / 2;
            case FLAT -> outputWidth();
        };
    }

    public int captureHeight() {
        return spherical() || cubeMap ? captureWidth() : outputHeight();
    }

    public boolean fitsDevice(int maxTextureSize) {
        int limit = maxTextureSize / (supersample ? 2 : 1);
        return outputWidth() >= 2 && outputHeight() >= 2 && (long) outputWidth() * outputHeight() <= Integer.MAX_VALUE / 4
            && captureWidth() <= limit && captureHeight() <= limit;
    }

    public boolean supportsHardware() {
        return format == Format.MP4_H264 || format == Format.MP4_H265;
    }

    public boolean supportsAlpha() {
        return format == Format.PNG_SEQUENCE || format == Format.MOV_PRORES;
    }

    public String ffmpeg = "";
}

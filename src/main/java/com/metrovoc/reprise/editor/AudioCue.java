package com.metrovoc.reprise.editor;

/** External stereo audio starts at a shot tick and advances in video seconds. */
public record AudioCue(double tick, String file, double offset, double length, double gain, long frames) {
    public long playableFrames(long sourceFrames) {
        long available = Math.max(0, sourceFrames - Math.round(offset * 48000));
        return length > 0 ? Math.min(available, Math.round(length * 48000)) : available;
    }

    public AudioCue at(double target) { return new AudioCue(target, file, offset, length, gain, frames); }
}

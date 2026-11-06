package com.metrovoc.reprise.export;

import com.metrovoc.reprise.editor.AudioCue;
import com.metrovoc.reprise.editor.FrameSchedule;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** All positions are absolute stereo sample frames on the shot's video clock. */
public final class SoundtrackMix {
    record Clip(long start, long offset, long end, double gain, SoundtrackSource source) {}
    private final List<Clip> clips;
    SoundtrackMix(List<Clip> clips) { this.clips = List.copyOf(clips); }
    public static long sampleAt(FrameSchedule clock, double tick) {
        return Math.round(clock.videoTimeAt(tick) * (SoundtrackSource.RATE / 20));
    }
    static Clip clip(FrameSchedule clock, AudioCue cue, SoundtrackSource source) {
        long start = sampleAt(clock, cue.tick());
        return new Clip(start, Math.round(cue.offset() * SoundtrackSource.RATE), start + cue.playableFrames(source.frames()), cue.gain(), source);
    }
    boolean audible(long at) { return clips.stream().anyMatch(c -> c.start < at + SoundtrackSource.RATE / 4 && at < c.end() && c.gain > 0); }

    // Each playback/export reader owns its scratch space; the decoded files support concurrent positional reads.
    static final class Reader {
        private final SoundtrackMix mix;
        private ByteBuffer scratch = ByteBuffer.allocate(0);
        Reader(SoundtrackMix mix) { this.mix = mix; }
        void add(float[] samples, long start, int frames) throws IOException {
            for (var clip : mix.clips) {
                long first = Math.max(start, clip.start), stop = Math.min(start + frames, clip.end());
                if (first >= stop || clip.gain == 0) continue;
                int count = (int)(stop - first);
                if (scratch.capacity() < count * 4) scratch = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN);
                clip.source.add(samples, (int)(first - start), first - clip.start + clip.offset, count, clip.gain, scratch);
            }
        }
    }
}

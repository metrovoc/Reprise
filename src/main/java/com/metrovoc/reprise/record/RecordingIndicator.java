package com.metrovoc.reprise.record;

import com.metrovoc.reprise.RepriseConfig;
import com.metrovoc.reprise.gui.Timecode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** A small red dot and the recorded time in the corner while recording. */
final class RecordingIndicator {
    static void draw(GuiGraphicsExtractor g) {
        Recorder recorder = Recorder.active();
        Minecraft mc = Minecraft.getInstance();
        if (recorder == null || mc.options.hideGui || !RepriseConfig.SHOW_RECORDING_INDICATOR.get()) {
            return;
        }
        boolean paused = recorder.isPaused();
        boolean blink = paused || (System.currentTimeMillis() / 600) % 2 == 0;
        int x = 6, y = 6;
        if (blink) {
            int color = paused ? 0xFFB0B0B8 : 0xFFE53935;
            g.fill(x + 1, y, x + 6, y + 7, color);
            g.fill(x, y + 1, x + 7, y + 6, color);
        }
        String label = paused ? Component.translatable("reprise.recording.indicator.paused").getString()
            : Timecode.format(recorder.recordedTicks());
        g.text(mc.font, label, x + 11, y, 0xFFFFFFFF);
    }

    private RecordingIndicator() {}
}

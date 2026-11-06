package com.metrovoc.reprise.gui;

import com.metrovoc.reprise.playback.ReplaySession;
import com.metrovoc.reprise.record.Recorder;
import com.metrovoc.reprise.record.RecordingControls;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** Entry points on vanilla screens: a Replays button on the title screen, recording controls in the pause menu. */
public final class MenuButtons {
    public static void register(IEventBus bus) {
        bus.addListener(ScreenEvent.Init.Post.class, e -> {
            if (e.getScreen() instanceof TitleScreen title) {
                RecordingControls.recoverInBackground();
                int x = title.width / 2 + 104;
                int y = title.height / 4 + 48;
                e.addListener(Button.builder(Component.translatable("reprise.menu.replays"), b -> title.getMinecraft().setScreen(new ReplayBrowserScreen(title)))
                    .bounds(x, y, 60, 20).tooltip(Tooltip.create(Component.translatable("reprise.menu.replays.tooltip"))).build());
            } else if (e.getScreen() instanceof PauseScreen pause && !ReplaySession.isActive() && pause.showsPauseMenu()) {
                // Bottom left, out of the way of the vanilla menu: start, or pause and stop.
                int y = pause.height - 24;
                Recorder recorder = Recorder.active();
                if (recorder == null) {
                    e.addListener(Button.builder(Component.translatable("reprise.menu.start_recording"), b -> {
                        RecordingControls.toggle();
                        pause.onClose();
                    }).bounds(4, y, 110, 20).build());
                } else {
                    e.addListener(Button.builder(Component.translatable(recorder.isPaused() ? "reprise.menu.resume_recording" : "reprise.menu.pause_recording"), b -> {
                        recorder.setPaused(!recorder.isPaused());
                        pause.onClose();
                    }).bounds(4, y, 80, 20).tooltip(Tooltip.create(Component.translatable("reprise.menu.pause_recording.tooltip"))).build());
                    e.addListener(Button.builder(Component.translatable("reprise.menu.stop_recording"), b -> {
                        RecordingControls.toggle();
                        pause.onClose();
                    }).bounds(88, y, 96, 20).tooltip(Tooltip.create(Component.translatable("reprise.menu.stop_recording.tooltip"))).build());
                }
            }
        });
    }

    private MenuButtons() {}
}

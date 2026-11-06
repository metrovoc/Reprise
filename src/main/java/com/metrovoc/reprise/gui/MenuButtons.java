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
                int x = title.width / 2 + 104;
                int y = title.height / 4 + 48;
                e.addListener(Button.builder(Component.translatable("reprise.menu.replays"), b -> title.getMinecraft().setScreen(new ReplayBrowserScreen(title)))
                    .bounds(x, y, 60, 20).tooltip(Tooltip.create(Component.translatable("reprise.menu.replays.tooltip"))).build());
            } else if (e.getScreen() instanceof PauseScreen pause && !ReplaySession.isActive() && pause.showsPauseMenu()) {
                Component label = Component.translatable(Recorder.isRecording() ? "reprise.menu.stop_recording" : "reprise.menu.start_recording");
                e.addListener(Button.builder(label, b -> {
                    RecordingControls.toggle();
                    pause.onClose();
                }).bounds(4, pause.height - 24, 110, 20).build());
            }
        });
    }

    private MenuButtons() {}
}

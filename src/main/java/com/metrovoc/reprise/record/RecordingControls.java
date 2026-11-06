package com.metrovoc.reprise.record;

import com.metrovoc.reprise.RepriseConfig;
import com.metrovoc.reprise.RepriseKeys;
import com.metrovoc.reprise.format.ReplayMeta;
import com.metrovoc.reprise.playback.ReplaySession;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Keys, auto-start and lifecycle of recordings. */
public final class RecordingControls {
    private static int autoStartDelay = -1;

    public static void register(IEventBus bus) {
        bus.addListener(ClientTickEvent.Post.class, e -> tick());
        bus.addListener(ClientPlayerNetworkEvent.LoggingIn.class, e -> {
            if (ReplaySession.active() != null) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            boolean auto = mc.isLocalServer() ? RepriseConfig.AUTO_RECORD_SINGLEPLAYER.get() : RepriseConfig.AUTO_RECORD_MULTIPLAYER.get();
            autoStartDelay = auto ? 20 : -1;
        });
        bus.addListener(ClientPlayerNetworkEvent.LoggingOut.class, e -> {
            autoStartDelay = -1;
            stopAndReport();
        });
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (autoStartDelay > 0 && --autoStartDelay == 0) {
            Recorder.start();
        }
        while (RepriseKeys.TOGGLE_RECORDING.consumeClick()) {
            toggle();
        }
        Recorder recorder = Recorder.active();
        while (RepriseKeys.PAUSE_RECORDING.consumeClick()) {
            if (recorder != null) {
                recorder.setPaused(!recorder.isPaused());
                mc.gui.setOverlayMessage(Component.translatable(recorder.isPaused() ? "reprise.recording.paused" : "reprise.recording.resumed"), false);
            }
        }
        if (ReplaySession.active() == null) {
            while (RepriseKeys.ADD_MARKER.consumeClick()) {
                if (recorder != null && mc.player != null) {
                    ReplayMeta.Marker marker = new ReplayMeta.Marker();
                    marker.x = mc.player.getX();
                    marker.y = mc.player.getY();
                    marker.z = mc.player.getZ();
                    marker.yaw = mc.player.getYRot();
                    marker.pitch = mc.player.getXRot();
                    marker.dimension = mc.player.level().dimension().identifier().toString();
                    recorder.addMarker(marker);
                    mc.gui.setOverlayMessage(Component.translatable("reprise.recording.marker"), false);
                }
            }
        }
        if (recorder != null) {
            recorder.endTick();
        }
    }

    public static void toggle() {
        if (Recorder.isRecording()) {
            stopAndReport();
        } else if (ReplaySession.active() == null) {
            Recorder.start();
        }
    }

    private static void stopAndReport() {
        if (!Recorder.isRecording()) {
            return;
        }
        Path file = Recorder.stop();
        Minecraft mc = Minecraft.getInstance();
        if (file != null && mc.player != null) {
            mc.player.sendSystemMessage(Component.translatable("reprise.recording.saved", file.getFileName().toString()));
        }
    }

    private RecordingControls() {}
}

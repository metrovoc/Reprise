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
        bus.addListener(net.neoforged.neoforge.client.event.RenderGuiEvent.Post.class, e -> RecordingIndicator.draw(e.getGuiGraphics()));
        bus.addListener(ClientPlayerNetworkEvent.LoggingIn.class, e -> {
            if (ReplaySession.active() != null) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            boolean auto = mc.isLocalServer() ? RepriseConfig.AUTO_RECORD_SINGLEPLAYER.get() : RepriseConfig.AUTO_RECORD_MULTIPLAYER.get();
            // Start right away: the opening snapshot waits until the world has loaded, so nothing is missed.
            autoStartDelay = auto ? 1 : -1;
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

    private static final net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId TOAST =
        new net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId(6000L);

    private static void stopAndReport() {
        Recorder recorder = Recorder.active();
        if (recorder == null) {
            return;
        }
        int ticks = recorder.recordedTicks();
        Path file = Recorder.stop();
        Minecraft mc = Minecraft.getInstance();
        Component title, message;
        if (file != null) {
            int lost = recorder.meta().unrecordedCount();
            title = Component.translatable("reprise.recording.saved.title", com.metrovoc.reprise.gui.Timecode.format(ticks));
            message = lost == 0 ? Component.translatable("reprise.recording.saved.message")
                : Component.translatable("reprise.recording.saved.lost", lost);
        } else {
            title = Component.translatable("reprise.recording.failed.title");
            message = Component.translatable("reprise.recording.failed.message");
        }
        // A toast, not chat: the player is usually leaving the world when a recording ends.
        net.minecraft.client.gui.components.toasts.SystemToast.addOrUpdate(mc.getToastManager(), TOAST, title, message);
    }

    /** Packs recordings left behind by a crash, once per game start, off the game thread. */
    public static void recoverInBackground() {
        if (recovered) return;
        recovered = true;
        Thread.ofVirtual().name("Reprise recovery").start(() -> {
            int count = ReplayPacker.recoverAll();
            if (count > 0) {
                Minecraft mc = Minecraft.getInstance();
                mc.execute(() -> net.minecraft.client.gui.components.toasts.SystemToast.addOrUpdate(mc.getToastManager(), TOAST,
                    Component.translatable("reprise.recording.recovered.title", count), Component.translatable("reprise.recording.recovered.message")));
            }
        });
    }

    private static boolean recovered;

    private RecordingControls() {}
}

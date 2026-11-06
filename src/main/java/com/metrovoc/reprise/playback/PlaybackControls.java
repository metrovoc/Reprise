package com.metrovoc.reprise.playback;

import com.metrovoc.reprise.RepriseKeys;
import com.metrovoc.reprise.editor.EditorScreen;
import com.metrovoc.reprise.editor.Keyframe;
import com.metrovoc.reprise.editor.ReplayProject;
import com.metrovoc.reprise.format.ReplayMeta;
import com.metrovoc.reprise.gui.Timecode;
import com.metrovoc.reprise.gui.Timeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/** Keys, HUD and per-frame hooks of the replay viewer. */
public final class PlaybackControls {
    private static final float[] SPEEDS = {0.05F, 0.1F, 0.25F, 0.5F, 0.75F, 1, 1.5F, 2, 4, 8, 16};
    private static final Timeline HUD_TIMELINE = new Timeline();

    public static void register(IEventBus bus) {
        bus.addListener(EventPriority.HIGHEST, ClientTickEvent.Pre.class, e -> tick());
        bus.addListener(RenderFrameEvent.Post.class, e -> {
            var exporter = com.metrovoc.reprise.export.VideoExporter.running();
            if (exporter != null) {
                exporter.afterRender();
            }
        });
        bus.addListener(RenderGuiEvent.Post.class, e -> hud(e.getGuiGraphics(), e.getPartialTick().getGameTimeDeltaPartialTick(false)));
        bus.addListener(ClientPlayerNetworkEvent.LoggingOut.class, e -> {
            ReplaySession session = ReplaySession.active();
            if (session != null) {
                session.close();
            }
        });
        bus.addListener(ViewportEvent.ComputeCameraAngles.class, e -> {
            if (ReplaySession.isActive()) {
                var pose = CameraPath.current();
                e.setRoll(pose != null ? pose.roll() : CameraController.roll());
            }
        });
        bus.addListener(ViewportEvent.ComputeFov.class, e -> {
            var pose = CameraPath.current();
            if (ReplaySession.isActive() && pose != null && e.usedConfiguredFov()) {
                e.setFOV(pose.fov());
            }
        });
        bus.addListener(ScreenEvent.Init.Post.class, e -> {
            if (e.getScreen() instanceof PauseScreen && ReplaySession.isActive()) {
                replaceDisconnectButton(e);
            }
        });
    }

    private static void replaceDisconnectButton(ScreenEvent.Init.Post e) {
        for (var listener : java.util.List.copyOf(e.getListenersList())) {
            if (listener instanceof Button button && button.getMessage().getContents() instanceof TranslatableContents t
                && t.getKey().equals("menu.disconnect")) {
                e.removeListener(button);
                e.addListener(Button.builder(Component.translatable("reprise.editor.exit"), b -> {
                    ReplaySession session = ReplaySession.active();
                    if (session != null) {
                        session.exit();
                    }
                }).bounds(button.getX(), button.getY(), button.getWidth(), button.getHeight()).build());
            }
        }
    }

    private static void tick() {
        ReplaySession session = ReplaySession.active();
        if (session == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        session.clientTick();
        if (mc.screen == null && mc.player != null) {
            while (RepriseKeys.OPEN_EDITOR.consumeClick()) {
                mc.setScreen(new EditorScreen(session));
            }
            while (RepriseKeys.PLAY_PAUSE.consumeClick()) {
                togglePlay(session);
            }
            while (RepriseKeys.SEEK_BACK.consumeClick()) {
                session.seek(session.tick() - (mc.options.keySprint.isDown() ? 200 : 100));
            }
            while (RepriseKeys.SEEK_FORWARD.consumeClick()) {
                session.seek(session.tick() + (mc.options.keySprint.isDown() ? 200 : 100));
            }
            while (RepriseKeys.SLOWER.consumeClick()) {
                changeSpeed(session, false);
            }
            while (RepriseKeys.FASTER.consumeClick()) {
                changeSpeed(session, true);
            }
            while (RepriseKeys.ADD_KEYFRAME.consumeClick()) {
                addKeyframe(session);
            }
            while (RepriseKeys.ADD_MARKER.consumeClick()) {
                addMarker(session);
            }
            // Spectate what the crosshair points at; sneak to fly free again.
            while (mc.options.keyAttack.consumeClick()) {
                if (mc.getCameraEntity() == mc.player && mc.hitResult instanceof EntityHitResult hit) {
                    mc.setCameraEntity(hit.getEntity());
                }
            }
            if (mc.getCameraEntity() != mc.player && mc.options.keyShift.isDown()) {
                Entity target = mc.getCameraEntity();
                mc.setCameraEntity(mc.player);
                if (target != null) {
                    mc.player.snapTo(target.getX(), target.getEyeY() - mc.player.getEyeHeight(), target.getZ(), target.getYRot(), target.getXRot());
                    mc.player.setOldPosAndRot();
                }
            }
        }
        if (mc.getCameraEntity() != null && mc.getCameraEntity() != mc.player && mc.getCameraEntity().isRemoved()) {
            mc.setCameraEntity(mc.player);
        }
    }

    public static void frame(float partialTick) {
        ReplaySession session = ReplaySession.active();
        if (session == null) {
            return;
        }
        CameraPath.frame(session.visualTick(partialTick));
        CameraController.frame();
    }

    public static boolean handleKey(ReplaySession session, KeyEvent event) {
        if (RepriseKeys.PLAY_PAUSE.matches(event)) {
            togglePlay(session);
        } else if (RepriseKeys.SEEK_BACK.matches(event)) {
            session.seek(session.tick() - (event.hasShiftDown() ? 20 : 100));
        } else if (RepriseKeys.SEEK_FORWARD.matches(event)) {
            session.seek(session.tick() + (event.hasShiftDown() ? 20 : 100));
        } else if (RepriseKeys.SLOWER.matches(event)) {
            changeSpeed(session, false);
        } else if (RepriseKeys.FASTER.matches(event)) {
            changeSpeed(session, true);
        } else {
            return false;
        }
        return true;
    }

    public static void togglePlay(ReplaySession session) {
        if (!session.isPlaying() && session.tick() >= session.duration()) {
            session.seek(session.project().start());
        }
        session.setPlaying(!session.isPlaying());
    }

    public static void changeSpeed(ReplaySession session, boolean faster) {
        float speed = session.speed();
        float next = speed;
        if (faster) {
            for (float s : SPEEDS) {
                if (s > speed + 1e-4) {
                    next = s;
                    break;
                }
            }
        } else {
            for (int i = SPEEDS.length - 1; i >= 0; i--) {
                if (SPEEDS[i] < speed - 1e-4) {
                    next = SPEEDS[i];
                    break;
                }
            }
        }
        session.setSpeed(next);
    }

    public static String formatSpeed(float speed) {
        return (speed == Math.round(speed) ? String.valueOf((int) speed) : String.valueOf(speed)) + "×";
    }

    /** Keyframes the camera where it is now. A keyframe already at this moment is replaced. */
    public static void addKeyframe(ReplaySession session) {
        Minecraft mc = Minecraft.getInstance();
        Entity view = mc.getCameraEntity();
        if (view == null) {
            return;
        }
        ReplayProject project = session.project();
        project.checkpoint();
        double tick = session.visualTick(0);
        project.keyframes.removeIf(k -> Math.abs(k.tick - tick) < 0.5);
        Keyframe k = new Keyframe();
        k.tick = Math.round(tick);
        // Keyframes store the camera player's position; when spectating, take the spectated entity's eyes.
        double eyeOffset = view == mc.player ? 0 : view.getEyeHeight() - mc.player.getEyeHeight();
        k.x = view.getX();
        k.y = view.getY() + eyeOffset;
        k.z = view.getZ();
        k.yaw = view.getYRot();
        k.pitch = view.getXRot();
        k.roll = view == mc.player ? CameraController.roll() : 0;
        k.fov = mc.options.fov().get();
        project.keyframes.add(k);
        project.sort();
        project.save();
        mc.gui.setOverlayMessage(Component.translatable("reprise.editor.keyframe.added", Timecode.precise(k.tick)), false);
    }

    private static void addMarker(ReplaySession session) {
        ReplayMeta.Marker marker = new ReplayMeta.Marker();
        marker.tick = session.tick();
        session.meta().markers.add(marker);
    }

    private static void hud(GuiGraphicsExtractor g, float partialTick) {
        ReplaySession session = ReplaySession.active();
        Minecraft mc = Minecraft.getInstance();
        if (session == null || mc.options.hideGui || mc.screen instanceof EditorScreen || com.metrovoc.reprise.export.VideoExporter.isRunning()) {
            return;
        }
        int w = g.guiWidth();
        int h = g.guiHeight();
        int barW = Math.min(360, w - 40);
        int x = (w - barW) / 2;
        int y = h - 14;
        HUD_TIMELINE.setBounds(x, y, barW, 6);
        HUD_TIMELINE.draw(g, mc.font, session, session.visualTick(partialTick), null, false);
        String state = (session.isPlaying() ? "▶ " : "❚❚ ") + Timecode.format(session.visualTick(partialTick)) + " / "
            + Timecode.format(session.duration()) + "   " + formatSpeed(session.speed());
        g.text(mc.font, state, x, y - 11, 0xFFFFFFFF);
        String hint;
        Entity view = mc.getCameraEntity();
        if (view != null && view != mc.player) {
            hint = Component.translatable("reprise.hud.spectating", view.getDisplayName()).getString();
        } else {
            hint = Component.translatable("reprise.hud.hint", RepriseKeys.OPEN_EDITOR.getTranslatedKeyMessage(),
                RepriseKeys.PLAY_PAUSE.getTranslatedKeyMessage()).getString();
        }
        g.text(mc.font, hint, x + barW - mc.font.width(hint), y - 11, 0xFFB0B0B8);
    }

    private PlaybackControls() {}
}

package com.metrovoc.reprise.mixin.playback;

import com.metrovoc.reprise.playback.ReplaySession;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    /** A seek rebuilds the world; the viewer should not see a loading screen for it. */
    @Inject(method = "startWaitingForNewLevel(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/gui/screens/LevelLoadingScreen$Reason;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/resources/ResourceKey;)V",
        at = @At("HEAD"), cancellable = true)
    private void reprise$noLoadingScreenWhenSeeking(LocalPlayer player, ClientLevel level, LevelLoadingScreen.Reason reason,
                                                    ResourceKey<Level> to, ResourceKey<Level> from, CallbackInfo ci) {
        ReplaySession session = ReplaySession.active();
        if (session != null && session.isSeeking()) {
            ci.cancel();
        }
    }
}

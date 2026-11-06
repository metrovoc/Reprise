package com.metrovoc.reprise.mixin.playback;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.metrovoc.reprise.playback.ReplaySession;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import java.util.Collection;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Gui.class)
abstract class RecordedHotbarMixin {
    @ModifyExpressionValue(method = "extractEffects", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/player/LocalPlayer;getActiveEffects()Ljava/util/Collection;"))
    private Collection<MobEffectInstance> reprise$effects(Collection<MobEffectInstance> original) {
        return ReplaySession.showingRecordedHotbar() && Minecraft.getInstance().getCameraEntity() instanceof Player subject
            ? subject.getActiveEffects() : original;
    }

    @ModifyExpressionValue(method = "tick()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/player/Inventory;getSelectedItem()Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack reprise$highlightedItem(ItemStack original) {
        return ReplaySession.showingRecordedHotbar() && Minecraft.getInstance().getCameraEntity() instanceof Player subject
            ? subject.getInventory().getSelectedItem() : original;
    }

    /** Vanilla reads inventory and vitals from the camera subject, but HUD mode and XP from the local player. */
    @WrapMethod(method = "extractRenderState")
    private void reprise$recordedHud(GuiGraphicsExtractor graphics, DeltaTracker delta, Operation<Void> original) {
        var mc = Minecraft.getInstance();
        if (!ReplaySession.showingRecordedHotbar() || !(mc.getCameraEntity() instanceof Player subject)) {
            original.call(graphics, delta);
            return;
        }
        var camera = mc.player;
        var mode = mc.gameMode;
        GameType savedMode = mode.localPlayerMode;
        int savedLevel = camera.experienceLevel;
        float savedProgress = camera.experienceProgress;
        try {
            GameType recorded = subject.gameMode();
            mode.localPlayerMode = recorded == null ? GameType.SURVIVAL : recorded;
            camera.experienceLevel = subject.experienceLevel;
            camera.experienceProgress = subject.experienceProgress;
            original.call(graphics, delta);
        } finally {
            mode.localPlayerMode = savedMode;
            camera.experienceLevel = savedLevel;
            camera.experienceProgress = savedProgress;
        }
    }
}

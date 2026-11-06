package com.metrovoc.reprise.mixin.playback;

import com.metrovoc.reprise.playback.PovHands;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin implements PovHands.Snappable {
    @Shadow private float mainHandHeight;
    @Shadow private float oMainHandHeight;
    @Shadow private float offHandHeight;
    @Shadow private float oOffHandHeight;
    @Shadow private ItemStack mainHandItem;
    @Shadow private ItemStack offHandItem;

    @Inject(method = "tick", at = @At("HEAD"))
    private void reprise$beginPov(CallbackInfo ci) {
        if (PovHands.begin()) {
            PovHands.tick();
        }
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void reprise$endPov(CallbackInfo ci) {
        PovHands.end();
    }

    @Override
    public void reprise$snap(ItemStack main, ItemStack off) {
        this.mainHandItem = main;
        this.offHandItem = off;
        this.mainHandHeight = this.oMainHandHeight = 1;
        this.offHandHeight = this.oOffHandHeight = 1;
    }
}

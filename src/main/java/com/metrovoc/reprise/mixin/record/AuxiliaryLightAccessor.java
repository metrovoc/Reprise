package com.metrovoc.reprise.mixin.record;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.common.world.LevelChunkAuxiliaryLightManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelChunkAuxiliaryLightManager.class)
public interface AuxiliaryLightAccessor {
    @Accessor("lights")
    Map<BlockPos, Byte> reprise$lights();
}

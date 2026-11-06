package com.metrovoc.reprise;

import com.metrovoc.reprise.record.RecordingControls;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(value = Reprise.MOD_ID, dist = Dist.CLIENT)
public final class Reprise {
    public static final String MOD_ID = "reprise";
    public static final Logger LOGGER = LoggerFactory.getLogger("Reprise");

    public Reprise(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, RepriseConfig.SPEC);
        modBus.addListener(RepriseKeys::register);
        RecordingControls.register(NeoForge.EVENT_BUS);
        com.metrovoc.reprise.playback.PlaybackControls.register(NeoForge.EVENT_BUS);
        com.metrovoc.reprise.gui.MenuButtons.register(NeoForge.EVENT_BUS);
    }
}

package driver;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Runs the driver on NeoForge. Its source set belongs to the mod (see neoforge.gradle), so it subscribes as the mod. */
@EventBusSubscriber(value = Dist.CLIENT)
public final class NeoForgeHook {
    private NeoForgeHook() {
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        Driver.tick();
    }
}

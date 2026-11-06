package driver;

import com.metrovoc.reprise.api.ReplaySnapshotEvent;
import com.metrovoc.reprise.record.Recorder;
import io.netty.buffer.ByteBuf;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.payload.AuxiliaryLightDataPayload;

/** Changes only the client world so replay tests can distinguish a mod payload from a vanilla block update. */
@EventBusSubscriber(value = Dist.CLIENT)
record MarkerPayload(BlockPos pos) implements CustomPacketPayload {
    static final Type<MarkerPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("reprise_driver", "marker"));
    static final StreamCodec<ByteBuf, MarkerPayload> CODEC = BlockPos.STREAM_CODEC.map(MarkerPayload::new, MarkerPayload::pos);

    private static final WeakHashMap<ClientLevel, Set<BlockPos>> markers = new WeakHashMap<>();

    static boolean contains(ClientLevel level, BlockPos pos) {
        return markers.getOrDefault(level, Set.of()).contains(pos);
    }

    private static boolean markBoundary;

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void tick(ClientTickEvent.Post event) {
        var recorder = Recorder.active();
        if (markBoundary && recorder != null && recorder.recordedTicks() == Recorder.SEGMENT_TICKS) {
            markBoundary = false;
            var packet = new MarkerPayload(new BlockPos(4, -59, 4)).toVanillaClientbound();
            packet.handle(Minecraft.getInstance().getConnection());
        }
    }

    static void snapshot(ReplaySnapshotEvent event) {
        for (var pos : markers.getOrDefault(event.level(), Set.of())) {
            event.write(new MarkerPayload(pos));
        }
    }

    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").optional().playToClient(TYPE, CODEC, (marker, context) -> {
            var level = (ClientLevel) context.player().level();
            markers.computeIfAbsent(level, unused -> new HashSet<>()).add(marker.pos());
            level.setBlock(marker.pos(), Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        });
    }

    static void step(Minecraft mc, String arg) {
        if (arg.equals("boundary")) {
            markBoundary = true;
            return;
        }
        String[] args = arg.split(" ");
        var pos = new BlockPos(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
        switch (args[0]) {
            case "send", "bundle", "light" -> {
                var server = mc.getSingleplayerServer();
                var id = mc.player.getUUID();
                server.execute(() -> {
                    var recipient = server.getPlayerList().getPlayer(id);
                    var packet = new MarkerPayload(pos).toVanillaClientbound();
                    switch (args[0]) {
                        case "send" -> recipient.connection.send(packet);
                        case "bundle" -> recipient.connection.send(new ClientboundBundlePacket(List.of(packet)));
                        case "light" -> recipient.connection.send(new AuxiliaryLightDataPayload(
                            ChunkPos.containing(pos), Map.of(pos, (byte) 12)).toVanillaClientbound());
                    }
                });
            }
            case "lit", "dark" -> {
                int light = mc.level.getAuxLightManager(pos).getLightAt(pos);
                if (light != (args[0].equals("lit") ? 12 : 0)) {
                    throw new IllegalStateException("Unexpected auxiliary light " + light + " at " + pos);
                }
            }
            case "gold", "air" -> {
                boolean marked = args[0].equals("gold");
                var actual = mc.level.getBlockState(pos);
                if (contains(mc.level, pos) != marked || !actual.is(marked ? Blocks.GOLD_BLOCK : Blocks.AIR)) {
                    throw new IllegalStateException("Unexpected marker or block at " + pos + ": " + actual);
                }
            }
            default -> throw new IllegalArgumentException(arg);
        }
    }

    @Override
    public Type<MarkerPayload> type() {
        return TYPE;
    }
}

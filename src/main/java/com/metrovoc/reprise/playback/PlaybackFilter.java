package com.metrovoc.reprise.playback;

import java.util.Optional;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.level.GameType;

/** Adapts recorded packets to a viewer: the camera is a spectator, and seeking stays silent. */
final class PlaybackFilter {
    private final ReplaySession session;

    PlaybackFilter(ReplaySession session) {
        this.session = session;
    }

    Packet<? super ClientGamePacketListener> apply(Packet<? super ClientGamePacketListener> packet) {
        boolean fast = this.session.isFastForwarding();
        switch (packet) {
            case net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom -> {
                return com.metrovoc.reprise.record.ReplayPayloads.records(custom.payload()) ? packet : null;
            }
            case ClientboundLoginPacket login -> {
                this.session.setRecorderId(login.playerId());
                return new ClientboundLoginPacket(ReplaySession.CAMERA_ID, login.hardcore(), login.levels(), login.maxPlayers(),
                    login.chunkRadius(), login.simulationDistance(), login.reducedDebugInfo(), false, login.doLimitedCrafting(),
                    spectator(login.commonPlayerSpawnInfo()), false);
            }
            case ClientboundRespawnPacket respawn -> {
                return new ClientboundRespawnPacket(spectator(respawn.commonPlayerSpawnInfo()), respawn.dataToKeep());
            }
            case ClientboundGameEventPacket event -> {
                var type = event.getEvent();
                boolean keep = type == ClientboundGameEventPacket.START_RAINING || type == ClientboundGameEventPacket.STOP_RAINING
                    || type == ClientboundGameEventPacket.RAIN_LEVEL_CHANGE || type == ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE
                    || type == ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START;
                return keep ? packet : null;
            }
            case ClientboundExplodePacket explode -> {
                if (fast) {
                    return null;
                }
                // The knockback was meant for the recording player, not the camera.
                return new ClientboundExplodePacket(explode.center(), explode.radius(), explode.blockCount(), Optional.empty(),
                    explode.explosionParticle(), explode.explosionSound(), explode.blockParticles());
            }
            case ClientboundSoundPacket ignored when fast -> {
                return null;
            }
            case ClientboundSoundEntityPacket ignored when fast -> {
                return null;
            }
            case ClientboundLevelParticlesPacket ignored when fast -> {
                return null;
            }
            case ClientboundLevelEventPacket ignored when fast -> {
                return null;
            }
            default -> {
                return packet;
            }
        }
    }

    private static CommonPlayerSpawnInfo spectator(CommonPlayerSpawnInfo info) {
        return new CommonPlayerSpawnInfo(info.dimensionType(), info.dimension(), info.seed(), GameType.SPECTATOR, null,
            info.isDebug(), info.isFlat(), Optional.empty(), 0, info.seaLevel());
    }
}

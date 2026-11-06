package com.metrovoc.reprise.playback;

import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.level.GameType;

/** Adapts recorded packets to a viewer: the camera is a spectator, and seeking stays silent. */
final class PlaybackFilter {
    private final ReplaySession session;
    private Player hotbarOwner;

    boolean hasHotbarFor(Entity player) {
        return player != null && player == hotbarOwner;
    }

    PlaybackFilter(ReplaySession session) {
        this.session = session;
    }

    private Player recorder() {
        var level = Minecraft.getInstance().level;
        return level != null && level.getEntity(session.recorderId()) instanceof Player player ? player : null;
    }

    Packet<? super ClientGamePacketListener> apply(Packet<? super ClientGamePacketListener> packet) {
        if (com.metrovoc.reprise.record.Recorder.liveOnly(packet)) return null;
        boolean fast = this.session.isFastForwarding();
        switch (packet) {
            case net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom -> {
                return com.metrovoc.reprise.record.ReplayPayloads.records(custom.payload()) ? packet : null;
            }
            case ClientboundPlayerChatPacket chat -> {
                // Imported recordings can start mid-chain. Display the archived content without
                // passing stale indexes or signatures to the live connection validator.
                var mc = Minecraft.getInstance();
                var mask = chat.filterMask();
                if (mask.isFullyFiltered() || mc.options.onlyShowSecureChat().get() || mc.isBlocked(chat.sender())) return null;
                Component content = mask.isEmpty() && chat.unsignedContent() != null
                    ? chat.unsignedContent() : mask.applyWithFormatting(chat.body().content());
                return new ClientboundDisguisedChatPacket(content, chat.chatType());
            }
            case ClientboundLoginPacket login -> {
                hotbarOwner = null;
                this.session.setRecorderId(login.playerId());
                return new ClientboundLoginPacket(ReplaySession.CAMERA_ID, login.hardcore(), login.levels(), login.maxPlayers(),
                    login.chunkRadius(), login.simulationDistance(), login.reducedDebugInfo(), false, login.doLimitedCrafting(),
                    spectator(login.commonPlayerSpawnInfo()), false);
            }
            case ClientboundRespawnPacket respawn -> {
                return new ClientboundRespawnPacket(spectator(respawn.commonPlayerSpawnInfo()), respawn.dataToKeep());
            }
            case ClientboundSetHeldSlotPacket selected -> {
                Player player = recorder();
                if (player != null && Inventory.isHotbarSlot(selected.slot())) {
                    player.getInventory().setSelectedSlot(selected.slot());
                    hotbarOwner = player;
                }
                return null;
            }
            case ClientboundSetPlayerInventoryPacket item -> {
                Player player = recorder();
                if (player != null && Inventory.isHotbarSlot(item.slot())) player.getInventory().setItem(item.slot(), item.contents());
                return null;
            }
            case ClientboundSetHealthPacket health -> {
                Player player = recorder();
                if (player != null) {
                    player.setHealth(health.getHealth());
                    player.getFoodData().setFoodLevel(health.getFood());
                    player.getFoodData().setSaturation(health.getSaturation());
                }
                return null;
            }
            case ClientboundSetExperiencePacket xp -> {
                Player player = recorder();
                if (player != null) {
                    player.experienceLevel = xp.getExperienceLevel();
                    player.experienceProgress = xp.getExperienceProgress();
                    player.totalExperience = xp.getTotalExperience();
                }
                return null;
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

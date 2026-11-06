package com.metrovoc.reprise.record;

import com.metrovoc.reprise.Reprise;
import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import net.minecraft.client.ClientClockManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.network.protocol.configuration.ClientboundUpdateEnabledFeaturesPacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/** Describes the client's current state as the packets a server would send to build it from nothing. */
final class SnapshotBuilder {
    static void write(Recorder recorder, Minecraft mc, EntitySpawnData spawnData, Runnable spawnSelf) {
        // Packets already queued for the game thread would otherwise be applied after the snapshot and recorded twice.
        mc.packetProcessor().processQueuedPackets();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        ClientPacketListener connection = mc.getConnection();

        writeConfiguration(recorder, mc, player);

        List<Packet<? super ClientGamePacketListener>> out = new ArrayList<>();
        CommonPlayerSpawnInfo spawnInfo = new CommonPlayerSpawnInfo(
            level.dimensionTypeRegistration(), level.dimension(), level.getBiomeManager().biomeZoomSeed,
            mc.gameMode.getPlayerMode(), mc.gameMode.getPreviousPlayerMode(), level.isDebug(), level.getLevelData().isFlat,
            Optional.empty(), 0, level.getSeaLevel());
        out.add(new ClientboundLoginPacket(player.getId(), level.getLevelData().isHardcore(), connection.levels(), 1,
            mc.options.getEffectiveRenderDistance(), level.getServerSimulationDistance(), player.isReducedDebugInfo(),
            player.shouldShowDeathScreen(), player.getDoLimitedCrafting(), spawnInfo, false));
        out.add(playerInfo(connection, level));
        out.add(new ClientboundTabListPacket(
            orEmpty(mc.gui.getTabList().header), orEmpty(mc.gui.getTabList().footer)));
        for (LerpingBossEvent event : mc.gui.getBossOverlay().events.values()) {
            out.add(ClientboundBossEventPacket.createAddPacket(event));
        }
        scoreboard(connection.scoreboard(), out);

        out.add(new ClientboundInitializeBorderPacket(level.getWorldBorder()));
        out.add(new ClientboundSetTimePacket(level.getGameTime(), clocks(level.clockManager())));
        out.add(new ClientboundSetDefaultSpawnPositionPacket(level.getRespawnData()));
        out.add(new ClientboundGameEventPacket(level.isRaining() ? ClientboundGameEventPacket.START_RAINING : ClientboundGameEventPacket.STOP_RAINING, 0));
        out.add(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, level.getRainLevel(1)));
        out.add(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, level.getThunderLevel(1)));
        out.add(new ClientboundSetChunkCacheCenterPacket(player.chunkPosition().x(), player.chunkPosition().z()));
        recorder.writeGame(out);
        out.clear();

        chunks(recorder, level, player);
        spawnSelf.run();

        for (Entity entity : level.entitiesForRendering()) {
            if (entity == player || entity instanceof EnderDragonPart) {
                continue;
            }
            entity(entity, spawnData, out);
        }
        for (Map.Entry<MapId, MapItemSavedData> entry : level.getAllMapData().entrySet()) {
            MapItemSavedData data = entry.getValue();
            List<MapDecoration> decorations = new ArrayList<>();
            data.getDecorations().forEach(decorations::add);
            out.add(new ClientboundMapItemDataPacket(entry.getKey(), data.scale, data.locked, Optional.of(decorations),
                Optional.of(new MapItemSavedData.MapPatch(0, 0, 128, 128, data.colors.clone()))));
        }
        recorder.writeGame(out);
    }

    private static void writeConfiguration(Recorder recorder, Minecraft mc, LocalPlayer player) {
        recorder.writeConfig(new ClientboundUpdateEnabledFeaturesPacket(FeatureFlags.REGISTRY.toNames(mc.level.enabledFeatures())));
        RegistryOps<Tag> ops = player.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        // No known packs: the replay carries every synced registry entry, so it plays without the server's data packs.
        RegistrySynchronization.packRegistries(ops, player.registryAccess(), Set.of(),
            (key, entries) -> recorder.writeConfig(new ClientboundRegistryDataPacket(key, entries)));
        Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> tags = new HashMap<>();
        player.registryAccess().registries().forEach(entry -> {
            try {
                TagNetworkSerialization.NetworkPayload payload = TagNetworkSerialization.serializeToNetwork(entry.value());
                if (!payload.isEmpty()) {
                    tags.put(entry.key(), payload);
                }
            } catch (RuntimeException ignored) {
                // A registry with holders the network cannot name; the server would not have sent its tags either.
            }
        });
        recorder.writeConfig(new ClientboundUpdateTagsPacket(tags));
        recorder.writeConfig(new ClientboundResourcePackPopPacket(Optional.empty()));
        for (var pack : mc.getDownloadedPackSource().manager.packs) {
            recorder.writeConfig(new ClientboundResourcePackPushPacket(pack.id, pack.url.toString(),
                pack.hash == null ? "" : pack.hash.toString(), false, Optional.empty()));
        }
    }

    private static ClientboundPlayerInfoUpdatePacket playerInfo(ClientPacketListener connection, ClientLevel level) {
        Map<UUID, ClientboundPlayerInfoUpdatePacket.Entry> entries = new java.util.LinkedHashMap<>();
        Set<UUID> listed = new HashSet<>();
        connection.getListedOnlinePlayers().forEach(info -> listed.add(info.getProfile().id()));
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            entries.put(info.getProfile().id(), entry(info, listed.contains(info.getProfile().id())));
        }
        // Players seen in the world but missing from the tab list (some servers hide them) still need a profile.
        for (var p : level.players()) {
            if (!entries.containsKey(p.getUUID())) {
                PlayerInfo info = connection.getPlayerInfo(p.getUUID());
                entries.put(p.getUUID(), info != null ? entry(info, false)
                    : new ClientboundPlayerInfoUpdatePacket.Entry(p.getUUID(), p.getGameProfile(), false, 0,
                        net.minecraft.world.level.GameType.SURVIVAL, null, true, 0, null));
            }
        }
        var packet = new ClientboundPlayerInfoUpdatePacket(EnumSet.of(
            ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_HAT,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LIST_ORDER), List.of());
        packet.entries = new ArrayList<>(entries.values());
        return packet;
    }

    private static ClientboundPlayerInfoUpdatePacket.Entry entry(PlayerInfo info, boolean listed) {
        return new ClientboundPlayerInfoUpdatePacket.Entry(info.getProfile().id(), info.getProfile(), listed, info.getLatency(),
            info.getGameMode(), info.getTabListDisplayName(), info.showHat(), info.getTabListOrder(), null);
    }

    private static void scoreboard(Scoreboard scoreboard, List<Packet<? super ClientGamePacketListener>> out) {
        for (PlayerTeam team : scoreboard.getPlayerTeams()) {
            out.add(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true));
        }
        for (Objective objective : scoreboard.getObjectives()) {
            out.add(new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_ADD));
            for (PlayerScoreEntry score : scoreboard.listPlayerScores(objective)) {
                out.add(new ClientboundSetScorePacket(score.owner(), objective.getName(), score.value(),
                    Optional.ofNullable(score.display()), Optional.ofNullable(score.numberFormatOverride())));
            }
        }
        for (DisplaySlot slot : DisplaySlot.values()) {
            Objective objective = scoreboard.getDisplayObjective(slot);
            if (objective != null) {
                out.add(new ClientboundSetDisplayObjectivePacket(slot, objective));
            }
        }
    }

    private static Map<Holder<WorldClock>, ClockNetworkState> clocks(ClientClockManager manager) {
        Map<Holder<WorldClock>, ClockNetworkState> states = new HashMap<>();
        manager.clocks.forEach((holder, clock) -> states.put(holder, new ClockNetworkState(clock.totalTicks, clock.partialTick, clock.rate)));
        return states;
    }

    private static void chunks(Recorder recorder, ClientLevel level, LocalPlayer player) {
        // Finish pending light work so the chunks carry their final light.
        Runnable task;
        while ((task = level.lightUpdateQueue.poll()) != null) {
            task.run();
        }
        level.getLightEngine().runLightUpdates();

        List<LevelChunk> chunks = new ArrayList<>();
        var storage = level.getChunkSource().storage.chunks;
        for (int i = 0; i < storage.length(); i++) {
            LevelChunk chunk = storage.get(i);
            if (chunk != null) {
                chunks.add(chunk);
            }
        }
        Vec3 pos = player.position();
        chunks.sort(Comparator.comparingDouble(c -> c.getPos().getMiddleBlockPosition(0).distToCenterSqr(pos.x, 0, pos.z)));

        // Packing a chunk only reads it; the game thread waits here, so nothing writes to the chunks meanwhile.
        List<ForkJoinTask<ClientboundLevelChunkWithLightPacket>> tasks = new ArrayList<>(chunks.size());
        for (LevelChunk chunk : chunks) {
            tasks.add(ForkJoinPool.commonPool().submit(() -> new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null)));
        }
        for (ForkJoinTask<ClientboundLevelChunkWithLightPacket> t : tasks) {
            try {
                recorder.writeGame(t.join());
            } catch (RuntimeException e) {
                Reprise.LOGGER.warn("Could not record a chunk", e);
            }
        }
    }

    private static void entity(Entity entity, EntitySpawnData spawnData, List<Packet<? super ClientGamePacketListener>> out) {
        // Spawn at the position the server's later relative moves are measured from.
        Vec3 base = entity.getPositionCodec().base;
        out.add(new ClientboundAddEntityPacket(entity.getId(), entity.getUUID(), base.x, base.y, base.z, entity.getXRot(), entity.getYRot(),
            entity.getType(), spawnData.get(entity.getId(), 0), entity.getDeltaMovement(), entity.getYHeadRot()));
        var data = entity.getEntityData().getNonDefaultValues();
        if (data != null && !data.isEmpty()) {
            out.add(new ClientboundSetEntityDataPacket(entity.getId(), data));
        }
        if (entity instanceof LivingEntity living) {
            var attributes = living.getAttributes().getSyncableAttributes();
            if (!attributes.isEmpty()) {
                out.add(new ClientboundUpdateAttributesPacket(entity.getId(), attributes));
            }
            List<Pair<EquipmentSlot, ItemStack>> equipment = new ArrayList<>();
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack stack = living.getItemBySlot(slot);
                if (!stack.isEmpty()) {
                    equipment.add(Pair.of(slot, stack.copy()));
                }
            }
            if (!equipment.isEmpty()) {
                out.add(new ClientboundSetEquipmentPacket(entity.getId(), equipment));
            }
            living.getActiveEffects().forEach(effect -> out.add(new ClientboundUpdateMobEffectPacket(entity.getId(), effect, false)));
        }
        if (entity.isVehicle()) {
            out.add(new ClientboundSetPassengersPacket(entity));
        }
        if (entity instanceof Leashable leashable && leashable.isLeashed()) {
            out.add(new ClientboundSetEntityLinkPacket(entity, leashable.getLeashHolder()));
        }
    }

    private static Component orEmpty(Component c) {
        return c == null ? Component.empty() : c;
    }

    private SnapshotBuilder() {}
}

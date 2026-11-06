package com.metrovoc.reprise.record;

import com.metrovoc.reprise.Reprise;
import com.metrovoc.reprise.ReprisePaths;
import com.metrovoc.reprise.format.EmbeddedPacks;
import com.metrovoc.reprise.format.ReplayMeta;
import com.metrovoc.reprise.format.Actions;
import io.netty.buffer.ByteBuf;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.resources.server.ServerPackManager;
import net.minecraft.network.PacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.*;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket;
import net.minecraft.network.protocol.game.*;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.connection.ConnectionType;

/**
 * Records the session the client is in. Every packet the client applies on the game thread is written in the order it
 * was applied; what the server never sends to the recording player about themself is synthesized by
 * {@link SelfTracker}. Each segment begins with a snapshot of the client's state, so playback can start anywhere.
 */
public final class Recorder {
    /** Segments are cut at this length so that a seek never has to fast-forward more than one segment. */
    public static final int SEGMENT_TICKS = Integer.getInteger("reprise.segmentTicks", 20 * 60 * 5);

    private static volatile Recorder active;

    private final Minecraft minecraft = Minecraft.getInstance();
    private final ReplayMeta meta = new ReplayMeta();
    private final ReplayWriter writer;
    private final PovRecorder pov;
    private final SelfTracker self = new SelfTracker(this);
    private final EntitySpawnData spawnData = new EntitySpawnData();
    private StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> gameCodec;
    private ConnectionType connectionType;
    private boolean snapshotPending = true;
    private boolean resetPending = false;
    private boolean paused;
    private boolean configuring;
    private List<ServerPackManager.ServerPackData> recordedPacks = List.of();
    private int failedEncodes;
    private int segmentTicks;
    private int recordedTicks;

    private Recorder(ClientPacketListener connection) throws IOException {
        int divisions = com.metrovoc.reprise.RepriseConfig.POV_DIVISIONS.get();
        pov = divisions > 1 ? new PovRecorder(divisions) : null;
        this.connectionType = connection.getConnectionType();
        this.gameCodec = PacketCodecs.game(connection.registryAccess(), this.connectionType);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        ServerData server = this.minecraft.getCurrentServer();
        this.meta.name = stamp;
        this.meta.recordedAt = System.currentTimeMillis();
        this.meta.mcVersion = SharedConstants.getCurrentVersion().name();
        this.meta.protocol = SharedConstants.getProtocolVersion();
        this.meta.dataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
        this.meta.snapshotConnectionType = true;
        this.meta.neoforgeConnection = this.connectionType == ConnectionType.NEOFORGE;
        this.meta.singleplayer = this.minecraft.isLocalServer();
        if (this.minecraft.getSingleplayerServer() != null) {
            this.meta.server = this.minecraft.getSingleplayerServer().getWorldData().getLevelName();
        } else if (server != null) {
            this.meta.server = server.ip;
        }
        this.meta.recorderName = this.minecraft.getUser().getName();
        this.meta.recorderUuid = this.minecraft.getUser().getProfileId().toString();
        ModList.get().getMods().forEach(mod -> this.meta.mods.put(mod.getModId(), mod.getVersion().toString()));
        Path dir = Files.createTempDirectory(ReprisePaths.recordings(), stamp + "-");
        this.writer = new ReplayWriter(dir, this.meta);
    }

    public static Recorder active() {
        return active;
    }

    public static boolean isRecording() {
        return active != null;
    }

    public static void start() {
        Minecraft mc = Minecraft.getInstance();
        if (active != null || mc.getConnection() == null || mc.level == null || com.metrovoc.reprise.playback.ReplaySession.active() != null) {
            return;
        }
        try {
            active = new Recorder(mc.getConnection());
            Reprise.LOGGER.info("Recording to {}", active.writer.dir());
            mc.gui.setOverlayMessage(Component.translatable("reprise.recording.started"), false);
        } catch (IOException e) {
            Reprise.LOGGER.error("Could not start recording", e);
        }
    }

    /** Stops recording and packs the replay into the replays folder. */
    public static Path stop() {
        Recorder recorder = active;
        if (recorder == null) {
            return null;
        }
        active = null;
        recorder.writer.close();
        if (recorder.writer.failure() != null) {
            Reprise.LOGGER.error("Recording stopped after a write error; recoverable data stays in {}", recorder.writer.dir());
            if (recorder.minecraft.player != null) {
                recorder.minecraft.player.sendSystemMessage(Component.translatable("reprise.recording.write_failed"));
            }
            return null;
        }
        try {
            Path file = ReplayPacker.pack(recorder.writer.dir(), recorder.meta.name);
            Reprise.LOGGER.info("Saved replay {}", file);
            return file;
        } catch (IOException e) {
            Reprise.LOGGER.error("Could not save replay; the recording stays in {}", recorder.writer.dir(), e);
            return null;
        }
    }

    /** Ticks recorded so far, counted on the game thread. */
    public int recordedTicks() {
        return this.recordedTicks;
    }

    public Path directory() {
        return this.writer.dir();
    }

    public boolean isPaused() {
        return this.paused;
    }

    public void setPaused(boolean paused) {
        if (this.paused && !paused) {
            this.snapshotPending = true;
            this.resetPending = true;
        }
        if (pov != null && paused) pov.reset();
        this.paused = paused;
    }

    public ReplayMeta meta() {
        return this.meta;
    }

    public void addMarker(ReplayMeta.Marker marker) {
        this.writer.submit(w -> w.addMarker(marker));
    }

    // Packet capture, on the game thread, before the client applies each packet.

    public void onPacket(Packet<?> packet, PacketListener listener) {
        if (listener instanceof ClientConfigurationPacketListener) {
            // A server switch or re-configuration: the registries may change, so start over with a fresh snapshot.
            this.configuring = true;
            return;
        }
        if (!(listener instanceof ClientPacketListener connection)) {
            return;
        }
        if (this.configuring) {
            this.configuring = false;
            this.connectionType = connection.getConnectionType();
            this.gameCodec = PacketCodecs.game(connection.registryAccess(), this.connectionType);
            this.snapshotPending = true;
            this.resetPending = true;
        }
        // Bundle members are observed individually as the listener applies them.
        if (packet instanceof ClientboundBundlePacket) {
            return;
        }
        this.spawnData.observe(packet);
        if (packet instanceof ClientboundLoginPacket || packet instanceof ClientboundRespawnPacket) {
            this.self.respawned();
            if (pov != null) pov.reset();
        }
        if (this.snapshotPending || this.paused) {
            return;
        }
        Packet<? super ClientGamePacketListener> recorded = filter(packet);
        if (recorded != null) {
            writeGame(recorded);
        }
    }

    @SuppressWarnings("unchecked")
    private Packet<? super ClientGamePacketListener> filter(Packet<?> packet) {
        if (liveOnly(packet) || CAPTURED_SEPARATELY.contains(packet.getClass())) {
            return null;
        }
        int selfId = this.minecraft.player == null ? Integer.MIN_VALUE : this.minecraft.player.getId();
        switch (packet) {
            case ClientboundSetEntityDataPacket data when data.id() == selfId -> {
                return null;
            }
            case ClientboundSetEntityMotionPacket motion when motion.id() == selfId -> {
                return null;
            }
            case ClientboundSetEquipmentPacket equipment when equipment.getEntity() == selfId -> {
                return null;
            }
            default -> {
                return (Packet<? super ClientGamePacketListener>) packet;
            }
        }
    }

    private static final Set<Class<?>> CAPTURED_SEPARATELY = Set.of(
        ClientboundPlayerChatPacket.class, ClientboundDisguisedChatPacket.class,
        ClientboundResourcePackPushPacket.class, ClientboundResourcePackPopPacket.class,
        ClientboundCustomPayloadPacket.class, ClientboundSetPlayerInventoryPacket.class, ClientboundSetHeldSlotPacket.class,
        ClientboundSetHealthPacket.class, ClientboundSetExperiencePacket.class, ClientboundSoundPacket.class, ClientboundSoundEntityPacket.class
    );

    /** Live connection and local UI instructions must not be delivered by an imported recording. */
    public static boolean liveOnly(Packet<?> packet) {
        return LIVE_ONLY.contains(packet.getClass());
    }

    private static final Set<Class<?>> LIVE_ONLY = Set.of(
        ClientboundKeepAlivePacket.class, ClientboundPingPacket.class, ClientboundDisconnectPacket.class,
        ClientboundStoreCookiePacket.class, ClientboundCookieRequestPacket.class,
        ClientboundTransferPacket.class, ClientboundShowDialogPacket.class, ClientboundClearDialogPacket.class,
        ClientboundCustomReportDetailsPacket.class, ClientboundStartConfigurationPacket.class,
        ClientboundChunkBatchStartPacket.class, ClientboundChunkBatchFinishedPacket.class,
        ClientboundPlayerPositionPacket.class, ClientboundPlayerRotationPacket.class, ClientboundPlayerLookAtPacket.class,
        ClientboundMoveVehiclePacket.class, ClientboundSetCameraPacket.class,
        ClientboundContainerSetContentPacket.class, ClientboundContainerSetSlotPacket.class, ClientboundContainerSetDataPacket.class,
        ClientboundContainerClosePacket.class, ClientboundOpenScreenPacket.class, ClientboundMountScreenOpenPacket.class,
        ClientboundOpenBookPacket.class, ClientboundOpenSignEditorPacket.class, ClientboundMerchantOffersPacket.class,
        ClientboundSetCursorItemPacket.class, ClientboundPlayerAbilitiesPacket.class,
        ClientboundCooldownPacket.class, ClientboundPlayerCombatEnterPacket.class, ClientboundPlayerCombatEndPacket.class,
        ClientboundPlayerCombatKillPacket.class, ClientboundAwardStatsPacket.class, ClientboundUpdateAdvancementsPacket.class,
        ClientboundSelectAdvancementsTabPacket.class, ClientboundRecipeBookAddPacket.class, ClientboundRecipeBookRemovePacket.class,
        ClientboundRecipeBookSettingsPacket.class, ClientboundUpdateRecipesPacket.class, ClientboundPlaceGhostRecipePacket.class,
        ClientboundCommandsPacket.class, ClientboundCommandSuggestionsPacket.class, ClientboundCustomChatCompletionsPacket.class,
        ClientboundTickingStatePacket.class, ClientboundTickingStepPacket.class, ClientboundBlockChangedAckPacket.class,
        ClientboundDeleteChatPacket.class, ClientboundTagQueryPacket.class, ClientboundServerDataPacket.class,
        ClientboundDebugBlockValuePacket.class, ClientboundDebugChunkValuePacket.class, ClientboundDebugEntityValuePacket.class,
        ClientboundDebugEventPacket.class, ClientboundDebugSamplePacket.class, ClientboundGameTestHighlightPosPacket.class,
        ClientboundTestInstanceBlockStatus.class, ClientboundLowDiskSpaceWarningPacket.class, ClientboundGameRuleValuesPacket.class
    );

    /** Called before a registered handler consumes its payload, possibly on the network thread. */
    public void capturePayload(ClientPacketListener listener, ClientboundCustomPayloadPacket packet) {
        if (!ReplayPayloads.records(packet.payload())) {
            return;
        }
        byte[] bytes;
        try {
            // The connection has already bound this codec to the live registries and negotiated connection type.
            @SuppressWarnings("unchecked")
            var protocol = (net.minecraft.network.ProtocolInfo<ClientGamePacketListener>) listener.getConnection().getInboundProtocol();
            bytes = PacketCodecs.encode(protocol.codec(), packet);
        } catch (RuntimeException e) {
            Reprise.LOGGER.warn("Could not record payload {}", packet.payload().type().id(), e);
            return;
        }
        Runnable append = () -> {
            if (active == this && this.minecraft.getConnection() == listener && !this.snapshotPending && !this.paused && !this.configuring) {
                this.writer.submit(w -> w.gamePacket(bytes));
            }
        };
        var processor = listener.getPacketProcessor();
        if (processor.isSameThread()) {
            append.run();
        } else {
            processor.scheduleIfPossible(append);
        }
    }

    // Writing; all on the game thread, the writer only sees bytes.

    void writeGame(Packet<? super ClientGamePacketListener> packet) {
        byte[] bytes;
        try {
            bytes = PacketCodecs.encode(this.gameCodec, packet);
        } catch (Exception e) {
            if (this.failedEncodes++ < 10) {
                Reprise.LOGGER.warn("Could not record {}", packet.type(), e);
            }
            return;
        }
        if (packet instanceof ClientboundLevelChunkWithLightPacket) {
            this.writer.submit(w -> w.chunkPacket(bytes));
        } else {
            this.writer.submit(w -> w.gamePacket(bytes));
        }
    }

    /** A packet the client made up for its own player; recorded unless a snapshot or pause is pending. */
    public void recordLocal(Packet<? super ClientGamePacketListener> packet) {
        if (!this.snapshotPending && !this.paused && !this.configuring) {
            writeGame(packet);
        }
    }

    void writeGame(List<Packet<? super ClientGamePacketListener>> packets) {
        for (Packet<? super ClientGamePacketListener> packet : packets) {
            writeGame(packet);
        }
    }

    void writeConfig(Packet<? super ClientConfigurationPacketListener> packet) {
        byte[] bytes = PacketCodecs.encode(PacketCodecs.CONFIG, packet);
        this.writer.submit(w -> w.configPacket(bytes));
    }

    /** Record the packs Minecraft actually applied, in stack order, after download and acceptance. */
    void recordPacks(boolean snapshot) {
        var packs = minecraft.getDownloadedPackSource().manager.packs;
        if (!snapshot && packs.isEmpty() && recordedPacks.isEmpty()) return;
        var applied = packs.stream().filter(pack -> pack.activationStatus
            == ServerPackManager.ActivationStatus.ACTIVE).toList();
        if (!snapshot && applied.equals(recordedPacks)) return;
        var clear = new ClientboundResourcePackPopPacket(Optional.empty());
        if (snapshot) writeConfig(clear);
        else writeGame(clear);
        var codec = gameCodec;
        for (var pack : applied) {
            var file = pack.path;
            var id = pack.id;
            writer.submit(w -> {
                String hash = w.storePack(pack, file);
                var push = new ClientboundResourcePackPushPacket(id, EmbeddedPacks.URL_PREFIX + hash,
                    hash, false, Optional.empty());
                if (snapshot) w.configPacket(PacketCodecs.encode(PacketCodecs.CONFIG, push));
                else w.gamePacket(PacketCodecs.encode(codec, push));
            });
        }
        recordedPacks = applied;
    }

    /** Observe player input at camera alignment, before later render events can change the next frame. */
    public void sampleView(float partial) {
        if (pov != null && !paused && !configuring && !snapshotPending && !minecraft.isPaused()) {
            pov.frame(minecraft.player, partial);
        }
    }

    public void endTick() {
        Throwable failure = this.writer.failure();
        if (failure != null) {
            Reprise.LOGGER.error("Stopping the recording after a write error");
            stop();
            return;
        }
        if (this.paused || this.configuring || this.minecraft.player == null || this.minecraft.level == null
            || this.minecraft.screen instanceof LevelLoadingScreen || this.minecraft.isPaused()) {
            if (pov != null) pov.reset();
            return;
        }
        byte[] samples = pov == null ? null : pov.finish(this.minecraft.player);
        if (this.snapshotPending) {
            beginSegment(this.resetPending || this.recordedTicks == 0, null);
            return;
        }
        recordPacks(false);
        this.self.tick();
        this.writer.submit(w -> {
            if (samples != null) w.povSamples(samples);
            w.tick();
        });
        this.recordedTicks++;
        if (++this.segmentTicks >= SEGMENT_TICKS) {
            beginSegment(false, samples);
        }
    }

    /** Cut between ticks, before any packets for the next tick have been applied. */
    private void beginSegment(boolean reset, byte[] samples) {
        this.snapshotPending = false;
        this.resetPending = false;
        this.segmentTicks = 0;
        this.writer.submit(w -> w.beginSegment(reset, pov == null ? Actions.VERSION : Actions.PACKED_POV_VERSION));
        SnapshotBuilder.write(this, this.minecraft, this.spawnData, this.self::spawn);
        this.writer.submit(w -> {
            if (samples != null) w.povSamples(samples);
            w.snapshotEnd();
        });
    }
}

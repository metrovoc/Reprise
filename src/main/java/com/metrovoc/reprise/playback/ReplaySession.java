package com.metrovoc.reprise.playback;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.metrovoc.reprise.Reprise;
import com.metrovoc.reprise.editor.EnvironmentSettings.Layer;
import com.metrovoc.reprise.editor.ReplayProject;
import com.metrovoc.reprise.format.Actions;
import com.metrovoc.reprise.format.EmbeddedPacks;
import com.metrovoc.reprise.format.ReplayFile;
import com.metrovoc.reprise.format.ReplayMeta;
import com.metrovoc.reprise.format.PovSamples;
import com.metrovoc.reprise.record.PacketCodecs;
import com.metrovoc.reprise.util.Varints;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientRegistryLayer;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.RunningOnDifferentThreadException;
import net.minecraft.server.ServerLinks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.payload.ModdedNetworkPayload;
import net.neoforged.neoforge.network.payload.ModdedNetworkQueryPayload;

/**
 * Plays a replay into the vanilla client. The client believes it is connected to a server: packets from the file are
 * decoded with the vanilla codecs and handed to the vanilla listeners on a connection whose channel discards
 * everything the client sends. The local player is the camera, a spectator no one else can see.
 *
 * <p>Time is counted in recorded ticks. While playing, one recorded tick is applied per client tick, and the client
 * ticks at 20 × speed per second. Seeking re-runs the nearest segment's snapshot and fast-forwards from there.
 */
public final class ReplaySession {
    /** The camera's entity id; recorded ids are never negative this far down. */
    public static final int CAMERA_ID = -1380995667;

    private static ReplaySession active;

    private final Minecraft mc = Minecraft.getInstance();
    private final ReplayFile file;
    private final ReplayMeta meta;
    private final ConnectionType connectionType;
    private final GameProfile cameraProfile;
    private final Screen exitScreen;
    private final PlaybackFilter filter = new PlaybackFilter(this);

    private Connection connection;
    private EmbeddedChannel channel;
    private ClientConfigurationPacketListenerImpl configListener;
    private StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> gameCodec;
    private HashCode configHash;
    private final ReplaySkyLightCache skyLightCache = new ReplaySkyLightCache();

    private int segment = -1;
    private ByteBuffer reader;
    /** Recorded ticks applied so far: the replay's current time. */
    private int tick;
    private int shotOffset;
    private List<ReplayProject.Freeze> currentFreezes;

    private boolean playing;
    private double pausedCursor = Double.NaN;
    private float speed = 1;
    private boolean fastForwarding;
    private boolean seeking;
    private boolean reconnecting;
    private boolean switchingWorld;
    private int recorderId = Integer.MIN_VALUE;
    private boolean cameraPlaced;
    private boolean usedEmbeddedPacks;
    private final Set<UUID> embeddedPackIds = new HashSet<>();
    private final List<ClientboundResourcePackPushPacket> snapshotPacks = new ArrayList<>();
    private Map<Integer, PovSamples> pov = new java.util.HashMap<>(), pendingPov = new java.util.HashMap<>();

    private int failedPackets;
    private UUID spectating;
    private final ReplayProject project;
    private ReplayProject queuedProject;

    private ReplaySession(ReplayFile file, Screen exitScreen) {
        this.file = file;
        this.meta = file.meta();
        this.exitScreen = exitScreen;
        this.connectionType = this.meta.neoforgeConnection ? ConnectionType.NEOFORGE : ConnectionType.OTHER;
        this.cameraProfile = new GameProfile(UUID.randomUUID(), this.mc.getUser().getName());
        this.project = ReplayProject.load(this.meta);
    }

    public ReplayProject project() {
        return this.queuedProject != null ? this.queuedProject : this.project;
    }

    public void setQueuedProject(ReplayProject value) {
        this.queuedProject = value;
        refreshEnvironment();
    }

    /** The replay time shown on screen, in ticks, with the fraction the renderer is between ticks. */
    public double visualTick(float partialTick) {
        if (this.playing && !project().freezes.isEmpty()) {
            partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        }
        int whole = tick();
        if (playing) return Math.max(0, whole - 1 + partialTick);
        return Double.isNaN(pausedCursor) ? whole : pausedCursor;
    }

    public static ReplaySession active() {
        return active;
    }

    public static boolean isActive() {
        return active != null;
    }

    /** Opens a replay from a menu. */
    public static void open(Path path, Screen exitScreen) {
        if (active != null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        try {
            ReplayFile file = new ReplayFile(path);
            if (file.meta().protocol != net.minecraft.SharedConstants.getProtocolVersion()) {
                Reprise.LOGGER.warn("Replay {} was recorded on protocol {}, this game is {}", path, file.meta().protocol,
                    net.minecraft.SharedConstants.getProtocolVersion());
            }
            try {
                active = new ReplaySession(file, exitScreen);
            } catch (RuntimeException e) {
                file.close();
                throw e;
            }
            mc.setScreen(new GenericMessageScreen(Component.translatable("reprise.replay.loading")));
            active.playSnapshot(0);
            active.applyResetsAtCurrentTick();
            if (active.meta.openingTick > 0) {
                active.fastForwarding = true;
                try {
                    active.advance(Math.min(active.meta.openingTick, active.meta.durationTicks));
                } finally {
                    active.fastForwarding = false;
                }
            }
            active.currentFreezes = null;
            active.settleEntities();
        } catch (Exception e) {
            Reprise.LOGGER.error("Could not open replay {}", path, e);
            if (active != null) {
                active.close();
            }
            mc.setScreen(new net.minecraft.client.gui.screens.AlertScreen(() -> mc.setScreen(exitScreen),
                Component.translatable("reprise.replay.failed"), Component.literal(String.valueOf(e.getMessage()))));
        }
    }

    /** Leaves the replay and returns to the screen it was opened from. */
    public void exit() {
        disconnect(this.exitScreen);
    }

    private void disconnect(Screen screen) {
        // Minecraft drops queued tasks as it disconnects. Drain the pack manager's scheduled
        // changes first so its update remains runnable on the next connection.
        if (usedEmbeddedPacks) mc.clearDownloadedResourcePacks();
        mc.disconnect(screen, usedEmbeddedPacks);
    }

    /** Called when the client disconnects from the replay. */
    public void close() {
        if (this.reconnecting) {
            return;
        }
        com.metrovoc.reprise.export.VideoExporter.sessionClosed(this);
        active = null;
        skyLightCache.clear();
        CameraPath.setPreview(false);
        this.project.save();
        clearEmbeddedPacks();
        try {
            this.file.close();
        } catch (IOException e) {
            Reprise.LOGGER.warn("Could not close replay files", e);
        }
        if (this.channel != null) {
            this.channel.close();
        }
    }

    // Accessors for the controls and the editor.

    public ReplayMeta meta() {
        return this.meta;
    }

    public ReplayFile file() {
        return this.file;
    }

    public int tick() {
        // Changing shots, undoing an import, or loading a project keeps the current world instant.
        if (currentFreezes != project().freezes) {
            if (!project().freezes.equals(currentFreezes)) {
                pausedCursor = Double.NaN;
                shotOffset = (int) project().shotTick(tick) - tick;
            }
            currentFreezes = project().freezes;
        }
        return this.tick + shotOffset;
    }

    public int recordedTick() { return this.tick; }

    public int duration() {
        return project().shotDuration(meta.durationTicks);
    }

    /** Export can render multiple camera poses at one world instant without ticking the client simulation. */
    public void skipFrozenTicks(int target) {
        if (project().freezes.isEmpty()) return;
        int from = tick();
        if (target <= from || Math.ceil(project().worldTick(from + 1)) != tick) return;
        int low = from + 1, high = target;
        while (low < high) {
            int mid = low + (high - low + 1) / 2;
            if (Math.ceil(project().worldTick(mid)) == tick) low = mid;
            else high = mid - 1;
        }
        shotOffset = low - tick;
    }

    /** Render fraction for the recorded world. The camera still uses the shot clock during a freeze. */
    public float worldPartial(float shotPartial) {
        if (!playing) return 1;
        double world = project().worldTick(Math.max(0, tick() - 1 + shotPartial));
        return (float) Math.clamp(world - tick + 1, 0, 1);
    }

    public static boolean hides(Layer layer) {
        return active != null && active.project().environment.hidden.contains(layer);
    }

    public boolean isPlaying() {
        return this.playing;
    }

    public void setPlaying(boolean playing) {
        this.playing = playing && tick() < duration();
        if (this.playing) pausedCursor = Double.NaN;
    }

    public float speed() {
        return this.speed;
    }

    public void setSpeed(float speed) {
        this.speed = Math.max(0.05F, Math.min(20, speed));
    }

    public boolean isFastForwarding() {
        return this.fastForwarding;
    }

    public boolean isSwitchingWorld() {
        return switchingWorld;
    }

    public boolean isSeeking() {
        return this.seeking;
    }

    /** Spectates {@code entity} until {@link #stopSpectating()}, following it across respawns and seeks. */
    public void spectate(Entity entity) {
        this.spectating = entity.getUUID();
        this.mc.setCameraEntity(entity);
    }

    public UUID spectatedId() {
        return this.spectating;
    }

    public void restoreSpectating(UUID id) {
        stopSpectating();
        this.spectating = id;
        Entity entity = id == null || this.mc.level == null ? null : this.mc.level.getEntity(id);
        if (entity != null) this.mc.setCameraEntity(entity);
    }

    public void stopSpectating() {
        this.spectating = null;
        this.mc.setCameraEntity(this.mc.player);
    }

    public static boolean showingRecordedHotbar() {
        if (active == null) return false;
        Entity camera = active.mc.getCameraEntity();
        return camera != active.mc.player && active.filter.hasHotbarFor(camera) && !camera.isSpectator();
    }

    public int recorderId() {
        return this.recorderId;
    }

    public void setRecorderId(int id) {
        this.recorderId = id;
    }

    public GameProfile cameraProfile() {
        return this.cameraProfile;
    }

    // Ticking.

    /** Runs at the start of every client tick: applies the next recorded tick while playing. */
    public void clientTick() {
        if (this.mc.level == null) {
            return;
        }
        boolean advanceWorld = false;
        if (this.playing) {
            if (tick() >= duration()) {
                this.playing = false;
            } else {
                int next = tick() + 1;
                int recorded = (int) Math.ceil(project().worldTick(next));
                advanceWorld = recorded > this.tick;
                if (advanceWorld) advance(recorded - this.tick);
                shotOffset = next - this.tick;
            }
        }
        this.mc.level.tickRateManager().setFrozen(!advanceWorld);
        if (!this.cameraPlaced && this.mc.player != null) {
            placeCameraAtRecorder();
        }
        // Keep following a spectated entity that was respawned, changed dimension or came back after a seek.
        if (this.spectating != null && this.mc.player != null && this.mc.getCameraEntity() == this.mc.player) {
            for (Entity e : this.mc.level.entitiesForRendering()) {
                if (e.getUUID().equals(this.spectating) && e != this.mc.player) {
                    this.mc.setCameraEntity(e);
                    PovHands.snap();
                    break;
                }
            }
        }
    }

    /** Milliseconds per client tick while this replay plays. */
    public float millisPerTick() {
        // The path preview plays as the export will: at the speed keys' speed.
        if (CameraPath.preview() && !project().speedKeys.isEmpty()) {
            return project().previewMillisPerTick(tick(), duration());
        }
        return (float) (50.0 / Math.max(0.01, this.speed));
    }

    /** World state advances in whole ticks; the paused camera path retains the exact cue time. */
    public void seekEditorCursor(double target) {
        setPlaying(false);
        target = Math.clamp(target, 0, duration());
        seek((int) Math.floor(target));
        pausedCursor = target;
    }

    /** Moves to a whole tick on the shot timeline, keeping the camera where it is. */
    public void seek(int target) {
        pausedCursor = Double.NaN;
        target = Math.max(0, Math.min(duration(), target));
        currentFreezes = project().freezes;
        int shotTarget = target;
        target = (int) Math.ceil(project().worldTick(target));
        shotOffset = shotTarget - target;
        if (target == this.tick) {
            return;
        }
        LocalPlayer camera = this.mc.player;
        Vec3 pos = camera == null ? null : camera.position();
        float yRot = camera == null ? 0 : camera.getYRot();
        float xRot = camera == null ? 0 : camera.getXRot();

        int targetSegment = segmentFor(target);
        boolean forwardInPlace = target > this.tick && !resetBetween(this.segment, targetSegment)
            && (targetSegment == this.segment || target - this.tick < 20 * 60);
        this.seeking = true;
        try {
            if (forwardInPlace) {
                settleEntities();
            } else {
                playSnapshot(targetSegment);
            }
            this.fastForwarding = true;
            advance(target - this.tick);
        } catch (Exception e) {
            Reprise.LOGGER.error("Seek to tick {} failed", target, e);
        } finally {
            this.fastForwarding = false;
            this.seeking = false;
        }
        settleEntities();
        refreshEnvironment();
        if (this.mc.player != null && pos != null) {
            this.mc.player.snapTo(pos.x, pos.y, pos.z, yRot, xRot);
            this.mc.player.setOldPosAndRot();
            this.cameraPlaced = true;
        }
        if (this.spectating != null && this.mc.level != null) {
            for (Entity e : this.mc.level.entitiesForRendering()) {
                if (e.getUUID().equals(this.spectating) && e != this.mc.player) {
                    this.mc.setCameraEntity(e);
                }
            }
        }
        PovHands.snap();
    }

    private boolean resetBetween(int from, int to) {
        for (int i = from + 1; i <= to; i++) {
            if (this.meta.segments.get(i).reset) {
                return true;
            }
        }
        return false;
    }

    private int segmentFor(int tick) {
        List<ReplayMeta.Segment> segments = this.meta.segments;
        for (int i = segments.size() - 1; i >= 0; i--) {
            if (segments.get(i).startTick <= tick) {
                return i;
            }
        }
        return 0;
    }

    /** Applies {@code ticks} recorded ticks. */
    private void advance(int ticks) {
        for (int i = 0; i < ticks && this.tick < meta.durationTicks; i++) {
            readUntilTick();
        }
    }

    private void readUntilTick() {
        while (true) {
            if (this.reader == null || !this.reader.hasRemaining()) {
                if (this.segment + 1 >= this.meta.segments.size()) {
                    this.tick = meta.durationTicks;
                    this.playing = false;
                    return;
                }
                enterNextSegment();
                continue;
            }
            int action = this.reader.get();
            switch (action) {
                case Actions.TICK -> {
                    // Chunk lighting is normally applied between frames. During a seek, later
                    // block changes must see the light data already delivered for that terrain.
                    if (this.fastForwarding) processFoldedTick();
                    advancePov();
                    this.tick++;
                    applyResetsAtCurrentTick();
                    return;
                }
                case Actions.GAME_PACKET -> deliverGame(ByteBuffer.wrap(readBytes()));
                case Actions.POV_SAMPLES, Actions.PACKED_POV -> readPov(action);
                case Actions.CHUNK_REF -> deliverChunk(Varints.read(this.reader));
                case Actions.CONFIG_PACKET -> deliverConfiguration(readBytes());
                case Actions.SNAPSHOT_END -> { }
                default -> throw new IllegalStateException("Unknown action " + action + " in segment " + this.segment);
            }
        }
    }

    /** A reset owns its first tick in both continuous playback and seeking. A cut may follow an empty segment. */
    private void applyResetsAtCurrentTick() {
        int reset = this.segment;
        for (int i = this.segment + 1; i < this.meta.segments.size(); i++) {
            var next = this.meta.segments.get(i);
            if (next.startTick != this.tick) break;
            if (next.reset) reset = i;
        }
        if (reset != this.segment) playSnapshot(reset);
    }

    private void enterNextSegment() {
        int next = this.segment + 1;
        if (this.meta.segments.get(next).reset) {
            playSnapshot(next);
            return;
        }
        try {
            this.reader = this.file.segmentActions(next);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.segment = next;
    }

    private void processFoldedTick() {
        if (mc.level != null && (!mc.level.lightUpdateQueue.isEmpty() || mc.level.getLightEngine().hasLightWork())) {
            mc.level.update();
        }
    }

    private byte[] readBytes() {
        int length = Varints.read(this.reader);
        byte[] bytes = new byte[length];
        this.reader.get(bytes);
        return bytes;
    }

    /** Logs the packet types of recorded ticks {@code from} to {@code to}, for debugging recordings. */
    public void dump(int from, int to) throws IOException {
        int seg = segmentFor(from);
        ByteBuffer buf = this.file.segment(seg);
        int t = this.meta.segments.get(seg).startTick;
        boolean snapshot = true;
        StringBuilder line = new StringBuilder();
        while (buf.hasRemaining() && t <= to) {
            int action = buf.get();
            switch (action) {
                case Actions.TICK -> {
                    if (t >= from) {
                        Reprise.LOGGER.info("tick {}: {}", t, line);
                    }
                    line.setLength(0);
                    t++;
                }
                case Actions.SNAPSHOT_END -> snapshot = false;
                case Actions.SNAPSHOT_TICK -> { }
                case Actions.GAME_PACKET, Actions.CONFIG_PACKET, Actions.POV_SAMPLES, Actions.PACKED_POV -> {
                    int length = Varints.read(buf);
                    byte[] bytes = new byte[length];
                    buf.get(bytes);
                    if (!snapshot && action == Actions.GAME_PACKET) {
                        try {
                            line.append(PacketCodecs.decode(this.gameCodec, bytes).type().id().getPath()).append(' ');
                        } catch (Exception e) {
                            line.append("? ");
                        }
                    }
                }
                case Actions.CHUNK_REF -> {
                    Varints.read(buf);
                    line.append("chunk ");
                }
                default -> throw new IllegalStateException();
            }
        }
    }

    private void readPov(int action) {
        var sample = new PovSamples(ByteBuffer.wrap(readBytes()), action == Actions.PACKED_POV);
        pendingPov.put(sample.entityId, sample);
    }

    private void advancePov() {
        var old = pov;
        pov = pendingPov;
        pendingPov = old;
        pendingPov.clear();
    }

    public PovSamples.Pose povAt(int entityId, float fraction) {
        if (pov.isEmpty()) return null;
        var samples = pov.get(entityId);
        if (samples == null) return null;
        if (!project().freezes.isEmpty()) {
            var export = com.metrovoc.reprise.export.VideoExporter.running();
            double shot = export == null ? visualTick(fraction) : export.shotTick();
            fraction = (float) (project().worldTick(shot) - this.tick + 1);
        } else if (!playing) {
            fraction = 1;
        }
        return samples.at(fraction);
    }

    // Snapshots.

    /** Rebuilds the client's state from segment {@code index}'s snapshot. */
    private void playSnapshot(int index) {
        ByteBuffer buf;
        try {
            buf = this.file.segment(index);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.segment = index;
        this.reader = buf;
        pov.clear();
        pendingPov.clear();
        this.tick = this.meta.segments.get(index).startTick;

        List<byte[]> config = new ArrayList<>();
        Hasher hasher = Hashing.sha256().newHasher();
        while (buf.hasRemaining() && buf.get(buf.position()) == Actions.CONFIG_PACKET) {
            buf.get();
            byte[] bytes = readBytes();
            config.add(bytes);
            hasher.putBytes(bytes);
        }
        HashCode hash = hasher.hash();
        boolean fresh = this.connection == null || !hash.equals(this.configHash) || this.mc.getConnection() == null;
        this.configHash = hash;

        boolean wasFastForwarding = this.fastForwarding;
        this.fastForwarding = true;
        try {
            if (fresh) {
                embeddedPackIds.clear();
                snapshotPacks.clear();
                connect(config);
            } else {
                clearEmbeddedPacks();
                resetListenerState();
                for (var pack : snapshotPacks) handleEmbeddedPack(pack);
            }
            boolean beforeFirstBoundary = true;
            while (buf.hasRemaining()) {
                int action = buf.get();
                if (action == Actions.SNAPSHOT_END) {
                    if (!pendingPov.isEmpty()) advancePov();
                    break;
                }
                switch (action) {
                    case Actions.SNAPSHOT_TICK -> {
                        advancePov();
                        processFoldedTick();
                        beforeFirstBoundary = false;
                    }
                    case Actions.GAME_PACKET -> deliverGame(ByteBuffer.wrap(readBytes()), -1, beforeFirstBoundary);
                    case Actions.POV_SAMPLES, Actions.PACKED_POV -> readPov(action);
                    case Actions.CHUNK_REF -> deliverChunk(Varints.read(buf));
                    case Actions.CONFIG_PACKET -> deliverConfiguration(readBytes());
                    default -> throw new IllegalStateException("Unexpected action " + action + " in snapshot");
                }
            }
        } finally {
            this.fastForwarding = wasFastForwarding;
        }
        ClientPacketListener listener = this.mc.getConnection();
        if (listener != null) {
            // Lets the loading screen close once the chunks around the camera are in.
            deliver(new ClientboundGameEventPacket(ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START, 0), listener);
        }
    }

    private void connect(List<byte[]> config) {
        skyLightCache.clear();
        if (this.mc.level != null) {
            this.reconnecting = true;
            try {
                disconnect(new GenericMessageScreen(Component.translatable("reprise.replay.loading")));
            } finally {
                this.reconnecting = false;
            }
        }
        this.connection = new Connection(PacketFlow.CLIENTBOUND);
        this.channel = new EmbeddedChannel(new DiscardOutbound(), this.connection);
        CommonListenerCookie cookie = new CommonListenerCookie(
            new LevelLoadTracker(), this.cameraProfile, this.mc.getTelemetryManager().createWorldSessionManager(false, null, null),
            ClientRegistryLayer.createRegistryAccess().compositeAccess(), FeatureFlags.DEFAULT_FLAGS, null, null, this.exitScreen,
            Map.of(), null, Map.of(), ServerLinks.EMPTY, Map.of(), false, this.connectionType);
        this.configListener = new ClientConfigurationPacketListenerImpl(this.mc, this.connection, cookie);
        this.connection.setupInboundProtocol(ConfigurationProtocols.CLIENTBOUND, this.configListener);
        this.connection.setupOutboundProtocol(ConfigurationProtocols.SERVERBOUND);
        this.mc.pendingConnection = this.connection;

        for (byte[] bytes : config) {
            var packet = PacketCodecs.decode(PacketCodecs.CONFIG, bytes);
            if (!(packet instanceof ClientboundFinishConfigurationPacket)) deliverConfigurationPacket(packet, true);
        }
        finishConfiguration();
    }

    private void deliverConfiguration(byte[] bytes) {
        if (!switchingWorld) return;
        var packet = PacketCodecs.decode(PacketCodecs.CONFIG, bytes);
        if (packet instanceof ClientboundFinishConfigurationPacket) finishConfiguration();
        else deliverConfigurationPacket(packet, false);
    }

    private void deliverConfigurationPacket(Packet<? super ClientConfigurationPacketListener> packet, boolean snapshot) {
        if (snapshot && packet instanceof ClientboundResourcePackPopPacket pop) {
            snapshotPacks.removeIf(push -> pop.id().isEmpty() || pop.id().get().equals(push.id()));
        }
        if (packet instanceof ClientboundResourcePackPushPacket push && handleEmbeddedPack(push)) {
            if (snapshot) {
                snapshotPacks.removeIf(old -> old.id().equals(push.id()));
                snapshotPacks.add(push);
            }
            return;
        }
        if (com.metrovoc.reprise.record.Recorder.liveOnly(packet)) return;
        if (packet instanceof ClientboundCustomPayloadPacket custom
            && !(custom.payload() instanceof ModdedNetworkQueryPayload)
            && !(custom.payload() instanceof ModdedNetworkPayload)) return;
        deliver(packet, this.configListener);
    }

    private void finishConfiguration() {
        deliver(ClientboundFinishConfigurationPacket.INSTANCE, this.configListener);
        if (!(this.connection.getPacketListener() instanceof ClientPacketListener play)) {
            throw new IllegalStateException("Replay did not reach the play phase");
        }
        this.gameCodec = PacketCodecs.game(play.registryAccess(), this.meta.snapshotConnectionType ? play.getConnectionType() : this.connectionType);
        this.cameraPlaced = false;
    }

    /** Clears what a new login does not reset, before a snapshot replaces the world. */
    private void resetListenerState() {
        ClientPacketListener listener = this.mc.getConnection();
        listener.playerInfoMap.clear();
        listener.listedPlayers.clear();
        Scoreboard scoreboard = listener.scoreboard();
        for (Objective objective : List.copyOf(scoreboard.getObjectives())) {
            scoreboard.removeObjective(objective);
        }
        for (PlayerTeam team : List.copyOf(scoreboard.getPlayerTeams())) {
            scoreboard.removePlayerTeam(team);
        }
        this.mc.gui.getBossOverlay().reset();
        this.mc.gui.getTabList().reset();
        this.mc.gui.clearTitles();
        this.mc.gui.getChat().clearMessages(false);
        this.mc.particleEngine.clearParticles();
        // The login that follows must make a new camera: a reused one keeps its old level, and a later respawn
        // into that dimension would then not switch worlds.
        this.mc.setCameraEntity(null);
        this.mc.player = null;
        this.cameraPlaced = false;
    }

    // Packet delivery.

    private void deliverChunk(int index) {
        try {
            deliverGame(this.file.chunkView(index), index, false);
        } catch (IOException e) {
            Reprise.LOGGER.warn("Missing cached chunk {}", index, e);
        }
    }

    public void initializeSkyLight(LevelChunk chunk, FriendlyByteBuf input, Runnable calculate) {
        skyLightCache.initialize(chunk, input, calculate);
    }

    private void deliverGame(ByteBuffer bytes) { deliverGame(bytes, -1, false); }

    private void deliverGame(ByteBuffer bytes, int terrainIndex, boolean untimedSnapshot) {
        ClientPacketListener listener = this.mc.getConnection();
        if (listener == null) {
            if (this.connection != null && this.connection.getPacketListener() instanceof ClientPacketListener pending) {
                listener = pending;
            } else {
                return;
            }
        }
        Packet<? super ClientGamePacketListener> packet;
        try {
            packet = PacketCodecs.decode(this.gameCodec, bytes);
        } catch (Exception e) {
            if (this.failedPackets++ < 10) {
                Reprise.LOGGER.warn("Could not read a recorded packet", e);
            }
            return;
        }
        if (packet instanceof ClientboundStartConfigurationPacket start) {
            // Vanilla carries the active registries, feature flags and cookies into the next
            // configuration listener. A backward seek must rebuild the opening registry state.
            if (reader.getInt(Integer.BYTES) < Actions.RECONFIGURATION_VERSION) return;
            switchingWorld = true;
            configHash = null;
            skyLightCache.clear();
            listener.handleConfigurationStart(start);
            configListener = (ClientConfigurationPacketListenerImpl) connection.getPacketListener();
            return;
        }
        if (handleEmbeddedPack(packet)) return;
        packet = this.filter.apply(packet);
        if (packet == null) {
            return;
        }
        if (untimedSnapshot && mc.level != null
            && (packet instanceof ClientboundBlockUpdatePacket || packet instanceof ClientboundSectionBlocksUpdatePacket)) {
            // Older clips only have packet order. Apply queued terrain light before the first
            // block edit that depends on it, even when the original tick boundaries are absent.
            while (!mc.level.lightUpdateQueue.isEmpty()) mc.level.pollLightUpdates();
            if (mc.level.getLightEngine().hasLightWork()) mc.level.getLightEngine().runLightUpdates();
        }
        Vec3 cameraPos = null;
        float yRot = 0, xRot = 0;
        if (packet instanceof ClientboundRespawnPacket && this.mc.player != null) {
            cameraPos = this.mc.player.position();
            yRot = this.mc.player.getYRot();
            xRot = this.mc.player.getXRot();
        }
        if (terrainIndex >= 0 && packet instanceof ClientboundLevelChunkWithLightPacket terrain) {
            skyLightCache.begin(terrainIndex, terrain.getChunkData().buffer);
            try {
                deliver(packet, listener);
            } finally {
                skyLightCache.end();
            }
        } else {
            deliver(packet, listener);
        }
        if (this.fastForwarding && this.mc.level != null) {
            // No world ticks occur between these packets. Finish this move before the next packet
            // chooses between interpolating and teleporting based on the current entity position.
            Entity moved = switch (packet) {
                case ClientboundEntityPositionSyncPacket sync -> this.mc.level.getEntity(sync.id());
                case ClientboundMoveEntityPacket move -> move.getEntity(this.mc.level);
                case ClientboundTeleportEntityPacket teleport -> this.mc.level.getEntity(teleport.id());
                default -> null;
            };
            if (moved != null) settleEntity(moved);
        }
        if (packet instanceof ClientboundLoginPacket) {
            switchingWorld = false;
            addCameraInfo(listener);
        } else if (cameraPos != null && this.mc.player != null) {
            this.mc.player.snapTo(cameraPos.x, cameraPos.y, cameraPos.z, yRot, xRot);
        }
    }

    private void clearEmbeddedPacks() {
        for (UUID id : embeddedPackIds) mc.getDownloadedPackSource().popPack(id);
        embeddedPackIds.clear();
    }

    private boolean handleEmbeddedPack(Packet<?> packet) {
        if (packet instanceof ClientboundResourcePackPopPacket pop) {
            if (pop.id().isPresent()) embeddedPackIds.remove(pop.id().get());
            else embeddedPackIds.clear();
            return false; // Vanilla applies the pop to the whole server pack stack.
        }
        if (!(packet instanceof ClientboundResourcePackPushPacket push) || !push.url().startsWith(EmbeddedPacks.URL_PREFIX)) return false;
        try {
            Path pack = file.localPack(push.url().substring(EmbeddedPacks.URL_PREFIX.length()));
            var source = mc.getDownloadedPackSource();
            source.allowServerPacks();
            source.pushLocalPack(push.id(), pack);
            usedEmbeddedPacks = true;
            embeddedPackIds.add(push.id());
        } catch (IOException e) {
            Reprise.LOGGER.warn("Could not load embedded resource pack {}", push.id(), e);
        }
        return true;
    }

    private void addCameraInfo(ClientPacketListener listener) {
        var info = new ClientboundPlayerInfoUpdatePacket(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE), List.of());
        info.entries = List.of(new ClientboundPlayerInfoUpdatePacket.Entry(this.cameraProfile.id(), this.cameraProfile, false, 0,
            GameType.SPECTATOR, null, false, 0, null));
        deliver(info, listener);
    }

    @SuppressWarnings("unchecked")
    private <T extends PacketListener> void deliver(Packet<T> packet, PacketListener listener) {
        try {
            packet.handle((T) listener);
        } catch (RunningOnDifferentThreadException ignored) {
        } catch (Exception e) {
            if (this.failedPackets++ < 10) {
                Reprise.LOGGER.warn("Recorded packet {} failed", packet.type(), e);
            }
        }
    }

    public void refreshEnvironment() {
        if (this.mc.level != null) {
            this.mc.level.environmentAttributes().invalidateTickCache();
            this.mc.level.updateSkyBrightness();
            var camera = this.mc.gameRenderer.getMainCamera();
            camera.attributeProbe().reset();
            camera.attributeProbe().tick(this.mc.level, camera.position());
        }
    }

    private void settleEntities() {
        if (this.mc.level == null) {
            return;
        }
        for (Entity entity : this.mc.level.entitiesForRendering()) {
            settleEntity(entity);
        }
    }

    private static void settleEntity(Entity entity) {
        InterpolationHandler interpolation = entity.getInterpolation();
        if (interpolation != null && interpolation.hasActiveInterpolation()) {
            entity.setPos(interpolation.position());
            entity.setYRot(interpolation.yRot());
            entity.setXRot(interpolation.xRot());
            interpolation.cancel();
        }
        entity.setOldPosAndRot();
    }

    private void placeCameraAtRecorder() {
        Entity recorder = this.mc.level.getEntity(this.recorderId);
        if (recorder != null) {
            // Start a little behind and above the recording player, looking at them.
            Vec3 eye = recorder.getEyePosition();
            Vec3 back = Vec3.directionFromRotation(0, recorder.getYRot()).scale(-3.5).add(0, 1.2, 0);
            Vec3 cam = eye.add(back);
            Vec3 d = eye.subtract(cam);
            float yaw = (float) (Math.toDegrees(Math.atan2(-d.x, d.z)));
            float pitch = (float) (-Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
            this.mc.player.snapTo(cam.x, cam.y - this.mc.player.getEyeHeight(), cam.z, yaw, pitch);
            this.mc.player.setOldPosAndRot();
            this.cameraPlaced = true;
        }
    }

    /** Swallows everything the client sends to the "server". */
    private static final class DiscardOutbound extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            ReferenceCountUtil.release(msg);
            promise.trySuccess();
        }
    }
}

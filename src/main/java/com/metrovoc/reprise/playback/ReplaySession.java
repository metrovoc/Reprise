package com.metrovoc.reprise.playback;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.metrovoc.reprise.Reprise;
import com.metrovoc.reprise.editor.EnvironmentSettings.Layer;
import com.metrovoc.reprise.format.Actions;
import com.metrovoc.reprise.format.ReplayFile;
import com.metrovoc.reprise.format.ReplayMeta;
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
import java.util.List;
import java.util.Map;
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
import net.minecraft.network.PacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.RunningOnDifferentThreadException;
import net.minecraft.server.ServerLinks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.payload.ModdedNetworkPayload;
import net.neoforged.neoforge.network.payload.ModdedNetworkQueryPayload;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;

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

    private int segment = -1;
    private ByteBuffer reader;
    /** Recorded ticks applied so far: the replay's current time. */
    private int tick;
    private boolean playing;
    private float speed = 1;
    private boolean fastForwarding;
    private boolean seeking;
    private boolean reconnecting;
    private int recorderId = Integer.MIN_VALUE;
    private boolean cameraPlaced;
    private int failedPackets;
    private UUID spectating;
    private final com.metrovoc.reprise.editor.ReplayProject project;

    private ReplaySession(ReplayFile file, Screen exitScreen) {
        this.file = file;
        this.meta = file.meta();
        this.exitScreen = exitScreen;
        this.connectionType = this.meta.neoforgeConnection ? ConnectionType.NEOFORGE : ConnectionType.OTHER;
        this.cameraProfile = new GameProfile(UUID.randomUUID(), this.mc.getUser().getName());
        this.project = com.metrovoc.reprise.editor.ReplayProject.load(this.meta);
    }

    public com.metrovoc.reprise.editor.ReplayProject project() {
        return this.project;
    }

    /** The replay time shown on screen, in ticks, with the fraction the renderer is between ticks. */
    public double visualTick(float partialTick) {
        return this.playing ? Math.max(0, this.tick - 1 + partialTick) : this.tick;
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
        this.mc.disconnect(this.exitScreen, false);
    }

    /** Called when the client disconnects from the replay. */
    public void close() {
        if (this.reconnecting) {
            return;
        }
        active = null;
        CameraPath.setPreview(false);
        this.project.save();
        try {
            this.file.close();
        } catch (IOException ignored) {
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
        return this.tick;
    }

    public int duration() {
        return this.meta.durationTicks;
    }

    public static boolean hides(Layer layer) {
        return active != null && active.project.environment.hidden.contains(layer);
    }

    public boolean isPlaying() {
        return this.playing;
    }

    public void setPlaying(boolean playing) {
        this.playing = playing && this.tick < duration();
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

    public boolean isSeeking() {
        return this.seeking;
    }

    /** Spectates {@code entity} until {@link #stopSpectating()}, following it across respawns and seeks. */
    public void spectate(Entity entity) {
        this.spectating = entity.getUUID();
        this.mc.setCameraEntity(entity);
    }

    public void stopSpectating() {
        this.spectating = null;
        this.mc.setCameraEntity(this.mc.player);
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
        this.mc.level.tickRateManager().setFrozen(!this.playing);
        if (this.playing) {
            if (this.tick >= duration()) {
                this.playing = false;
            } else {
                advance(1);
            }
        }
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
        double speed = CameraPath.preview() && !this.project.speedKeys.isEmpty() ? this.project.speedAt(this.tick) : this.speed;
        return (float) (50.0 / Math.max(0.01, speed));
    }

    /** Moves to {@code target} (in recorded ticks), keeping the camera where it is. */
    public void seek(int target) {
        target = Math.max(0, Math.min(duration(), target));
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
        for (int i = 0; i < ticks && this.tick < duration(); i++) {
            readUntilTick();
        }
    }

    private void readUntilTick() {
        while (true) {
            if (this.reader == null || !this.reader.hasRemaining()) {
                if (this.segment + 1 >= this.meta.segments.size()) {
                    this.tick = duration();
                    this.playing = false;
                    return;
                }
                enterNextSegment();
                continue;
            }
            int action = this.reader.get();
            switch (action) {
                case Actions.TICK -> {
                    this.tick++;
                    applyResetsAtCurrentTick();
                    return;
                }
                case Actions.GAME_PACKET -> deliverGame(ByteBuffer.wrap(readBytes()));
                case Actions.CHUNK_REF -> deliverChunk(Varints.read(this.reader));
                case Actions.CONFIG_PACKET -> readBytes(); // only valid inside a snapshot
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
                case Actions.GAME_PACKET, Actions.CONFIG_PACKET -> {
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
                connect(config);
            } else {
                resetListenerState();
            }
            while (buf.hasRemaining()) {
                int action = buf.get();
                if (action == Actions.SNAPSHOT_END) {
                    break;
                }
                switch (action) {
                    case Actions.GAME_PACKET -> deliverGame(ByteBuffer.wrap(readBytes()));
                    case Actions.CHUNK_REF -> deliverChunk(Varints.read(buf));
                    case Actions.CONFIG_PACKET -> readBytes();
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
        if (this.mc.level != null) {
            this.reconnecting = true;
            try {
                this.mc.disconnect(new GenericMessageScreen(Component.translatable("reprise.replay.loading")), false);
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
            Packet<? super ClientConfigurationPacketListener> packet = PacketCodecs.decode(PacketCodecs.CONFIG, bytes);
            if (packet instanceof ClientboundCustomPayloadPacket custom
                && !(custom.payload() instanceof ModdedNetworkQueryPayload)
                && !(custom.payload() instanceof ModdedNetworkPayload)) {
                continue;
            }
            deliver(packet, this.configListener);
        }
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
    }

    // Packet delivery.

    private void deliverChunk(int index) {
        try {
            deliverGame(this.file.chunkView(index));
        } catch (IOException e) {
            Reprise.LOGGER.warn("Missing cached chunk {}", index, e);
        }
    }

    private void deliverGame(ByteBuffer bytes) {
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
        packet = this.filter.apply(packet);
        if (packet == null) {
            return;
        }
        Vec3 cameraPos = null;
        float yRot = 0, xRot = 0;
        if (packet instanceof ClientboundRespawnPacket && this.mc.player != null) {
            cameraPos = this.mc.player.position();
            yRot = this.mc.player.getYRot();
            xRot = this.mc.player.getXRot();
        }
        deliver(packet, listener);
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
            addCameraInfo(listener);
        } else if (cameraPos != null && this.mc.player != null) {
            this.mc.player.snapTo(cameraPos.x, cameraPos.y, cameraPos.z, yRot, xRot);
        }
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

package driver;

import com.metrovoc.reprise.ReprisePaths;
import com.metrovoc.reprise.editor.EditorScreen;
import com.metrovoc.reprise.playback.CameraPath;
import com.metrovoc.reprise.playback.PlaybackControls;
import com.metrovoc.reprise.playback.ReplaySession;
import com.metrovoc.reprise.record.Recorder;
import com.mojang.logging.LogUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;

/** Driver steps for Reprise: {@code record start|stop|pause} and {@code replay ...}. */
@EventBusSubscriber(value = Dist.CLIENT)
public final class RepriseSteps {
    private static final Logger LOG = LogUtils.getLogger();

    private static String precipitation;
    private static float fogStart, fogEnd;
    private static int fogColor;

    private static void recordFog(net.neoforged.neoforge.client.event.ViewportEvent.RenderFog event) {
        var fog = event.getFogData();
        fogStart = fog.environmentalStart;
        fogEnd = fog.environmentalEnd;
        fogColor = Math.round(fog.color.x * 255) << 16 | Math.round(fog.color.y * 255) << 8 | Math.round(fog.color.z * 255);
    }

    private static void afterWeather(net.neoforged.neoforge.client.event.RenderLevelStageEvent.AfterWeather event) {
        var state = event.getLevelRenderState().weatherRenderState;
        precipitation = !state.snowColumns.isEmpty() ? "snow" : !state.rainColumns.isEmpty() ? "rain" : "none";
    }

    @SubscribeEvent
    static void registerPayloads(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        MarkerPayload.register(event);
    }

    @SubscribeEvent
    static void setup(FMLClientSetupEvent event) {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(MarkerPayload::snapshot);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(RepriseSteps::afterWeather);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,
            net.neoforged.neoforge.client.event.ViewportEvent.RenderFog.class, RepriseSteps::recordFog);
        Driver.STEPS.put("pixels", FrameChecks::verify);
        Driver.STEPS.put("vitals", (mc, arg) -> {
            var id = mc.player.getUUID();
            String[] values = arg.split(" ");
            mc.getSingleplayerServer().execute(() -> {
                var player = mc.getSingleplayerServer().getPlayerList().getPlayer(id);
                player.setHealth(Float.parseFloat(values[0]));
                player.getFoodData().setFoodLevel(Integer.parseInt(values[1]));
                player.getFoodData().setSaturation(0);
            });
        });
        Driver.STEPS.put("record", (mc, arg) -> {
            switch (arg) {
                case "start" -> Recorder.start();
                case "stop" -> LOG.info("[driver] saved {}", Recorder.stop());
                case "pause" -> Recorder.active().setPaused(!Recorder.active().isPaused());
                case "state" -> LOG.info("[driver] recording ticks={} segments={}", Recorder.active().meta().durationTicks, Recorder.active().meta().segments.size());
                default -> throw new IllegalArgumentException(arg);
            }
        });
        Driver.STEPS.put("field", (mc, arg) -> {
            String[] parts = arg.split(" ", 2);
            for (var widget : mc.screen.children()) {
                if (widget instanceof net.minecraft.client.gui.components.EditBox field
                    && field.getMessage().getString().equals(parts[0].replace('_', ' '))) {
                    field.setValue(parts[1]);
                    return;
                }
            }
            throw new IllegalStateException("No field named " + parts[0]);
        });
        Driver.STEPS.put("row", (mc, name) -> {
            for (var widget : mc.screen.children()) {
                if (widget instanceof net.minecraft.client.gui.components.ObjectSelectionList<?> list) {
                    for (var entry : list.children()) {
                        if (entry.getNarration().getString().startsWith(name)) {
                            var click = new net.minecraft.client.input.MouseButtonEvent(entry.getContentXMiddle(), entry.getContentYMiddle(),
                                new net.minecraft.client.input.MouseButtonInfo(0, 0));
                            mc.screen.mouseClicked(click, false);
                            mc.screen.mouseReleased(click);
                            return;
                        }
                    }
                }
            }
            throw new IllegalStateException("No visible row named " + name);
        });
        Driver.STEPS.put("payload", MarkerPayload::step);
        Driver.STEPS.put("connect", (mc, arg) -> net.minecraft.client.gui.screens.ConnectScreen.startConnecting(new TitleScreen(), mc,
            net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(arg),
            new net.minecraft.client.multiplayer.ServerData("Test", arg, net.minecraft.client.multiplayer.ServerData.Type.OTHER), false, null));
        Driver.STEPS.put("disconnect", (mc, arg) -> mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("bye")));
        Driver.STEPS.put("replay", (mc, arg) -> {
            String[] a = arg.split(" ");
            ReplaySession s = ReplaySession.active();
            switch (a[0]) {
                case "latest" -> {
                    Path newest;
                    try (Stream<Path> files = Files.list(ReprisePaths.replays())) {
                        newest = files.filter(p -> p.toString().endsWith(".reprise"))
                            .max(Comparator.comparingLong(p -> p.toFile().lastModified())).orElseThrow();
                    }
                    LOG.info("[driver] opening {}", newest);
                    ReplaySession.open(newest, new TitleScreen());
                }
                case "play" -> s.setPlaying(true);
                case "pause" -> s.setPlaying(false);
                case "speed" -> s.setSpeed(Float.parseFloat(a[1]));
                case "seek" -> {
                    int target = Math.clamp(Integer.parseInt(a[1]), 0, s.duration());
                    var memory = java.lang.management.ManagementFactory.getPlatformMXBean(com.sun.management.ThreadMXBean.class);
                    long before = memory.getCurrentThreadAllocatedBytes();
                    long start = System.nanoTime();
                    s.seek(target);
                    if (s.tick() != target) {
                        throw new IllegalStateException("Seek stopped at tick " + s.tick() + ", expected " + target);
                    }
                    LOG.info("[driver] sought to tick {} in {} ms, allocated {} bytes on client thread", target,
                        (System.nanoTime() - start) / 1_000_000, memory.getCurrentThreadAllocatedBytes() - before);
                }
                case "recorder_pos" -> {
                    var recorder = mc.level.getEntity(s.recorderId());
                    var expected = new net.minecraft.world.phys.Vec3(Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]));
                    if (recorder == null || recorder.position().distanceToSqr(expected) > 1.0e-6) {
                        throw new IllegalStateException("Expected recorder at " + expected + ", found " + recorder);
                    }
                }
                case "block" -> {
                    var pos = new net.minecraft.core.BlockPos(Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]));
                    var block = mc.level.getBlockState(pos).getBlock();
                    var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
                    if (!id.toString().equals(a[4])) {
                        throw new IllegalStateException("Expected " + a[4] + " at " + pos + ", found " + id);
                    }
                }
                case "editor" -> mc.setScreen(new EditorScreen(s));
                case "close" -> mc.setScreen(null);
                case "key_count" -> {
                    int count = s.project().keyframes.size();
                    if (count != Integer.parseInt(a[1])) throw new IllegalStateException("Camera key count: " + count);
                }
                case "keyframe" -> PlaybackControls.addKeyframe(s);
                case "preview" -> CameraPath.setPreview(a[1].equals("on"));
                case "camera" -> {
                    mc.player.snapTo(Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]),
                        Float.parseFloat(a[4]), Float.parseFloat(a[5]));
                    mc.player.setOldPosAndRot();
                }
                case "hotbar" -> {
                    var player = (net.minecraft.world.entity.player.Player) mc.level.getEntity(s.recorderId());
                    int slot = Integer.parseInt(a[2]);
                    var item = player.getInventory().getItem(slot);
                    String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
                    if (!ReplaySession.showingRecordedHotbar() || player.getInventory().getSelectedSlot() != Integer.parseInt(a[1])
                        || !id.equals(a[3]) || item.getCount() != Integer.parseInt(a[4])) {
                        throw new IllegalStateException("Hotbar " + player.getInventory().getSelectedSlot() + ", slot " + slot + ": " + item
                            + ", visible=" + ReplaySession.showingRecordedHotbar());
                    }
                }
                case "no_hotbar" -> {
                    if (ReplaySession.showingRecordedHotbar()) throw new IllegalStateException("Unexpected recorded hotbar");
                }
                case "vitals" -> {
                    var player = (net.minecraft.world.entity.player.Player) mc.level.getEntity(s.recorderId());
                    double scale = player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.SCALE);
                    if (player.getHealth() != Float.parseFloat(a[1]) || player.getFoodData().getFoodLevel() != Integer.parseInt(a[2])
                        || player.experienceLevel != Integer.parseInt(a[3]) || scale != Double.parseDouble(a[4])) {
                        throw new IllegalStateException("Recorded health/food/level/scale: " + player.getHealth() + "/"
                            + player.getFoodData().getFoodLevel() + "/" + player.experienceLevel + "/" + scale);
                    }
                }
                case "equipment" -> {
                    var player = (net.minecraft.world.entity.player.Player) mc.level.getEntity(s.recorderId());
                    var item = player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.valueOf(a[1]));
                    if (!net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem()).toString().equals(a[2])) {
                        throw new IllegalStateException("Unexpected equipment in " + a[1] + ": " + item);
                    }
                }
                case "speed_effect" -> {
                    var player = (net.minecraft.world.entity.player.Player) mc.level.getEntity(s.recorderId());
                    if (player.hasEffect(net.minecraft.world.effect.MobEffects.SPEED) != Boolean.parseBoolean(a[1])) {
                        throw new IllegalStateException("Unexpected Speed effect state");
                    }
                }
                case "spectate" -> {
                    s.spectate(mc.level.getEntity(s.recorderId()));
                }
                case "range" -> {
                    s.project().exportStart = Integer.parseInt(a[1]);
                    s.project().exportEnd = Integer.parseInt(a[2]);
                }
                case "export", "export_settings" -> {
                    var settings = s.project().export;
                    settings.width = Integer.parseInt(a[1]);
                    settings.height = Integer.parseInt(a[2]);
                    settings.fps = Integer.parseInt(a[3]);
                    settings.format = com.metrovoc.reprise.editor.ExportSettings.Format.valueOf(a.length > 4 ? a[4] : "MP4_H264");
                    settings.hardwareEncoder = java.util.Arrays.asList(a).contains("gpu");
                    settings.audio = java.util.Arrays.asList(a).contains("audio");
                    settings.supersample = java.util.Arrays.asList(a).contains("ssaa");
                    settings.transparentSky = java.util.Arrays.asList(a).contains("alpha");
                    if (a[0].equals("export")) com.metrovoc.reprise.export.VideoExporter.start(s, settings, null);
                }
                case "clear" -> {
                    s.project().keyframes.clear();
                    s.project().save();
                }
                case "dump" -> s.dump(Integer.parseInt(a[1]), Integer.parseInt(a[2]));
                case "exit" -> s.exit();
                case "fog" -> {
                    if (fogStart != Float.parseFloat(a[1]) || fogEnd != Float.parseFloat(a[2]) || fogColor != Integer.parseInt(a[3], 16)) {
                        throw new IllegalStateException("Unexpected rendered fog: " + fogStart + ".." + fogEnd + ", " + Integer.toHexString(fogColor));
                    }
                }
                case "vision" -> {
                    float actual = mc.gameRenderer.getGameRenderState().lightmapRenderState.nightVisionEffectIntensity;
                    if (actual != Float.parseFloat(a[1])) {
                        throw new IllegalStateException("Unexpected night vision intensity " + actual);
                    }
                }
                case "environment" -> {
                    long time = Math.floorMod(mc.level.getOverworldClockTime(), 24000L);
                    float rain = mc.level.getRainLevel(1), thunder = mc.level.getThunderLevel(1);
                    if (time != Long.parseLong(a[1]) || rain != Float.parseFloat(a[2]) || thunder != Float.parseFloat(a[3])
                        || !precipitation.equals(a[4])) {
                        throw new IllegalStateException("Expected " + arg + " but got " + time + " " + rain + " " + thunder + " " + precipitation);
                    }
                }
                case "camera_at" -> {
                    var expected = new net.minecraft.world.phys.Vec3(Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]));
                    if (mc.player.position().distanceTo(expected) > 0.025) {
                        throw new IllegalStateException("Expected camera at " + expected + ", found " + mc.player.position());
                    }
                }
                case "aimed" -> {
                    var actor = java.util.stream.StreamSupport.stream(mc.level.entitiesForRendering().spliterator(), false)
                        .filter(e -> e != mc.player && e.getName().getString().equals(a[1].replace('_', ' '))).findFirst().orElseThrow();
                    double height = switch (a[2]) {
                        case "eyes" -> actor.getEyeHeight();
                        case "center" -> actor.getBbHeight() * 0.5;
                        case "feet" -> 0;
                        default -> throw new IllegalArgumentException(arg);
                    };
                    var direction = actor.position().add(0, height, 0).subtract(mc.player.getEyePosition()).normalize();
                    if (direction.dot(mc.player.getLookAngle()) < 0.9999) {
                        throw new IllegalStateException("Camera missed " + actor.getName().getString() + " at tick " + s.tick());
                    }
                }
                case "fallback" -> {
                    var key = s.project().keyframes.getFirst();
                    if (mc.level.getEntity(key.target.id()) != null || mc.player.position().distanceToSqr(key.x, key.y, key.z) > 1e-6
                        || Math.abs(mc.player.getYRot() - key.yaw) > 0.01 || Math.abs(mc.player.getXRot() - key.pitch) > 0.01) {
                        throw new IllegalStateException("Missing-subject fallback differs from authored pose");
                    }
                }
                case "appearance" -> {
                    Entity target = java.util.stream.StreamSupport.stream(mc.level.entitiesForRendering().spliterator(), false)
                        .filter(e -> e != mc.player && e.getName().getString().equals(a[1])).findFirst().orElseThrow();
                    var override = s.project().entityOverrides.get(target.getUUID());
                    boolean hidden = override != null && override.hidden();
                    Integer color = override == null ? null : override.outlineColor();
                    if (hidden != a[2].equals("hidden") || !java.util.Objects.equals(color, a.length == 4 ? Integer.valueOf(a[3], 16) : null)) {
                        throw new IllegalStateException("Unexpected appearance for " + a[1] + ": " + override);
                    }
                }
                case "state" -> {
                    int entities = 0;
                    for (Entity ignored : mc.level.entitiesForRendering()) {
                        entities++;
                    }
                    LOG.info("[driver] replay tick {}/{} playing={} speed={} entities={} chunks={} camera={} recorder={} screen={}",
                        s.tick(), s.duration(), s.isPlaying(), s.speed(), entities, mc.level.getChunkSource().getLoadedChunksCount(),
                        mc.player.position(), mc.level.getEntity(s.recorderId()), mc.screen);
                }
                default -> throw new IllegalArgumentException(arg);
            }
        });
    }

    private RepriseSteps() {}
}

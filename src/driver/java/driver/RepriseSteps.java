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

    @SubscribeEvent
    static void setup(FMLClientSetupEvent event) {
        Driver.STEPS.put("record", (mc, arg) -> {
            switch (arg) {
                case "start" -> Recorder.start();
                case "stop" -> LOG.info("[driver] saved {}", Recorder.stop());
                case "pause" -> Recorder.active().setPaused(!Recorder.active().isPaused());
                case "state" -> LOG.info("[driver] recording ticks={} segments={}", Recorder.active().meta().durationTicks, Recorder.active().meta().segments.size());
                default -> throw new IllegalArgumentException(arg);
            }
        });
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
                case "seek" -> s.seek(Integer.parseInt(a[1]));
                case "editor" -> mc.setScreen(new EditorScreen(s));
                case "close" -> mc.setScreen(null);
                case "keyframe" -> PlaybackControls.addKeyframe(s);
                case "preview" -> CameraPath.setPreview(a[1].equals("on"));
                case "camera" -> {
                    mc.player.snapTo(Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]),
                        Float.parseFloat(a[4]), Float.parseFloat(a[5]));
                    mc.player.setOldPosAndRot();
                }
                case "spectate" -> {
                    Entity target = mc.level.getEntity(s.recorderId());
                    mc.setCameraEntity(target);
                }
                case "export" -> {
                    var settings = s.project().export;
                    settings.width = Integer.parseInt(a[1]);
                    settings.height = Integer.parseInt(a[2]);
                    settings.fps = Integer.parseInt(a[3]);
                    settings.format = com.metrovoc.reprise.editor.ExportSettings.Format.valueOf(a.length > 4 ? a[4] : "MP4_H264");
                    com.metrovoc.reprise.export.VideoExporter.start(s, settings, null);
                }
                case "clear" -> {
                    s.project().keyframes.clear();
                    s.project().save();
                }
                case "exit" -> s.exit();
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

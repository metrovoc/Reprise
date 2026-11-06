package driver;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

/**
 * Plays a script in the client, so that a mod can be played, looked at and recorded with no one at the keyboard.
 * Written against Minecraft 26.1.2 with vanilla code only; a loader hook calls {@link #tick()} at the end of every
 * client tick. The script comes from the {@code driver.script} system property, and {@code driver.monitor} names a
 * monitor (such as a virtual display) to put the window on.
 *
 * <p>One step runs per client tick. Lines starting with # are comments. GUI coordinates are GUI pixels, counted from
 * a container screen's corner when one is open.
 * <pre>
 * world [SEED]               create a fresh creative world and wait until it has loaded: flat, or with SEED a
 *                            normal world with structures
 * cmd COMMAND                run a command as the player, without the slash
 * chat TEXT                  say something in chat
 * wait TICKS
 * use X Y Z [FACE]           right-click a block with the main hand; stand within reach first (cmd tp)
 * entity TYPE                right-click the nearest entity of TYPE (minecraft:villager), stepping up to it first
 * useitem                    right-click the air with the main hand
 * creative TAB               open the creative inventory on a tab, by id (minecraft:building_blocks)
 * press LABEL                click the open screen's first widget whose label starts with LABEL
 * click X Y [right]          click the open screen
 * shift N|ITEM               shift-click hotbar slot N (0-8), or the first one holding ITEM, in the open container
 * take N                     shift-click slot number N of the open container (see inspect)
 * hover X Y [TICKS]          move the mouse; with TICKS, glide there in that many ticks
 * point TICKS LABEL          glide the mouse onto the open screen's first widget whose label starts with LABEL
 * drag X1 Y1 X2 Y2 [TICKS] [middle]   drag with the left or middle button over TICKS ticks (4)
 * scroll AMOUNT              scroll the open screen where the mouse is
 * key NAME                   press and release a key by GLFW name (escape, enter, e, f1), as the keyboard would
 * type TEXT                  type into the open screen
 * slot N|ITEM                select hotbar slot N (0-8), or the first one holding ITEM
 * frame STRUCTURE DIST HEIGHT ANGLE  stand DIST blocks out from the nearest STRUCTURE, HEIGHT above its top and
 *                            ANGLE degrees round from south, looking at its centre
 * survey X Z RADIUS          log the ground's height around a column (~ water, T a tree), to find a spot for a scene
 * fly on|off                 hover in place, so the camera stays where cmd tp put it
 * sneak on|off               hold the sneak key
 * hud on|off
 * toasts on|off              let toasts (advancements, new recipes) show; off at the start, to keep them out of shots
 * gui SCALE                  set the GUI scale (0 = auto)
 * volume LEVEL               set the master volume, 0 to 1; the driver starts the game at 0
 * inspect                    log the open screen: its widgets with labels and bounds, a container's slots and items
 * missing NAMESPACE          log the items, blocks, entities and creative tabs of NAMESPACE whose names have no
 *                            translation
 * shot NAME                  save screenshots/NAME.png in the game directory
 * clip NAME EVERY            save a half-size frame every EVERY ticks into screenshots/NAME/, with the cursor's
 *                            positions in clip.txt, while the script goes on
 * clip off                   stop saving frames
 * quit                       leave the world and close the game
 * </pre>
 * A mod adds steps of its own to {@link #STEPS}, from a class in the driver's source set.
 */
public final class Driver {
    private static final Logger LOG = LogUtils.getLogger();
    private static final String SCRIPT = System.getProperty("driver.script", "");
    private static final String WORLD = "Driver";
    private static final MouseButtonInfo LEFT = new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
    private static final MouseButtonInfo RIGHT = new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_RIGHT, 0);

    /** Steps by name, for a mod's own needs: build a test structure, fill a machine, give a configured item. */
    public static final Map<String, Step> STEPS = new HashMap<>();

    @FunctionalInterface
    public interface Step {
        void run(Minecraft mc, String arg) throws Exception;
    }

    private static List<String> steps;
    private static int next;
    private static int failed;
    private static int waiting;
    private static boolean moved;
    private static boolean loading;
    private static boolean quitting;
    /** Keeps the player flying: creative flight ends whenever the player touches the ground. */
    private static boolean hover;
    private static boolean toasts;
    private static double mouseX;
    private static double mouseY;
    /** Where the mouse is gliding to, and the ticks it has left to get there. */
    private static double glideX;
    private static double glideY;
    private static int gliding;
    /** The button held during a scripted drag, or null for an ordinary glide. */
    private static MouseButtonInfo dragButton;
    /** The clip being recorded, or null, and its frames so far. */
    private static Path clip;
    private static int every;
    private static int frames;

    private Driver() {
    }

    /** Runs at the end of every client tick. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (SCRIPT.isEmpty()) {
            return;
        }
        if (!moved) {
            moved = true;
            if (!moveWindow(mc, System.getProperty("driver.monitor", ""))) {
                mc.stop();
                return;
            }
        }
        Quiet.tick(mc);
        if (mc.getOverlay() != null) {
            return;
        }
        if (steps == null) {
            start(mc);
        }
        if (!toasts) {
            mc.getToastManager().clear();
        }
        if (quitting) {
            if (mc.level == null) {
                mc.stop();
            }
            return;
        }
        if (loading) {
            if (mc.level == null || mc.player == null || mc.screen != null) {
                return;
            }
            loading = false;
        }
        if (hover && !mc.player.getAbilities().flying) {
            mc.player.getAbilities().flying = true;
            mc.player.onUpdateAbilities();
        }
        if (gliding > 0) {
            double x = mouseX + (glideX - mouseX) / gliding, y = mouseY + (glideY - mouseY) / gliding;
            if (dragButton != null) {
                mc.screen.mouseDragged(new MouseButtonEvent(x, y, dragButton), x - mouseX, y - mouseY);
            }
            moveMouse(mc, x, y);
            if (--gliding == 0 && dragButton != null) {
                if (mc.screen != null) {
                    mc.screen.mouseReleased(new MouseButtonEvent(x, y, dragButton));
                }
                dragButton = null;
            }
        }
        if (clip != null && mc.player != null && mc.player.tickCount % every == 0) {
            frame(mc);
        }
        if (waiting > 0) {
            waiting--;
            return;
        }
        while (next < steps.size()) {
            String step = steps.get(next++).strip();
            if (step.isEmpty() || step.startsWith("#")) {
                continue;
            }
            LOG.info("[driver] {}", step);
            try {
                run(mc, step);
            } catch (Exception e) {
                failed++;
                LOG.error("[driver] step failed: {}", step, e);
            }
            return;
        }
    }

    private static void start(Minecraft mc) {
        try {
            steps = Files.readAllLines(Path.of(SCRIPT));
        } catch (IOException e) {
            LOG.error("[driver] cannot read {}", SCRIPT, e);
            steps = List.of();
        }
        mc.options.pauseOnLostFocus = false;
        mc.options.onboardAccessibility = false;
        mc.options.tutorialStep = TutorialSteps.NONE;
        mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
        mc.options.save();
    }

    /** Puts the window on the named monitor, such as a virtual display, and returns false if there is none by that name. */
    private static boolean moveWindow(Minecraft mc, String name) {
        if (name.isEmpty()) {
            return true;
        }
        PointerBuffer monitors = GLFW.glfwGetMonitors();
        for (int i = 0; monitors != null && i < monitors.limit(); i++) {
            long monitor = monitors.get(i);
            if (name.equals(GLFW.glfwGetMonitorName(monitor))) {
                int[] x = new int[1];
                int[] y = new int[1];
                GLFW.glfwGetMonitorPos(monitor, x, y);
                GLFW.glfwSetWindowPos(mc.getWindow().handle(), x[0] + 32, y[0] + 64);
                return true;
            }
        }
        // A locked macOS session can omit all monitors from GLFW while AppKit retains their geometry.
        if (org.lwjgl.system.Platform.get() == org.lwjgl.system.Platform.MACOSX) {
            try {
                var query = new ProcessBuilder("/usr/bin/osascript", "-l", "JavaScript", "-e", """
                    ObjC.import('AppKit')
                    function run(args) {
                        const screens = ObjC.unwrap($.NSScreen.screens);
                        const target = screens.find(s => ObjC.unwrap(s.localizedName) === args[0]);
                        if (!target) return '';
                        const f = target.frame;
                        return [f.origin.x, screens[0].frame.size.height - f.origin.y - f.size.height,
                                f.size.width, f.size.height].join(' ');
                    }
                    """, name).start();
                try (var coords = new java.util.Scanner(query.getInputStream())) {
                    if (query.waitFor() == 0 && coords.hasNextInt()) {
                        int left = coords.nextInt(), top = coords.nextInt(), width = coords.nextInt(), height = coords.nextInt();
                        long window = mc.getWindow().handle();
                        GLFW.glfwSetWindowSize(window, Math.min(1500, width - 64), Math.min(900, height - 96));
                        GLFW.glfwSetWindowPos(window, left + 32, top + 64);
                        int[] x = new int[1], y = new int[1];
                        GLFW.glfwGetWindowPos(window, x, y);
                        if (x[0] == left + 32 && y[0] == top + 64) {
                            LOG.info("[driver] positioned on {} via AppKit at {},{}", name, x[0], y[0]);
                            return true;
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warn("[driver] AppKit placement failed", e);
            }
        }
        LOG.error("[driver] no monitor named {}", name);
        LOG.info("[driver] done: 1 step(s) failed");
        return false;
    }

    private static void run(Minecraft mc, String step) throws Exception {
        String[] parts = step.split("\\s+", 2);
        String arg = parts.length > 1 ? parts[1] : "";
        String[] args = arg.split("\\s+");
        switch (parts[0]) {
            case "world" -> createWorld(mc, arg);
            case "cmd" -> mc.player.connection.sendCommand(arg);
            case "chat" -> mc.player.connection.sendChat(arg);
            case "wait" -> waiting = Integer.parseInt(arg);
            case "use" -> {
                BlockPos pos = new BlockPos(Integer.parseInt(args[0]), Integer.parseInt(args[1]), Integer.parseInt(args[2]));
                Direction face = args.length > 3 ? Direction.byName(args[3]) : Direction.UP;
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(pos).relative(face, 0.5), face, pos, false));
            }
            case "entity" -> {
                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(arg));
                Entity target = mc.level.getEntities((Entity) null, mc.player.getBoundingBox().inflate(24), e -> e.getType() == type)
                        .stream().min(Comparator.comparingDouble(mc.player::distanceToSqr)).orElseThrow();
                if (mc.player.distanceTo(target) > 3) {
                    // the server only lets a player use what is within reach: step up to it, then try again
                    Vec3 to = target.position().add(mc.player.position().subtract(target.position()).horizontal().normalize().scale(2));
                    mc.player.connection.sendCommand(String.format(Locale.ROOT, "tp @s %.2f %.2f %.2f", to.x, target.getY(), to.z));
                    next--;
                    waiting = 4;
                    return;
                }
                mc.gameMode.interact(mc.player, target, new EntityHitResult(target), InteractionHand.MAIN_HAND);
            }
            case "useitem" -> mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            case "creative" -> {
                Field selected = CreativeModeInventoryScreen.class.getDeclaredField("selectedTab");
                selected.setAccessible(true);
                selected.set(null, BuiltInRegistries.CREATIVE_MODE_TAB.getValue(Identifier.parse(arg)));
                mc.setScreen(new CreativeModeInventoryScreen(mc.player, mc.player.connection.enabledFeatures(),
                        mc.options.operatorItemsTab().get()));
            }
            case "press" -> {
                AbstractWidget button = widget(mc, arg);
                click(mc, button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0, LEFT);
            }
            case "point" -> {
                String[] rest = arg.split("\\s+", 2);
                AbstractWidget button = widget(mc, rest[1]);
                glide(button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0,
                        Integer.parseInt(rest[0]));
            }
            case "click" -> {
                point(mc, args);
                click(mc, mouseX, mouseY, args.length > 2 && args[2].equals("right") ? RIGHT : LEFT);
            }
            case "shift" -> {
                AbstractContainerMenu menu = mc.player.containerMenu;
                Slot slot = menu.slots.stream()
                        .filter(sl -> sl.container == mc.player.getInventory() && sl.getContainerSlot() == hotbar(mc, arg))
                        .findFirst().orElseThrow();
                mc.gameMode.handleContainerInput(menu.containerId, slot.index, 0, ContainerInput.QUICK_MOVE, mc.player);
            }
            case "take" -> mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, Integer.parseInt(arg), 0,
                    ContainerInput.QUICK_MOVE, mc.player);
            case "hover" -> {
                if (args.length > 2) {
                    double[] to = spot(mc, args[0], args[1]);
                    glide(to[0], to[1], Integer.parseInt(args[2]));
                } else {
                    point(mc, args);
                }
            }
            case "drag" -> {
                point(mc, args);
                dragButton = args.length > 5 && args[5].equals("middle") ? new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_MIDDLE, 0) : LEFT;
                mc.screen.mouseClicked(new MouseButtonEvent(mouseX, mouseY, dragButton), false);
                double[] to = spot(mc, args[2], args[3]);
                glide(to[0], to[1], args.length > 4 ? Integer.parseInt(args[4]) : 4);
            }
            case "scroll" -> mc.screen.mouseScrolled(mouseX, mouseY, 0, Double.parseDouble(arg));
            case "key" -> {
                // through the game's own keyboard handler: screens, key bindings and Escape to pause all respond
                String[] keys = arg.toUpperCase(Locale.ROOT).split("\\+");
                int modifiers = 0;
                for (int i = 0; i < keys.length - 1; i++) {
                    modifiers |= switch (keys[i]) {
                        case "CTRL" -> GLFW.GLFW_MOD_CONTROL;
                        case "SHIFT" -> GLFW.GLFW_MOD_SHIFT;
                        case "ALT" -> GLFW.GLFW_MOD_ALT;
                        default -> throw new IllegalArgumentException("Unknown modifier " + keys[i]);
                    };
                }
                KeyEvent key = new KeyEvent(GLFW.class.getField("GLFW_KEY_" + keys[keys.length - 1]).getInt(null), 0, modifiers);
                Method press = KeyboardHandler.class.getDeclaredMethod("keyPress", long.class, int.class, KeyEvent.class);
                press.setAccessible(true);
                press.invoke(mc.keyboardHandler, mc.getWindow().handle(), GLFW.GLFW_PRESS, key);
                press.invoke(mc.keyboardHandler, mc.getWindow().handle(), GLFW.GLFW_RELEASE, key);
            }
            case "type" -> arg.codePoints().forEach(c -> mc.screen.charTyped(new CharacterEvent(c)));
            case "slot" -> mc.player.getInventory().setSelectedSlot(hotbar(mc, arg));
            case "frame" -> frame(mc, args);
            case "survey" -> survey(mc, args);
            case "fly" -> {
                hover = arg.equals("on");
                mc.player.getAbilities().flying = hover;
                mc.player.onUpdateAbilities();
            }
            case "sneak" -> mc.options.keyShift.setDown(arg.equals("on"));
            case "hud" -> mc.options.hideGui = arg.equals("off");
            case "toasts" -> toasts = arg.equals("on");
            case "gui" -> {
                mc.options.guiScale().set(Integer.parseInt(arg));
                mc.resizeGui();
            }
            case "inspect" -> inspect(mc);
            case "missing" -> missing(arg);
            case "shot" -> Screenshot.grab(mc.gameDirectory, arg + ".png", mc.getMainRenderTarget(), 1,
                    message -> LOG.info("[driver] {}", message.getString()));
            case "clip" -> clip(mc, args);
            case "volume" -> mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(Double.parseDouble(arg));
            case "quit" -> {
                LOG.info("[driver] done: {} step(s) failed", failed);
                quitting = true;
                if (mc.level != null) {
                    mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
                }
            }
            default -> {
                Step custom = STEPS.get(parts[0]);
                if (custom == null) {
                    throw new IllegalArgumentException("unknown step " + parts[0]);
                }
                custom.run(mc, arg);
            }
        }
    }

    private static void createWorld(Minecraft mc, String seed) throws IOException {
        LevelStorageSource source = mc.getLevelSource();
        if (source.levelExists(WORLD)) {
            try (LevelStorageSource.LevelStorageAccess access = source.createAccess(WORLD)) {
                access.deleteLevel();
            }
        }
        LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE,
                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
        if (seed.isEmpty()) {
            mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(0, false, false),
                    WorldPresets::createFlatWorldDimensions, mc.screen);
        } else {
            mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(Long.parseLong(seed), true, false),
                    WorldPresets::createNormalWorldDimensions, mc.screen);
        }
        loading = true;
    }

    private static void clip(Minecraft mc, String[] args) throws IOException {
        if (args[0].equals("off")) {
            LOG.info("[driver] clip {}: {} frames", clip, frames);
            clip = null;
            return;
        }
        if (args.length != 2) {
            throw new IllegalArgumentException("clip NAME EVERY, or clip off");
        }
        int ticks = Integer.parseInt(args[1]);
        Path dir = mc.gameDirectory.toPath().resolve(Screenshot.SCREENSHOT_DIR).resolve(args[0]);
        Files.createDirectories(dir);
        try (Stream<Path> old = Files.list(dir)) {
            for (Path file : (Iterable<Path>) old::iterator) {
                Files.delete(file);
            }
        }
        Files.writeString(dir.resolve("clip.txt"), "every=" + ticks + "\n");
        clip = dir;
        every = ticks;
        frames = 0;
    }

    /** Saves a half-size frame of the clip, and where the cursor is in it, in frame pixels, and how big a GUI pixel is. */
    private static void frame(Minecraft mc) {
        RenderTarget target = mc.getMainRenderTarget();
        int downscale = target.width % 2 == 0 && target.height % 2 == 0 ? 2 : 1;
        String name = String.format(Locale.ROOT, "%04d", frames++);
        Screenshot.grab(mc.gameDirectory, clip.getFileName() + "/" + name + ".png", target, downscale, message -> {});
        Window window = mc.getWindow();
        double unit = (double) window.getWidth() / window.getGuiScaledWidth() / downscale;
        String line = mc.screen == null ? name + " -\n"
                : String.format(Locale.ROOT, "%s %.1f %.1f %.3f%n", name, mouseX * unit, mouseY * unit, unit);
        try {
            Files.writeString(clip.resolve("clip.txt"), line, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.error("[driver] cannot write the cursor track", e);
        }
    }

    private static void inspect(Minecraft mc) throws ReflectiveOperationException {
        if (mc.screen == null) {
            LOG.info("[driver] no screen open");
            return;
        }
        Window window = mc.getWindow();
        LOG.info("[driver] screen {} at GUI scale {}, {}x{} GUI px", mc.screen.getClass().getName(), window.getGuiScale(),
                window.getGuiScaledWidth(), window.getGuiScaledHeight());
        double[] origin = spot(mc, "0", "0");
        for (GuiEventListener child : mc.screen.children()) {
            if (child instanceof AbstractWidget w) {
                LOG.info("[driver]   {} \"{}\" at {},{} size {}x{}{}", w.getClass().getSimpleName(), w.getMessage().getString(),
                        (int) (w.getX() - origin[0] + 0.5), (int) (w.getY() - origin[1] + 0.5), w.getWidth(), w.getHeight(),
                        w.active ? "" : " (inactive)");
            } else {
                LOG.info("[driver]   {}", child.getClass().getName());
            }
        }
        if (mc.screen instanceof AbstractContainerScreen<?> screen) {
            LOG.info("[driver]   container {}x{}; slot number, position, contents:",
                    intField(screen, "imageWidth"), intField(screen, "imageHeight"));
            for (Slot slot : screen.getMenu().slots) {
                LOG.info("[driver]   slot {} at {},{}: {}", slot.index, slot.x, slot.y,
                        slot.getItem().isEmpty() ? "empty" : slot.getItem().getCount() + " "
                                + BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()));
            }
        }
    }

    private static void missing(String namespace) {
        Language language = Language.getInstance();
        List<String> keys = new ArrayList<>();
        int[] checked = {0};
        checkNames(BuiltInRegistries.ITEM, namespace, checked, keys, Item::getDescriptionId);
        checkNames(BuiltInRegistries.BLOCK, namespace, checked, keys, b -> b.getDescriptionId());
        checkNames(BuiltInRegistries.ENTITY_TYPE, namespace, checked, keys, EntityType::getDescriptionId);
        checkNames(BuiltInRegistries.CREATIVE_MODE_TAB, namespace, checked, keys, (CreativeModeTab tab) ->
                tab.getDisplayName().getContents() instanceof TranslatableContents t ? t.getKey() : null);
        keys.removeIf(key -> key == null || language.has(key));
        LOG.info("[driver] {} names in {}, {} without a translation{}", checked[0], namespace, keys.size(),
                keys.isEmpty() ? "" : ": " + String.join(", ", keys));
    }

    private static <T> void checkNames(Registry<T> registry, String namespace, int[] checked, List<String> keys,
                                       java.util.function.Function<T, String> key) {
        for (T entry : registry) {
            if (registry.getKey(entry).getNamespace().equals(namespace)) {
                checked[0]++;
                keys.add(key.apply(entry));
            }
        }
    }

    private static void frame(Minecraft mc, String[] args) {
        MinecraftServer server = mc.getSingleplayerServer();
        UUID id = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            ServerLevel level = player.level();
            Holder<Structure> structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
                    .getOrThrow(ResourceKey.create(Registries.STRUCTURE, Identifier.parse(args[0])));
            Pair<BlockPos, Holder<Structure>> found = level.getChunkSource().getGenerator()
                    .findNearestMapStructure(level, HolderSet.direct(structure), player.blockPosition(), 100, false);
            if (found == null) {
                LOG.error("[driver] no {} nearby", args[0]);
                return;
            }
            // the structure starts in the chunk it was found in or next to it
            ChunkPos near = ChunkPos.containing(found.getFirst());
            BoundingBox box = null;
            for (int dx = -1; dx <= 1 && box == null; dx++) {
                for (int dz = -1; dz <= 1 && box == null; dz++) {
                    StructureStart start = level.getChunk(near.x() + dx, near.z() + dz, ChunkStatus.STRUCTURE_STARTS)
                            .getStartForStructure(structure.value());
                    box = start != null && start.isValid() ? start.getBoundingBox() : null;
                }
            }
            Vec3 centre = Vec3.atCenterOf(box.getCenter());
            double angle = Math.toRadians(Double.parseDouble(args[3]));
            double dist = Double.parseDouble(args[1]);
            Vec3 eye = new Vec3(centre.x + Math.sin(angle) * dist, box.maxY() + Double.parseDouble(args[2]),
                    centre.z + Math.cos(angle) * dist);
            Vec3 d = centre.subtract(eye);
            float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            float pitch = (float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
            player.teleportTo(level, eye.x, eye.y - player.getEyeHeight(), eye.z, Set.of(), yaw, pitch, true);
            LOG.info("[driver] framed {} at {}", args[0], box);
        });
    }

    private static void survey(Minecraft mc, String[] args) {
        MinecraftServer server = mc.getSingleplayerServer();
        UUID id = mc.player.getUUID();
        int cx = Integer.parseInt(args[0]), cz = Integer.parseInt(args[1]), r = Integer.parseInt(args[2]);
        server.execute(() -> {
            ServerLevel level = server.getPlayerList().getPlayer(id).level();
            StringBuilder out = new StringBuilder();
            for (int z = cz - r; z <= cz + r; z++) {
                out.append('\n');
                for (int x = cx - r; x <= cx + r; x++) {
                    BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z)).below();
                    BlockState state = level.getBlockState(top);
                    out.append(!state.getFluidState().isEmpty() ? "   ~" : state.is(BlockTags.LOGS) ? "   T"
                            : String.format(Locale.ROOT, " %3d", top.getY() + 1));
                }
            }
            LOG.info("[driver] ground around {} {}, rows from z {} and columns from x {}:{}", cx, cz, cz - r, cx - r, out);
        });
    }

    /** A hotbar slot by number, or the first one holding the item with this id. */
    private static int hotbar(Minecraft mc, String arg) {
        if (arg.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(arg);
        }
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(arg));
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(item)) {
                return i;
            }
        }
        throw new IllegalStateException("no " + arg + " in the hotbar");
    }

    private static void click(Minecraft mc, double x, double y, MouseButtonInfo button) {
        MouseButtonEvent click = new MouseButtonEvent(x, y, button);
        mc.screen.mouseClicked(click, false);
        if (mc.screen != null) {  // a button may close its screen
            mc.screen.mouseReleased(click);
        }
    }

    private static void point(Minecraft mc, String[] args) throws ReflectiveOperationException {
        double[] at = spot(mc, args[0], args[1]);
        moveMouse(mc, at[0], at[1]);
    }

    /** The centre of GUI pixel (x, y) on the open screen, counted from a container screen's corner. */
    private static double[] spot(Minecraft mc, String x, String y) throws ReflectiveOperationException {
        double[] at = {Integer.parseInt(x) + 0.5, Integer.parseInt(y) + 0.5};
        if (mc.screen instanceof AbstractContainerScreen<?> screen) {
            at[0] += intField(screen, "leftPos");
            at[1] += intField(screen, "topPos");
        }
        return at;
    }

    private static void glide(double x, double y, int ticks) {
        glideX = x;
        glideY = y;
        gliding = ticks;
        waiting = ticks;
    }

    /** Moves the mouse and tells the open screen, which the game does itself only while its window has focus. */
    private static void moveMouse(Minecraft mc, double x, double y) {
        mouseX = x;
        mouseY = y;
        Window window = mc.getWindow();
        setDouble(mc.mouseHandler, "xpos", x * window.getScreenWidth() / window.getGuiScaledWidth());
        setDouble(mc.mouseHandler, "ypos", y * window.getScreenHeight() / window.getGuiScaledHeight());
        if (mc.screen != null) {
            mc.screen.mouseMoved(x, y);
            mc.screen.afterMouseMove();
        }
    }

    private static AbstractWidget widget(Minecraft mc, String label) {
        AbstractWidget prefix = null;
        for (var child : mc.screen.children()) {
            if (child instanceof AbstractWidget widget) {
                String text = widget.getMessage().getString();
                if (text.equals(label)) {
                    return widget;
                }
                if (prefix == null && text.startsWith(label)) {
                    prefix = widget;
                }
            }
        }
        if (prefix == null) {
            throw new IllegalStateException("no widget labelled " + label + " (see inspect)");
        }
        return prefix;
    }

    private static int intField(AbstractContainerScreen<?> screen, String name) throws ReflectiveOperationException {
        Field field = AbstractContainerScreen.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(screen);
    }

    private static void setDouble(MouseHandler handler, String name, double value) {
        try {
            Field field = MouseHandler.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setDouble(handler, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}

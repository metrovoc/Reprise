# Reprise

Reprise records your Minecraft sessions and lets you watch them again from any angle. Scrub and seek in both
directions, fly a free camera or see through any player's eyes, build camera moves from keyframes, and export them to
video with sound.

Client-side mod for NeoForge, Minecraft 26.1.2. It works in singleplayer and on any server; the server needs nothing
installed.

## Recording

Reprise records every world and server you join, from the moment it loads. You don't have to remember to start it.

- A red dot and the recorded time show in the top-left corner while recording.
- The game menu has **❚❚ Pause** and **■ Stop & save**, or **● Record** when nothing is recording. Paused time is
  left out of the replay. To record only on demand, turn off automatic recording in Mods → Reprise → Config.
- Press `M` to drop a marker on a moment to come back to.
- Leaving the world saves the replay; a toast confirms it. If the game crashes, at most the last second is lost:
  the recording is saved the next time the game starts.

Reprise records what the server sends, including mods' network packets and the server resource packs Minecraft
applied, plus what the server never sends back about you: your movement, held items, swings, block breaking,
placed blocks and sounds, your hotbar, health, food and experience. First-person turns are sampled eight times per
tick (160 Hz), so watching through your own eyes moves as smoothly as it did live.

If something could not be recorded or read back, Reprise says so instead of dropping it silently: after saving, in
the replay list, and as a warning while watching, with the mods that changed since the recording on hover.

## Finding a replay

**Replays** on the title screen lists your recordings, newest first. Each row shows a picture from the recording,
the world or server, when, how long and how big it is. Search by name, world, server or version. Double-click or
press Enter to watch. **Delete** moves a recording to **Trash**, where it can be restored.

## Watching

| Key | Action |
| --- | --- |
| `W` `A` `S` `D`, `Space`, `Shift` | Fly; hold `Ctrl` to go faster, scroll to change speed |
| `P` | Play / pause |
| `←` `→` | Back / forward 5 s (`Ctrl`: 10 s) |
| `↑` `↓` | Faster / slower, 0.05× to 16× |
| Click someone | See through their eyes, with their hands, items and HUD; sneak to fly free again |
| `B` | Players and mobs |
| `Tab` | Editor |
| `J` / `L` | Roll the camera |

The keys follow ReplayMod's. They only act during a replay, so they don't get in the way of normal play.

While you watch through someone's eyes, the view keeps following them through deaths, dimension changes and seeks.
Seeking is fast both ways: a replay is stored in five-minute segments that each begin with a full picture of the
world, so a seek replays at most one segment.

## Making a shot

`Tab` opens the editor: a dock along the bottom with the playback controls, camera controls, and a timeline with a
lane for each kind of key. Close it with `Tab` to fly the camera with the mouse again.

1. Fly to a good view and press `I` (**+ Camera key**). Move along the timeline and add more keys.
2. Turn **Preview** on (`H`) and play: the camera flies the path exactly as the video will. The path is drawn in
   the world while the editor is open.
3. Select a key to change how the camera travels on to the next one: smooth curve, straight line, ease in and out,
   or hold then cut. **Edit…** takes exact positions, angles and times.
4. Drag the blue handles on the ruler, or press `[` and `]`, to set where the video starts and ends.

Click the timeline to jump; drag keys to move them, Shift-drag to select several, and use Ctrl+C/V to copy them.
Scroll the timeline (or press `=` and `-`) to zoom. Ctrl+Z and Ctrl+Shift+Z undo and redo, even after reopening the
replay.

**More** holds everything else:

- **Time and weather:** keep the recorded time, fix it (dawn, noon, dusk, night…), or change it over the shot with
  time keys. Weather can be clear, overcast, rain, snow or thunder. **Layers, sky and fog** hides terrain, players,
  mobs, particles or clouds, sets a solid sky color for chroma keying, and adjusts fog.
- **Players and mobs:** hide anyone, outline or rename them, watch through their eyes, or add camera keys that keep
  them in view, follow them, or circle around them.
- **Play at this speed from here:** slow motion and timelapses; speeds blend between keys.
- **Freeze the world here:** the camera keeps moving while the world holds still.
- **Orbit around a point**, **camera shake** (subtle, handheld or strong), and **music and sound clips** placed on the
  timeline.
- **Save this part as a new replay** cuts the range between the handles into its own recording.

Projects are saved with each replay, in `reprise/projects/`.

## Exporting

**Export…** renders the shot to `reprise/exports/`. Frames are rendered at a fixed time step, so the video is smooth
however fast your computer is; the export waits for every chunk in view to finish building, including with Sodium,
and keeps Iris shader animation in time with the replay.

The first page has what most videos need: file type (MP4 plays anywhere; MOV ProRes is for editing; PNG images need
no ffmpeg), size, frame rate, quality, game sound, and for first-person shots whether to include hands and the HUD.
**More options** holds the projection (360° panorama, VR stereo, cube map, side-by-side 3D), graphics-card encoding,
smoother edges (2×2 supersampling), a transparent sky and depth images for compositing. **Custom…** takes exact sizes,
frame rates like `30000/1001`, and start and end ticks. **Queue** saves shots to render together.

Video files need [ffmpeg](https://ffmpeg.org/). Reprise finds it on your `PATH`, in the usual Homebrew folders, or in
`reprise/ffmpeg/` in the game folder.

## Importing other recordings

Drop a file onto the replay list to import it as a separate replay:

- **Flashback** ZIP recordings from Minecraft 26.1.2, including server switches, pauses and first-person samples.
- **ReplayMod** MCPR recordings from Minecraft 26.1.2, with their camera paths (**More → ReplayMod paths…**).
  Minecraft 1.21 and 1.21.1 recordings import when ReplayMod's `replaymod-26.1-2.6.28.jar` is placed in
  `reprise/converters/`. [Get it from ReplayMod's release page.](https://modrinth.com/mod/replaymod/version/5LxshONI)

## Playback measurements

On a roughly 29-second Minecraft 26.1.2 route through 965 chunk coordinates, a fixed camera faced a wall that the
recording player built after two teleports. Seeking across the placement alternated the center pixel between gold
and sky. The seek columns measure elapsed time until both the loaded client block and its rendered pixel matched the
target. Each is the median of 12 seeks after eight warm-up seeks.

| Production client and recording | Forward seek | Backward seek | Playback FPS across three passes |
|---|---:|---:|---:|
| Reprise, native recording | 11 ms | 76 ms | 119.2–119.3 |
| Reprise, imported Flashback recording | 17 ms | 69 ms | 119.2–119.3 |
| Flashback 0.43.3, original recording | 42 ms | 89 ms | 119.6 |

The last two rows use the same source recording. The test used an Apple M5 Pro, a 1200×780 framebuffer, a 120 FPS cap,
VSync off, a 10-chunk view distance and a 3 GiB JVM heap. Reprise ran on NeoForge 26.1.2.126 and Flashback on Fabric
Loader 0.19.3. Reprise applies seeks on the client thread; Flashback queues them. Loading the recording and
first-time seeks are outside these measurements.

## Limits

- A replay plays on the Minecraft version it was recorded on.
- Play modded recordings with the same mods; the replay list warns when they changed. Mods that keep visual state
  outside the world can restore it at seeks by subscribing to `ReplaySnapshotEvent` on the NeoForge game bus.
- Time spent on loading screens (joining, changing dimension) is left out of the replay.

## Building

Needs JDK 25.

```bash
./gradlew build
```

The jar is written to `build/libs/`. `./gradlew test` runs the unit tests.

## License

[GPL-3.0-or-later](LICENSE).

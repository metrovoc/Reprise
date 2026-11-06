# Reprise

Reprise records your Minecraft sessions and lets you watch them again from any angle. Scrub and seek in both
directions, fly a free camera or ride along in another player's first-person view, build camera moves from keyframes,
and export them to video with sound.

Client-side mod for NeoForge, Minecraft 26.1.2. It works in singleplayer and on any server; the server needs nothing
installed.

## Recording

- **Start and stop** from the pause menu (*Start Recording* / *Stop Recording*), or bind *Start/Stop Recording* in
  Controls. Recordings can also start by themselves when you open a world or join a server (Mods → Reprise → Config).
- **Pause** with *Pause/Resume Recording*; the paused time is left out of the replay.
- **Markers** (`M`) flag moments to come back to. They show on the timeline; double-click one to jump to it.
- A red dot and the recorded time show in the corner while recording.
- Leaving the world saves the replay to `reprise/replays/` in the game folder. A recording cut short by a crash is
  repaired and saved the next time you open the replay list.

Reprise records world updates and registered clientbound mod payloads during play. Recordings include the server
resource packs Minecraft applied and follow pack changes during playback and export. It also records your own movement,
held items, swings, block breaking, block placement and sounds. To capture all nine hotbar slots, stack
counts, selection, food and experience, enable **Record my hotbar and HUD** in the mod's recording settings
before starting a recording. When you spectate the recording player, their items and applicable survival
HUD appear below the replay timeline.

Native recordings save player chat as it reaches the chat window, including server masking and client chat
filters. During playback, imported player messages use their recorded display text and filter masks without
requiring the original server’s chat session.

### Importing recordings

Open **Replays → Import…** or drop a recording onto the replay browser. Reprise creates a separate
archive that you can seek, edit, clip, and export.

**Flashback ZIP.** The importer reads same-version Minecraft 26.1.2 game
packets, terrain, recorder and entity movement, snapshot boundaries, and markers. Modded packets still
require compatible client mods and registries. Mid-segment connection reconfiguration is unsupported.
Flashback editor projects, optional sub-tick camera samples, and wall-clock timing are outside the import.

**ReplayMod MCPR.** Minecraft 26.1.2 format 14 recordings import directly. For Minecraft 1.21 and
1.21.1 recordings (protocol 767), put `replaymod-26.1-2.6.28.jar` in the game directory’s
`reprise/converters/` folder, then import the MCPR as usual. Reprise uses the bundled ReplayStudio
library to translate the packets locally. Mod-specific data needs compatible registries and client mods.
[Download the converter from ReplayMod’s release page.](https://modrinth.com/mod/replaymod/version/5LxshONI)

Recorded packets and markers use the 20-tick timeline. Packets in a bundle take effect together at its
opening timestamp. To apply this timing to an earlier import, import the original MCPR again. Playback
opens when the recording player and terrain first appear. Backward seeks rebuild the world from the start of the recording. Import covers a single
connection configuration and includes embedded server resource packs.

If the recording contains a ReplayMod path library, open **Editor → ReplayMod paths…** and select the autosave
or a named shot. Loading it replaces the camera keys, speed keys, world freezes, and export range in one undoable edit.
Linear, cubic spline, and Catmull–Rom segments retain their position, rotation, roll, and timing. Reprise
adds a camera key where a time-speed change splits a segment and uses your current FOV. Import supports
free-camera and entity spectator segments with a forward or stationary replay clock. During a freeze
the camera continues along the shot while the recorded world stays at the held timestamp. Camera movement
before or after the time track uses the nearest time key. Spectator segments show the recorded entity’s
viewpoint during preview and export. Select a spectator key and click **Spectate** to turn its outgoing
segment into a linear camera move. If the camera starts late, the shot holds your incoming viewpoint
until the first camera key. The export range rounds outward to whole timeline ticks.

## Watching

Open **Replays** on the title screen and pick a recording to watch. Search by filename, recording name,
world or server, or Minecraft version. Sort by date, name, or duration. **Rename** changes the filename.
**Move to Trash** takes a recording out of the library. Open **Trash** to restore it with its camera work
and queued shots, or delete the recording permanently. Restoring uses a numbered filename if needed.
To play two recordings back to back,
select the first, choose **Join recordings…**, then select the second and confirm. Reprise creates a new
recording with its own editor project. Markers from both recordings appear on the combined timeline.

| Key | Action |
| --- | --- |
| `W` `A` `S` `D`, `Space`, `Shift` | Fly the camera; hold `Ctrl` to go faster, scroll to change the speed |
| `P` | Play / pause |
| `←` `→` | Seek 5 s back or forward (hold `Ctrl` for 10 s) |
| `↑` `↓` | Faster / slower playback, from 0.05× to 16× |
| Left click | Spectate the entity under the crosshair; sneak to fly free again |
| `Tab` | Open the editor |
| `Z` / `C` | Roll the camera |

While you spectate a player you see what they saw, with their held items and arm swings. The camera keeps following
them through deaths, dimension changes and seeks.

Seeking is fast both ways: a replay is stored in five-minute segments that each begin with a full picture of the
world, so a seek replays at most one segment.

## Editing

The editor (`Tab`) puts a timeline under the live world. Scroll over the timeline to zoom around the pointer,
middle-drag or scroll horizontally to pan, and double-middle-click to show the full replay. The editor remembers
the visible range when reopened. Dropping a camera key onto an occupied tick replaces the key at that tick.
Undo restores both keys. The movement keys still fly the camera.

Set the in-point with **[** and the out-point with **]**, then choose **Save replay clip** to create a
standalone recording. The clip opens at the in-point and keeps markers within the selected range. Its
opening snapshot includes packet history and tick boundaries from the preceding checkpoint so terrain
lighting and modded state can be restored at the cut. That history can include events from before the visible
clip. Clips that fold ticks into the opening snapshot use segment format 2 and require a reader that supports it.

- **Keyframe** (`K`) saves the camera's position, angle, roll and field of view at the current moment. Drag a
  keyframe to move it in time, double-click it to jump there, right-click it to change how the camera travels on to
  the next one:
  - *Smooth*: a curve through the keyframes at an even speed;
  - *Linear*: straight lines;
  - *Ease*: starts and stops gently;
  - *Hold*: stays put, then cuts.
- For a moving subject, frame the shot with the free camera, open **Entities**, select the subject and choose eyes,
  center or feet. **Aim keyframe** pans from the camera path to keep that point in frame. **Follow keyframe** also
  carries the camera with the subject. Two **Orbit keyframes** around the same subject and target point move the
  camera along an arc. Select the first orbit key and use **+1 turn** or **−1 turn** for extra revolutions.
  If the subject leaves the loaded world, preview and export use the camera poses stored in the keys.
- For an orbit around scenery, point the free camera at a block and choose **Orbit point…** in the editor.
  You can enter exact world coordinates or use the aimed block's center. Add a second orbit key at another time;
  keeping the center fixed circles the same landmark, and changing it moves the center during the shot.
- **Path: On** previews the shot while playing or scrubbing. Switch it off to fly the camera freely. The editor draws the path
  against the subjects at the current replay time.
- **Speed** keys make slow motion and timelapses: set a speed with `−`/`+`, press *Speed*, and from that moment the
  preview and the export run at that speed. Double-click a speed key to switch between a linear ramp and holding that speed
  until the next key. Right-click a speed key to remove it.
- **Freeze world…** inserts time at the playhead for a camera move over a stationary world. Enter the length
  in timeline ticks (20 is one second at 1×). Later camera and speed keys shift together. The shaded interval
  can contain camera keys, and **Undo** removes the insertion. **Save replay clip** uses the underlying recording range.
- **Entities** lists players and mobs at the current replay time. Search by name or UUID, then select a row to
  spectate, hide, or outline that entity. Enter a six-digit hex color to change its outline, or set a custom name tag. These edits
  are saved with the project and apply to video exports. Hiding a spectated player also hides their first-person hands.
- **Environment** changes the replay's weather and daylight. Choose recorded weather, clear, overcast, rain, snow,
  or thunderstorm. Set a fixed time from 0 to 23999, or use **Set time key** at several replay ticks for a timelapse.
  The clock moves forward between keys and wraps through midnight. **Recorded time** restores the original lighting.
- Under **Environment → Scene layers**, show or hide terrain, players, other entities, particles and clouds.
  Set a solid sky color for chroma key, adjust fog start and end in blocks, tint the fog, or enable night vision.
  Shader packs that draw their own sky can override the solid color.
- **Names** shows or hides name tags.
- `Ctrl+Z` / `Ctrl+Shift+Z` undo and redo. The last 200 edits stay available when you reopen the replay.

Keyframes and settings are saved with each replay, in `reprise/projects/`.

## Exporting

*Export* in the editor renders the selected range to `reprise/exports/`. Frames are rendered at a fixed time step,
so the video is smooth however fast your computer is. Exports start with the HUD hidden. In the export screen,
choose **HUD: On** to include gameplay overlays and first-person hands, or **Hands only** for a first-person
shot with a clean screen. Spectate the recorder to include their recorded hotbar and survival stats. The export
waits for every chunk in view
to finish building. With Sodium, Reprise requests synchronous terrain rendering during export. With Iris, shader time
follows the replay and the shader frame counter advances once per exported frame.

- Choose **360°** in place of **Flat** to export a full equirectangular panorama. The output is twice as wide as
  it is tall. The camera path sets the forward view, and all six directions use the same replay instant. Video
  files carry spherical projection metadata for 360° players. Panoramas capture the world without first-person
  hands or HUD overlays, and the optional stereo soundtrack follows the forward view.
- Formats: H.264 MP4, H.265 MP4, VP9 WebM, ProRes 4444 MOV, or a PNG sequence.
- H.264 and H.265 support CPU encoding with constant quality, or GPU encoding with a target bitrate.
  Reprise tests the chosen resolution and frame rate before starting a GPU export. It checks VideoToolbox on
  macOS and NVENC, Quick Sync, and AMF on other systems.
- **SSAA: 2×2** renders at twice the output width and height, then averages four samples per pixel on the GPU.
  It works with video and PNG, including transparent exports.
- For compositing, choose **PNG sequence** and enable **Depth**. Each color frame has a matching
  `frame_000000.depth.exr` with a 32-bit float `Z` channel. Values are distances along the camera’s viewing axis
  in blocks. In a 360° export, depth is radial distance from the camera. Pixels without a world depth value are positive infinity. Hands and HUD appear only in the color pass.
  At 2×2 SSAA, depth uses the nearest of the four surfaces at each pixel.
- **Transparent sky** exports straight alpha in PNG sequences and ProRes 4444. For a character cutout, hide terrain
  and clouds in **Scene layers** and choose clear weather. Each output frame samples the same instant against black
  and white to recover transparency, including partially transparent materials. Turn off active Iris shader packs
  for transparent exports.
- Resolutions from 720p to 4K, plus vertical and square; 24, 30, 60 or 120 fps.
- Sound: the replay's sounds as you would hear them at the camera, in 48 kHz stereo.

To batch shots from one replay, open **Queue** in the export screen and choose **Add current shot**.
Each entry saves the current range, camera path or viewpoint, spectated player, scene settings, and encoder
settings. You can name, reorder, or remove shots before choosing **Render queue**. Completed shots leave
the queue. Cancelling or hitting an error keeps the unfinished shot and later entries for retry, even after
restarting Minecraft.

Completed exports appear in the exports folder after all frames and any soundtrack are written. You can cancel
with Escape or **Cancel**. Repeated exports with the same name get a numbered suffix.

Video formats need [ffmpeg](https://ffmpeg.org/). Reprise finds it on your `PATH`, in the usual Homebrew folders,
or in `reprise/ffmpeg/` in the game folder. PNG sequences need no ffmpeg. With sound enabled, the sequence includes a 48 kHz stereo PCM soundtrack in `audio.wav`.

## Playback measurements

On a roughly 29-second Minecraft 26.1.2 route through 965 chunk coordinates, a fixed camera faced a wall that the
recording player built after two teleports. Seeking across the placement alternated the center pixel between
gold and sky. The seek columns measure elapsed time until both the loaded client block and its rendered
pixel matched the target. Each is the median of 12 seeks after eight warm-up seeks.

| Production client and recording | Forward seek | Backward seek | Playback FPS across three passes |
|---|---:|---:|---:|
| Reprise, native recording | 11 ms | 76 ms | 119.2–119.3 |
| Reprise, imported Flashback recording | 17 ms | 69 ms | 119.2–119.3 |
| Flashback 0.43.3, original recording | 42 ms | 89 ms | 119.6 |

The last two rows use the same source recording. The native recording follows the same scripted route.
The test used an Apple M5 Pro, a 1200×780 framebuffer, a 120 FPS cap, VSync off, a 10-chunk view distance,
and a 3 GiB JVM heap. Reprise ran on NeoForge 26.1.2.126 and Flashback on Fabric Loader 0.19.3.
Reprise applies seeks on the client thread. Its median backward request took 71 ms for the native
recording and 68 ms for the import. Flashback queued its requests in under 1 ms. Loading the recording and first-time seeks
are outside these measurements.

## Limits

- A replay plays on the Minecraft version it was recorded on.
- Play modded recordings with the same mods and versions. For visual state stored outside the vanilla world, mods can
  subscribe to `ReplaySnapshotEvent` on the NeoForge game bus and write their current state as play payloads. This
  restores the effect when a recording starts mid-session or a seek rebuilds the world from a later snapshot.
- Time spent on loading screens (joining, changing dimension) is left out of the replay.

## Building

Needs JDK 25.

```bash
./gradlew build
```

The jar is written to `build/libs/`. `./gradlew test` runs the unit tests.

## License

[GPL-3.0-or-later](LICENSE).

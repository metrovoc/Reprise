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

Reprise records world updates and registered clientbound mod payloads during play. It also records your own movement,
held items, swings, block breaking, block placement and sounds.

## Watching

Open **Replays** on the title screen and pick a recording to watch. To play two recordings back to back,
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
opening snapshot includes packet history from the preceding checkpoint so it can restore the world
and modded state. That history can include events from before the visible clip.

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
  preview and the export run at that speed. Between two keys the speed changes linearly. Right-click a speed key to remove
  it.
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
so the video is smooth however fast your computer is. The GUI is left out, and the export waits for every chunk in view
to finish building. With Sodium, Reprise requests synchronous terrain rendering during export. With Iris, shader time
follows the replay and the shader frame counter advances once per exported frame.

- Formats: H.264 MP4, H.265 MP4, VP9 WebM, ProRes 4444 MOV, or a PNG sequence.
- H.264 and H.265 support CPU encoding with constant quality, or GPU encoding with a target bitrate.
  Reprise tests the chosen resolution and frame rate before starting a GPU export. It checks VideoToolbox on
  macOS and NVENC, Quick Sync, and AMF on other systems.
- **SSAA: 2×2** renders at twice the output width and height, then averages four samples per pixel on the GPU.
  It works with video and PNG, including transparent exports.
- **Transparent sky** exports straight alpha in PNG sequences and ProRes 4444. For a character cutout, hide terrain
  and clouds in **Scene layers** and choose clear weather. Each output frame samples the same instant against black
  and white to recover transparency, including partially transparent materials. Turn off active Iris shader packs
  for transparent exports.
- Resolutions from 720p to 4K, plus vertical and square; 24, 30, 60 or 120 fps.
- Sound: the replay's sounds as you would hear them at the camera, in 48 kHz stereo.

Completed exports appear in the exports folder after all frames and any soundtrack are written. You can cancel
with Escape or **Cancel**. Repeated exports with the same name get a numbered suffix.

Video formats need [ffmpeg](https://ffmpeg.org/). Reprise finds it on your `PATH`, in the usual Homebrew folders,
or in `reprise/ffmpeg/` in the game folder. PNG sequences need no ffmpeg. With sound enabled, the sequence includes a 48 kHz stereo PCM soundtrack in `audio.wav`.

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

# FireCric — Boundary (Fire TV cricket + phone-bat companion)

A two-app cricket game built to the "Boundary" Figma designs. **v2.0 renders the in-play
scene in real 3D (OpenGL ES 2.0)** matching the shared 3D game visual.

- **`boundary/`** — the Fire TV app (Fire OS 6+, minSdk 22, targetSdk 28, leanback).
  - `GlSceneView` — OpenGL ES 2.0 scene: low broadcast camera beside the pitch, the
    design's stadium backdrop (`res/drawable/bg_3d.png`, extracted from the visual),
    procedural grass/pitch textures, a lit 3D batsman (fixed at the crease) holding a
    **3D bat that swings in 3D** on the phone-controlled bat angle, a 3D bowler run-up,
    a sphere ball that bounces at the pitch marker, blob shadows, ball trail.
  - `HudView` — 2D overlay with the designed screens: lobby with pairing code, top HUD
    (`YOU 12/0`, `OVERS 0.3/2`, `PHONE CONNECTED`), `SWING NOW` tag, contact ring,
    shot-result breakdown, dot-ball / wicket panels, innings-complete stats, and the
    "GAME PAUSED — your bat disconnected" watchdog.
  - `BoundaryModel` — pure-Java game logic (unit-tested).
- **`boundary-remote/`** — the Android phone companion (minSdk 21, targetSdk 34).
  Auto-discovers the TV on launch, then connect → calibrate (hold still 3 s) →
  "Your bat is ready". Tilt the phone left/right to angle the bat (±60°, 120° forward
  zone); the shot fires automatically when the bat meets the ball. SWING button /
  flick = deliberate shot. Quiet PING keepalive every 3 s.

## Protocol (same Wi-Fi)

TV listens on **TCP 8771** + **UDP 8772**. Phone → TV: `MOVE dx dy` (bat angle),
`SWING`/`=`/`CLICK`, `S`, `C`, `PING`. TV → phone: `PONG` or `YOU: r/w` + overs.

Quick Play = **2 overs / 12 balls** or 3 wickets. Runs banded 1/2/3/4/6 by hit
distance. Bat angle within ±18° of the ball line = contact (PERFECT/GOOD by error);
too early on a short ball = caught.

## Build (no Android Studio needed)

`tools/build-apk.sh <project-dir> <out.apk> <minSdk> <targetSdk> <versionCode> <versionName>`
(JDK 17 + build-tools 34: aapt2/d8/apksigner, debug-signed):

```bash
bash tools/build-apk.sh boundary boundary-tv.apk 22 28 5 2.0
bash tools/build-apk.sh boundary-remote boundary-remote.apk 21 34 3 1.2
```

Run each project's `tools/gen_assets.py` from its root (Pillow) for icons/banner.
`boundary/res/drawable/bg_3d.png` is the stadium backdrop extracted from the design
visual; `bg_stadium.jpg` (older 2.5D backdrop) is kept for reference.

## Tests

Pure-Java model + socket tests (any JDK 17): compile `BoundaryModel`, `RemoteServer`,
`RemoteClient`, `MotionMapper` + `TestBoundary` and run — run bands, wobble bounds,
1.5 s ball travel, ±60° clamp, no-shake angle EMA, fixed batsman, auto-hit/auto-dot,
12-ball innings → GAME_OVER, wire protocol incl. UDP discovery. 20/20 passing.

## APK downloads

Signed APKs via the Mistral document library (rename `.txt` → `.apk`):
`boundary-tv-v2.0-3d.apk.txt` and `boundary-remote-v1.2.apk.txt`.

## Other apps in this workspace

`fire-calc`/`fire-remote` (8765/8766), `fire-game`/`fire-gameremote` (8767/8768),
older `fire-cricket` (8769/8770). Boundary uses 8771/8772 — open only one TV app at a time.

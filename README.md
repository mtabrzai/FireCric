# FireCric — Boundary (Fire TV cricket + phone-bat companion)

A two-app cricket game built to the "Boundary" Figma designs. **v2.5 renders the
in-play scene in real 3D (OpenGL ES 2.0)**, gives the batsman a properly-shaped
bat, and makes the innings **five overs with a distinct bowling phase per
over** — matching the design visual (`design/boundary-face-the-delivery.svg`).
Full requirements: [`requirement.md`](requirement.md).

- **3D bat swing** — keyframed backlift → downswing → contact → follow-through
  combining yaw, elevation, roll and a moving hand pivot (a real cone through
  space, not a flat arc), ghost trail sampled along the swing path, and a live
  backlift stance between balls whose blade points at ~85% of the commanded
  tilt, so aiming reads true.
- **Proper bat shape** — cane handle with rubber grip, willow blade with spine
  wedge, shoulders and rounded toe (3D scene and 2D fallback alike).
- **3D ball pitching** — released from the bowler's hand at ~2 m, parabolic
  descent, sharp velocity-flip bounce at the pitch marker with length-dependent
  rise (yorker stays low, short ball climbs to the chest), dust puff + shock ring
  on the bounce, seam spin, trajectory-following trail, shadow that meets the
  ball exactly at the bounce. Full tosses never pitch.
- **Five overs, five bowling phases** — Over 1 PACE (straight, ~1.5 s), Over 2
  SPIN (slower, grips and turns off the pitch), Over 3 SWING (curls in the
  air before pitching), Over 4 BOUNCE (short-pitch barrage), Over 5 DEATH
  (fast yorkers, ~1.3 s). The HUD labels the current over at all times.
- **Broadcast camera** — subtle push-in during the delivery, pan/pull-back
  following the struck ball into the deep field.

- **`boundary/`** — the Fire TV app (Fire OS 6+, minSdk 22, targetSdk 28, leanback).
  - `GlSceneView` — the OpenGL ES 2.0 scene above: design stadium backdrop
    (`res/drawable/bg_3d.png`, extracted from the design visual, 1024×512 POT),
    procedural grass/pitch textures, lit 3D batsman with swinging 3D bat,
    3D bowler run-up with bowling-arm windmill, sphere ball, blob shadows.
  - `HudView` — 2D overlay with the designed screens: lobby with pairing code,
    top HUD (`YOU 12/0`, `OVERS 0.3/2`, `PHONE CONNECTED`), `SWING NOW` tag,
    contact ring, shot-result breakdown, dot-ball / wicket panels,
    innings-complete stats, and the "GAME PAUSED — your bat disconnected"
    watchdog. The 2D fallback field mirrors the same keyframed swing and
    piecewise-bounce trajectory when GL is unavailable.
  - `BoundaryModel` — pure-Java game logic (unit-tested, unit-tested; v2.5 adds the 5-over phase engine).
- **`boundary-remote/`** — the Android phone companion (minSdk 21, targetSdk 34).
  Auto-discovers the TV on launch, then connect → calibrate (hold still 3 s) →
  "Your bat is ready". Tilt the phone left/right to angle the bat (±60°, 120°
  forward zone); the shot fires automatically when the bat meets the ball.
  SWING button / flick = deliberate shot. Quiet PING keepalive every 3 s.

## Protocol (same Wi-Fi)

TV listens on **TCP 8771** + **UDP 8772** (`FIRECALC_DISCOVER` probe). Phone → TV:
`MOVE dx dy` (bat angle), `SWING`/`=`/`CLICK`, `S`, `C`, `PING`. TV → phone:
`PONG` or `YOU: r/w` + overs.

Quick Play = **5 overs / 30 balls** or 3 wickets, one bowling phase per over
(PACE → SPIN → SWING → BOUNCE → DEATH). Runs banded 1/2/3/4/6 by hit distance.
Bat angle within **±30°** of the ball line = contact (PERFECT/GOOD by error;
modest tilt angles hit easily); too early on a short ball = caught.

## Build (no Android Studio needed)

`tools/build-apk.sh <project-dir> <out.apk> <minSdk> <targetSdk> <versionCode> <versionName>`
(JDK 17 + build-tools 34: aapt2/d8/apksigner, debug-signed):

```bash
bash tools/build-apk.sh boundary boundary-tv.apk 22 28 10 2.5
bash tools/build-apk.sh boundary-remote boundary-remote.apk 21 34 3 1.2
```

Binary assets are generated (Pillow) and not committed: run each project's
`tools/gen_assets.py` from its root for icons/banner, and the TV app's
`tools/gen_bg3d.py` to extract the stadium backdrop
(`res/drawable/bg_3d.png`, 1024×512 POT) from the design visual
`design/boundary-face-the-delivery.svg`. With those three scripts run, a clean
clone builds both APKs exactly as released.

## Tests

`tools/TestBoundary.java` — compile `BoundaryModel`, `RemoteServer`,
`RemoteClient`, `MotionMapper` + `TestBoundary` (any JDK 17) and run: run bands,
5-over format, over-phase order and per-over travel time, wobble bounds, ±60°
clamp, no-shake angle EMA, fixed batsman, easy-contact band, innings completion
(30 balls or 3 wickets), wire protocol incl. UDP discovery, MotionMapper
deadzone/flush. 23/23 passing.

```bash
javac --release 8 -d /tmp/t boundary/src/com/example/boundary/{BoundaryModel,RemoteServer}.java \
  boundary-remote/src/com/example/boundaryremote/{RemoteClient,MotionMapper}.java tools/TestBoundary.java
java -cp /tmp/t TestBoundary
```

## APK downloads

Two signed (debug-key) APKs ship with v2.5, delivered as download cards with
the release notes:

- **Fire TV app** — `boundary-tv-v2.5-5overs.apk` (versionCode 10 / versionName 2.5)
- **Phone remote** — `boundary-remote-v1.2.apk` (versionCode 3 / versionName 1.2)

Build locally with the commands above to reproduce them exactly.

## Other apps in this workspace

`fire-calc`/`fire-remote` (8765/8766), `fire-game`/`fire-gameremote` (8767/8768),
older `fire-cricket` (8769/8770). Boundary uses 8771/8772 — open only one TV app
at a time.

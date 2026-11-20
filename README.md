# FireCric — Boundary (Fire TV cricket + phone-bat companion)

A two-app cricket game built to the "Boundary" Figma designs. **v3.1 renders a
100% live 3D stadium (OpenGL ES 2.0)** — floodlit bowl, mowed turf, clay pitch,
3D stumps with knockback, umpire, articulated bowler and batsman — with a
Figma-faithful HUD, **metric ball physics (bouncing vs full toss)**, and
**2-axis phone control (line ±60° + bat height)**. No screenshot crops, no baked
backgrounds: every frame is rendered. Full requirements:
[`requirement.md`](requirement.md).

- **Live 3D scene, no static crops** — procedural night stadium
  (`tools/gen_stadium.py` → `res/drawable-nodpi/bg_stadium.png`; never a
  Figma screenshot), grass outfield with mow stripes, clay pitch strip with
  creases, 3D wooden stumps + bails that cartwheel on `BOWLED`, a crouched
  wicketkeeper and a standing umpire behind the stumps, fielders spread in the
  outfield, an articulated bowler (striding run-in, windmilling arm, ball
  released from his hand) and an articulated, slightly translucent batsman
  (helmet + grille, pads, gloves) holding a 3D willow bat (cane handle, grip,
  spine wedge, rounded toe) pivoted at his hands. The frame mirrors the
  "Face the delivery" design: **right-handed batsman on the right of the
  screen, bowler on the left, stumps centre**. Stance = bat down; the phone's
  pitch axis lifts the bat up/down, roll aims left/right, and a swing plays
  backlift → contact at the sweet-spot reticle → follow-through. Exactly
  **one ball** on screen at all times — drawn large with a pulsing ring around
  it while it travels; no ghost trail.
- **Real pitching: bouncing vs non-bouncing** — `FULL TOSS` flies through
  the air on the full (aerial reticle, no pitch ring); `YORKER` pitches at the
  toes and skids (< 0.30 m); `GOOD LENGTH` bounces mid-pitch to stump/waist
  (0.55–0.88 m); `SHORT` pitches early and rises chest-high (> 1.05 m).
  `SPIN` grips and kicks laterally off the pitch, `SWING` curls in the air
  before pitching (verified path-shape tests).
- **2-axis bat control** — phone roll (±60°) moves the bat line across the
  crease (±0.65 m); phone pitch (±35°) sets bat height and footwork
  (0.16–1.32 m, low front-foot dig ↔ drive ↔ back-foot pull). A live lime
  sweet-spot reticle at the crease shows your (line, height) against the
  incoming ball.
- **Strike physics & four real outcomes** — deliberate swing (button or a
  physical phone flick, only while a delivery is live) with a 220 ms
  biomechanical downswing that squares the blade face at contact; **Smart Assist** (toggleable, default on) auto-strokes 1–4 runs
  when you line up line ≤ 0.36 m and height ≤ 0.40 m; manual timed swings
  unlock full power and sixes. Miss high over a yorker and it can be
  **BOWLED** through the gate; mis-timed edges loop up and are **CAUGHT**;
  a clean hit flies in `FLYING` with a camera that pans after it; anything
  else is a **DOT** to the keeper.
- **Five overs, five bowling phases** — 5 overs / 30 balls or 3 wickets:
  PACE → SPIN → SWING → BOUNCE → DEATH, each over labelled in the HUD with its
  own delivery mix (BOUNCE is short-heavy, DEATH is yorker/full-toss heavy).
- **Figma-faithful UI** — Inter typeface from `assets/fonts`, the design
  palette (#060F0E / #132D25 / lime #D7FF67 / coral #FF895E / ball #F15D4D),
  lobby with 6-digit pairing code, in-play top/bottom bars, SWING NOW prompt,
  delivery tags, shot breakdown with 120° forward-field arc, YOUR SWING timing
  meter, innings-complete log, game-paused overlay. D-pad navigable without
  the phone.
- **Effortless pairing** — the phone auto-discovers the TV (UDP), you confirm
  the 6-digit code, calibrate once (hold still 3 s), and later launches
  reconnect automatically. One persistent TCP connection (no per-message
  sockets), 1 Hz heartbeat, 3 s watchdog pauses the TV ("delivery not
  counted") if the bat drops. Haptics: release tick, boundary double-pulse,
  wicket buzz.

## Protocol v3 (same Wi-Fi) — TV and phone versions must match

Discovery: phone broadcasts `BOUNDARY_DISCOVER 3` to UDP 8772; the TV replies
`BOUNDARY 3 <ip> <port> <tvName>` (the code is never broadcast). Session (one
persistent TCP connection to 8771, `\n`-terminated, tab-separated):

- Phone → TV: `HELLO 3 <code> <phoneName>` (first line), then
  `TILT <lineDeg> <heightNorm>` (absolute 2-axis pose, ~30 Hz),
  `SWING` `START` `NEXT` `PAUSE` `RESUME` `AGAIN` `LOBBY`
  `ASSIST <0|1>`, `PING` (1 Hz).
- TV → phone: `WELCOME <tvName>` or `ERR CODE` / `ERR VERSION`;
  `STATE k=v …` pushed on every change (screen, score, overs, over label,
  delivery, bounces, assist, hint); `EVENT HIT <runs> <timing>` /
  `EVENT WICKET` / `EVENT DOT` / `EVENT BOWL`; `PONG`.

## Build (no Android Studio needed)

`tools/build-apk.sh <project-dir> <out.apk> <minSdk> <targetSdk> <versionCode> <versionName>`
(JDK 17 + build-tools 34: aapt2/d8/zipalign/apksigner, debug-signed;
`ANDROID_SDK` and `JAVA_HOME` env vars override the default tool paths):

```bash
bash tools/get_fonts.sh                                  # Inter (SIL OFL) into both apps
bash tools/build-apk.sh boundary boundary-tv.apk 22 28 14 3.1.1
bash tools/build-apk.sh boundary-remote boundary-remote.apk 21 34 6 2.1.1
```

Binary assets are generated/downloaded, never committed: run
`tools/get_fonts.sh` once (fonts), then each project's `tools/gen_assets.py`
for icons/banner, and the TV app's `tools/gen_stadium.py` for the procedural
stadium backdrop (`res/drawable-nodpi/bg_stadium.png`). With those run, a
clean clone builds both APKs exactly as released.

## Tests

`tools/TestBoundary.java` — **61/61 passing**. Covers: 5-over format, run
bands, phase order and per-over delivery mixes, wobble bounds, angle clamps
and the no-shake EMA glide, full-toss-never-bounces, yorker/good-length/short
pitch zones and rise ordering, spin kick + swing curve path shapes, 2-axis
pose mapping, Smart Assist (alignment, 57 m cap, manual sixes), misalignment
beats, BOWLED into the stumps, high-bat-on-short contact, CAUGHT edge skyers,
timing meter/labels, FLYING-before-RESULT, auto-advance, pause-not-counted,
lobby reset, state payload keys, headlines, protocol mirror across both apps,
discovery/pairing/STATE codecs, TiltMath deadzone + absolute no-drift mapping,
and live loopback sessions (WELCOME / ERR CODE / ERR VERSION / PING-PONG /
TILT / STATE push / bat takeover / UDP discovery without code leak / 3 s
watchdog / phone SessionClient pairing).

```bash
javac --release 8 -cp <android.jar> -d /tmp/t \
  boundary/src/com/example/boundary/{BoundaryModel,Protocol,PairingCode,SessionServer}.java \
  boundary-remote/src/com/example/boundaryremote/{Protocol,TiltMath,SessionClient,Discovery}.java \
  tools/TestBoundary.java
java -cp /tmp/t TestBoundary
```

## APK downloads

Two signed (debug-key) APKs ship with v3.1, delivered as download cards with
the release notes:

- **Fire TV app** — `boundary-tv-v3.1.1.apk` (versionCode 14 / versionName 3.1.1)
- **Phone remote** — `boundary-remote-v2.1.1.apk` (versionCode 6 / versionName 2.1.1)

Build locally with the commands above to reproduce them exactly.

## Other apps in this workspace

`fire-calc`/`fire-remote` (8765/8766), `fire-game`/`fire-gameremote` (8767/8768),
older `fire-cricket` (8769/8770). Boundary uses 8771/8772 — open only one TV app
at a time.

# FireCric — Boundary (Fire TV cricket + phone-bat companion)

A two-app cricket game built to the "Boundary" Figma designs:

- **`boundary/`** — the Fire TV app (Fire OS 6+, minSdk 22, targetSdk 28, leanback launcher).
  Stadium view, scoreboard (`YOU 12/0`, `OVERS 0.3/2`), bowler, random deliveries
  (yorker / good length / short / full toss), pitch bounce marker, a FIXED batsman whose
  bat pivots on angle only (120° forward zone), a bigger ball (~3x, ~1.5 s release→bat,
  small line wobble) that auto-fires when the bat angle meets the ball, contact ring,
  shot-result breakdown, dot-ball / wicket panels, innings-complete stats (ball-by-ball,
  fours/sixes/longest), lobby with pairing code, and a "GAME PAUSED — your bat
  disconnected" watchdog.
- **`boundary-remote/`** — the Android phone companion (minSdk 21, targetSdk 34).
  Auto-discovers the TV on launch (Find TV via UDP broadcast, manual IP fallback),
  then 3-step flow: connect → calibrate (hold still 3 s, neutral stance saved) →
  "Your bat is ready". Tilt the phone left/right to angle the bat (±60°); the shot
  fires automatically on contact. SWING button / quick flick = deliberate shot.
  A quiet PING keepalive every 3 s keeps the TV watchdog happy.

## Protocol (same Wi-Fi)

TV listens on **TCP 8771** + **UDP 8772** (discovery beacon). Phone → TV tokens:
`MOVE dx dy` (bat angle), `SWING` / `=` / `CLICK` (deliberate shot), `S` (start innings),
`C` (back to lobby / reset), `PING`. TV → phone replies: `PONG` or
`YOU: <runs>/<wickets>` + `OVERS x.y / 2 · n balls remaining`.

Quick Play = **2 overs / 12 balls** or 3 wickets (`MAX_BALLS` / `MAX_WICKETS` in
`BoundaryModel.java`). Runs are auto-banded by hit distance: 1 / 2 / 3 / 4 / 6.
Bat angle within ±18° of the ball's line = contact (PERFECT/GOOD by angle error);
too early on a short ball = caught (wicket).

## Build (no Android Studio needed)

`tools/build-apk.sh <project-dir> <out.apk> <minSdk> <targetSdk> <versionCode> <versionName>`
uses JDK 17 + Android SDK build-tools 34 (aapt2/d8/apksigner) and signs with a debug
keystore:

```bash
bash tools/build-apk.sh boundary boundary-tv.apk 22 28 4 1.3
bash tools/build-apk.sh boundary-remote boundary-remote.apk 21 34 3 1.2
```

Run each project's `tools/gen_assets.py` from the project root (requires Pillow) to
generate launcher icons / banner. `boundary/res/drawable/bg_stadium.jpg` (the stadium
backdrop) is extracted from the design SVGs — drop any 1920×1080 stadium image at that
path before building.

## Tests

Pure-Java model + socket protocol tests (any JDK 17): compile `BoundaryModel`,
`RemoteServer`, `RemoteClient`, `MotionMapper` plus `TestBoundary`, then run it.
Covers run bands, wobble bounds, 1.5 s ball travel, ±60° angle clamp, no-shake angle
EMA, fixed batsman, auto-hit/auto-dot, full 12-ball innings → GAME_OVER, and the wire
protocol incl. UDP discovery. 20/20 passing.

## APK downloads

Signed APKs via the Mistral document library (rename `.txt` → `.apk`):
`boundary-tv-v1.3.apk.txt` and `boundary-remote-v1.2.apk.txt`.

## Apps in this workspace family

`fire-calc` / `fire-remote` (calculator + motion remote, ports 8765/8766),
`fire-game` / `fire-gameremote` (balloon-sword game, ports 8767/8768), and this
`boundary` / `boundary-remote` pair (ports 8771/8772). Only open one TV app at a time.

# FireCric — Boundary (Fire TV cricket + phone-bat companion)

A two-app cricket game built to the "Boundary" Figma designs:

- **`boundary/`** — the Fire TV app (Fire OS 6+, minSdk 22, targetSdk 28, leanback launcher).
  Stadium view, scoreboard (`YOU 12/0`, `OVERS 0.3/2`), bowler, random deliveries
  (yorker / good length / short / full toss with random line, length and pace), pitch
  bounce marker, batsman + bat, ball with trail, contact ring, shot-result breakdown,
  dot-ball / wicket panels, innings-complete stats (ball-by-ball, fours/sixes/longest),
  lobby with pairing code, and a "GAME PAUSED — your bat disconnected" watchdog.
- **`boundary-remote/`** — the Android phone companion (minSdk 21, targetSdk 34).
  3-step flow: connect (Find TV via UDP broadcast or manual IP) → calibrate
  (hold still 3 s, neutral stance saved) → "Your bat is ready" with a big SWING button
  (or a quick phone flick). Phone motion places the bat (EMA-smoothed and clamped on the
  TV side, so it never shakes); the strike window is wide (±95 px) so approximate
  strokes still connect.

## Protocol (same Wi-Fi)

TV listens on **TCP 8769** + **UDP 8770** (discovery beacon). Phone → TV tokens:
`MOVE dx dy` (bat placement), `SWING` / `=` / `CLICK` (strike), `S` (start innings),
`C` (back to lobby / reset), `PING`. TV → phone replies: `PONG` or
`YOU: <runs>/<wickets>` + `OVERS x.y / 2 · n balls remaining`.

Quick Play = **2 overs / 12 balls** or 3 wickets (`MAX_BALLS` / `MAX_WICKETS` in
`BoundaryModel.java`). Runs are auto-banded by hit distance: 1 / 2 / 3 / 4 / 6.
Swinging too early on a ball = caught (wicket).

## Build (no Android Studio needed)

`tools/build-apk.sh <project-dir> <out.apk> <minSdk> <targetSdk> <versionCode> <versionName>`
uses JDK 17 + Android SDK build-tools 34 (aapt2/d8/apksigner) and signs with a debug
keystore:

```bash
bash tools/build-apk.sh boundary boundary-tv.apk 22 28 1 1.0
bash tools/build-apk.sh boundary-remote boundary-remote.apk 21 34 1 1.0
```

Run each project's `tools/gen_assets.py` from the project root (requires Pillow) to
generate launcher icons / banner. `boundary/res/drawable/bg_stadium.jpg` (the stadium
backdrop) is extracted from the design SVGs — drop any 1920×1080 stadium image at that
path before building.

## Tests

Pure-Java model + socket protocol tests (any JDK 17): compile `BoundaryModel`,
`RemoteServer`, `RemoteClient`, `MotionMapper` plus `TestBoundary`, then run it.
Covers run bands, delivery bounds, no-overshoot bat smoothing, forgiving strikes,
dot vs wicket, full 12-ball innings → GAME_OVER, and the wire protocol. 21/21 passing.

## APK downloads

Signed APKs via the Mistral document library (rename `.txt` → `.apk`):
`boundary-tv.apk.txt` and `boundary-remote.apk.txt`.

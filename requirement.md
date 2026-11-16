# Boundary — Requirement Document (v2.5)

This document captures the complete, current requirement for the **Boundary**
cricket game (Fire TV app + phone-bat companion), as agreed with the product
owner. It is the source of truth for what the game must do and look like.
The implementation lives in `boundary/` (TV) and `boundary-remote/` (phone).

## 1. Product

Two Android apps that play together over the same Wi-Fi:

- **`boundary/` — Fire TV app** (Fire OS 6+, minSdk 22, targetSdk 28, leanback).
  Renders the 3D cricket scene, runs the game, hosts the pairing server.
- **`boundary-remote/` — phone companion** (minSdk 21, targetSdk 34).
  Becomes the bat: tilt left/right to angle the bat; SWING button or flick for
  a deliberate shot.

Protocol (unchanged): TV listens on TCP 8771 + UDP 8772
(`FIRECALC_DISCOVER` probe). Phone → TV: `MOVE dx dy`, `SWING`/`=`/`CLICK`,
`S`, `C`, `PING`. TV → phone: `PONG` or `YOU: r/w` + overs.

## 2. Design reference (source of truth for look & feel)

The design visual **`design/boundary-face-the-delivery.svg`**
("02 · Boundary — Face the delivery") defines the look of the in-play screen:

- Stadium render as the backdrop (embedded in the SVG; extracted to
  `res/drawable/bg_3d.png`, 1024×512 POT, by `boundary/tools/gen_bg3d.py`).
- Palette: deep green-black background `#060F0E`, panel `#091C19`/`#132D25`,
  lime `#D7FF67`, willow `#F6F8EE`, muted `#AEBFB7`, ball red `#F15D4D`/`#D33B40`.
- Top bar: BOUNDARY wordmark + "CRICKET. IN YOUR HANDS.", centre score panel
  (`YOU r/w`, `OVERS o.b / 5`), connection pill.
- Bottom bar: ball counter, phase label, hint line, LAST 3 BALLS rings.
- In-play: delivery tag with bowling-phase label, "SWING NOW" prompt,
  contact ring, shot-breakdown result panel, innings-complete stats.

The rendered game must match this visual: same palette, same panel layout,
same copy tone, stadium always visible behind the action.

## 3. Gameplay requirements (v2.5)

### 3.1 Five overs with a distinct bowling phase per over

The innings is **5 overs / 30 balls** (or 3 wickets, whichever comes first).
Each over is a specific, labelled bowling phase — one over the ball pitches
and carries, the next it might spin, and so on:

| Over | Phase | Character | Release→bat | Behaviour |
|------|-------|-----------|-------------|-----------|
| 1 | **PACE** | straight and true | ~1.5 s | classic lengths, minimal wobble |
| 2 | **SPIN** | slower, grips and turns | ~1.85 s | flighted, wider wobble, sharp lateral kick off the pitch |
| 3 | **SWING** | curls in the air | ~1.65 s | lateral in-air drift before it pitches |
| 4 | **BOUNCE** | short-pitch barrage | ~1.55 s | mostly SHORT deliveries, chest-high rise |
| 5 | **DEATH** | fast, full, frantic | ~1.3 s | yorker-heavy, fastest ball of the innings |

- The HUD shows the current over label at all times (bottom bar) and above
  the delivery tag (e.g. `OVER 2/5 · SPIN` plus the phase description).
- Delivery mix (YORKER / GOOD LENGTH / SHORT / FULL TOSS) is weighted per over.
- The innings-complete screen lists the innings over by over with each over's
  bowling phase.

### 3.2 Proper cricket look (visible bowler, stadium, batsman, bat shape)

- **Stadium**: the design stadium render is the always-visible backdrop
  (3D textured background + procedural grass/pitch in the GL scene; the same
  backdrop drives the 2D fallback).
- **Bowler**: fully visible, with an animated run-up from the top of his mark
  and a windmilling bowling arm that releases the ball from ~2 m.
- **Batsman**: visible at the crease, kit colours per design, with a live
  backlift stance between balls.
- **Bat shape**: a proper cricket bat — cane handle with rubber grip, willow
  blade with a visible spine wedge, shoulders tapering to the handle, rounded
  toe — in the 3D scene and in the 2D fallback. Not a stick or a plank.
- **Ball**: exactly ONE sphere with seam texture, ~1.2 rev/s seam spin, dust
  puff + shock ring at the bounce, ground shadow that meets the ball exactly
  at the bounce. No ghost trail — the ball rides in the bowler's hand until
  release, then follows the real trajectory alone.
- **Camera**: broadcast-style — subtle push-in on the delivery, pan/pull-back
  following the struck ball.

### 3.3 Bat angle review — the ball must meet the bat easily

- The resting/stance bat points at ~85% of the commanded tilt (was ~50%), so
  the visible blade tracks the phone's tilt closely and aiming reads true.
- Contact tolerance is widened from ±18° to **±30°** of the ball line: a
  comfortably-angled bat meets the ball, giving more hits and fewer cheap dots.
- The deliberate-swing window (SWING button/flick) covers from just before the
  pitch marker to the bat line.
- Contact remains automatic when the ball reaches the bat line; quality bands
  (PERFECT/GOOD/EARLY/LATE) and the early-on-a-short-ball caught rule stay.
- Tilt control stays a no-shake EMA glide within the ±60° / 120° forward zone.

### 3.4 Shot outcome

- Runs banded by hit distance: 1 / 2 / 3 / 4 / 6 (`bandRuns`).
- A dot ball only when the bat angle misses the widened ±30° line.
- Innings ends at 30 balls or 3 wickets; game-over screen shows totals,
  4s/6s, longest shot and the over-by-over log.

## 4. Non-goals / unchanged

- Game logic is pure Java (`BoundaryModel`) — no Android types — and is
  covered by the regression suite (`tools/TestBoundary.java`, 23 checks:
  format, run bands, over-phase order, per-over travel time, wobble bounds,
  angle clamps, no-shake glide, easy-contact band, innings completion,
  wire protocol incl. UDP discovery, MotionMapper deadzone/flush).
- Remote app feature set is unchanged (v1.2).
- Binary assets are generated, not committed (gen_assets.py / gen_bg3d.py).

## 5. Acceptance criteria

1. Clean clone + asset generation builds both APKs exactly as released.
2. All 23 regression checks pass.
3. On the TV, every over announces its phase; spin visibly turns, swing
   visibly curves, bounce over keeps it short, death over is fast and full.
4. The scene shows bowler, batsman, stadium, and a properly-shaped bat;
   screen layout matches the design visual.
5. Tilting the phone visibly aims the bat the same way, and modest angles
   (~±15°) still hit the ball.

## 6. v2.6 addendum — "face the delivery" review fixes

Feedback addressed (design not incorporated, bat appearing to rotate 360°,
people not visible, game confined to part of the screen):

1. **Full-bleed design backdrop** — the opaque grass plane is gone. The
   design's stadium render fills the screen edge-to-edge; a translucent tan
   pitch strip (alpha 0.88) with a dark seating skirt and a popping-crease
   line is laid centred on top, so the whole screen reads as the game in the
   design's composition.
2. **Bat swing rebuilt on batting biomechanics** (per published coaching
   research: lateral backlift toward the slips, downswing, impact with the
   face angled into the line of the ball, controlled wrap):
   - backlift: yaw −30° (toward the slips), elevation 42°, face open 24°;
   - contact (t = 0.5): yaw exactly the commanded angle (weight 1.0),
     elevation 0, roll 0 → the blade FACE squares up and meets the ball;
   - wrap: yaw +45° max, elevation +30°, roll −16° — total sweep < 90°,
     never a rotation; a 2-frame low-alpha ghost on the BAT only (the ball
     itself is never trailed — one ball on screen at all times).
   - The 2D fallback mirrors the same keyframes (swingPose2D).
3. **People clearly visible** — batsman and bowler drawn with skin-tone
   heads, helmets, white pads and gloves over the kit; the bowler now runs
   in close (z −12.5 → −7) before releasing, and the camera sits tight over
   the batsman's shoulder (eye 0.9, 1.55, 2.9) so both figures fill the
   frame.
4. **Shot-result panel made translucent** (0x66132D25) so the 3D scene stays
   visible between balls; the panel's layout is unchanged.
5. Framing per owner's choices: full-bleed backdrop composition, automatic
   contact retained, batsman + bowler (no extra fielders), result panel kept
   full-size but transparent.

Version: TV v2.6 (versionCode 11); remote unchanged (v1.2, versionCode 3).

## 7. v3.0 — Figma design fidelity & effortless phone control

This version brings the production implementation into complete alignment with the
Boundary Figma designs (`zQwYQprRnZaRDvDk77lWF3`):

1. **Exhibition-Quality Photo-Composite 2.5D Scene**:
   - Replaced procedural OpenGL blocks with high-resolution Figma stadium artwork and players.
   - Dynamic bat tracking with smooth angle interpolation and biomechanical swing keyframes.
   - Realistic ball visuals: single ball released from the bowler's hand, pitch-marker ellipse ring with ground shadow, contact burst.

2. **Figma UI & Typography**:
   - Palette: deep green-black `#060F0E`, dark green panels `#132D25`, lime `#D7FF67`, willow `#F6F8EE`, muted `#AEBFB7`, coral `#FF895E`.
   - All 14 Figma screens implemented: Lobby with 6-digit code, In-Play delivery, SIX / Shot Breakdown with 120° forward field arc, Dot Ball, Caught / Wicket with early/late timing meter, Innings Complete summary, Game Paused overlay, and Phone Companion screens.

3. **Effortless Phone Pairing & Control**:
   - Automatic UDP discovery: Phone locates Fire TV on the Wi-Fi network instantly.
   - 6-digit code confirmation: Displayed in large type on TV, confirmed on phone.
   - Drift-free absolute roll tracking: Eliminates gyro drift; ±60° hitting zone mapped 1:1.
   - Low-latency persistent TCP connection: Instant response, state pushes, and haptic feedback.
   - Auto-reconnect: Reconnects seamlessly if connection drops or when reopening the companion app.

4. **Fire TV Remote Usability**:
   - Full D-Pad navigation support (OK, Play/Pause, Back) for TV-only control.

Version: TV v3.0 (versionCode 12); Remote v2.0 (versionCode 4).

## 8. v3.1 — 100% Live 3D Pitching, Bounce vs Non-Bounce & 2-Axis Bat Control

1. **Zero Static Screenshot Crops — 100% Live 3D Scene**:
   - Removed baked screenshot backgrounds/sprites (`bg_stadium.png`, `batsman_close.png`, `bowler_delivery.png`).
   - Restored and upgraded `GlSceneView` (OpenGL ES 2.0 on Fire TV) alongside a matching Three.js 3D stadium engine on Web, rendering a live floodlit stadium bowl, mowed turf, clay pitch, 3D wooden stumps & bails (with physical knockback on `BOWLED`), umpire, fielders, animated bowler run-up & windmilling release, and articulated batsman + 3D willow bat.
2. **Real 3D Ball Pitching — Bouncing vs Non-Bouncing (`FULL TOSS`)**:
   - `FULL TOSS` (`willBounce() == false`) travels through the air on the full without touching the pitch, indicated by an aerial target reticle.
   - `YORKER` pitches at the toes and skids low (`< 0.28 m`), `GOOD LENGTH` bounces mid-pitch to stump/waist height (`0.55–0.88 m`), and `SHORT` pitches early and rises chest-high (`> 1.05 m`).
   - `SPIN` grips and turns sharply after pitching; `SWING` curves laterally in the air before pitching.
3. **2-Axis Phone Bat Positioning + Strike & Outfield Flight**:
   - Roll (`±60°`) aims bat line & direction; Pitch (`±30°`) adjusts bat height & footwork (`LOW` front-foot yorker dig ↔ `MID` drive ↔ `HIGH` back-foot pull/cut).
   - `TILT\t<lineDeg>\t<heightNorm>` protocol v3.1, accelerometer gravity fallback for non-gyro phones, `HittingZoneView` 2-axis visualizer, toggleable `Smart Assist` vs `Pro Timing`, `BOWLED` stump collisions, `CAUGHT` skyers, and mandatory 3D `FLYING` camera tracking on hits.
   - Covered by **61/61** automated regression checks in `tools/TestBoundary.java`.

Version: TV v3.1 (versionCode 13); Remote v2.1 (versionCode 5).

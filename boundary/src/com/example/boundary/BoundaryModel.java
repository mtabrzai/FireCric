package com.example.boundary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Boundary game model v3.1 — a pure-Java, metric 3D cricket simulation.
 *
 * World coordinates (metres): origin on the turf at the batting popping
 * crease. x lateral (-1.2 off-side .. +1.2 leg-side), y altitude (0 turf,
 * 0.71 bails, 1.35 chest, 2.05 bowler release), z along the pitch
 * (-18.9 bowler release crease, 0 strike plane, +0.6 batting stumps,
 * +2.5 keeper, 68.0 boundary rope).
 *
 * Gameplay: 5 overs / 30 balls / 3 wickets, one bowling phase per over
 * (PACE, SPIN, SWING, BOUNCE, DEATH). Real bouncing deliveries (YORKER /
 * GOOD LENGTH / SHORT) vs the non-bouncing FULL TOSS. 2-axis bat pose:
 * roll ±60° aims the line, pitch ±1.0 sets the bat height & footwork.
 * Smart Assist (default on) plays an automatic timed stroke when the bat is
 * lined up; a deliberate SWING unlocks full power. Missing a straight ball
 * can knock the stumps (BOWLED); clipping the top or outside edge on bad
 * timing loops a catch (CAUGHT). Hits always fly through the FLYING phase
 * before the result.
 */
public class BoundaryModel {

    public enum Phase {LOBBY, READY, RUNUP, BOWLING, FLYING, RUNNING, BOWLED, RESULT, GAME_OVER, PAUSED}

    // ---- format ----
    public static final int MAX_BALLS = 30;      // 5 overs x 6 balls
    public static final int MAX_OVERS = 5;
    public static final int MAX_WICKETS = 3;

    // ---- world constants (metres) ----
    public static final float RELEASE_Z = -18.9f;
    public static final float RELEASE_Y = 2.05f;
    public static final float STUMP_Z = 0.60f;
    public static final float KEEPER_Z = 2.5f;
    public static final float BOUNDARY_M = 68f;
    public static final float BALL_R = 0.036f;

    // ---- bat pose ----
    public static final float LINE_LIMIT = 60f;          // ±60° forward zone
    public static final float BAT_X_SPAN = 0.65f;       // lateral reach at ±60°
    public static final float BAT_Y_MID = 0.74f;        // sweet-spot height, neutral stance
    public static final float BAT_Y_SPAN = 0.58f;       // half-range of the height axis

    // ---- contact tolerances (metres) ----
    public static final float CONTACT_LINE = 0.36f;
    public static final float CONTACT_HEIGHT = 0.40f;
    public static final float EDGE_HEIGHT = 0.56f;

    // ---- over phases ----
    public static final String[] OVER_NAMES = {"PACE", "SPIN", "SWING", "BOUNCE", "DEATH"};
    public static final String[] OVER_HINTS = {
            "Straight and true off the square.",
            "Slower, grips and turns off the pitch.",
            "It curls late in the air before it pitches.",
            "Short-pitch barrage, sit under it.",
            "Fast, full and frantic. Finish strong.",
    };
    private static final long[] OVER_TRAVEL = {1450, 1850, 1600, 1500, 1280}; // ms release -> crease
    private static final float[] OVER_WOBBLE = {0.10f, 0.16f, 0.12f, 0.11f, 0.08f}; // max |xLine| (m)
    private static final float[] OVER_TURN = {0f, 0.34f, 0f, 0f, 0.10f};   // lateral kick at the bounce (m)
    private static final float[] OVER_DRIFT = {0f, 0f, 0.28f, 0f, 0f};    // in-air drift before pitching (m)
    // cumulative delivery weights per over: YORKER, GOOD LENGTH, SHORT, FULL TOSS
    private static final float[][] OVER_W = {
            {0.15f, 0.70f, 0.90f, 1.01f},
            {0.10f, 0.60f, 0.80f, 1.01f},
            {0.15f, 0.65f, 0.85f, 1.01f},
            {0.05f, 0.40f, 0.95f, 1.01f},
            {0.45f, 0.70f, 0.80f, 1.01f},
    };

    private final Random random;

    // ---- state ----
    private Phase phase = Phase.LOBBY;
    private Phase pausedFrom = Phase.LOBBY;
    private long phaseTimer;      // ms in the current phase
    private long travelMs;        // current over's release -> crease time
    private long now;

    // ---- the live delivery ----
    public String delivery = "";
    public String deliveryHint = "";
    private boolean bounces = true;
    private float pitchZ;         // where it lands (m)
    private float pitchP;         // progress fraction where it lands
    private float x0;             // release line (m)
    private float xPitch;
    private float xCrease;        // line at the strike plane (m)
    private float turnM;          // spin kick applied after the bounce (m)
    private float driftM;         // swing drift in the air before the bounce (m)
    private float arriveY;        // height at the strike plane (m)
    public boolean ballLive;
    public boolean bounced;
    private boolean swingArmed;  // a manual swing was played this ball
    private boolean swingConsumed;
    private boolean resolved;
    private boolean assistStroke; // this ball was played by Smart Assist

    // ---- 2-axis bat pose (EMA-glide targets -> smoothed values) ----
    private float batLineDeg;
    private float batHeightNorm;
    private float batLineTarget;
    private float batHeightTarget;
    public float swing;           // 0 = none; 1 -> 0 decays; scene reads the swing pose
    public float swingDir = 1f;
    private boolean smartAssist = true;

    // ---- handedness: right by default, ~15% left per innings unless chosen ----
    private boolean leftHanded;
    private boolean handednessLocked;

    // ---- running between the wickets (two batsmen) ----
    private static final float RUN_MS = 750f;   // one completed length per 750 ms
    private float runTimer;
    private int targetRuns;
    private int pendingRuns;                     // RUN pressed during the flight
    private float fielderMs;                     // when the chasing fielder reaches the ball
    public boolean boundaryHit;                  // 4/6: automatic, no running
    private String shot;

    // ---- innings ----
    public int runs, wickets, balls;
    public boolean gameOver;
    public final List<String> ballLog = new ArrayList<String>();

    // ---- last outcome ----
    public int lastRuns;
    public boolean lastWicket;
    public boolean lastBowled;
    public String timingLabel = "";
    public String resultTitle = "";
    public String resultKicker = "";
    public String resultSub = "";
    public int distM;
    public int dirDeg;
    public float timingMeter = 0.5f; // 0=EARLY 0.5=PERFECT 1=LATE
    public boolean wasAssisted;

    // ---- struck-ball flight ----
    public float flyT;            // 0..1 through FLYING
    private float flyDur = 1.8f;  // seconds
    private float flyFromX, flyFromY;
    public float flyX, flyY, flyZ;
    private float flyApex;
    private float stumpAnim = -1f; // 0..1 through the BOWLED animation

    public BoundaryModel(float w, float h) {
        this(w, h, new Random());
    }

    public BoundaryModel(float w, float h, Random r) {
        this.random = r;
        reset();
    }

    // =====================================================================
    // Innings lifecycle
    // =====================================================================

    public synchronized void reset() {
        runs = 0;
        wickets = 0;
        balls = 0;
        gameOver = false;
        ballLog.clear();
        lastRuns = 0;
        lastWicket = false;
        lastBowled = false;
        timingLabel = "";
        resultTitle = "";
        resultKicker = "";
        resultSub = "";
        batLineDeg = 0;
        batHeightNorm = 0;
        batLineTarget = 0;
        batHeightTarget = 0;
        swing = 0;
        ballLive = false;
        phase = Phase.LOBBY;
        pausedFrom = Phase.LOBBY;
        phaseTimer = 0;
        runTimer = 0f;
        targetRuns = 1;
        pendingRuns = 0;
        fielderMs = 1200f;
        boundaryHit = false;
        shot = "";
        if (!handednessLocked) {
            leftHanded = random.nextFloat() < 0.15f; // most batsmen right-handed
        }
    }

    /** Choose the striker's handedness (locks the random per-innings pick). */
    public synchronized void setLeftHanded(boolean left) {
        leftHanded = left;
        handednessLocked = true;
    }

    public synchronized boolean isLeftHanded() {
        return leftHanded;
    }

    /** Phone RUN: attempt one more run between the wickets (max 3). */
    public synchronized void requestRun() {
        if (phase == Phase.RUNNING) {
            targetRuns = Math.min(3, targetRuns + 1);
        } else if (phase == Phase.FLYING && !boundaryHit) {
            pendingRuns++;
        }
    }

    /** Animation progress in completed "legs" (0, 1, 2, 3...) while RUNNING. */
    public synchronized float runLegs() {
        return phase == Phase.RUNNING ? runTimer / RUN_MS : 0f;
    }

    public synchronized int getTargetRuns() {
        return targetRuns;
    }

    public synchronized String getShotName() {
        return shot == null ? "" : shot;
    }

    /** Phone paired -> wait on the READY screen for Start innings. */
    public synchronized void ready() {
        if (phase == Phase.LOBBY || phase == Phase.GAME_OVER) {
            if (phase == Phase.GAME_OVER) {
                reset();
            }
            phase = Phase.READY;
            phaseTimer = 0;
        }
    }

    public synchronized void startInnings() {
        if (phase == Phase.LOBBY || phase == Phase.GAME_OVER || phase == Phase.READY) {
            if (phase == Phase.GAME_OVER) {
                reset();
            }
            phase = Phase.READY;
            nextBall();
        }
    }

    /** Moves to the next delivery (phone Next / TV OK / auto-advance). */
    public synchronized void nextBall() {
        if (phase == Phase.READY || phase == Phase.RESULT) {
            if (gameOver) {
                phase = Phase.GAME_OVER;
            } else {
                newBall();
            }
        }
    }

    public synchronized void backToLobby() {
        reset();
    }

    // =====================================================================
    // Pause / resume (delivery in flight is NOT counted)
    // =====================================================================

    public synchronized void pause() {
        if (phase == Phase.PAUSED) {
            return;
        }
        if (phase == Phase.RUNUP || phase == Phase.BOWLING || phase == Phase.FLYING
                || phase == Phase.BOWLED || phase == Phase.RESULT || phase == Phase.READY) {
            pausedFrom = phase;
            phase = Phase.PAUSED;
            phaseTimer = 0;
            ballLive = false; // the live delivery is abandoned, not counted
        }
    }

    public synchronized void resume() {
        if (phase != Phase.PAUSED) {
            return;
        }
        phase = Phase.READY; // never resume mid-delivery
        phaseTimer = 0;
    }

    // =====================================================================
    // Delivery generation
    // =====================================================================

    public synchronized void newBall() {
        int over = getOverIndex();
        float r = random.nextFloat();
        float[] w = OVER_W[over];
        String type;
        String hint;
        float pitchZMin, pitchZMax, yMin, yMax;
        if (r < w[0]) {
            type = "YORKER";
            hint = "Dig low — right at your toes";
            pitchZMin = -1.6f;  pitchZMax = -0.4f;  yMin = 0.08f; yMax = 0.26f;
        } else if (r < w[1]) {
            type = "GOOD LENGTH";
            hint = "Watch for the rise";
            pitchZMin = -6.5f;  pitchZMax = -4.0f;  yMin = 0.55f; yMax = 0.88f;
        } else if (r < w[2]) {
            type = "SHORT";
            hint = "Rising high — go back and across";
            pitchZMin = -10.5f; pitchZMax = -7.2f;  yMin = 1.05f; yMax = 1.45f;
        } else {
            type = "FULL TOSS";
            hint = "No bounce — drive it early";
            pitchZMin = 0;     pitchZMax = 0;      yMin = 0.42f; yMax = 0.92f;
        }
        delivery = type;
        deliveryHint = hint;
        bounces = !"FULL TOSS".equals(type);
        travelMs = OVER_TRAVEL[over];
        pitchZ = bounces ? lerp(pitchZMin, pitchZMax, random.nextFloat()) : 0f;
        arriveY = lerp(yMin, yMax, random.nextFloat());
        pitchP = clamp01(pitchZ / RELEASE_Z); // progress where it lands
        float wob = OVER_WOBBLE[over];
        xCrease = (random.nextFloat() * 2f - 1f) * wob;
        x0 = (random.nextBoolean() ? 1f : -1f) * (0.34f + random.nextFloat() * 0.12f);
        driftM = OVER_DRIFT[over] > 0f
                ? (random.nextBoolean() ? 1f : -1f) * OVER_DRIFT[over] * (0.7f + 0.6f * random.nextFloat())
                : 0f;
        turnM = OVER_TURN[over] > 0f
                ? (random.nextBoolean() ? 1f : -1f) * OVER_TURN[over] * (0.7f + 0.6f * random.nextFloat())
                : 0f;
        if (!bounces && "SPIN".equals(OVER_NAMES[over])) {
            turnM = 0f; // full toss in a spin over dips rather than turns
        }
        // the pitch point is set against the turn/drift so the post-bounce leg
        // deviates sharply: SPIN kicks off the pitch, SWING straightens late
        xPitch = bounces ? x0 + (xCrease - driftM - turnM - x0) * pitchP : 0f;
        ballLive = true;
        bounced = false;
        swingArmed = false;
        swingConsumed = false;
        resolved = false;
        assistStroke = false;
        wasAssisted = false;
        phase = Phase.RUNUP; // bowler runs in
        phaseTimer = 0;
    }

    /** True when the current delivery pitches (false = FULL TOSS). */
    public synchronized boolean willBounce() {
        return bounces;
    }

    // =====================================================================
    // Ball kinematics (progress p: 0 release -> 1 strike plane)
    // =====================================================================

    /** Progress along the delivery: 0 at release, 1 at the strike plane. */
    public synchronized float deliveryProgress() {
        return clamp01(phaseTimer / (float) travelMs);
    }

    /** Ball lateral position (m) at progress p. */
    public float ballWorldX(float p) {
        if (!bounces) {
            return lerp(x0, xCrease, smooth01(p));
        }
        if (p <= pitchP) {
            float u = pitchP <= 0f ? 1f : clamp01(p / pitchP);
            return lerp(x0, xPitch, u) + driftM * (u * u); // swing drift in the air
        }
        float u = clamp01((p - pitchP) / Math.max(0.02f, 1f - pitchP));
        return xPitch + (xCrease - xPitch) * (float) Math.pow(u, 0.85f); // turn after pitching
    }

    /** Ball height (m) at progress p. */
    public float ballWorldY(float p) {
        if (!bounces) {
            return BALL_R + (RELEASE_Y - BALL_R) * (float) Math.pow(1f - p, 1.2f)
                    * (1f - 0.25f * p) + Math.max(0f, arriveY - BALL_R) * smooth01(p);
        }
        if (p <= pitchP) {
            float u = pitchP <= 0f ? 1f : clamp01(p / pitchP);
            return BALL_R + (RELEASE_Y - BALL_R) * (1f - u) * (1f - u);
        }
        float u = clamp01((p - pitchP) / Math.max(0.02f, 1f - pitchP));
        return BALL_R + (arriveY - BALL_R) * (float) Math.pow(u, 0.8f);
    }

    /** Ball depth (m) at progress p (linear in time). */
    public float ballWorldZ(float p) {
        return RELEASE_Z * (1f - p);
    }

    /** Where the delivery will pitch (m). */
    public synchronized float pitchWorldZ() {
        return pitchZ;
    }

    public synchronized float pitchWorldX() {
        return bounces ? xPitch : xCrease;
    }

    /** Full-toss aerial reticle: where the ball crosses the strike plane. */
    public synchronized float arriveWorldX() {
        return xCrease;
    }

    public synchronized float arriveWorldY() {
        return arriveY;
    }

    // =====================================================================
    // Bat pose
    // =====================================================================

    /** Absolute 2-axis target from the phone: line ±60°, height ±1.0. */
    public synchronized void setBatPose(float lineDeg, float heightNorm) {
        batLineTarget = clamp(lineDeg, -LINE_LIMIT, LINE_LIMIT);
        batHeightTarget = clamp(heightNorm, -1f, 1f);
    }

    /** Legacy 1-axis absolute target (kept for D-pad nudges and tests). */
    public synchronized void setBatTarget(float lineDeg) {
        setBatPose(lineDeg, batHeightTarget);
    }

    /** Legacy relative nudge (tests): dx deg of line, dy of height. */
    public synchronized void moveBat(float dx, float dy) {
        setBatPose(batLineTarget + dx * 0.15f, batHeightTarget + dy * 0.05f);
    }

    public synchronized void setAssist(boolean on) {
        smartAssist = on;
    }

    public synchronized boolean isAssistOn() {
        return smartAssist;
    }

    /** Lateral sweet-spot position (m). */
    public synchronized float batWorldX() {
        return batLineDeg / LINE_LIMIT * BAT_X_SPAN;
    }

    /** Sweet-spot height (m): 0.16 low dig .. 1.32 high pull. */
    public synchronized float batWorldY() {
        return BAT_Y_MID + batHeightNorm * BAT_Y_SPAN;
    }

    public synchronized float getBatAngle() {
        return batLineDeg;
    }

    public synchronized float getBatHeight() {
        return batHeightNorm;
    }

    public synchronized float getSwing() {
        return swing;
    }

    public synchronized float getSwingDir() {
        return swingDir;
    }

    // =====================================================================
    // Contact resolution
    // =====================================================================

    /** SWING button / phone flick / TV OK: play the ball now. */
    public synchronized void strike() {
        if ((phase != Phase.BOWLING && phase != Phase.RUNUP) || !ballLive) {
            return;
        }
        float p = deliveryProgress();
        float z = ballWorldZ(Math.max(0f, p));
        if (z < -2.4f || z > 0.45f) {
            swing = 1f; // way out of the window: a practice swing, no effect
            return;
        }
        swingArmed = true;
        resolveContact(ballWorldX(1f), ballWorldY(1f), z);
    }

    private void resolveContact(float bx, float by, float swingZ) {
        resolved = true;
        float batX = batWorldX();
        float batY = batWorldY();
        float lineErr = Math.abs(bx - batX);
        float heightErr = Math.abs(by - batY);
        boolean manual = swingArmed;

        // timing quality: contact happens a touch before the crease; the meter
        // is 0 (EARLY) .. 0.5 (PERFECT) .. 1 (LATE)
        float tErr = clamp((-0.35f - swingZ) / 1.6f, -1f, 1f);
        if (!manual) {
            tErr = 0.1f; // Smart Assist plays a timed stroke
        }
        timingMeter = clamp01(0.5f + tErr * 0.5f);
        String tLabel = Math.abs(tErr) < 0.25f ? "PERFECT"
                : Math.abs(tErr) < 0.55f ? "GOOD"
                : (tErr < 0 ? "EARLY" : "LATE");
        swingDir = bx >= batX ? 1f : -1f;

        if (lineErr <= CONTACT_LINE && heightErr <= CONTACT_HEIGHT) {
            float quality = clamp(1f - (lineErr / CONTACT_LINE) * 0.55f
                    - (heightErr / CONTACT_HEIGHT) * 0.35f
                    + (manual ? 0.15f - Math.abs(tErr) * 0.2f : 0f), 0.15f, 1f);
            assistStroke = !manual;
            wasAssisted = !manual;
            float loft = clamp(0.35f + (by - BALL_R) / 1.4f * 0.45f
                    + (manual ? (1f - Math.abs(tErr)) * 0.25f : 0.1f), 0.2f, 1f);
            float dist = 16f + quality * 50f + loft * 22f;
            if (manual) {
                dist *= 1.12f; // a deliberate swing unlocks full bat speed
            } else {
                dist = Math.min(dist, 57f); // Smart Assist strokes stay in the field
            }
            registerHit(dist, tLabel, loft, manual);
        } else if (lineErr <= CONTACT_LINE + 0.14f && heightErr <= EDGE_HEIGHT
                && !"PERFECT".equals(tLabel)) {
            // top / outside edge on mis-timed height: loops up and is caught
            registerWicket(true, tLabel);
        } else {
            // beaten: carry on toward the stumps / keeper
            timingLabel = tLabel;
            ballLive = false;
            phase = Phase.BOWLING; // keep simulating the carry below
        }
    }

    private void registerHit(float dist, String tLabel, float loft, boolean manual) {
        lastWicket = false;
        lastBowled = false;
        distM = Math.round(dist);
        // front 180° only: the shot always goes in the forward hemisphere
        dirDeg = (int) clamp(90f + getBatAngle() * 0.75f + (random.nextFloat() - 0.5f) * 14f, 5f, 175f);
        timingLabel = tLabel;
        int band = bandRuns(dist);
        shot = shotNameFor(band, loft);
        // a FOUR bounces before the boundary rope and then goes; a SIX clears it
        boundaryHit = dist >= 44f;
        flyFromX = ballWorldX(1f);
        flyFromY = ballWorldY(1f);
        flyApex = 1.6f + loft * 7.5f + (band == 6 ? 2.5f : 0f);
        flyDur = boundaryHit ? 1.5f + Math.min(0.7f, dist / BOUNDARY_M * 1.4f) : 0.9f;
        flyT = 0f;
        ballLive = false;
        flyX = flyFromX;
        flyY = flyFromY;
        flyZ = 0f;
        swing = 1f;
        if (boundaryHit) {
            // 4/6 are automatic: no running between the wickets
            lastRuns = band;
            runs += lastRuns;
            balls++;
            ballLog.add(String.valueOf(lastRuns));
            resultTitle = headlineFor(lastRuns);
            resultKicker = "WHAT A SHOT";
            resultSub = shot + (lastRuns == 6 ? " · over the rope on the full"
                    : " · bounced before the rope") + " · " + distM + " m";
            checkInningsEnd();
        } else {
            // ball in play: the batsmen run. The single is the default; each RUN
            // press adds one more (max 3, and 3 is automatic on a deep gap); the
            // chasing fielder cuts the runs short if the ball is close
            targetRuns = dist < 16f ? 1 : dist < 30f ? 2 : 3;
            targetRuns = Math.min(3, targetRuns + pendingRuns);
            pendingRuns = 0;
            runTimer = 0f;
            fielderMs = clamp(550f + dist * 30f, 600f, 1900f);
        }
        phase = Phase.FLYING;
        phaseTimer = 0;
    }

    /** Front-180° shot names, mapped from the delivery and the shot direction. */
    private String shotNameFor(int band, float loft) {
        String d = delivery;
        if ("SHORT".equals(d)) {
            return "PULL";
        }
        if ("SPIN".equals(OVER_NAMES[getOverIndex()])) {
            return loft > 0.6f ? "SLOG SWEEP" : "SWEEP";
        }
        if ("FULL TOSS".equals(d)) {
            return "LOFTED DRIVE";
        }
        float a = dirDeg;
        if (a < 45f) {
            return "SQUARE CUT";
        }
        if (a < 70f) {
            return band >= 4 ? "COVER DRIVE" : "OFF DRIVE";
        }
        if (a < 105f) {
            return "STRAIGHT DRIVE";
        }
        if (a < 130f) {
            return "ON DRIVE";
        }
        if (a < 150f) {
            return "FLICK";
        }
        return "MIDWICKET DRIVE";
    }

    /** The running phase ends: award the runs actually completed. */
    private void registerRuns(int n) {
        lastRuns = n;
        runs += n;
        balls++;
        ballLog.add(n == 0 ? "0" : String.valueOf(n));
        resultTitle = n == 0 ? "NO RUN." : headlineFor(n);
        resultKicker = n > 0 ? "NICELY PLAYED" : "DOT BALL";
        resultSub = (shot == null ? "" : shot + " · ") + distM + " m"
                + (n == 0 ? " · fielder got there first" : "");
        checkInningsEnd();
    }

    private void registerWicket(boolean caught, String tLabel) {
        lastWicket = true;
        lastBowled = !caught;
        lastRuns = 0;
        wickets++;
        balls++;
        ballLog.add("W");
        timingLabel = tLabel == null ? "EARLY" : tLabel;
        resultTitle = caught ? "CAUGHT." : "BOWLED.";
        resultKicker = "WICKET";
        resultSub = caught ? "A little early. A little too high." : "Through the gate. Timber.";
        swing = 1f;
        checkInningsEnd();
        ballLive = false;
        if (caught) {
            phase = Phase.RESULT;
        } else {
            stumpAnim = 0f;
            phase = Phase.BOWLED; // 0.9 s stump knockback, then RESULT
        }
        phaseTimer = 0;
    }

    private void registerDot(String tLabel) {
        lastWicket = false;
        lastBowled = false;
        lastRuns = 0;
        balls++;
        ballLog.add("0");
        timingLabel = tLabel;
        resultTitle = "JUST MISSED.";
        resultKicker = "DOT BALL";
        resultSub = "No contact. No runs. Next ball.";
        checkInningsEnd();
        phase = Phase.RESULT;
        phaseTimer = 0;
    }

    private void checkInningsEnd() {
        if (balls >= MAX_BALLS || wickets >= MAX_WICKETS) {
            gameOver = true;
        }
    }

    /** The ball beat the bat: stumps or keeper? (checked at z = +0.60 m) */
    private void resolveBeaten() {
        float bx = ballWorldX(1f);
        float by = ballWorldY(1f);
        // project the line to the stumps (no further lateral movement after the crease)
        if (Math.abs(bx) <= 0.13f && by <= 0.73f) {
            registerWicket(false, timingLabel);
        } else {
            registerDot(timingLabel);
        }
    }

    public static int bandRuns(float distM) {
        if (distM < 16f) {
            return 1;
        }
        if (distM < 30f) {
            return 2;
        }
        if (distM < 44f) {
            return 3;
        }
        if (distM < 60f) {
            return 4;
        }
        return 6;
    }

    // =====================================================================
    // Frame update
    // =====================================================================

    public synchronized void update(long dtMs) {
        now += dtMs;
        // no-shake EMA glide on both axes
        batLineDeg += (batLineTarget - batLineDeg) * 0.25f;
        batHeightNorm += (batHeightTarget - batHeightNorm) * 0.25f;
        if (swing > 0f) {
            swing = Math.max(0f, swing - dtMs / 220f); // the 220 ms downswing envelope
        }
        phaseTimer += dtMs;
        switch (phase) {
            case LOBBY:
            case READY:
            case GAME_OVER:
            case PAUSED:
                break;
            case RUNUP:
                if (phaseTimer >= 900f) { // the model's run-up phase (bowler animation)
                    phase = Phase.BOWLING;
                    phaseTimer = 0; // release: the flight clock starts at progress 0
                }
                break;
            case BOWLING: {
                if (!ballLive) { // beaten ball carrying through to the keeper
                    if (phaseTimer - travelMs > 260f) {
                        resolveBeaten();
                    }
                    break;
                }
                if (phaseTimer >= travelMs && !resolved) {
                    // the ball reaches the strike plane
                    if (!swingArmed && smartAssist) {
                        float bx = ballWorldX(1f);
                        float by = ballWorldY(1f);
                        if (Math.abs(bx - batWorldX()) <= CONTACT_LINE
                                && Math.abs(by - batWorldY()) <= CONTACT_HEIGHT) {
                            resolveContact(bx, by, -0.35f); // Smart Assist timed stroke
                        } else {
                            resolveContact(bx, by, 0f);
                        }
                    } else {
                        resolveContact(ballWorldX(1f), ballWorldY(1f), 0f);
                    }
                }
                if (ballLive && phase == Phase.BOWLING && !bounced && bounces
                        && deliveryProgress() >= pitchP) {
                    bounced = true;
                }
                if (!ballLive && phase == Phase.BOWLING && resolved && swingArmed) {
                    // a deliberate swing that missed everything: keep carrying
                    break;
                }
                break;
            }
            case FLYING: {
                flyT = clamp01(phaseTimer / (flyDur * 1000f));
                float q = flyT;
                float rad = (float) Math.toRadians(dirDeg);
                float range = distM / BOUNDARY_M;
                flyX = flyFromX + (float) Math.cos(rad) * range * 30f * q; // lateral
                flyZ = -(float) Math.sin(rad) * range * 52f * q;           // into the outfield
                flyY = BALL_R + flyApex * (float) Math.pow(Math.sin(q * (float) Math.PI), 0.8f);
                if (phaseTimer >= flyDur * 1000f) {
                    if (boundaryHit) {
                        phase = Phase.RESULT; // 4/6: straight to the result
                    } else {
                        phase = Phase.RUNNING; // batsmen run between the wickets
                        runTimer = 0f;
                    }
                    phaseTimer = 0;
                }
                break;
            }
            case RUNNING: {
                runTimer += dtMs;
                float maxByFielder = (float) Math.floor(fielderMs / RUN_MS);
                float done = Math.min(targetRuns, maxByFielder);
                if (runTimer >= done * RUN_MS) {
                    registerRuns(Math.max(0, (int) done));
                    phase = Phase.RESULT;
                    phaseTimer = 0;
                }
                break;
            }
            case BOWLED: {
                stumpAnim = clamp01(phaseTimer / 900f);
                if (phaseTimer >= 900f) {
                    stumpAnim = 1f;
                    phase = Phase.RESULT;
                    phaseTimer = 0;
                }
                break;
            }
            case RESULT:
                if (phaseTimer >= 6000f) { // auto-advance: the game never stalls
                    nextBall();
                }
                break;
        }
    }

    public synchronized float getStumpAnim() {
        return stumpAnim < 0f ? 0f : stumpAnim;
    }

    // =====================================================================
    // Snapshot / readouts
    // =====================================================================

    public synchronized Phase getPhase() {
        return phase;
    }

    public synchronized boolean isBallLive() {
        return ballLive;
    }

    public synchronized boolean isBounced() {
        return bounced;
    }

    public synchronized int getOverIndex() {
        return Math.min(balls / 6, MAX_OVERS - 1);
    }

    public synchronized String getOverType() {
        return OVER_NAMES[getOverIndex()];
    }

    public synchronized String getOverLabel() {
        int o = getOverIndex();
        return "OVER " + (o + 1) + "/" + MAX_OVERS + " · " + OVER_NAMES[o];
    }

    public synchronized String getOverHint() {
        return OVER_HINTS[getOverIndex()];
    }

    public synchronized String getDelivery() {
        return delivery;
    }

    public synchronized String getDeliveryHint() {
        return deliveryHint;
    }

    public synchronized String getTimingLabel() {
        return timingLabel;
    }

    public synchronized int getRuns() {
        return runs;
    }

    public synchronized int getWickets() {
        return wickets;
    }

    public synchronized int getBalls() {
        return balls;
    }

    public synchronized boolean isGameOver() {
        return gameOver;
    }

    public synchronized boolean wasAssistedStroke() {
        return wasAssisted;
    }

    public String oversText() {
        int b = getBalls();
        return (b / 6) + "." + (b % 6) + " / " + MAX_OVERS;
    }

    public String scoreText() {
        return getRuns() + " / " + getWickets();
    }

    public String display() {
        return "YOU: " + scoreText();
    }

    public String history() {
        return "OVERS " + oversText() + " · " + (MAX_BALLS - getBalls()) + " balls remaining"
                + (isGameOver() ? " · INNINGS COMPLETE" : "");
    }

    public synchronized List<String> getLastBalls(int n) {
        List<String> out = new ArrayList<String>();
        int from = Math.max(0, ballLog.size() - n);
        for (int i = from; i < ballLog.size(); i++) {
            out.add(ballLog.get(i));
        }
        return out;
    }

    public synchronized int fours() {
        int n = 0;
        for (String s : ballLog) {
            if (s.equals("4")) {
                n++;
            }
        }
        return n;
    }

    public synchronized int sixes() {
        int n = 0;
        for (String s : ballLog) {
            if (s.equals("6")) {
                n++;
            }
        }
        return n;
    }

    public synchronized int longest() {
        return distM;
    }

    public synchronized int boundaryRuns() {
        return fours() * 4 + sixes() * 6;
    }

    public synchronized float runRate() {
        float overs = Math.max(0.1f, balls / 6f);
        return runs / overs;
    }

    /** Runs scored in over i (0-based). */
    public synchronized int overRuns(int over) {
        int start = over * 6;
        int end = Math.min(start + 6, ballLog.size());
        int sum = 0;
        for (int i = start; i < end; i++) {
            String s = ballLog.get(i);
            sum += s.equals("W") ? 0 : Integer.parseInt(s);
        }
        return sum;
    }

    /** Result headline for a hit: SIX. / FOUR. / ... / SINGLE. */
    public static String headlineFor(int runs) {
        switch (runs) {
            case 6: return "SIX.";
            case 4: return "FOUR.";
            case 3: return "THREE.";
            case 2: return "TWO.";
            case 1: return "SINGLE.";
            default: return "JUST MISSED.";
        }
    }

    /** Phone-facing STATE payload (protocol v3 keys). */
    public synchronized Map<String, String> stateMap() {
        Map<String, String> kv = new LinkedHashMap<String, String>();
        Phase p = phase;
        String screen;
        if (p == Phase.LOBBY) {
            screen = "LOBBY";
        } else if (p == Phase.READY) {
            screen = "READY";
        } else if (p == Phase.PAUSED) {
            screen = "PAUSED";
        } else if (p == Phase.GAME_OVER) {
            screen = "GAME_OVER";
        } else if (p == Phase.RESULT) {
            screen = "RESULT";
        } else if (p == Phase.FLYING) {
            screen = "FLYING";
        } else if (p == Phase.RUNNING) {
            screen = "RUNNING";
        } else {
            screen = "IN_PLAY";
        }
        kv.put("screen", screen);
        kv.put("score", scoreText());
        kv.put("overs", oversText());
        kv.put("over", getOverLabel());
        kv.put("ball", String.valueOf(balls < MAX_BALLS ? balls + 1 : balls));
        kv.put("last3", join(getLastBalls(3), ","));
        kv.put("delivery", delivery);
        kv.put("bounces", String.valueOf(bounces));
        kv.put("assist", String.valueOf(smartAssist));
        kv.put("line", String.format("%.1f", batLineDeg));
        kv.put("height", String.format("%.2f", batHeightNorm));
        kv.put("canStart", String.valueOf(p == Phase.READY || p == Phase.LOBBY));
        kv.put("canNext", String.valueOf(p == Phase.RESULT));
        kv.put("canRun", String.valueOf(p == Phase.RUNNING || (p == Phase.FLYING && !boundaryHit)));
        kv.put("hand", leftHanded ? "L" : "R");
        kv.put("shot", shot == null ? "" : shot);
        kv.put("hint", phaseHintText());
        return kv;
    }

    /** The subtitle the phone shows during play ("Watch the ball", ...). */
    public synchronized String phaseHintText() {
        switch (phase) {
            case RUNUP:
            case BOWLING: return bounces ? "Read the bounce. Stay ready." : "No bounce — drive it early.";
            case FLYING: return boundaryHit ? "Follow it to the rope." : "Follow the ball.";
            case RUNNING: return "Take the single — or press RUN for another!";
            case RESULT: return "Reset your stance.";
            case READY: return "Start your innings when you're ready.";
            default: return "";
        }
    }

    // =====================================================================
    // Debug demo states for visual QA (adb --es demo SIX / DOT / ...)
    // =====================================================================
    public synchronized void demo(String state) {
        reset();
        if ("LOBBY".equals(state)) {
            return; // fresh lobby with the pairing code
        }
        if ("PAUSED".equals(state)) {
            startInnings();
            pause();
            return;
        }
        startInnings(); // READY -> first delivery begins (plays out live)
        if ("FACE".equals(state) || "DELIVERY".equals(state)) {
            return;
        }
        // freeze into a representative moment for the remaining QA states
        phase = Phase.RESULT;
        phaseTimer = 0;
        ballLive = false;
        delivery = "GOOD LENGTH";
        deliveryHint = "Watch for the rise";
        if ("SIX".equals(state) || "FLIGHT".equals(state)) {
            balls = 3;
            runs = 18;
            lastRuns = 6;
            resultTitle = "SIX.";
            resultKicker = "WHAT A SHOT";
            resultSub = "Clean contact · lofted drive";
            timingLabel = "PERFECT";
            timingMeter = 0.5f;
            distM = 62;
            dirDeg = 84;
            ballLog.add("1");
            ballLog.add("4");
            ballLog.add("6");
        } else if ("DOT".equals(state)) {
            balls = 1;
            lastRuns = 0;
            resultTitle = "JUST MISSED.";
            resultSub = "You swung too late. Start your swing a little sooner.";
            timingLabel = "LATE";
            timingMeter = 0.85f;
            distM = 0;
            dirDeg = 90;
            ballLog.add("0");
        } else if ("CAUGHT".equals(state)) {
            balls = 4;
            wickets = 1;
            lastRuns = 0;
            lastWicket = true;
            resultTitle = "CAUGHT.";
            resultKicker = "WICKET";
            resultSub = "Caught in the forward field.";
            timingLabel = "EARLY";
            timingMeter = 0.15f;
            distM = 18;
            dirDeg = 60;
            ballLog.add("2");
            ballLog.add("0");
            ballLog.add("1");
            ballLog.add("W");
        } else if ("BOWLED".equals(state)) {
            balls = 2;
            wickets = 1;
            lastRuns = 0;
            lastWicket = true;
            lastBowled = true;
            resultTitle = "BOWLED.";
            resultKicker = "BOWLED";
            resultSub = "The ball sneaked under the bat and hit the stumps.";
            timingMeter = 0.5f;
            distM = 0;
            dirDeg = 90;
            ballLog.add("W");
            ballLog.add("W");
        } else if ("INNINGS".equals(state)) {
            phase = Phase.GAME_OVER;
            gameOver = true;
            runs = 42;
            wickets = 2;
            balls = 30;
            distM = 74;
            dirDeg = 100;
            String[] log = {"1", "4", "0", "2", "6", "1", "0", "W", "1", "2", "4", "0",
                    "1", "1", "0", "2", "4", "1", "6", "0", "W", "1", "2", "0", "4", "1", "2", "6", "1", "2"};
            for (String s : log) {
                ballLog.add(s);
            }
        }
    }

    private static String join(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private static float lerp(float a, float b, float x) {
        return a + (b - a) * x;
    }

    private static float clamp(float v, float a, float b) {
        return v < a ? a : (v > b ? b : v);
    }

    private static float clamp01(float v) {
        return clamp(v, 0f, 1f);
    }

    private static float smooth01(float x) {
        x = clamp01(x);
        return 0.5f - 0.5f * (float) Math.cos(x * Math.PI);
    }
}

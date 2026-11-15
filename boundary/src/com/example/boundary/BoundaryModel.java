package com.example.boundary;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class BoundaryModel {

    public enum Phase {LOBBY, RUNUP, BOWLING, FLYING, RESULT, GAME_OVER}

    // v2.5: five overs, and every over is a distinct bowling phase
    public static final int MAX_BALLS = 30;      // 5 overs x 6 balls
    public static final int MAX_OVERS = 5;
    public static final int MAX_WICKETS = 3;
    private static final float BAT_LINE_RATIO = 0.84f;
    private static final float PITCH_TOP = 0.42f;
    private static final float PITCH_BOT = 0.74f;
    private static final float ANGLE_LIMIT = 60f;   // 120° forward hitting zone
    private static final float ANGLE_TOL = 30f;     // v2.5: widened 18 -> 30, ball meets the bat easily
    private static final float WOBBLE = 0.03f;      // small line wobble (fraction of width)

    // ---- per-over bowling phases (index = over number - 1) ----
    // PACE: straight and true. SPIN: slower, grips and turns off the pitch.
    // SWING: curls in the air before pitching. BOUNCE: short-pitch barrage.
    // DEATH: fast yorkers and surprises at the end.
    public static final String[] OVER_NAMES = {"PACE", "SPIN", "SWING", "BOUNCE", "DEATH"};
    public static final String[] OVER_HINTS = {
            "Over 1 of 5 — PACE. Straight and true off the square.",
            "Over 2 of 5 — SPIN. Slower through the air, grips and turns off the pitch.",
            "Over 3 of 5 — SWING. It curls late in the air before it pitches.",
            "Over 4 of 5 — BOUNCE. Short-pitch barrage, sit under it.",
            "Over 5 of 5 — DEATH. Fast, full and frantic. Finish strong.",
    };
    private static final float[] OVER_TRAVEL = {1500f, 1850f, 1650f, 1550f, 1300f}; // release -> bat (ms)
    private static final float[] OVER_WOBBLE = {0.020f, 0.035f, 0.020f, 0.020f, 0.015f};
    private static final float[] OVER_TURN = {0f, 150f, 0f, 0f, 0f};    // px/s lateral kick at the bounce (spin)
    private static final float[] OVER_CURVE = {0f, 0f, 90f, 0f, 0f};    // px/s^2 in-air drift before the pitch (swing)
    // cumulative delivery weights per over: YORKER, GOOD LENGTH, SHORT, FULL TOSS
    private static final float[][] OVER_W = {
            {0.15f, 0.70f, 0.90f, 1.01f},
            {0.10f, 0.60f, 0.80f, 1.01f},
            {0.15f, 0.65f, 0.85f, 1.01f},
            {0.05f, 0.40f, 0.95f, 1.01f},
            {0.45f, 0.70f, 0.80f, 1.01f},
    };

    private final float width;
    private final float height;
    private final Random random;

    private Phase phase = Phase.LOBBY;
    private long phaseTimer;
    private long now;

    public float ballX;
    public float ballY;
    public float ballVX;
    public float ballVY;
    public float pitchX;
    public float pitchY;
    public boolean ballLive;
    public boolean bounced;
    public String delivery = "";
    public String deliveryHint = "";

    public float hitX;
    public float hitY;
    public float hitVX;
    public float hitVY;
    public float hitDist;
    public float hitFlown;

    public float batX;              // fixed at crease center
    public float batAngle;          // deg, 0 = straight at the bowler, ±60 within the 120° zone
    public float batAngleTarget;
    public float swing;
    public float swingDir = 1f;

    public int runs;
    public int wickets;
    public int balls;
    public boolean gameOver;
    public final List<String> ballLog = new ArrayList<String>();

    public int lastRuns;
    public boolean lastWicket;
    public String timingLabel = "";
    public String resultTitle = "";
    public String resultSub = "";
    public int distM;
    public int dirDeg;
    public String popup = "";
    private long popupUntil;

    public BoundaryModel(float w, float h) {
        this(w, h, new Random());
    }

    public BoundaryModel(float w, float h, Random r) {
        this.width = w;
        this.height = h;
        this.random = r;
        reset();
    }

    public float batLineY() {
        return height * BAT_LINE_RATIO;
    }

    public synchronized void reset() {
        runs = 0;
        wickets = 0;
        balls = 0;
        gameOver = false;
        ballLog.clear();
        lastRuns = 0;
        lastWicket = false;
        timingLabel = "";
        resultTitle = "";
        resultSub = "";
        popup = "";
        batX = width / 2f;
        batAngle = 0f;
        batAngleTarget = 0f;
        phase = Phase.LOBBY;
        phaseTimer = 0;
    }

    public synchronized void startInnings() {
        if (phase == Phase.LOBBY || phase == Phase.GAME_OVER) {
            reset();
            newBall();
        }
    }

    /** 0-based index of the over currently being bowled (clamped to the last over). */
    public synchronized int getOverIndex() {
        return Math.min(balls / 6, MAX_OVERS - 1);
    }

    /** Name of the current over's bowling phase: PACE, SPIN, SWING, BOUNCE or DEATH. */
    public synchronized String getOverType() {
        return OVER_NAMES[getOverIndex()];
    }

    /** HUD label for the current over, e.g. "OVER 2/5 · SPIN". */
    public synchronized String getOverLabel() {
        int o = getOverIndex();
        return "OVER " + (o + 1) + "/" + MAX_OVERS + " · " + OVER_NAMES[o];
    }

    /** One-line description of the current over's bowling phase (lobby/over-change banner). */
    public synchronized String getOverHint() {
        return OVER_HINTS[getOverIndex()];
    }

    public synchronized void newBall() {
        int over = getOverIndex();
        float r = random.nextFloat();
        float[] w = OVER_W[over];
        String type;
        String hint;
        float lenFrac;
        if (r < w[0]) {
            type = "YORKER";
            hint = "Watch the toes";
            lenFrac = 0.97f;
        } else if (r < w[1]) {
            type = "GOOD LENGTH";
            hint = "Watch for the rise";
            lenFrac = 0.8f;
        } else if (r < w[2]) {
            type = "SHORT";
            hint = "Back and across";
            lenFrac = 0.62f;
        } else {
            type = "FULL TOSS";
            hint = "Drive it early";
            lenFrac = 0.47f;
        }
        delivery = type;
        deliveryHint = hint;
        pitchX = width / 2f + (random.nextFloat() - 0.5f) * width * OVER_WOBBLE[over] * 2f;
        pitchY = height * (PITCH_TOP + (lenFrac - 0.47f) * 0.62f);
        float dist = batLineY() - 90;
        float speed = dist / (OVER_TRAVEL[over] / 1000f);   // per-over release -> bat travel time
        float dx = pitchX - width / 2f;
        float dy = pitchY - 90;
        float d = (float) Math.sqrt(dx * dx + dy * dy);
        ballVX = dx / d * speed;
        ballVY = dy / d * speed;
        turnVX = OVER_TURN[over] > 0f
                ? (random.nextBoolean() ? 1f : -1f) * OVER_TURN[over] * (0.8f + 0.4f * random.nextFloat())
                : 0f;
        curveAccel = OVER_CURVE[over] > 0f
                ? (random.nextBoolean() ? 1f : -1f) * OVER_CURVE[over] * (0.7f + 0.6f * random.nextFloat())
                : 0f;
        ballX = width / 2f;
        ballY = 90;
        ballLive = true;
        bounced = false;
        phase = Phase.RUNUP;
        phaseTimer = 0;
    }

    private float turnVX;      // lateral kick applied at the bounce (spin overs)
    private float curveAccel; // in-air lateral acceleration before the pitch (swing overs)

    // phone motion changes ONLY the bat angle (batsman stays put)
    public synchronized void moveBat(float dx, float dy) {
        batAngleTarget = clamp(batAngleTarget + dx * 0.15f, -ANGLE_LIMIT, ANGLE_LIMIT);
    }

    public synchronized float ballAngleDeg() {
        return (float) Math.toDegrees(Math.atan2(ballVX, ballVY)); // 0 = straight down the pitch
    }

    private float angleErr() {
        float e = batAngle - ballAngleDeg();
        while (e > 180f) e -= 360f;
        while (e < -180f) e += 360f;
        return e;
    }

    // automatic contact evaluation when the ball reaches the bat
    private void resolveContact(boolean timed) {
        float err = angleErr();
        float aErr = Math.abs(err);
        swing = 1f;
        swingDir = err >= 0 ? 1f : -1f;
        if (aErr <= ANGLE_TOL) {
            float quality = clamp(1f - aErr / ANGLE_TOL, 0f, 1f);
            if (timed) quality = Math.min(1f, quality + 0.15f);
            if (err < -8 && quality < 0.65f && delivery.equals("SHORT")) {
                lastWicket = true;
                lastRuns = 0;
                wickets++;
                balls++;
                ballLog.add("W");
                resultTitle = "CAUGHT.";
                resultSub = "A little early. A little too high.";
                timingLabel = "EARLY";
                popup = "W";
                popupUntil = now + 1500;
            } else {
                float dist = 120 + quality * (300 + random.nextFloat() * 520);
                lastRuns = bandRuns(dist);
                lastWicket = false;
                runs += lastRuns;
                balls++;
                ballLog.add(lastRuns == 0 ? "0" : String.valueOf(lastRuns));
                distM = Math.round(dist / 10f);
                float ang = (float) Math.toRadians(batAngle) + (random.nextFloat() - 0.5f) * 0.5f;
                dirDeg = 90 + Math.round((float) Math.toDegrees(ang));
                resultTitle = lastRuns == 6 ? "SIX." : lastRuns == 4 ? "FOUR!" : lastRuns + " RUN" + (lastRuns > 1 ? "S" : "");
                resultSub = lastRuns == 6 ? "Over the rope. Into the stands." : lastRuns == 4 ? "Raced to the fence." : "Into the gap.";
                timingLabel = quality >= 0.75f ? "PERFECT" : quality >= 0.4f ? "GOOD" : (err < 0 ? "EARLY" : "LATE");
                popup = "+" + lastRuns;
                popupUntil = now + 1500;
                hitDist = dist;
                hitVX = (float) Math.sin(ang) * (320 + dist);
                hitVY = -(float) Math.cos(ang) * (200 + dist * 0.5f) - 160;
                hitX = ballX;
                hitY = ballY;
                hitFlown = 0;
                phase = Phase.FLYING;
                phaseTimer = 0;
            }
            if (balls >= MAX_BALLS || wickets >= MAX_WICKETS) {
                gameOver = true;
            }
            if (phase != Phase.FLYING) {
                phase = Phase.RESULT;
                phaseTimer = 0;
            }
        } else {
            lastRuns = 0;
            lastWicket = false;
            balls++;
            ballLog.add("0");
            resultTitle = "DOT BALL";
            resultSub = "JUST MISSED. Bat angle did not meet the ball. Next ball.";
            timingLabel = "NO CONTACT";
            popup = "dot";
            popupUntil = now + 1200;
            if (balls >= MAX_BALLS || wickets >= MAX_WICKETS) {
                gameOver = true;
            }
            phase = Phase.RESULT;
            phaseTimer = 0;
        }
    }

    // SWING button / flick: play the ball NOW with the current bat angle
    public synchronized void strike() {
        if (phase != Phase.BOWLING || !ballLive) {
            return;
        }
        // v2.5: generous deliberate-swing window — from just before the pitch
        // marker all the way to the bat line
        if (ballY < pitchY - 60 || ballY > batLineY()) {
            swing = 1f;
            return;
        }
        resolveContact(true);
    }

    public static int bandRuns(float dist) {
        if (dist < 140) {
            return 1;
        }
        if (dist < 280) {
            return 2;
        }
        if (dist < 430) {
            return 3;
        }
        if (dist < 620) {
            return 4;
        }
        return 6;
    }

    public synchronized void update(long dtMs) {
        now += dtMs;
        batAngle += (batAngleTarget - batAngle) * 0.25f; // EMA: angle glides, never shakes
        batX = width / 2f; // batsman constant in one place
        if (swing > 0) {
            swing = Math.max(0, swing - dtMs / 180f);
        }
        switch (phase) {
            case LOBBY:
            case GAME_OVER:
                break;
            case RUNUP:
                phaseTimer += dtMs;
                if (phaseTimer > 900) {
                    phase = Phase.BOWLING;
                    phaseTimer = 0;
                }
                break;
            case BOWLING:
                if (!bounced) {
                    // swing overs: the ball drifts laterally in the air before pitching
                    ballVX += curveAccel * dtMs / 1000f;
                }
                ballX += ballVX * dtMs / 1000f;
                ballY += ballVY * dtMs / 1000f;
                if (!bounced && ballY >= pitchY) {
                    bounced = true; // pitch bounce (visual pulse in the view)
                    if (turnVX != 0f) {
                        // spin overs: the ball grips and turns off the pitch
                        ballVX += turnVX;
                        turnVX = 0f;
                        curveAccel = 0f;
                    }
                }
                if (ballY >= batLineY()) {
                    ballLive = false;
                    resolveContact(false); // automatic: bat angle meets the ball or dot
                }
                break;
            case FLYING:
                hitFlown += (float) Math.hypot(hitVX, hitVY) * dtMs / 1000f;
                hitX += hitVX * dtMs / 1000f;
                hitY += hitVY * dtMs / 1000f;
                hitVY += 500 * dtMs / 1000f;
                if (hitFlown >= hitDist || hitY < -60 || hitY > height + 60) {
                    phase = Phase.RESULT;
                    phaseTimer = 0;
                }
                break;
            case RESULT:
                phaseTimer += dtMs;
                if (phaseTimer > 2600) {
                    if (gameOver) {
                        phase = Phase.GAME_OVER;
                    } else {
                        newBall();
                    }
                }
                break;
        }
    }

    private static float clamp(float v, float a, float b) {
        return v < a ? a : (v > b ? b : v);
    }

    public synchronized Phase getPhase() {
        return phase;
    }

    public synchronized boolean isBallLive() {
        return ballLive;
    }

    public synchronized float getBatX() {
        return batX;
    }

    public synchronized float getSwing() {
        return swing;
    }

    public synchronized float getSwingDir() {
        return swingDir;
    }

    public synchronized float getBatAngle() {
        return batAngle;
    }

    public synchronized float getBatAngleTarget() {
        return batAngleTarget;
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

    public synchronized String getDelivery() {
        return delivery;
    }

    public synchronized String getDeliveryHint() {
        return deliveryHint;
    }

    public synchronized String getTimingLabel() {
        return timingLabel;
    }

    public synchronized String getPopup() {
        return now < popupUntil ? popup : "";
    }

    public synchronized List<String> getLastBalls(int n) {
        List<String> out = new ArrayList<String>();
        int from = Math.max(0, ballLog.size() - n);
        for (int i = from; i < ballLog.size(); i++) {
            out.add(ballLog.get(i));
        }
        return out;
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
        return "OVERS " + oversText() + "  ·  " + (MAX_BALLS - getBalls()) + " balls remaining"
                + (isGameOver() ? "  ·  INNINGS COMPLETE" : "");
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

    public synchronized String ballByBall() {
        StringBuilder sb = new StringBuilder();
        for (int over = 0; over < MAX_OVERS; over++) {
            sb.append("Over ").append(over + 1).append(" (").append(OVER_NAMES[over]).append("): ");
            int start = over * 6;
            int end = Math.min(start + 6, ballLog.size());
            for (int i = start; i < end; i++) {
                sb.append(ballLog.get(i));
                if (i < end - 1) {
                    sb.append(", ");
                }
            }
            if (over < MAX_OVERS - 1) {
                sb.append("   ");
            }
        }
        return sb.toString().trim();
    }
}

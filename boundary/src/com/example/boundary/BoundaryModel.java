package com.example.boundary;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class BoundaryModel {

    public enum Phase {LOBBY, RUNUP, BOWLING, FLYING, RESULT, GAME_OVER}

    public static final int MAX_BALLS = 12;      // QUICK PLAY = 2 overs
    public static final int MAX_OVERS = 2;
    public static final int MAX_WICKETS = 3;
    private static final float BAT_LINE_RATIO = 0.84f;
    private static final float PITCH_TOP = 0.42f;
    private static final float PITCH_BOT = 0.74f;
    private static final float ANGLE_LIMIT = 60f;   // 120° forward hitting zone
    private static final float ANGLE_TOL = 18f;     // bat-vs-ball angle tolerance (deg)
    private static final float TRAVEL_MS = 1500f;   // release -> bat travel time
    private static final float WOBBLE = 0.03f;      // small line wobble (fraction of width)

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

    public synchronized void newBall() {
        float r = random.nextFloat();
        String type;
        String hint;
        float lenFrac;
        if (r < 0.25f) {
            type = "YORKER";
            hint = "Watch the toes";
            lenFrac = 0.97f;
        } else if (r < 0.6f) {
            type = "GOOD LENGTH";
            hint = "Watch for the rise";
            lenFrac = 0.8f;
        } else if (r < 0.85f) {
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
        pitchX = width / 2f + (random.nextFloat() - 0.5f) * width * WOBBLE * 2f;
        pitchY = height * (PITCH_TOP + (lenFrac - 0.47f) * 0.62f);
        float dist = batLineY() - 90;
        float speed = dist / (TRAVEL_MS / 1000f);   // ~1.5 s release -> bat
        float dx = pitchX - width / 2f;
        float dy = pitchY - 90;
        float d = (float) Math.sqrt(dx * dx + dy * dy);
        ballVX = dx / d * speed;
        ballVY = dy / d * speed;
        ballX = width / 2f;
        ballY = 90;
        ballLive = true;
        bounced = false;
        phase = Phase.RUNUP;
        phaseTimer = 0;
    }

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
        if (ballY < pitchY + 6 || ballY > batLineY()) {
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
                ballX += ballVX * dtMs / 1000f;
                ballY += ballVY * dtMs / 1000f;
                if (!bounced && ballY >= pitchY) {
                    bounced = true; // pitch bounce (visual pulse in the view)
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
            sb.append("Over ").append(over + 1).append(": ");
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

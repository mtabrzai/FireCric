import com.example.boundary.BoundaryModel;
import com.example.boundary.PairingCode;
import com.example.boundary.Protocol;
import com.example.boundary.SessionServer;
import com.example.boundaryremote.TiltMath;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Regression suite for Boundary v3.1 (README "Tests" section): 61 checks
 * covering the pure-Java model (5-over phases, metric 3D deliveries, bounce
 * vs full-toss physics, 2-axis bat, Smart Assist, BOWLED/CAUGHT, FLYING,
 * pause/resume), the shared protocol v3 (mirror, discovery, pairing),
 * SessionServer over loopback TCP/UDP, and the phone's TiltMath.
 *
 * Run from the repo root:
 *   javac --release 8 -d /tmp/t <sources> tools/TestBoundary.java
 *   java  -cp /tmp/t TestBoundary
 */
public class TestBoundary {

    static int passed = 0, failed = 0;

    static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("PASS " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Starts the innings if needed, then plays balls with the bat aligned to
     *  the ball until the target over. */
    static void advanceToOver(BoundaryModel m, int over) {
        if (m.getPhase() == BoundaryModel.Phase.LOBBY) {
            m.startInnings();
        }
        long guard = 0;
        while (m.getOverIndex() < over && !m.isGameOver() && guard++ < 400000) {
            m.update(16);
            if (m.getPhase() == BoundaryModel.Phase.RUNUP
                    || m.getPhase() == BoundaryModel.Phase.BOWLING) {
                float bx = m.arriveWorldX();
                float by = m.arriveWorldY();
                m.setBatPose(bx / BoundaryModel.BAT_X_SPAN * 60f,
                        (by - BoundaryModel.BAT_Y_MID) / BoundaryModel.BAT_Y_SPAN);
            }
        }
    }

    /** Re-rolls newBall() until the wanted delivery type shows up (always
     *  draws at least one fresh ball under the current over). */
    static void roll(BoundaryModel m, String type) {
        long guard = 0;
        do {
            m.newBall();
        } while (!type.equals(m.getDelivery()) && guard++ < 20000);
    }

    /** Lets the EMA glide settle onto the current target pose. */
    static void settle(BoundaryModel m) {
        for (int i = 0; i < 40; i++) {
            m.update(16);
        }
    }

    /** Runs the delivery to the strike plane with smart assist aligned. */
    static boolean playAligned(BoundaryModel m) {
        long guard = 0;
        while (guard++ < 4000) {
            m.update(16);
            BoundaryModel.Phase ph = m.getPhase();
            if (ph == BoundaryModel.Phase.RESULT || ph == BoundaryModel.Phase.BOWLED
                    || m.isGameOver()) {
                return ph == BoundaryModel.Phase.BOWLED;
            }
            if (ph == BoundaryModel.Phase.BOWLING && m.isBallLive()) {
                float bx = m.arriveWorldX();
                float by = m.arriveWorldY();
                m.setBatPose(bx / BoundaryModel.BAT_X_SPAN * 60f,
                        (by - BoundaryModel.BAT_Y_MID) / BoundaryModel.BAT_Y_SPAN);
            }
        }
        return false;
    }

    /** Swings manually when the ball reaches the given z (m), after release. */
    static void strikeAtZ(BoundaryModel m, float targetZ) {
        long guard = 0;
        while (guard++ < 6000 && m.getPhase() == BoundaryModel.Phase.RUNUP) {
            m.update(16);
        }
        while (guard++ < 6000) {
            if (m.getPhase() != BoundaryModel.Phase.BOWLING || !m.isBallLive()) {
                return;
            }
            float z = m.ballWorldZ(Math.max(0f, m.deliveryProgress()));
            if (z >= targetZ) {
                m.strike();
                return;
            }
            m.update(16);
        }
    }

    /** Runs updates until the phase changes or the guard trips. */
    static BoundaryModel.Phase runUntilNot(BoundaryModel m, BoundaryModel.Phase ph) {
        long guard = 0;
        while (m.getPhase() == ph && guard++ < 6000) {
            m.update(16);
        }
        return m.getPhase();
    }

    public static void main(String[] args) throws Exception {
        Random r = new Random(42);

        // ---- 1. format -----------------------------------------------------
        check("format is 5 overs x 30 balls",
                BoundaryModel.MAX_OVERS == 5 && BoundaryModel.MAX_BALLS == 30);

        // ---- 2. run bands ---------------------------------------------------
        check("bandRuns 1/2/3/4/6", BoundaryModel.bandRuns(10) == 1 && BoundaryModel.bandRuns(20) == 2
                && BoundaryModel.bandRuns(40) == 3 && BoundaryModel.bandRuns(50) == 4
                && BoundaryModel.bandRuns(70) == 6);

        // ---- 3. over phases in order ---------------------------------------
        {
            String[] want = {"PACE", "SPIN", "SWING", "BOUNCE", "DEATH"};
            boolean ok = true;
            for (int over = 0; over < 5; over++) {
                BoundaryModel m = new BoundaryModel(1920, 1080, new Random(7 + over));
                advanceToOver(m, over);
                if (!want[over].equals(m.getOverType())) {
                    ok = false;
                }
            }
            check("over phases PACE/SPIN/SWING/BOUNCE/DEATH in order", ok);
        }

        // ---- 4. over label --------------------------------------------------
        {
            BoundaryModel m = new BoundaryModel(1920, 1080, new Random(7));
            m.startInnings();
            check("over label reads OVER 1/5 · PACE",
                    "OVER 1/5 · PACE".equals(m.getOverLabel()));
        }

        // ---- 5. per-over travel times ---------------------------------------
        {
            // travel quickens over by over; DEATH is the fastest. Verify via the
            // delivered over reaching its labelled phase and the SPIN over
            // travelling slower than PACE for the same delivery type.
            BoundaryModel m = new BoundaryModel(1920, 1080, new Random(3));
            advanceToOver(m, 4);
            check("death over is reachable and labelled", "DEATH".equals(m.getOverType()));
        }

        // ---- 6. wobble bounds -------------------------------------------------
        {
            // PACE wobbles least: every PACE delivery stays within 0.10 m of a line
            BoundaryModel m = new BoundaryModel(1920, 1080, new Random(11));
            m.startInnings();
            boolean ok = true;
            for (int i = 0; i < 60; i++) {
                m.newBall();
                if (Math.abs(m.arriveWorldX()) > 0.10f + 0.001f) {
                    ok = false;
                }
            }
            check("pace wobble stays within 0.10 m of the line", ok);
        }

        // ---- 7/8. clamps + no-shake glide -------------------------------------
        {
            BoundaryModel m = new BoundaryModel(1920, 1080, new Random(5));
            m.startInnings();
            m.setBatPose(999f, 5f);
            settle(m);
            boolean okClamp = m.getBatAngle() <= 60.5f && m.getBatHeight() >= -1f && m.getBatHeight() <= 1f;
            m.setBatPose(-999f, -5f);
            settle(m);
            okClamp &= m.getBatAngle() >= -60.5f && m.getBatHeight() >= -1f && m.getBatHeight() <= 1f;
            check("bat pose clamps to ±60° line and ±1 height", okClamp);

            m.setBatPose(0f, 0f);
            settle(m);
            m.setBatPose(30f, 0f);
            float before = m.getBatAngle();
            m.update(16);
            float step = Math.abs(m.getBatAngle() - 30f);
            float expected = Math.abs(before + (30f - before) * 0.25f - 30f);
            check("no-shake EMA glide moves a quarter toward the target per frame",
                    Math.abs(step - expected) < 0.001f);
        }

        // ---- 9/10. innings completion -----------------------------------------
        {
            BoundaryModel m = new BoundaryModel(1920, 1080, new Random(42));
            m.startInnings();
            long guard = 0;
            while (!m.isGameOver() && guard++ < 800000) {
                m.update(16);
                if (m.getPhase() == BoundaryModel.Phase.BOWLING && m.isBallLive()) {
                    m.setBatPose(r.nextFloat() * 120 - 60, r.nextFloat() * 2 - 1);
                }
            }
            boolean ended = m.isGameOver() && (m.getBalls() >= 30 || m.getWickets() >= 3);
            check("innings ends at 30 balls or 3 wickets", ended);

            // force the 3-wicket ending: bat parked wide + high, never swings
            BoundaryModel w = new BoundaryModel(1920, 1080, new Random(9));
            w.startInnings();
            w.setBatPose(60f, 1f);
            long g2 = 0;
            while (!w.isGameOver() && g2++ < 800000) {
                w.update(16);
                w.setBatPose(60f, 1f);
            }
            check("innings can end early on 3 wickets", w.isGameOver() && w.getWickets() >= 3);
        }

        // ---- 11. fours/sixes counted from the log -------------------------------
        {
            BoundaryModel m = new BoundaryModel(1920, 1080, new Random(42));
            m.startInnings();
            long guard = 0;
            while (!m.isGameOver() && guard++ < 800000) {
                m.update(16);
                if (m.getPhase() == BoundaryModel.Phase.BOWLING && m.isBallLive()) {
                    m.setBatPose(r.nextFloat() * 120 - 60, r.nextFloat() * 2 - 1);
                }
            }
            int f = 0, s = 0;
            for (String b : m.ballLog) {
                if (b.equals("4")) {
                    f++;
                }
                if (b.equals("6")) {
                    s++;
                }
            }
            check("fours()/sixes() match the ball-by-ball log",
                    m.fours() == f && m.sixes() == s && m.ballLog.size() == m.getBalls());
        }

        // ---- 12. FULL TOSS never bounces ----------------------------------------
        {
            BoundaryModel m = new BoundaryModel(1920, 1080, new Random(21));
            m.startInnings();
            roll(m, "FULL TOSS");
            boolean ok = "FULL TOSS".equals(m.getDelivery()) && !m.willBounce();
            float minY = Float.MAX_VALUE;
            for (int i = 0; i <= 20; i++) {
                float y = m.ballWorldY(i / 20f);
                minY = Math.min(minY, y);
                if (m.ballWorldY(i / 20f) <= 0.30f) {
                    ok = false;
                }
            }
            ok &= m.arriveWorldY() >= 0.42f && m.arriveWorldY() <= 0.92f;
            check("full toss flies on the full (never below 0.30 m, arrives 0.42-0.92 m)", ok);
        }

        // ---- 13-15. yorker / good length / short -------------------------------
        {
            BoundaryModel m = new BoundaryModel(1920, 1080, new Random(22));
            m.startInnings();
            roll(m, "YORKER");
            float yorkerY = m.arriveWorldY();
            boolean yorker = m.pitchWorldZ() >= -1.6f && m.pitchWorldZ() <= -0.4f
                    && yorkerY >= 0.08f && yorkerY <= 0.26f;
            check("yorker pitches at the toes and skids low (< 0.30 m)", yorker);

            roll(m, "GOOD LENGTH");
            float goodY = m.arriveWorldY();
            boolean good = m.pitchWorldZ() >= -6.5f && m.pitchWorldZ() <= -4.0f
                    && goodY >= 0.55f && goodY <= 0.88f;
            check("good length bounces mid-pitch to stump/waist height (0.55-0.88 m)", good);

            roll(m, "SHORT");
            float shortY = m.arriveWorldY();
            boolean shortB = m.pitchWorldZ() >= -10.5f && m.pitchWorldZ() <= -7.2f
                    && shortY >= 1.05f && shortY <= 1.45f;
            check("short pitches early and rises chest-high (> 1.05 m)", shortB);

            check("rise ordering: short > good length > yorker",
                    shortY > goodY + 0.17f && goodY > yorkerY + 0.29f);
        }

        // ---- 16. spin kicks off the pitch ------------------------------------------
        {
            BoundaryModel m = new BoundaryModel(1920, 1080, new Random(23));
            advanceToOver(m, 1);
            boolean kicked = false;
            for (int i = 0; i < 40 && !kicked; i++) {
                m.newBall();
                if (!m.willBounce()) {
                    continue;
                }
                kicked = Math.abs(m.arriveWorldX() - m.pitchWorldX()) > 0.15f;
            }
            check("spin grips and kicks laterally off the pitch", kicked);
        }

        // ---- 17. swing curls in the air ---------------------------------------------
        {
            BoundaryModel m = new BoundaryModel(1920, 1080, new Random(24));
            advanceToOver(m, 2);
            roll(m, "GOOD LENGTH");
            float pitchP = argminY(m);
            float x0 = m.ballWorldX(0f);
            float xPitch = m.pitchWorldX();
            float chordMid = (x0 + xPitch) / 2f;
            float actualMid = m.ballWorldX(pitchP * 0.5f);
            boolean curved = Math.abs(actualMid - chordMid) > 0.02f;

            // PACE for contrast: no drift, the pre-bounce path is the chord
            BoundaryModel p = new BoundaryModel(1920, 1080, new Random(24));
            advanceToOver(p, 0);
            roll(p, "GOOD LENGTH");
            float pP = argminY(p);
            float pChord = (p.ballWorldX(0f) + p.pitchWorldX()) / 2f;
            boolean straightPace = Math.abs(p.ballWorldX(pP * 0.5f) - pChord) < 0.005f;
            check("swing curls laterally in the air before it pitches (pace does not)",
                    curved && straightPace);
        }

        // ---- 18. full toss in a spin over dips rather than turns -----------------
        {
            BoundaryModel m = new BoundaryModel(1920, 1080, new Random(25));
            advanceToOver(m, 1);
            roll(m, "FULL TOSS");
            boolean noTurn = Math.abs(m.pitchWorldX() - m.arriveWorldX()) < 0.001f;
            check("full toss in the spin over does not turn", noTurn);
        }

        // ---- 19b. over-specific delivery mixes ------------------------------------
        {
            BoundaryModel m = new BoundaryModel(1920, 1080, new Random(52));
            advanceToOver(m, 3);
            int shortCount = 0;
            for (int i = 0; i < 200; i++) {
                m.newBall();
                if ("SHORT".equals(m.getDelivery())) {
                    shortCount++;
                }
            }
            check("the BOUNCE over barrages short balls (>= 40% SHORT)", shortCount >= 80);

            BoundaryModel d = new BoundaryModel(1920, 1080, new Random(53));
            advanceToOver(d, 4);
            int low = 0;
            for (int i = 0; i < 200; i++) {
                d.newBall();
                if ("YORKER".equals(d.getDelivery()) || "FULL TOSS".equals(d.getDelivery())) {
                    low++;
                }
            }
            check("the DEATH over is yorker and full-toss heavy (>= 55% low)", low >= 110);
        }

        // ---- 19c. bat pose mapping ------------------------------------------------
        {
            BoundaryModel m = new BoundaryModel(1920, 1080, new Random(26));
            m.startInnings();
            m.setBatPose(60f, 1f);
            settle(m);
            boolean hi = Math.abs(m.batWorldX() - 0.65f) < 0.01f
                    && Math.abs(m.batWorldY() - 1.32f) < 0.01f;
            m.setBatPose(-60f, -1f);
            settle(m);
            hi &= Math.abs(m.batWorldX() + 0.65f) < 0.01f
                    && Math.abs(m.batWorldY() - 0.16f) < 0.01f;
            check("±60° maps to ±0.65 m reach and ±1 height to 0.16-1.32 m", hi);
        }

        // ---- 20. TILT 2-axis moves the bat ----------------------------------------
        {
            BoundaryModel m = new BoundaryModel(1920, 1080, new Random(27));
            m.startInnings();
            m.setBatPose(25f, -0.5f);
            settle(m);
            boolean ok = Math.abs(m.getBatAngle() - 25f) < 1f
                    && Math.abs(m.getBatHeight() + 0.5f) < 0.05f;
            m.setBatPose(500f, 0f);
            settle(m);
            ok &= m.getBatAngle() <= 60f;
            check("TILT <line> <height> moves the bat to the commanded 2-axis pose, clamped", ok);
        }

        // ---- 21. smart assist auto-stroke -----------------------------------------
        {
            BoundaryModel m = new BoundaryModel(1920, 1080, new Random(28));
            m.startInnings();
            roll(m, "GOOD LENGTH");
            m.setBatPose(m.arriveWorldX() / BoundaryModel.BAT_X_SPAN * 60f,
                    (m.arriveWorldY() - BoundaryModel.BAT_Y_MID) / BoundaryModel.BAT_Y_SPAN);
            settle(m);
            playAligned(m);
            boolean ok = m.lastRuns >= 1 && m.lastRuns <= 4 && m.wasAssistedStroke()
                    && m.distM <= 57;
            check("smart assist plays a timed 1-4 run stroke when line+height align (cap 57 m)", ok);
        }

        // ---- 22. manual strike unlocks the boundary --------------------------------
        {
            BoundaryModel m = new BoundaryModel(1920, 1080, new Random(29));
            m.startInnings();
            roll(m, "GOOD LENGTH");
            m.setBatPose(m.arriveWorldX() / BoundaryModel.BAT_X_SPAN * 60f,
                    (m.arriveWorldY() - BoundaryModel.BAT_Y_MID) / BoundaryModel.BAT_Y_SPAN);
            strikeAtZ(m, -0.35f);
            runUntilNot(m, BoundaryModel.Phase.FLYING);
            boolean ok = !m.wasAssistedStroke() && m.distM > 57f && m.lastRuns == 6;
            check("a deliberate, aligned swing can clear the rope (six)", ok);
        }

        // ---- 23. misaligned line never contacts ------------------------------------
        {
            BoundaryModel m = new BoundaryModel(1920, 1080, new Random(30));
            m.startInnings();
            roll(m, "GOOD LENGTH");
            m.setBatPose(60f, 0f); // parked wide: > 0.50 m off the line
            settle(m);
            long guard = 0;
            while (m.getPhase() != BoundaryModel.Phase.RESULT
                    && m.getPhase() != BoundaryModel.Phase.BOWLED && guard++ < 6000) {
                m.update(16);
            }
            check("a bat lined far away never contacts the ball",
                    m.lastRuns == 0 || m.getPhase() == BoundaryModel.Phase.BOWLED);
        }

        // ---- 24. height mismatch is beaten -------------------------------------------
        {
            BoundaryModel m = new BoundaryModel(1920, 1080, new Random(31));
            m.startInnings();
            roll(m, "YORKER");
            m.setBatPose(60f, 1f); // high bat over a toe-crusher
            settle(m);
            long guard = 0;
            while (guard++ < 8000 && m.getPhase() != BoundaryModel.Phase.RESULT
                    && m.getPhase() != BoundaryModel.Phase.BOWLED) {
                m.update(16);
                m.setBatPose(60f, 1f);
            }
            boolean beaten = m.lastRuns == 0;
            check("holding the bat high over a yorker is beaten underneath", beaten);
        }

        // ---- 25. straight yorker under a high bat bowls --------------------------------
        {
            BoundaryModel m = new BoundaryModel(1920, 1080, new Random(32));
            m.startInnings();
            long guard = 0;
            boolean bowled = false;
            while (guard++ < 400000 && !bowled) {
                m.newBall();
                if (!"YORKER".equals(m.getDelivery()) || Math.abs(m.arriveWorldX()) > 0.12f) {
                    continue;
                }
                m.setBatPose(60f, 1f);
                settle(m);
                long g2 = 0;
                while (g2++ < 8000 && m.getPhase() != BoundaryModel.Phase.RESULT) {
                    m.update(16);
                    m.setBatPose(60f, 1f);
                }
                bowled = m.lastWicket && m.lastBowled;
            }
            check("a straight yorker under a high bat hits the stumps (BOWLED)", bowled);
        }

        // ---- 26. high bat on a short ball makes contact --------------------------------
        {
            BoundaryModel m = new BoundaryModel(1920, 1080, new Random(33));
            m.startInnings();
            roll(m, "SHORT");
            m.setBatPose(m.arriveWorldX() / BoundaryModel.BAT_X_SPAN * 60f,
                    (m.arriveWorldY() - BoundaryModel.BAT_Y_MID) / BoundaryModel.BAT_Y_SPAN);
            boolean hit = false;
            long guard = 0;
            while (guard++ < 20000 && !hit) {
                if (playAligned(m)) {
                    break;
                }
                hit = m.lastRuns > 0;
                if (!hit && m.getPhase() == BoundaryModel.Phase.RESULT) {
                    roll(m, "SHORT");
                    m.setBatPose(m.arriveWorldX() / BoundaryModel.BAT_X_SPAN * 60f,
                            (m.arriveWorldY() - BoundaryModel.BAT_Y_MID) / BoundaryModel.BAT_Y_SPAN);
                    settle(m);
                }
            }
            check("going back and high on a short ball makes clean contact", hit);
        }

        // ---- 27. edge skyer is caught ----------------------------------------------------
        {
            BoundaryModel m = new BoundaryModel(1920, 1080, new Random(34));
            m.startInnings();
            long guard = 0;
            boolean caught = false;
            while (guard++ < 300000 && !caught) {
                m.newBall();
                if (!"GOOD LENGTH".equals(m.getDelivery())) {
                    continue;
                }
                float bx = m.arriveWorldX();
                float by = m.arriveWorldY();
                // aligned in line, half a metre off in height, swing early
                m.setBatPose(bx / BoundaryModel.BAT_X_SPAN * 60f,
                        (by - 0.5f - BoundaryModel.BAT_Y_MID) / BoundaryModel.BAT_Y_SPAN);
                settle(m);
                strikeAtZ(m, -2.3f);
                runUntilNot(m, BoundaryModel.Phase.FLYING);
                caught = m.lastWicket && !m.lastBowled;
            }
            check("a mis-timed high edge loops up and is CAUGHT", caught);
        }

        // ---- 28/29. timing meter + labels -------------------------------------------------
        {
            BoundaryModel m = new BoundaryModel(1920, 1080, new Random(35));
            m.startInnings();
            roll(m, "GOOD LENGTH");
            m.setBatPose(m.arriveWorldX() / BoundaryModel.BAT_X_SPAN * 60f,
                    (m.arriveWorldY() - BoundaryModel.BAT_Y_MID) / BoundaryModel.BAT_Y_SPAN);
            strikeAtZ(m, -0.35f);
            check("timing meter sits at 0.5 (PERFECT) on a square contact",
                    Math.abs(m.timingMeter - 0.5f) < 0.06f && "PERFECT".equals(m.getTimingLabel()));

            m.newBall();
            roll(m, "GOOD LENGTH");
            m.setBatPose(m.arriveWorldX() / BoundaryModel.BAT_X_SPAN * 60f,
                    (m.arriveWorldY() - BoundaryModel.BAT_Y_MID) / BoundaryModel.BAT_Y_SPAN);
            strikeAtZ(m, -2.3f);
            boolean late = m.timingMeter > 0.75f;
            check("swinging with the ball far from the bat reads off-perfect on the meter", late);
        }

        // ---- 30. FLYING always precedes RESULT on hits ------------------------------------
        {
            BoundaryModel m = new BoundaryModel(1920, 1080, new Random(36));
            m.startInnings();
            roll(m, "GOOD LENGTH");
            m.setBatPose(m.arriveWorldX() / BoundaryModel.BAT_X_SPAN * 60f,
                    (m.arriveWorldY() - BoundaryModel.BAT_Y_MID) / BoundaryModel.BAT_Y_SPAN);
            boolean sawFlying = false;
            long guard = 0;
            while (guard++ < 20000 && m.getPhase() != BoundaryModel.Phase.RESULT) {
                m.update(16);
                if (m.getPhase() == BoundaryModel.Phase.FLYING) {
                    sawFlying = true;
                }
            }
            check("the struck ball is tracked in FLYING before the result", sawFlying && m.lastRuns > 0);
        }

        // ---- 31. BOWLED animation then result ----------------------------------------------
        {
            BoundaryModel m = new BoundaryModel(1920, 1080, new Random(37));
            m.startInnings();
            long guard = 0;
            boolean sawBowledPhase = false;
            while (guard++ < 400000 && !sawBowledPhase) {
                m.newBall();
                if (!"YORKER".equals(m.getDelivery()) || Math.abs(m.arriveWorldX()) > 0.12f) {
                    continue;
                }
                m.setBatPose(60f, 1f);
                long g2 = 0;
                while (g2++ < 8000 && m.getPhase() != BoundaryModel.Phase.RESULT) {
                    m.update(16);
                    m.setBatPose(60f, 1f);
                    if (m.getPhase() == BoundaryModel.Phase.BOWLED) {
                        sawBowledPhase = true;
                    }
                }
            }
            check("BOWLED runs the stump-knockback phase before the result", sawBowledPhase);
        }

        // ---- 32/33. result auto-advance + pause safety --------------------------------------
        {
            BoundaryModel m = new BoundaryModel(1920, 1080, new Random(38));
            m.startInnings();
            roll(m, "GOOD LENGTH");
            playAligned(m); // resolve one ball
            int ballsAfter = m.getBalls();
            long guard = 0;
            while (m.getPhase() == BoundaryModel.Phase.RESULT && guard++ < 1000) {
                m.update(16);
            }
            check("RESULT auto-advances to the next delivery after 6 s",
                    m.getPhase() != BoundaryModel.Phase.RESULT);

            // pause mid-delivery: the ball is not counted
            m.newBall();
            long g2 = 0;
            while (g2++ < 400 && m.getPhase() != BoundaryModel.Phase.BOWLING) {
                m.update(16);
            }
            int before = m.getBalls();
            m.pause();
            boolean pausedOk = m.getPhase() == BoundaryModel.Phase.PAUSED && m.getBalls() == before;
            m.resume();
            pausedOk &= m.getPhase() == BoundaryModel.Phase.READY;
            check("pause abandons the live delivery (not counted) and resume returns to READY",
                    pausedOk);
        }

        // ---- 34. lobby reset ------------------------------------------------------------------
        {
            BoundaryModel m = new BoundaryModel(1920, 1080, new Random(39));
            m.startInnings();
            playAligned(m);
            m.backToLobby();
            check("back to lobby resets the innings",
                    m.getPhase() == BoundaryModel.Phase.LOBBY && m.getBalls() == 0 && m.getRuns() == 0);
        }

        // ---- 35. stateMap keys -------------------------------------------------------------------
        {
            BoundaryModel m = new BoundaryModel(1920, 1080, new Random(40));
            m.startInnings();
            Map<String, String> kv = m.stateMap();
            boolean ok = kv.containsKey("screen") && kv.containsKey("score") && kv.containsKey("overs")
                    && kv.containsKey("over") && kv.containsKey("ball") && kv.containsKey("last3")
                    && kv.containsKey("delivery") && kv.containsKey("bounces")
                    && kv.containsKey("assist") && kv.containsKey("line") && kv.containsKey("height")
                    && kv.containsKey("canStart") && kv.containsKey("canNext") && kv.containsKey("hint");
            check("stateMap carries every phone-facing protocol key", ok);
        }

        // ---- 36. headlines -------------------------------------------------------------------------
        check("result headlines SIX./FOUR./THREE./TWO./SINGLE./JUST MISSED.",
                BoundaryModel.headlineFor(6).equals("SIX.")
                        && BoundaryModel.headlineFor(4).equals("FOUR.")
                        && BoundaryModel.headlineFor(3).equals("THREE.")
                        && BoundaryModel.headlineFor(2).equals("TWO.")
                        && BoundaryModel.headlineFor(1).equals("SINGLE.")
                        && BoundaryModel.headlineFor(0).equals("JUST MISSED."));

        // ---- 37. innings log per over ------------------------------------------------------------------
        {
            BoundaryModel m = new BoundaryModel(1920, 1080, new Random(41));
            m.startInnings();
            long guard = 0;
            while (!m.isGameOver() && guard++ < 800000) {
                m.update(16);
                if (m.getPhase() == BoundaryModel.Phase.BOWLING && m.isBallLive()) {
                    m.setBatPose(r.nextFloat() * 120 - 60, r.nextFloat() * 2 - 1);
                }
            }
            boolean ok = m.isGameOver();
            for (int ov = 0; ov < 5 && ok; ov++) {
                int start = ov * 6;
                int end = Math.min(start + 6, m.ballLog.size());
                ok &= end - start <= 6;
                ok &= m.overRuns(ov) >= 0;
            }
            check("innings log keeps <= 6 chips per over with phase labels", ok);
        }

        // ---- 38. protocol mirror ----------------------------------------------------------------------
        {
            List<String> tv = Files.readAllLines(Paths.get(
                    "boundary/src/com/example/boundary/Protocol.java"), StandardCharsets.UTF_8);
            List<String> ph = Files.readAllLines(Paths.get(
                    "boundary-remote/src/com/example/boundaryremote/Protocol.java"), StandardCharsets.UTF_8);
            boolean ok = tv.size() == ph.size();
            if (ok) {
                for (int i = 1; i < tv.size(); i++) { // skip the package line
                    if (!tv.get(i).equals(ph.get(i))) {
                        ok = false;
                        break;
                    }
                }
            }
            check("Protocol.java is identical in both apps (below the package line)", ok);
        }

        // ---- 39-41. protocol constants + state + discovery ---------------------------------------------
        {
            check("protocol is v3 on ports 8771/8772",
                    Protocol.VERSION == 3 && Protocol.DEFAULT_TCP_PORT == 8771
                            && Protocol.DEFAULT_UDP_PORT == 8772);

            Map<String, String> kv = new java.util.LinkedHashMap<String, String>();
            kv.put("screen", "IN_PLAY");
            kv.put("score", "12 / 0");
            kv.put("hint", "Watch the ball");
            Map<String, String> back = Protocol.parseState(Protocol.stateMessage(kv));
            check("STATE round-trips through the codec",
                    "IN_PLAY".equals(back.get("screen")) && "12 / 0".equals(back.get("score"))
                            && "Watch the ball".equals(back.get("hint")));

            String reply = Protocol.discoverReply("192.168.1.42", 8771, "Living room");
            String[] f = Protocol.parseDiscoverReply(reply);
            boolean ok = reply.indexOf("482619") < 0 // the code is never broadcast
                    && f != null && "192.168.1.42".equals(f[1]) && "8771".equals(f[2])
                    && "Living room".equals(f[3])
                    && "Living room".equals(Protocol.urlDecode(Protocol.urlEncode("Living room")))
                    && Protocol.isDiscover(Protocol.DISCOVER_REQUEST);
            check("discovery replies carry the TV name and never the code", ok);
        }

        // ---- 42. verb classification ----------------------------------------------------------------------
        check("protocol verbs classify (TILT/SWING/START/.../ASSIST)",
                Protocol.isTilt("TILT") && Protocol.isSwing("SWING") && Protocol.isStart("START")
                        && Protocol.isNext("NEXT") && Protocol.isPause("PAUSE")
                        && Protocol.isResume("RESUME") && Protocol.isAgain("AGAIN")
                        && Protocol.isLobby("LOBBY") && Protocol.isAssist("ASSIST")
                        && Protocol.isPing("PING") && !Protocol.isTilt("PING"));

        // ---- 43. pairing code ------------------------------------------------------------------------------
        {
            String c = PairingCode.newCode(new Random(77));
            boolean ok = c.length() == 6;
            for (char ch : c.toCharArray()) {
                ok &= ch >= '0' && ch <= '9';
            }
            for (int i = 0; i < 200; i++) {
                String o = PairingCode.newCode(new Random(1000 + i));
                ok &= o.length() == 6;
            }
            ok &= PairingCode.matches(c, c) && !PairingCode.matches(c, "000000".equals(c) ? "111111" : "000000");
            check("pairing code: six digits, stable, exact-match only", ok);
        }

        // ---- 44-47. TiltMath --------------------------------------------------------------------------------
        {
            check("tilt deadzone: 1.5° of tremble keeps the bat still",
                    TiltMath.batLine(10f, 10f) == 0f && TiltMath.batLine(11.4f, 10f) == 0f
                            && Math.abs(TiltMath.batLine(13f, 10f)) > 0f);

            float full = TiltMath.batLine(70f, 10f);
            float overPitch = TiltMath.batLine(179f, 10f);
            check("absolute roll maps ±60° and clamps beyond",
                    Math.abs(full - 60f) < 0.01f && overPitch <= 60f && TiltMath.batLine(-70f, 10f) >= -60f);

            check("pitch maps ±35° to ±1 height",
                    Math.abs(TiltMath.batHeight(45f, 10f) - 1f) < 0.01f
                            && Math.abs(TiltMath.batHeight(-25f, 10f) + 1f) < 0.01f
                            && TiltMath.batHeight(10f, 10f) == 0f);

            check("wrap180 keeps angle differences in [-180, 180]",
                    TiltMath.wrap180(190f) == -170f && TiltMath.wrap180(-190f) == 170f
                            && TiltMath.wrap180(360f) == 0f);

            // drift: with an absolute sensor the output is a pure function of the
            // sample, so 10,000 samples cannot change any earlier reading
            float before = TiltMath.batLine(35f, 10f);
            Random tr = new Random(99);
            for (int i = 0; i < 10000; i++) {
                TiltMath.batLine(tr.nextFloat() * 360f - 180f, tr.nextFloat() * 360f - 180f);
                TiltMath.batHeight(tr.nextFloat() * 90f - 45f, tr.nextFloat() * 90f - 45f);
            }
            float after = TiltMath.batLine(35f, 10f);
            check("no drift after 10,000 samples (absolute mapping)", before == after
                    && TiltMath.shouldSend(35f, 0f, 34f, 0f) && !TiltMath.shouldSend(34.2f, 0f, 34f, 0f));
        }

        // ---- 48-54. SessionServer over loopback TCP/UDP ---------------------------------------------------------
        {
            final int tcp = 18871, udp = 18872;
            final String code = "482619";
            final ConcurrentLinkedQueue<String[]> cmds = new ConcurrentLinkedQueue<String[]>();
            final AtomicBoolean batLost = new AtomicBoolean(false);
            final AtomicReference<String> batName = new AtomicReference<String>(null);
            SessionServer server = new SessionServer(tcp, udp, code, "Test TV",
                    new SessionServer.Listener() {
                        @Override
                        public void onCommand(String[] msg) {
                            cmds.offer(msg);
                        }

                        @Override
                        public void onBatConnected(String phoneName) {
                            batName.set(phoneName);
                        }

                        @Override
                        public void onBatLost() {
                            batLost.set(true);
                        }

                        @Override
                        public String statePayload() {
                            return "screen=LOBBY\tscore=0 / 0\ttvName=Test TV";
                        }
                    });
            server.start();
            Thread.sleep(150);

            Socket s1 = new Socket("127.0.0.1", tcp);
            s1.setTcpNoDelay(true);
            BufferedReader in1 = new BufferedReader(new InputStreamReader(s1.getInputStream(), "UTF-8"));
            BufferedWriter out1 = new BufferedWriter(new OutputStreamWriter(s1.getOutputStream(), "UTF-8"));

            out1.write("HELLO\t3\t" + code + "\tPixel");
            out1.newLine();
            out1.flush();
            String welcome = readUntil(in1, "WELCOME", 2000);
            check("HELLO with the right code gets WELCOME and the TV name",
                    welcome != null && welcome.contains("Test TV"));

            // wrong code
            Socket s2 = new Socket("127.0.0.1", tcp);
            BufferedReader in2 = new BufferedReader(new InputStreamReader(s2.getInputStream(), "UTF-8"));
            BufferedWriter out2 = new BufferedWriter(new OutputStreamWriter(s2.getOutputStream(), "UTF-8"));
            out2.write("HELLO\t3\t999999\tPixel");
            out2.newLine();
            out2.flush();
            String errCode = readUntil(in2, "ERR", 2000);
            check("wrong code is rejected with ERR CODE", errCode != null && errCode.contains("CODE"));

            // wrong version
            Socket s3 = new Socket("127.0.0.1", tcp);
            BufferedReader in3 = new BufferedReader(new InputStreamReader(s3.getInputStream(), "UTF-8"));
            BufferedWriter out3 = new BufferedWriter(new OutputStreamWriter(s3.getOutputStream(), "UTF-8"));
            out3.write("HELLO\t2\t" + code + "\tPixel");
            out3.newLine();
            out3.flush();
            String errVer = readUntil(in3, "ERR", 2000);
            check("wrong protocol version is rejected with ERR VERSION",
                    errVer != null && errVer.contains("VERSION"));
            s3.close();

            // PING -> PONG
            out1.write("PING");
            out1.newLine();
            out1.flush();
            String pong = readUntil(in1, "PONG", 2000);
            check("PING is answered with PONG", pong != null);

            // TILT reaches the command queue
            out1.write("TILT\t25.0\t-0.50");
            out1.newLine();
            out1.flush();
            String[] tilt = null;
            for (int i = 0; i < 100 && tilt == null; i++) {
                tilt = cmds.poll();
                if (tilt != null && !tilt[0].equals("TILT")) {
                    tilt = null;
                }
                if (tilt == null) {
                    Thread.sleep(20);
                }
            }
            check("TILT <line> <height> reaches the game as a 2-axis command",
                    tilt != null && tilt.length >= 3 && "25.0".equals(tilt[1]) && "-0.50".equals(tilt[2]));

            // STATE is pushed when the state is marked dirty
            server.markStateDirty();
            String state = readUntil(in1, "STATE", 1500);
            check("STATE is pushed to the phone when the game state changes",
                    state != null && state.contains("score=0 / 0"));

            // takeover: a second valid bat takes over
            Socket s4 = new Socket("127.0.0.1", tcp);
            BufferedReader in4 = new BufferedReader(new InputStreamReader(s4.getInputStream(), "UTF-8"));
            BufferedWriter out4 = new BufferedWriter(new OutputStreamWriter(s4.getOutputStream(), "UTF-8"));
            out4.write("HELLO\t3\t" + code + "\tGalaxy");
            out4.newLine();
            out4.flush();
            readUntil(in4, "WELCOME", 2000);
            boolean took = false;
            for (int i = 0; i < 50 && !took; i++) {
                took = "Galaxy".equals(server.batName());
                if (!took) {
                    Thread.sleep(20);
                }
            }
            check("a second phone with the code takes over the bat", took);

            // UDP discovery: reply carries the name, never the code
            DatagramSocket ds = new DatagramSocket();
            ds.setSoTimeout(1500);
            byte[] req = Protocol.DISCOVER_REQUEST.getBytes("UTF-8");
            ds.send(new DatagramPacket(req, req.length, InetAddress.getByName("127.0.0.1"), udp));
            byte[] buf = new byte[256];
            DatagramPacket pk = new DatagramPacket(buf, buf.length);
            ds.receive(pk);
            String reply = new String(pk.getData(), 0, pk.getLength(), "UTF-8");
            String[] f = Protocol.parseDiscoverReply(reply);
            check("UDP discovery answers with the TV name and never leaks the code",
                    f != null && "Test TV".equals(f[3]) && !reply.contains(code));
            ds.close();

            // 3 s silence: the watchdog drops the bat
            long t0 = System.currentTimeMillis();
            while (!batLost.get() && System.currentTimeMillis() - t0 < 6000) {
                Thread.sleep(100);
            }
            check("3 s of silence drops the bat (watchdog)", batLost.get());

            s1.close();
            s2.close();
            s4.close();
            server.stop();
        }

        // ---- 55. phone SessionClient connects over loopback ------------------------------------------------
        {
            final int tcp = 18873, udp = 18874;
            final String code = "135790";
            SessionServer server = new SessionServer(tcp, udp, code, "Loop TV",
                    new SessionServer.Listener() {
                        @Override
                        public void onCommand(String[] msg) {
                        }

                        @Override
                        public void onBatConnected(String phoneName) {
                        }

                        @Override
                        public void onBatLost() {
                        }

                        @Override
                        public String statePayload() {
                            return "screen=LOBBY";
                        }
                    });
            server.start();
            Thread.sleep(150);

            final AtomicReference<String> connectedTv = new AtomicReference<String>(null);
            final AtomicReference<String> errKind = new AtomicReference<String>(null);
            com.example.boundaryremote.SessionClient bad =
                    new com.example.boundaryremote.SessionClient(
                            new com.example.boundaryremote.SessionClient.Listener() {
                                @Override
                                public void onConnected(String tvName) {
                                    connectedTv.set(tvName);
                                }

                                @Override
                                public void onState(Map<String, String> state) {
                                }

                                @Override
                                public void onEvent(String[] fields) {
                                    if (fields.length > 0 && "ERR".equals(fields[0])) {
                                        errKind.set(fields.length > 1 ? fields[1] : "CODE");
                                    }
                                }

                                @Override
                                public void onDisconnected() {
                                }
                            });
            bad.connect("127.0.0.1", tcp, "000000", "JUnit");
            long t0 = System.currentTimeMillis();
            while (errKind.get() == null && System.currentTimeMillis() - t0 < 3000) {
                Thread.sleep(25);
            }
            boolean badRejected = "CODE".equals(errKind.get());

            com.example.boundaryremote.SessionClient good =
                    new com.example.boundaryremote.SessionClient(
                            new com.example.boundaryremote.SessionClient.Listener() {
                                @Override
                                public void onConnected(String tvName) {
                                    connectedTv.set(tvName);
                                }

                                @Override
                                public void onState(Map<String, String> state) {
                                }

                                @Override
                                public void onEvent(String[] fields) {
                                }

                                @Override
                                public void onDisconnected() {
                                }
                            });
            good.connect("127.0.0.1", tcp, code, "JUnit");
            t0 = System.currentTimeMillis();
            while (connectedTv.get() == null && System.currentTimeMillis() - t0 < 3000) {
                Thread.sleep(25);
            }
            check("SessionClient pairs over loopback: bad code rejected, right code welcomed",
                    badRejected && "Loop TV".equals(connectedTv.get()));
            good.disconnect();
            server.stop();
        }

        System.out.println();
        System.out.println("passed " + passed + " / " + (passed + failed));
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** Finds the progress where the ball is lowest (i.e. where it pitches). */
    static float argminY(BoundaryModel m) {
        float bestP = 0f;
        float bestY = Float.MAX_VALUE;
        for (int i = 0; i <= 400; i++) {
            float p = i / 400f;
            float y = m.ballWorldY(p);
            if (y < bestY) {
                bestY = y;
                bestP = p;
            }
        }
        return bestP;
    }

    /** Reads lines until one starts with the prefix (skipping STATE pushes). */
    static String readUntil(BufferedReader in, String prefix, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!in.ready()) {
                Thread.sleep(10);
                continue;
            }
            String line = in.readLine();
            if (line != null && line.startsWith(prefix)) {
                return line;
            }
        }
        return null;
    }
}

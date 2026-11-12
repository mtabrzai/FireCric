package com.example.boundary;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.util.List;

/**
 * 2D overlay on top of the 3D scene: top/bottom HUD bars and the designed
 * panels (lobby, delivery tag, contact ring, result, game over, paused).
 * Also renders a 2D fallback field that replicates the 3D visual when OpenGL
 * is unavailable (field2D).
 */
public class HudView extends View {

    private BoundaryModel model;

    public volatile String pairingCode = "000 000";
    public volatile String ipText = "";
    public volatile String serverError = "";
    public volatile int rxCount;
    public volatile boolean phoneConnected = true;
    public volatile boolean everConnected;
    public volatile boolean field2D; // GL fallback

    private final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint muted = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lime = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint red = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public HudView(Context context) {
        super(context);
        white.setColor(0xFFF6F8EE);
        muted.setColor(0xFFAEBFB7);
        lime.setColor(0xFFD7FF67);
        red.setColor(0xFFFF895E);
        panel.setColor(0xCC132D25);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(10f);
    }

    public void setModel(BoundaryModel m) {
        model = m;
    }

    private void text(Canvas c, Paint p, float size, String s, float x, float y) {
        p.setTextSize(size);
        c.drawText(s, x, y, p);
    }

    private void centerText(Canvas c, Paint p, float size, String s, float cx, float y) {
        p.setTextSize(size);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(s, cx, y, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || model == null) {
            return;
        }
        BoundaryModel m = model;
        drawTopBar(canvas, m, w, h);
        drawBottomBar(canvas, m, w, h);
        BoundaryModel.Phase phase = m.getPhase();
        if (phase == BoundaryModel.Phase.LOBBY) {
            drawLobby(canvas, w, h);
        } else {
            if (field2D) {
                drawField2D(canvas, m, w, h);
            }
            if (phase == BoundaryModel.Phase.BOWLING || phase == BoundaryModel.Phase.RUNUP) {
                drawDeliveryTag(canvas, m, w, h);
            }
            if (phase == BoundaryModel.Phase.FLYING) {
                drawContactRing(canvas, m, w, h);
            }
            if (phase == BoundaryModel.Phase.RESULT) {
                drawResult(canvas, m, w, h);
            }
            if (phase == BoundaryModel.Phase.GAME_OVER) {
                drawGameOver(canvas, m, w, h);
            }
            if (everConnected && !phoneConnected) {
                drawPaused(canvas, m, w, h);
            }
        }
        if (serverError.length() > 0) {
            centerText(canvas, red, 30, "Remote server failed: " + serverError, w / 2f, h - 60);
        }
    }

    private void drawTopBar(Canvas canvas, BoundaryModel m, int w, int h) {
        canvas.drawRect(0, 0, w, 100, panel);
        text(canvas, white, 44, "BOUNDARY", 40, 66);
        text(canvas, lime, 22, "CRICKET. IN YOUR HANDS.", 40, 92);
        centerText(canvas, muted, 20, "YOU", w / 2f, 34);
        centerText(canvas, white, 52, m.scoreText(), w / 2f, 86);
        centerText(canvas, muted, 20, "OVERS", w / 2f + 190, 34);
        centerText(canvas, white, 40, m.oversText(), w / 2f + 190, 86);
        String conn = everConnected ? (phoneConnected ? "PHONE CONNECTED" : "PHONE DISCONNECTED") : "WAITING FOR PHONE";
        Paint cp = phoneConnected || !everConnected ? lime : red;
        float tw = cp.measureText(conn);
        text(canvas, cp, 24, conn, w - tw - 180, 66);
        text(canvas, muted, 22, "QUICK PLAY / " + BoundaryModel.MAX_OVERS + " OVERS", 220, 66);
        text(canvas, muted, 22, "Pause", w - 120, 66);
    }

    private void drawBottomBar(Canvas canvas, BoundaryModel m, int w, int h) {
        int ballNo = m.getBalls() + 1;
        String phaseLabel;
        String hint;
        switch (m.getPhase()) {
            case RUNUP:
            case BOWLING:
                phaseLabel = "BALL IN PLAY";
                hint = "WATCH THE BALL — angle the bat to meet it. It fires on contact.";
                break;
            case FLYING:
                phaseLabel = "SHOT IN FLIGHT";
                hint = "Follow the ball into the gap.";
                break;
            case RESULT:
                phaseLabel = "DELIVERY " + ballNo;
                hint = "Reset your stance. Your next ball is waiting.";
                break;
            default:
                phaseLabel = "QUICK PLAY";
                hint = "";
        }
        text(canvas, white, 34, String.format("%02d", Math.min(ballNo, BoundaryModel.MAX_BALLS)), 40, h - 90);
        text(canvas, lime, 22, phaseLabel, 40, h - 56);
        text(canvas, muted, 26, hint, 40, h - 22);
        List<String> last = m.getLastBalls(3);
        float x = w - 60 - last.size() * 64;
        text(canvas, lime, 20, "LAST 3 BALLS", w - 260, h - 110);
        for (String s : last) {
            ringPaint.setColor(s.equals("W") ? 0xFFFF895E : 0xFFD7FF67);
            canvas.drawCircle(x + 24, h - 62, 26, ringPaint);
            centerText(canvas, s.equals("W") ? red : white, 26, s, x + 24, h - 52);
            x += 64;
        }
    }

    private void drawLobby(Canvas canvas, int w, int h) {
        Paint overlay = new Paint();
        overlay.setColor(0xB3060F0E);
        canvas.drawRect(0, 100, w, h, overlay);
        text(canvas, white, 64, "Your phone. Your bat. Your big moment.", 80, 300);
        text(canvas, muted, 30, "Step up to the crease. Tilt your phone to angle the bat — it fires when the bat meets the ball.", 80, 350);
        text(canvas, white, 54, BoundaryModel.MAX_OVERS + " OVERS", 80, 470);
        text(canvas, muted, 28, BoundaryModel.MAX_BALLS + " balls. Make every swing count.", 80, 512);
        text(canvas, muted, 26, "Keep a clear space around you. Hold your phone securely.", 80, h - 40);
        text(canvas, lime, 24, "HOW TO PLAY", w - 260, h - 40);

        float rx = w * 0.56f;
        text(canvas, lime, 26, "01 / CONNECT YOUR BAT", rx, 240);
        text(canvas, white, 46, "The next innings starts here.", rx, 300);
        text(canvas, muted, 28, "Open the Boundary companion on your phone and enter the TV code below.", rx, 344);
        centerText(canvas, white, 90, pairingCode, rx + 260, 500);
        centerText(canvas, muted, 22, "PAIRING CODE", rx + 260, 540);
        String status = everConnected ? (phoneConnected ? "Phone connected — start your innings" : "Waiting for your phone...") : "Waiting for your phone...";
        centerText(canvas, lime, 28, status, rx + 260, 600);
        centerText(canvas, muted, 24, "Same Wi-Fi network • Fire TV • " + ipText, rx + 260, 640);
    }

    private void drawDeliveryTag(Canvas canvas, BoundaryModel m, int w, int h) {
        float x = w / 2f + 260;
        float y = h * 0.30f;
        text(canvas, lime, 30, m.getDelivery(), x, y);
        text(canvas, muted, 24, m.getDeliveryHint(), x, y + 32);
        centerText(canvas, lime, 34, "SWING NOW", w / 2f, h * 0.22f);
    }

    private void drawContactRing(Canvas canvas, BoundaryModel m, int w, int h) {
        float cx = w / 2f, cy = h * 0.42f;
        ringPaint.setColor("PERFECT".equals(m.getTimingLabel()) ? 0xFFD7FF67 : "GOOD".equals(m.getTimingLabel()) ? 0xFFFFD740 : 0xFFFF895E);
        canvas.drawCircle(cx, cy, 60, ringPaint);
        Paint ball = new Paint(Paint.ANTI_ALIAS_FLAG);
        ball.setColor(0xFFF15D4D);
        canvas.drawCircle(cx, cy, 18, ball);
        centerText(canvas, "PERFECT".equals(m.getTimingLabel()) ? lime : white, 40, m.getTimingLabel(), cx, cy + 120);
        centerText(canvas, muted, 26, "Clean contact - into the gap.", cx, cy + 156);
    }

    private void drawResult(Canvas canvas, BoundaryModel m, int w, int h) {
        canvas.drawRect(w * 0.30f, h * 0.16f, w * 0.98f, h * 0.94f, panel);
        text(canvas, lime, 24, "SHOT BREAKDOWN", w * 0.33f, h * 0.24f);
        text(canvas, white, 64, m.resultTitle, w * 0.33f, h * 0.33f);
        text(canvas, muted, 28, m.resultSub, w * 0.33f, h * 0.38f);
        text(canvas, white, 44, m.scoreText(), w * 0.33f, h * 0.47f);
        text(canvas, muted, 26, m.oversText() + " overs / " + (BoundaryModel.MAX_BALLS - m.getBalls()) + " balls remaining", w * 0.33f, h * 0.52f);
        text(canvas, lime, 24, "TIMING", w * 0.33f, h * 0.60f);
        centerText(canvas, "PERFECT".equals(m.getTimingLabel()) ? lime : white, 34, m.getTimingLabel(), w * 0.42f, h * 0.66f);
        float bx = w * 0.55f, by = h * 0.60f, bw = w * 0.38f;
        Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        bar.setColor(0xFF4C4230);
        canvas.drawRect(bx, by, bx + bw, by + 26, bar);
        bar.setColor(0xFFD7FF67);
        canvas.drawRect(bx + bw * 0.35f, by, bx + bw * 0.65f, by + 26, bar);
        text(canvas, muted, 20, "EARLY", bx, by + 52);
        text(canvas, muted, 20, "PERFECT", bx + bw * 0.45f, by + 52);
        text(canvas, muted, 20, "LATE", bx + bw - 40, by + 52);
        if (!m.lastWicket && m.lastRuns > 0) {
            text(canvas, muted, 26, "Distance: " + m.distM + " m", w * 0.55f, h * 0.72f);
            text(canvas, muted, 26, "Direction: " + m.dirDeg + "° · forward", w * 0.55f, h * 0.77f);
            text(canvas, muted, 26, "Forward hitting zone: 120° arc · find the open field", w * 0.55f, h * 0.82f);
        } else if (m.lastWicket) {
            text(canvas, muted, 26, "Caught in the forward field", w * 0.55f, h * 0.72f);
            text(canvas, red, 26, "0 runs - 1 wicket added", w * 0.55f, h * 0.77f);
        } else {
            text(canvas, muted, 26, "Ball passed safely outside the bat", w * 0.55f, h * 0.72f);
            text(canvas, white, 26, "0 runs - No wicket", w * 0.55f, h * 0.77f);
        }
        Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
        pill.setColor(0xFFD7FF67);
        canvas.drawRoundRect(w * 0.82f, h * 0.86f, w * 0.97f, h * 0.92f, 24, 24, pill);
        Paint dark = new Paint();
        dark.setColor(0xFF060F0E);
        dark.setAntiAlias(true);
        centerText(canvas, dark, 26, "Next delivery", w * 0.895f, h * 0.898f);
    }

    private void drawGameOver(Canvas canvas, BoundaryModel m, int w, int h) {
        Paint overlay = new Paint();
        overlay.setColor(0xD9060F0E);
        canvas.drawRect(0, 100, w, h, overlay);
        centerText(canvas, white, 72, "That's a wrap.", w / 2f, 260);
        text(canvas, lime, 26, "INNINGS COMPLETE", 80, 330);
        text(canvas, white, 110, m.scoreText(), 80, 460);
        text(canvas, muted, 28, m.getBalls() + " balls played. " + BoundaryModel.MAX_OVERS + " overs complete.", 80, 510);
        text(canvas, lime, 26, "STATS", 80, 590);
        text(canvas, white, 34, "Fours: " + m.fours() + "    Sixes: " + m.sixes() + "    Longest shot: " + m.longest() + " m", 80, 636);
        text(canvas, lime, 26, "YOUR INNINGS, BALL BY BALL", 80, 700);
        text(canvas, white, 30, m.ballByBall(), 80, 744);
        text(canvas, muted, 26, (m.fours() * 4 + m.sixes() * 6) + " runs from boundaries", 80, 800);
        Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
        pill.setColor(0xFFD7FF67);
        canvas.drawRoundRect(w - 560, h - 110, w - 340, h - 50, 24, 24, pill);
        Paint dark = new Paint();
        dark.setColor(0xFF060F0E);
        dark.setAntiAlias(true);
        centerText(canvas, dark, 28, "Play again", w - 450, h - 68);
        pill.setColor(0xFF132D25);
        canvas.drawRoundRect(w - 300, h - 110, w - 80, h - 50, 24, 24, pill);
        centerText(canvas, white, 28, "Back to lobby", w - 190, h - 68);
    }

    private void drawPaused(Canvas canvas, BoundaryModel m, int w, int h) {
        Paint overlay = new Paint();
        overlay.setColor(0xCC060F0E);
        canvas.drawRect(0, 0, w, h, overlay);
        centerText(canvas, red, 64, "GAME PAUSED", w / 2f, h * 0.34f);
        centerText(canvas, white, 34, "Your bat disconnected.", w / 2f, h * 0.42f);
        centerText(canvas, muted, 26, "Your score is safe. No delivery will start until your phone is connected and you're ready.", w / 2f, h * 0.48f);
        centerText(canvas, muted, 24, "01  Keep the companion app open on your phone.", w / 2f, h * 0.56f);
        centerText(canvas, muted, 24, "02  Check both devices are on the same Wi-Fi.", w / 2f, h * 0.61f);
        centerText(canvas, lime, 30, "WAITING FOR YOUR PHONE", w / 2f, h * 0.72f);
        centerText(canvas, white, 26, m.scoreText() + "  -  " + m.oversText() + " overs  -  delivery not counted", w / 2f, h * 0.78f);
    }

    // ---- 2D fallback field: replicates the 3D visual (stadium backdrop + perspective pitch) ----
    private android.graphics.Bitmap bg3d;

    private void drawField2D(Canvas canvas, BoundaryModel m, int w, int h) {
        if (bg3d == null) {
            bg3d = android.graphics.BitmapFactory.decodeResource(getResources(), R.drawable.bg_3d);
        }
        // stadium backdrop (crowd, floodlights, sky) exactly like the design visual
        if (bg3d != null) {
            canvas.drawBitmap(bg3d, null, new android.graphics.Rect(0, 0, w, h), null);
        } else {
            canvas.drawColor(0xFF0A1F14);
        }
        // vignette + top shade for HUD readability
        android.graphics.Paint vg = new android.graphics.Paint();
        android.graphics.RadialGradient rg = new android.graphics.RadialGradient(
                w / 2f, h * 0.55f, h * 0.95f,
                new int[]{0x00000000, 0x55000000}, null, android.graphics.Shader.TileMode.CLAMP);
        vg.setShader(rg);
        canvas.drawRect(0, 0, w, h, vg);
        android.graphics.Paint top = new android.graphics.Paint();
        top.setColor(0x66060F0E);
        canvas.drawRect(0, 0, w, 110, top);

        // pitch strip: horizontal perspective trapezoid across the lower half (as in the visual)
        float farY = h * 0.50f, nearY = h * 0.88f;
        android.graphics.Paint pitch = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        pitch.setColor(0xFFC9A96F);
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(w * 0.30f, farY);
        p.lineTo(w * 0.72f, farY);
        p.lineTo(w * 0.88f, nearY);
        p.lineTo(w * 0.14f, nearY);
        p.close();
        canvas.drawPath(p, pitch);
        for (int i = 0; i < 6; i++) {
            float t0 = i / 6f, t1 = (i + 1) / 6f;
            pitch.setColor(i % 2 == 0 ? 0xFFC9A96F : 0xFFB99A63);
            android.graphics.Path b = new android.graphics.Path();
            b.moveTo(w * (0.30f + 0.42f * t0), farY);
            b.lineTo(w * (0.30f + 0.42f * t1), farY);
            b.lineTo(w * (0.14f + 0.74f * t1), nearY);
            b.lineTo(w * (0.14f + 0.74f * t0), nearY);
            b.close();
            canvas.drawPath(b, pitch);
        }

        // batsman at the crease (right-center, like the visual), detailed kit
        float bx = w * 0.64f, by = h * 0.88f, s = h / 860f;
        android.graphics.Paint sh = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        sh.setColor(0x55000000);
        canvas.drawOval(bx - 70 * s, by - 6 * s, bx + 70 * s, by + 10 * s, sh);
        Paint stump = new Paint(Paint.ANTI_ALIAS_FLAG);
        stump.setColor(0xFFFFF3D2);
        stump.setStrokeWidth(7 * s);
        for (int i = -1; i <= 1; i++) {
            canvas.drawLine(bx - 46 * s + i * 12 * s, by - 46 * s, bx - 46 * s + i * 12 * s, by, stump);
        }
        Paint kit = new Paint(Paint.ANTI_ALIAS_FLAG);
        kit.setStrokeCap(Paint.Cap.ROUND);
        kit.setColor(0xFFF2F2F2); // pads
        kit.setStrokeWidth(16 * s);
        canvas.drawLine(bx - 14 * s, by - 120 * s, bx - 22 * s, by - 8 * s, kit);
        canvas.drawLine(bx + 14 * s, by - 120 * s, bx + 26 * s, by - 8 * s, kit);
        kit.setColor(0xFF1E5AA8); // blue jersey
        kit.setStrokeWidth(34 * s);
        canvas.drawLine(bx, by - 210 * s, bx, by - 118 * s, kit);
        kit.setColor(0xFFFF7A1A); // orange stripe
        kit.setStrokeWidth(8 * s);
        canvas.drawLine(bx + 14 * s, by - 205 * s, bx + 14 * s, by - 125 * s, kit);
        kit.setColor(0xFF1E5AA8); // arms
        kit.setStrokeWidth(12 * s);
        canvas.drawLine(bx - 12 * s, by - 195 * s, bx - 34 * s, by - 160 * s, kit);
        canvas.drawLine(bx + 12 * s, by - 195 * s, bx + 36 * s, by - 168 * s, kit);
        kit.setColor(0xFFFFF3D2); // glove
        canvas.drawCircle(bx + 36 * s, by - 168 * s, 9 * s, kit);
        kit.setColor(0xFF2E6BD6); // helmet
        canvas.drawCircle(bx, by - 232 * s, 20 * s, kit);
        kit.setColor(0xFF0F2E5C); // helmet peak
        canvas.drawRect(bx - 20 * s, by - 232 * s, bx + 20 * s, by - 224 * s, kit);
        // bat: brown, angled back at stance; swings across on stroke (angle-driven)
        float ang = (float) Math.toRadians(35 + m.getBatAngle() * 0.9 + m.getSwing() * 120f * m.getSwingDir());
        float hx = bx + 30 * s, hy = by - 170 * s;
        float len = 150 * s;
        Paint bat = new Paint(Paint.ANTI_ALIAS_FLAG);
        bat.setColor(0xFFC08A4E);
        bat.setStrokeCap(Paint.Cap.ROUND);
        bat.setStrokeWidth(13 * s);
        canvas.drawLine(hx, hy, hx + (float) Math.sin(ang) * len, hy + (float) Math.cos(ang) * len, bat);
        bat.setColor(0xFFE8C79A);
        bat.setStrokeWidth(5 * s);
        canvas.drawLine(hx + (float) Math.sin(ang) * len * 0.55f, hy + (float) Math.cos(ang) * len * 0.55f,
                hx + (float) Math.sin(ang) * len, hy + (float) Math.cos(ang) * len, bat);

        // bowler: small red figure in the background, runs in during RUNUP
        if (m.getPhase() == BoundaryModel.Phase.RUNUP || m.getPhase() == BoundaryModel.Phase.BOWLING) {
            float runT = m.getPhase() == BoundaryModel.Phase.RUNUP ? 0.4f : 1f;
            float px = w * (0.78f - runT * 0.06f), py = h * (0.50f + runT * 0.04f), ps = h / 860f * 0.55f;
            Paint bw = new Paint(Paint.ANTI_ALIAS_FLAG);
            bw.setStrokeCap(Paint.Cap.ROUND);
            bw.setColor(0xFFD33B40);
            bw.setStrokeWidth(10 * ps);
            canvas.drawLine(px, py - 60 * ps, px, py, bw);
            bw.setStrokeWidth(7 * ps);
            canvas.drawLine(px - 6 * ps, py - 30 * ps, px - 16 * ps, py, bw);
            canvas.drawLine(px + 6 * ps, py - 30 * ps, px + 16 * ps, py, bw);
            canvas.drawCircle(px, py - 72 * ps, 9 * ps, bw);
        }

        // ball: maps model coords onto the pitch, ground shadow, bigger near the bat
        if (m.isBallLive() || m.getPhase() == BoundaryModel.Phase.FLYING) {
            float t = m.getPhase() == BoundaryModel.Phase.FLYING ? 1f
                    : Math.max(0f, Math.min(1f, (m.ballY - 90f) / Math.max(1f, m.batLineY() - 90f)));
            float sx = w * (0.51f + (m.getPhase() == BoundaryModel.Phase.FLYING ? (m.hitX - 960f) / 960f * 0.3f : (m.ballX - 960f) / 960f * 0.3f));
            float sy = h * (0.52f + t * 0.34f) + (m.getPhase() == BoundaryModel.Phase.FLYING ? (m.hitY / 1080f) * h * 0.2f : 0f);
            float br = (10 + t * 18);
            sh.setColor(0x44000000);
            canvas.drawOval(sx - br, sy + br * 0.7f, sx + br, sy + br * 1.05f, sh);
            Paint ball = new Paint(Paint.ANTI_ALIAS_FLAG);
            ball.setColor(0xFFD33B40);
            canvas.drawCircle(sx, sy, br, ball);
            Paint seam = new Paint(Paint.ANTI_ALIAS_FLAG);
            seam.setColor(0xFFFFF3D2);
            seam.setStrokeWidth(Math.max(2, br * 0.22f));
            canvas.drawLine(sx - br * 0.6f, sy - br * 0.6f, sx + br * 0.6f, sy + br * 0.6f, seam);
        }
    }
}

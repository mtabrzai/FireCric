package com.example.boundary;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.view.View;

import java.util.List;

public class BoundaryView extends View {

    private BoundaryModel model;
    private Thread loopThread;
    private volatile boolean running;
    private long lastFrame;

    public volatile String pairingCode = "000 000";
    public volatile String ipText = "";
    public volatile String serverError = "";
    public volatile int rxCount;
    public volatile boolean phoneConnected = true;
    public volatile boolean everConnected;

    private Bitmap bg;

    private final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint muted = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lime = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint red = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint figure = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint batPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ballPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint seamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public BoundaryView(Context context) {
        super(context);
        bg = BitmapFactory.decodeResource(context.getResources(), R.drawable.bg_stadium);
        white.setColor(0xFFF6F8EE);
        muted.setColor(0xFFAEBFB7);
        lime.setColor(0xFFD7FF67);
        red.setColor(0xFFFF895E);
        panel.setColor(0xCC132D25);
        figure.setColor(0xFFF6F8EE);
        figure.setStrokeWidth(10f);
        figure.setStrokeCap(Paint.Cap.ROUND);
        batPaint.setColor(0xFFD7A86E);
        batPaint.setStrokeWidth(16f);
        batPaint.setStrokeCap(Paint.Cap.ROUND);
        seamPaint.setColor(0xFFFFF3D2);
        seamPaint.setStrokeWidth(3f);
        trailPaint.setColor(0xFFD7FF67);
        trailPaint.setStrokeCap(Paint.Cap.ROUND);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(10f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        model = new BoundaryModel(w, h);
        startLoop();
    }

    public BoundaryModel getModel() {
        return model;
    }

    private void startLoop() {
        if (running) {
            return;
        }
        running = true;
        lastFrame = System.currentTimeMillis();
        loopThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    long now = System.currentTimeMillis();
                    long dt = now - lastFrame;
                    lastFrame = now;
                    BoundaryModel m = model;
                    if (m != null) {
                        m.update(dt);
                    }
                    postInvalidate();
                    try {
                        Thread.sleep(16);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }, "boundary-loop");
        loopThread.setDaemon(true);
        loopThread.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        super.onDetachedFromWindow();
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
        if (w <= 0 || h <= 0) {
            return;
        }
        BoundaryModel m = model;
        if (m == null) {
            return;
        }

        canvas.drawColor(0xFF060F0E);
        if (bg != null) {
            canvas.drawBitmap(bg, null, new Rect(0, 0, w, h), null);
            canvas.drawColor(0x88060F0E);
        }

        drawTopBar(canvas, m, w, h);
        drawBottomBar(canvas, m, w, h);

        BoundaryModel.Phase phase = m.getPhase();
        if (phase == BoundaryModel.Phase.LOBBY) {
            drawLobby(canvas, w, h);
        } else {
            drawField(canvas, m, w, h);
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

    private void drawField(Canvas canvas, BoundaryModel m, int w, int h) {
        float batLine = m.batLineY();
        float horizon = h * 0.30f;

        // outfield with mowing stripes (perspective fan)
        Paint grass = new Paint();
        for (int i = 0; i < 9; i++) {
            grass.setColor(i % 2 == 0 ? 0xFF256B3A : 0xFF1E5631);
            float t0 = i / 9f, t1 = (i + 1) / 9f;
            android.graphics.Path stripe = new android.graphics.Path();
            stripe.moveTo(w * t0, horizon);
            stripe.lineTo(w * t1, horizon);
            stripe.lineTo(w * (0.5f + (t1 - 0.5f) * 2.6f), h);
            stripe.lineTo(w * (0.5f + (t0 - 0.5f) * 2.6f), h);
            stripe.close();
            canvas.drawPath(stripe, grass);
        }
        // pitch: trapezoid narrowing toward the bowler (3D perspective)
        Paint pitch = new Paint(Paint.ANTI_ALIAS_FLAG);
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(w / 2f - 34, horizon);
        p.lineTo(w / 2f + 34, horizon);
        p.lineTo(w / 2f + w * 0.13f, batLine + 40);
        p.lineTo(w / 2f - w * 0.13f, batLine + 40);
        p.close();
        canvas.drawPath(p, pitch);
        // mow bands across the pitch
        for (int i = 0; i < 5; i++) {
            float t0 = i / 5f, t1 = (i + 1) / 5f;
            float y0 = horizon + (batLine + 40 - horizon) * t0;
            float y1 = horizon + (batLine + 40 - horizon) * t1;
            float half0 = 34 + (w * 0.13f - 34) * t0;
            float half1 = 34 + (w * 0.13f - 34) * t1;
            Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
            band.setColor(i % 2 == 0 ? 0xFFD7B98A : 0xFFC9A96F);
            android.graphics.Path b = new android.graphics.Path();
            b.moveTo(w / 2f - half0, y0);
            b.lineTo(w / 2f + half0, y0);
            b.lineTo(w / 2f + half1, y1);
            b.lineTo(w / 2f - half1, y1);
            b.close();
            canvas.drawPath(b, band);
        }
        // crease + stumps
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(0xFFF6F8EE);
        line.setStrokeWidth(4f);
        canvas.drawLine(w / 2f - w * 0.05f, batLine, w / 2f + w * 0.05f, batLine, line);
        figure.setColor(0xFFFFF3D2);
        figure.setStrokeWidth(6f);
        for (int i = -1; i <= 1; i++) {
            canvas.drawLine(w / 2f + i * 12, batLine - 34, w / 2f + i * 12, batLine, figure);
        }
        // boundary rope
        Paint rope = new Paint(Paint.ANTI_ALIAS_FLAG);
        rope.setColor(0xFFF6F8EE);
        rope.setStyle(Paint.Style.STROKE);
        rope.setStrokeWidth(8f);
        canvas.drawArc(w / 2f - w * 0.46f, h * 0.55f, w / 2f + w * 0.46f, h * 1.35f, 200, 140, false, rope);

        // bowler runs in during RUNUP, scales up as he approaches the crease
        drawBowler(canvas, m, w, h, horizon);

        // batsman FIXED at the crease, bat in hand pivots on angle
        drawBatsman(canvas, m, m.getBatX(), batLine);

        // pitch marker
        if (m.isBallLive() && (m.getPhase() == BoundaryModel.Phase.RUNUP || m.getPhase() == BoundaryModel.Phase.BOWLING)) {
            Paint pm = new Paint(Paint.ANTI_ALIAS_FLAG);
            pm.setColor(0xFFD7FF67);
            float pr = 4 + 5 * (m.pitchY / h);
            canvas.drawCircle(m.pitchX, m.pitchY, pr, pm);
        }

        // ball: 3x bigger near the bat, ground shadow, trail, bounce pulse
        float bx2, by2;
        if (m.getPhase() == BoundaryModel.Phase.FLYING) {
            bx2 = m.hitX;
            by2 = m.hitY;
            trailPaint.setStrokeWidth(8f);
            trailPaint.setAlpha(160);
            canvas.drawLine(bx2 - m.hitVX * 0.05f, by2 - m.hitVY * 0.05f, bx2, by2, trailPaint);
            trailPaint.setAlpha(90);
            canvas.drawLine(bx2 - m.hitVX * 0.1f, by2 - m.hitVY * 0.1f, bx2 - m.hitVX * 0.05f, by2 - m.hitVY * 0.05f, trailPaint);
        } else if (m.isBallLive()) {
            bx2 = m.ballX;
            by2 = m.ballY;
            trailPaint.setStrokeWidth(6f);
            trailPaint.setAlpha(140);
            canvas.drawLine(bx2 - m.ballVX * 0.06f, by2 - m.ballVY * 0.06f, bx2, by2, trailPaint);
        } else {
            return;
        }
        float depth = Math.max(0f, Math.min(1f, by2 / h));
        float br = 12 + depth * 22; // ~3x bigger as it reaches the bat (~30 px at 1080p)
        if (m.bounced && m.isBallLive()) {
            Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
            bp.setColor(0xFFD7FF67);
            bp.setStyle(Paint.Style.STROKE);
            bp.setStrokeWidth(3f);
            float pr = 8 + 6 * (m.ballY / h);
            canvas.drawCircle(m.pitchX, m.pitchY, pr + 10, bp); // bounce pulse at the pitch marker
        }
        Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
        sh.setColor(0x66000000);
        float groundY = Math.min(by2, batLine + 30);
        canvas.drawOval(bx2 - br * 1.1f, groundY - br * 0.25f, bx2 + br * 1.1f, groundY + br * 0.25f, sh);
        ballPaint.setShader(new RadialGradient(bx2 - br * 0.3f, by2 - br * 0.3f, br,
                new int[]{0xFFFFAC77, 0xFFF15D4D, 0xFFD33B40}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(bx2, by2, br, ballPaint);
        seamPaint.setStrokeWidth(Math.max(2f, br * 0.3f));
        canvas.drawLine(bx2 - br * 0.6f, by2 - br * 0.6f, bx2 + br * 0.6f, by2 + br * 0.6f, seamPaint);
        String popup = m.getPopup();
        if (popup.length() > 0) {
            centerText(canvas, lime, 110, popup, w / 2f, h * 0.5f);
        }
    }

    private void drawFigure(Canvas canvas, float cx, float cy, float s, int color) {
        figure.setColor(color);
        figure.setStrokeWidth(9f * s);
        canvas.drawCircle(cx, cy, 15 * s, figure);
        canvas.drawLine(cx, cy + 15 * s, cx, cy + 52 * s, figure);
        canvas.drawLine(cx, cy + 52 * s, cx - 12 * s, cy + 88 * s, figure);
        canvas.drawLine(cx, cy + 52 * s, cx + 12 * s, cy + 88 * s, figure);
        canvas.drawLine(cx, cy + 26 * s, cx - 18 * s, cy + 42 * s, figure);
        canvas.drawLine(cx, cy + 26 * s, cx + 18 * s, cy + 42 * s, figure);
    }

    private void drawBowler(Canvas canvas, BoundaryModel m, int w, int h, float horizon) {
        float runT = m.getPhase() == BoundaryModel.Phase.RUNUP ? 0.4f : 1f;
        float bY = h * 0.10f + runT * (horizon - h * 0.10f);
        float bScale = 0.55f + 0.45f * runT;
        drawFigure(canvas, w / 2f, bY, bScale, 0xFFD7FF67);
    }

    private void drawBatsman(Canvas canvas, BoundaryModel m, float px, float batLine) {
        float s = 1.5f; // close to camera
        drawFigure(canvas, px, batLine - 92 * s, s, 0xFFF6F8EE);
        // bat pivots at the hands on the bat ANGLE (0 = straight at the bowler);
        // a stroke adds a brief follow-through sweep in the swing direction
        float sw = m.getSwing();
        float eff = m.getBatAngle() + sw * 45f * m.getSwingDir();
        float ang = (float) Math.toRadians(eff);
        float hx = px, hy = batLine - 58 * s;
        float len = 105 * s * 0.75f; // ~105 px at 1920w, per design
        float tipX = hx + (float) Math.sin(ang) * len;
        float tipY = hy - (float) Math.cos(ang) * len;
        batPaint.setColor(0xFFF6F8EE); // white bat line per design
        batPaint.setStrokeWidth(13f * s);
        canvas.drawLine(hx, hy, tipX, tipY, batPaint);
        // 120° forward hitting zone arc around the bat
        Paint zone = new Paint(Paint.ANTI_ALIAS_FLAG);
        zone.setColor(0x66D7FF67);
        zone.setStyle(Paint.Style.STROKE);
        zone.setStrokeWidth(3f);
        canvas.drawArc(hx - len, hy - len, hx + len, hy + len, -150, 120, false, zone);
    }

    private void drawDeliveryTag(Canvas canvas, BoundaryModel m, int w, int h) {
        float batLine = m.batLineY();
        float x = m.getBatX() + 130;
        float y = batLine - 160;
        text(canvas, lime, 30, m.getDelivery(), x, y);
        text(canvas, muted, 24, m.getDeliveryHint(), x, y + 32);
        centerText(canvas, lime, 34, "SWING NOW", m.getBatX(), batLine - 190);
    }

    private void drawContactRing(Canvas canvas, BoundaryModel m, int w, int h) {
        float cx = w / 2f, cy = h * 0.42f;
        ringPaint.setColor("PERFECT".equals(m.getTimingLabel()) ? 0xFFD7FF67 : "GOOD".equals(m.getTimingLabel()) ? 0xFFFFD740 : 0xFFFF895E);
        canvas.drawCircle(cx, cy, 60, ringPaint);
        ballPaint.setShader(null);
        ballPaint.setColor(0xFFF15D4D);
        canvas.drawCircle(cx, cy, 18, ballPaint);
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
}

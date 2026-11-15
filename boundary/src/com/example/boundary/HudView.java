package com.example.boundary;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.util.List;
import java.util.Locale;

/**
 * The Figma-exact TV overlay for Boundary v3.1.
 *
 * Drawn on a 1920x1080 design canvas (Figma px == canvas px, the canvas is
 * scaled to the physical screen). Uses the Inter family from assets and the
 * exact Figma palette: deep green-black #060F0E, panels #091C19/#132D25,
 * lime #D7FF67, willow #F6F8EE, muted #AEBFB7, dividers #33463E, coral
 * #FF895E, ball #F15D4D. Screens: lobby with the 6-digit code, in-play top
 * and bottom bars with delivery tags, the shot-breakdown result with the
 * 120° forward field, dot / wicket with the YOUR SWING timing meter, the
 * innings wrap, and the game-paused dialog. Pills are D-pad focusable with
 * a willow focus ring. If OpenGL is unavailable this view also renders a
 * clean procedural 2D pitch fallback (field2D) beneath the HUD.
 */
public class HudView extends View {

    // ---- Figma palette -----------------------------------------------------
    private static final int BG = 0xFF060F0E;
    private static final int PANEL = 0xFF091C19;
    private static final int PANEL2 = 0xFF132D25;
    private static final int LIME = 0xFFD7FF67;
    private static final int WILLOW = 0xFFF6F8EE;
    private static final int MUTED = 0xFFAEBFB7;
    private static final int DIV = 0xFF33463E;
    private static final int CORAL = 0xFFFF895E;
    private static final int BALLRED = 0xFFF15D4D;

    // translucent variants (alpha from the Figma frames)
    private static final int SCRIM = 0x92091C19;      // rgba(9,28,25,0.57)
    private static final int PANEL_A = 0xF5091C19;    // rgba(9,28,25,0.96)
    private static final int TOP_BAR = 0xB3091C19;    // 70%
    private static final int BOT_BAR = 0xE0091C19;    // 88%

    private BoundaryModel model;
    private String phoneName;      // null when no bat is connected
    private String pairingCode = "000000";
    private String tvName = "Fire TV";
    private boolean field2D;        // 2D fallback when GL is unavailable
    private String demo;            // debug state override
    private long clockMs;

    // focus management for D-pad pills
    private int focus = 0;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();
    private Typeface reg, med, semi, bold;

    public HudView(Context ctx) {
        super(ctx);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        loadFonts(ctx);
    }

    private void loadFonts(Context ctx) {
        try {
            reg = Typeface.createFromAsset(ctx.getAssets(), "fonts/Inter-Regular.ttf");
            med = Typeface.createFromAsset(ctx.getAssets(), "fonts/Inter-Medium.ttf");
            semi = Typeface.createFromAsset(ctx.getAssets(), "fonts/Inter-SemiBold.ttf");
            bold = Typeface.createFromAsset(ctx.getAssets(), "fonts/Inter-Bold.ttf");
        } catch (Exception e) {
            reg = med = semi = bold = null;
        }
        if (reg == null) {
            reg = Typeface.SANS_SERIF;
            med = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
            semi = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
            bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
        }
    }

    public void setModel(BoundaryModel m) {
        model = m;
    }

    public void setPhone(String name) {
        phoneName = name;
    }

    public void setPairing(String code, String tv) {
        if (code != null) {
            pairingCode = code;
        }
        if (tv != null) {
            tvName = tv;
        }
    }

    public void setField2D(boolean b) {
        field2D = b;
    }

    public void setDemo(String state) {
        demo = state;
    }

    /** Pills available on the current screen; see pillAction(). */
    public int pillCount() {
        String s = screen();
        if (s.equals("lobby")) {
            return 1;                       // START INNINGS (once a bat is paired)
        }
        if (s.equals("result")) {
            return 1;                       // NEXT DELIVERY
        }
        if (s.equals("gameover")) {
            return 2;                       // PLAY AGAIN / BACK TO LOBBY
        }
        if (s.equals("paused")) {
            return 2;                       // RESUME / BACK TO LOBBY
        }
        return 0;
    }

    public String pillAction(int i) {
        String s = screen();
        if (s.equals("lobby")) {
            return "start";
        }
        if (s.equals("result")) {
            return "next";
        }
        if (s.equals("gameover")) {
            return i == 0 ? "again" : "lobby";
        }
        if (s.equals("paused")) {
            return i == 0 ? "resume" : "lobby";
        }
        return "";
    }

    public void focusNext() {
        int n = pillCount();
        if (n > 0) {
            focus = (focus + 1) % n;
            invalidate();
        }
    }

    public void focusPrev() {
        int n = pillCount();
        if (n > 0) {
            focus = (focus + n - 1) % n;
            invalidate();
        }
    }

    public void resetFocus() {
        focus = 0;
    }

    /** Index of the currently focused pill (for OK on the remote). */
    public int focusIndex() {
        return focus;
    }

    /** Current screen key from the model phase (or the debug demo state). */
    private String screen() {
        if (demo != null) {
            if (demo.equals("LOBBY")) {
                return "lobby";
            }
            if (demo.equals("FACE") || demo.equals("DELIVERY")) {
                return "play";
            }
            if (demo.equals("FLIGHT") || demo.equals("SIX") || demo.equals("DOT")
                    || demo.equals("CAUGHT")) {
                return "result";
            }
            if (demo.equals("INNINGS")) {
                return "gameover";
            }
            if (demo.equals("PAUSED")) {
                return "paused";
            }
        }
        if (model == null) {
            return "lobby";
        }
        BoundaryModel.Phase ph = model.getPhase();
        switch (ph) {
            case LOBBY:
            case READY:
                return "lobby";
            case RUNUP:
            case BOWLING:
            case FLYING:
            case BOWLED:
                return "play";
            case RESULT:
                return "result";
            case GAME_OVER:
                return "gameover";
            case PAUSED:
            default:
                return "paused";
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        clockMs = android.os.SystemClock.uptimeMillis();
        float sx = getWidth() / 1920f;
        float sy = getHeight() / 1080f;
        c.save();
        c.scale(sx, sy);

        String s = screen();
        if (field2D && (s.equals("play"))) {
            draw2DScene(c);
        } else if (!field2D) {
            c.drawColor(BG); // GL failed to init: still avoid flicker
        }
        if (field2D && !s.equals("play")) {
            c.drawColor(BG);
        }

        switch (s) {
            case "lobby":
                drawLobby(c);
                break;
            case "play":
                drawPlayHud(c);
                break;
            case "result":
                drawResult(c);
                break;
            case "gameover":
                drawInnings(c);
                break;
            case "paused":
                drawPaused(c);
                break;
        }
        c.restore();
    }

    // =====================================================================
    // Typography helpers (Figma px sizes, Inter)
    // =====================================================================
    private void text(Canvas c, String t, float x, float y, Typeface f, float size, int color) {
        p.setTypeface(f);
        p.setTextSize(size);
        p.setColor(color);
        c.drawText(t, x, y, p);
    }

    private void textCenter(Canvas c, String t, float cx, float y, Typeface f, float size, int color) {
        p.setTypeface(f);
        p.setTextSize(size);
        p.setColor(color);
        float w = p.measureText(t);
        c.drawText(t, cx - w / 2f, y, p);
    }

    private void textRight(Canvas c, String t, float rx, float y, Typeface f, float size, int color) {
        p.setTypeface(f);
        p.setTextSize(size);
        p.setColor(color);
        float w = p.measureText(t);
        c.drawText(t, rx - w, y, p);
    }

    private void caps(Canvas c, String t, float x, float y, float size, int color) {
        text(c, t.toUpperCase(Locale.ROOT), x, y, semi, size, color);
    }

    private void panel(Canvas c, float x, float y, float w, float h, int fill) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(fill);
        r.set(x, y, x + w, y + h);
        c.drawRoundRect(r, 24f, 24f, p);
    }

    private void strokePanel(Canvas c, float x, float y, float w, float h) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        p.setColor(DIV);
        r.set(x, y, x + w, y + h);
        c.drawRoundRect(r, 24f, 24f, p);
        p.setStyle(Paint.Style.FILL);
    }

    /** Rounded pill; returns the rect for focus ring drawing. */
    private RectF pill(Canvas c, String label, float cx, float cy, float w, float h,
                       boolean lime, boolean focused) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(lime ? LIME : PANEL);
        r.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        c.drawRoundRect(r, Math.min(23f, h / 2f), Math.min(23f, h / 2f), p);
        if (focused) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(5f);
            p.setColor(WILLOW);
            r.inset(-7f, -7f);
            c.drawRoundRect(r, 28f, 28f, p);
            p.setStyle(Paint.Style.FILL);
        }
        textCenter(c, label, cx, cy + (lime ? 13f : 12f), semi, 28f, lime ? BG : WILLOW);
        r.set(cx - w / 2f - 12f, cy - h / 2f - 12f, cx + w / 2f + 12f, cy + h / 2f + 12f);
        return r;
    }

    // =====================================================================
    // Lobby (Figma frame 01)
    // =====================================================================
    private void drawLobby(Canvas c) {
        // headline block, left column
        caps(c, "01 / CONNECT YOUR BAT", 104, 210, 26f, LIME);
        text(c, "Your phone.", 104, 300, bold, 76f, WILLOW);
        text(c, "Your bat.", 104, 384, bold, 76f, WILLOW);
        text(c, "Your big moment.", 104, 468, bold, 76f, LIME);
        text(c, "Boundary turns your phone into the bat. Tilt to aim, swing to hit.", 104, 530, reg, 28f, MUTED);

        // quick-play card under the headline
        panel(c, 104, 610, 420, 170, PANEL_A);
        strokePanel(c, 104, 610, 420, 170);
        caps(c, "QUICK PLAY", 140, 668, 24f, LIME);
        text(c, "5 OVERS", 140, 726, bold, 44f, WILLOW);
        text(c, "30 balls. Five overs, five bowling phases.", 140, 760, reg, 24f, MUTED);

        // pairing panel (right)
        float px = 1080, py = 224, pw = 728, ph = 650;
        panel(c, px, py, pw, ph, PANEL_A);
        strokePanel(c, px, py, pw, ph);
        caps(c, "PAIR YOUR PHONE", px + 64, py + 78, 26f, LIME);
        text(c, "On your phone, enter this code:", px + 64, py + 132, reg, 26f, MUTED);

        // the 6-digit code, Bold 88 lime, letterspaced
        p.setTypeface(bold);
        p.setTextSize(88f);
        p.setColor(LIME);
        p.setLetterSpacing(0.12f);
        float cw = p.measureText(pairingCode);
        c.drawText(pairingCode, px + (pw - cw) / 2f, py + 262, p);
        p.setLetterSpacing(0f);

        // divider + TV name
        p.setColor(DIV);
        p.setStrokeWidth(2f);
        c.drawLine(px + 64, py + 310, px + pw - 64, py + 310, p);
        caps(c, "THIS TV", px + 64, py + 362, 22f, MUTED);
        text(c, tvName, px + 64, py + 420, semi, 40f, WILLOW);
        text(c, "Fire TV app · living room", px + 64, py + 452, reg, 24f, MUTED);

        boolean ready = model != null && model.getPhase() == BoundaryModel.Phase.READY;
        if (ready) {
            // bat paired: offer the start
            p.setColor(LIME);
            c.drawCircle(px + 84, py + 512, 11f, p);
            text(c, phoneName != null ? phoneName + " is ready" : "Bat ready", px + 112, py + 522, semi, 28f, LIME);
            pill(c, "START INNINGS", px + pw / 2f, py + 578, 460, 66, true, focus == 0 && pillCount() > 0);
        } else {
            // waiting pill with a soft pulse
            float pulse = 0.55f + 0.45f * (0.5f + 0.5f * (float) Math.sin(clockMs / 500.0));
            p.setColor(LIME);
            p.setAlpha((int) (255 * pulse));
            c.drawCircle(px + 84, py + 512, 11f, p);
            p.setAlpha(255);
            text(c, "WAITING FOR YOUR PHONE…", px + 112, py + 522, semi, 28f, WILLOW);
            text(c, "No phone? You can also play with the Fire TV remote (D-pad).",
                    px + 64, py + 580, reg, 24f, MUTED);
        }
        drawBottomBarBase(c);
    }

    // =====================================================================
    // In-play HUD (Figma frames 02 / 07 / 08)
    // =====================================================================
    private void drawPlayHud(Canvas c) {
        drawTopBar(c);
        drawBottomBar(c);

        BoundaryModel.Phase ph = model.getPhase();
        boolean bowling = ph == BoundaryModel.Phase.BOWLING;
        boolean runup = ph == BoundaryModel.Phase.RUNUP;

        // delivery tag (left, mid-screen) once the bowler is running in
        if (runup || bowling) {
            float ty = 386;
            caps(c, model.getOverLabel(), 104, ty, 26f, LIME);
            text(c, model.getDelivery(), 104, ty + 74, bold, 64f, WILLOW);
            text(c, model.getDeliveryHint(), 104, ty + 122, med, 30f, MUTED);
        }

        // SWING NOW prompt as the ball nears the bat
        if (bowling && model.isBallLive()) {
            float prog = model.deliveryProgress();
            if (prog > 0.45f && prog < 1.02f) {
                float pulse = 1f + 0.05f * (float) Math.sin(clockMs / 90.0);
                float w = 380 * pulse;
                c.save();
                p.setColor(LIME);
                r.set(960 - w / 2f, 800, 960 + w / 2f, 800 + 88);
                c.drawRoundRect(r, 24f, 24f, p);
                textCenter(c, "SWING NOW", 960, 858, bold, 40f, BG);
                c.restore();
            }
            // bouncing vs full-toss callout
            if (!model.willBounce()) {
                caps(c, "FULL TOSS · NO BOUNCE", 104, 300, 24f, CORAL);
            }
        }

        // shot-in-flight contact tag
        if (ph == BoundaryModel.Phase.FLYING) {
            caps(c, "04 / SHOT IN FLIGHT", 104, 386, 26f, LIME);
            text(c, model.getTimingLabel().isEmpty() ? "PERFECT" : model.getTimingLabel().toUpperCase(Locale.ROOT),
                    104, 460, bold, 64f, LIME);
            text(c, "Middle of the bat. Into the gap.", 104, 508, med, 30f, WILLOW);
        }
        if (ph == BoundaryModel.Phase.BOWLED) {
            text(c, "BOWLED.", 104, 460, bold, 84f, CORAL);
            text(c, "Through the gate. Bails flying.", 104, 514, med, 30f, WILLOW);
        }
    }

    private void drawTopBar(Canvas c) {
        p.setColor(TOP_BAR);
        c.drawRect(0, 0, 1920, 200, p);

        // wordmark + tagline
        text(c, "BOUNDARY", 64, 92, bold, 44f, LIME);
        text(c, "CRICKET. IN YOUR HANDS.", 64, 142, reg, 24f, MUTED);

        // centre score panel
        panel(c, 660, 26, 600, 148, PANEL_A);
        strokePanel(c, 660, 26, 600, 148);
        p.setTypeface(bold);
        p.setTextSize(66f);
        p.setColor(WILLOW);
        float sw = p.measureText("YOU");
        c.drawText("YOU", 960 - sw - 24, 110, p);
        String score = model == null ? "0 / 0" : model.scoreText();
        p.setTextSize(66f);
        float scw = p.measureText(score);
        c.drawText(score, 960 + 24, 110, p);
        textCenter(c, model == null ? "OVERS 0.0 / 5" : "OVERS " + model.oversText(), 960, 152, reg, 26f, MUTED);

        // connection pill (right)
        boolean conn = phoneName != null;
        float px = conn ? 1740 : 1690;
        p.setColor(PANEL2);
        r.set(px - 230, 64, px, 136);
        c.drawRoundRect(r, 36f, 36f, p);
        p.setColor(conn ? LIME : CORAL);
        c.drawCircle(px - 196, 100, 11f, p);
        textRight(c, conn ? "PHONE CONNECTED" : "NO PHONE", px - 28, 110, semi, 26f, WILLOW);
    }

    private void drawBottomBarBase(Canvas c) {
        p.setColor(BOT_BAR);
        c.drawRect(0, 886, 1920, 1080, p);
    }

    private void drawBottomBar(Canvas c) {
        drawBottomBarBase(c);

        // ball number + phase
        int ballNo = model == null ? 1 : model.getBalls() + 1;
        caps(c, String.format(Locale.US, "%02d BALL", ballNo), 64, 948, 26f, LIME);
        text(c, model == null ? "OVER 1/5 · PACE" : model.getOverLabel(), 64, 996, semi, 30f, WILLOW);
        text(c, model == null ? "" : model.getOverHint(), 64, 1036, reg, 24f, MUTED);

        // centre hint line
        textCenter(c, "Watch the ball. Time your swing.", 960, 990, med, 28f, MUTED);

        // LAST 3 BALLS chips (right)
        caps(c, "LAST 3 BALLS", 1856 - 260, 948, 24f, MUTED);
        List<String> last = model == null ? null : model.getLastBalls(3);
        for (int i = 0; i < 3; i++) {
            float cx = 1500 + i * 72;
            float cy = 1010;
            String v = (last != null && last.size() >= 3) ? last.get(last.size() - 3 + i) : null;
            p.setColor(PANEL2);
            r.set(cx, cy - 26, cx + 52, cy + 26);
            c.drawRoundRect(r, 14f, 14f, p);
            int col;
            boolean big = "6".equals(v) || "4".equals(v);
            boolean wick = "W".equals(v);
            if (big) {
                col = LIME;
            } else if (wick) {
                col = CORAL;
            } else {
                col = MUTED;
            }
            textCenter(c, v == null ? "–" : v, cx + 26, cy + 10, semi, 28f, col);
        }
    }

    // =====================================================================
    // Result screens (Figma frames 03 / 09 / 10)
    // =====================================================================
    private void drawResult(Canvas c) {
        // the scene stays faintly visible under a 57% scrim
        p.setColor(SCRIM);
        c.drawRect(0, 0, 1920, 1080, p);

        boolean wicket = model != null && model.lastWicket;
        boolean hit = model != null && !wicket && model.lastRuns > 0;
        int accent = wicket ? CORAL : LIME;

        // kicker + giant result word
        String kicker = "DOT BALL";
        String word = "JUST MISSED.";
        if (hit) {
            kicker = model.resultKicker.isEmpty() ? "WHAT A SHOT" : model.resultKicker;
            word = model.resultTitle.isEmpty() ? BoundaryModel.headlineFor(model.lastRuns) : model.resultTitle;
        } else if (wicket) {
            kicker = model.lastBowled ? "BOWLED" : "WICKET";
            word = model.resultTitle.isEmpty() ? (model.lastBowled ? "BOWLED." : "CAUGHT.") : model.resultTitle;
        }
        caps(c, kicker, 104, 300, 32f, accent);
        text(c, word, 96, 490, bold, 190f, wicket ? CORAL : WILLOW);

        if (hit) {
            // timing pill + score
            String tl = model.getTimingLabel();
            if (tl != null && !tl.isEmpty()) {
                float tw = 60 + tl.length() * 22f;
                p.setColor(LIME);
                r.set(104, 540, 104 + tw, 540 + 56);
                c.drawRoundRect(r, 28f, 28f, p);
                text(c, tl.toUpperCase(Locale.ROOT), 128, 580, semi, 26f, BG);
            }
            text(c, "YOU " + model.scoreText(), 104, 700, bold, 66f, WILLOW);
            if (model.wasAssistedStroke()) {
                text(c, "Smart Assist played the shot", 104, 748, reg, 24f, MUTED);
            }

            // FORWARD FIELD: 120° arc with the shot needle
            drawForwardField(c, 620, 940, model.dirDeg);

            // SHOT BREAKDOWN panel
            drawShotBreakdown(c);
        } else {
            // YOUR SWING panel with the timing meter
            drawYourSwing(c, kicker, word);
        }

        // Next delivery pill
        boolean showNext = model == null || !model.isGameOver();
        if (showNext) {
            pill(c, "Next delivery", 1635, 953, 370, 66, true, focus == 0 && pillCount() > 0);
        }
    }

    /** 120° forward field arc (Figma frame 03) with the shot-direction needle. */
    private void drawForwardField(Canvas c, float cx, float cy, float dirDeg) {
        float rad = 300f;
        caps(c, "FORWARD FIELD", cx - 96, cy - rad - 74, 24f, MUTED);

        p.setStyle(Paint.Style.FILL);
        p.setColor(0x2ED7FF67); // translucent lime wedge
        path.reset();
        path.moveTo(cx, cy);
        path.arcTo(new RectF(cx - rad, cy - rad, cx + rad, cy + rad), 180f + 30f, 120f);
        path.close();
        c.drawPath(path, p);

        // arc outline + ticks every 30°
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4f);
        p.setColor(DIV);
        c.drawArc(new RectF(cx - rad, cy - rad, cx + rad, cy + rad), 210f, 120f, false, p);
        p.setColor(MUTED);
        for (int deg = 30; deg <= 150; deg += 30) {
            double a = Math.toRadians(deg);
            float x0 = cx + (float) Math.cos(a) * (rad - 10);
            float y0 = cy - (float) Math.sin(a) * (rad - 10);
            float x1 = cx + (float) Math.cos(a) * (rad + 10);
            float y1 = cy - (float) Math.sin(a) * (rad + 10);
            c.drawLine(x0, y0, x1, y1, p);
            textCenter(c, deg + "°", cx + (float) Math.cos(a) * (rad + 40),
                    cy - (float) Math.sin(a) * (rad + 40) + 8, reg, 22f, MUTED);
        }
        p.setStyle(Paint.Style.FILL);

        // shot direction needle
        float clamped = Math.max(30f, Math.min(150f, dirDeg));
        double a = Math.toRadians(clamped);
        float nx = cx + (float) Math.cos(a) * (rad - 26);
        float ny = cy - (float) Math.sin(a) * (rad - 26);
        p.setStrokeWidth(8f);
        p.setColor(WILLOW);
        c.drawLine(cx, cy, nx, ny, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(WILLOW);
        c.drawCircle(nx, ny, 12f, p);
        textCenter(c, (int) clamped + "°", cx, cy - rad - 30, semi, 26f, WILLOW);
    }

    private void drawShotBreakdown(Canvas c) {
        float x = 1070, y = 253, w = 746, h = 585;
        panel(c, x, y, w, h, PANEL_A);
        strokePanel(c, x, y, w, h);
        caps(c, "SHOT BREAKDOWN", x + 56, y + 72, 26f, LIME);
        row(c, x + 56, y + 130, "DISTANCE", model.distM + " m");
        row(c, x + 56, y + 218, "DIRECTION", model.dirDeg + "°");
        row(c, x + 56, y + 306, "SWING TIMING", model.getTimingLabel().isEmpty()
                ? "PERFECT" : model.getTimingLabel());
        row(c, x + 56, y + 394, "FORWARD HITTING ZONE",
                model.getTimingLabel().isEmpty() ? "MIDDLE OF THE BAT" : model.getTimingLabel());
        if (!model.resultSub.isEmpty()) {
            text(c, model.resultSub, x + 56, y + 480, reg, 24f, MUTED);
        }
    }

    private void row(Canvas c, float x, float y, String label, String value) {
        text(c, label, x, y, reg, 23f, MUTED);
        textRight(c, value.toUpperCase(Locale.ROOT), x + 634, y, semi, 25f, WILLOW);
        p.setColor(DIV);
        p.setStrokeWidth(2f);
        c.drawLine(x, y + 26, x + 634, y + 26, p);
    }

    /** YOUR SWING panel (Figma frames 09 / 10): timing meter with marker. */
    private void drawYourSwing(Canvas c, String kicker, String word) {
        float x = 1070, y = 253, w = 746, h = 585;
        panel(c, x, y, w, h, PANEL_A);
        strokePanel(c, x, y, w, h);
        caps(c, "YOUR SWING", x + 56, y + 72, 26f, LIME);

        float tm = model == null ? 0.5f : Math.max(0f, Math.min(1f, model.timingMeter));
        String timing = model == null ? "" : model.getTimingLabel();

        // timing copy
        if (!model.resultSub.isEmpty()) {
            text(c, model.resultSub, x + 56, y + 140, med, 30f, WILLOW);
        } else if (model.lastBowled) {
            text(c, "The ball sneaked under the bat and hit the stumps.", x + 56, y + 140, med, 28f, WILLOW);
        } else if (model.lastWicket) {
            text(c, "Caught in the forward field.", x + 56, y + 140, med, 30f, WILLOW);
        } else {
            text(c, "Ball passed safely outside the bat.", x + 56, y + 140, med, 30f, WILLOW);
        }

        // early / perfect / late meter: 212 + 140 + 212 = 564 px wide, 20 px tall
        float my = y + 307; // Figma y560 within the panel
        float mx = x + 56;
        p.setColor(MUTED);
        r.set(mx, my, mx + 212, my + 20);
        c.drawRoundRect(r, 10f, 10f, p);
        p.setColor(LIME);
        r.set(mx + 212, my, mx + 352, my + 20);
        c.drawRoundRect(r, 10f, 10f, p);
        p.setColor(MUTED);
        r.set(mx + 352, my, mx + 564, my + 20);
        c.drawRoundRect(r, 10f, 10f, p);
        caps(c, "EARLY", mx, my + 64, 22f, MUTED);
        textCenter(c, "PERFECT", mx + 282, my + 64, semi, 22f, LIME);
        textRight(c, "LATE", mx + 564, my + 64, semi, 22f, MUTED);

        // the marker: 6 x 48, positioned on the meter
        float px = mx + 8 + tm * 548;
        p.setColor(WILLOW);
        c.drawRect(px, my - 14, px + 6, my + 34, p);

        text(c, timing.isEmpty() ? "SWING TIMING" : timing.toUpperCase(Locale.ROOT),
                x + 56, y + 470, semi, 28f, timing.isEmpty() || timing.equals("PERFECT") ? LIME : WILLOW);
        text(c, "Bat line " + (model == null ? "0°" : Math.round(model.getBatAngle()) + "°"
                + "  ·  height " + heightWord(model == null ? 0 : model.getBatHeight())),
                x + 56, y + 510, reg, 24f, MUTED);
    }

    private static String heightWord(float h) {
        if (h < -0.33f) {
            return "LOW";
        }
        return h > 0.33f ? "HIGH" : "MID";
    }

    // =====================================================================
    // Innings complete (Figma frame 11)
    // =====================================================================
    private void drawInnings(Canvas c) {
        p.setColor(SCRIM);
        c.drawRect(0, 0, 1920, 1080, p);

        caps(c, "INNINGS COMPLETE", 104, 200, 26f, LIME);
        text(c, "That's a wrap.", 104, 290, bold, 76f, WILLOW);
        text(c, "YOU " + model.scoreText(), 104, 400, bold, 90f, LIME);
        text(c, "OVERS " + model.oversText() + "  ·  RUN RATE " + String.format(Locale.US, "%.1f", model.runRate()),
                104, 448, reg, 26f, MUTED);

        // stat cards: FOURS / SIXES / LONGEST SHOT
        String[] labels = {"FOURS", "SIXES", "LONGEST SHOT"};
        int[] vals = {model.fours(), model.sixes(), model.longest()};
        String[] units = {"", "", " m"};
        for (int i = 0; i < 3; i++) {
            float x = 104 + i * 292;
            panel(c, x, 500, 268, 150, PANEL_A);
            strokePanel(c, x, 500, 268, 150);
            caps(c, labels[i], x + 32, 548, 22f, MUTED);
            text(c, String.valueOf(vals[i]) + units[i], x + 32, 612, bold, 52f, WILLOW);
        }

        // ball-by-ball log: one row per over
        caps(c, "YOUR INNINGS, BALL BY BALL", 104, 730, 26f, LIME);
        for (int ov = 0; ov < BoundaryModel.MAX_OVERS; ov++) {
            int start = ov * 6;
            int end = Math.min(start + 6, model.ballLog.size());
            float y = 780 + ov * 62;
            text(c, "OVER " + (ov + 1) + " · " + BoundaryModel.OVER_NAMES[ov]
                    + "  ·  " + model.overRuns(ov) + " RUNS", 104, y + 22, semi, 24f, MUTED);
            for (int b = start; b < end; b++) {
                float cx = 620 + (b - start) * 86;
                String v = model.ballLog.get(b);
                p.setColor(PANEL2);
                r.set(cx, y, cx + 74, y + 44);
                c.drawRoundRect(r, 12f, 12f, p);
                boolean big = "6".equals(v) || "4".equals(v);
                textCenter(c, v, cx + 37, y + 31, semi, 26f,
                        "W".equals(v) ? CORAL : (big ? LIME : WILLOW));
            }
        }

        pill(c, "Play again", 1600, 953, 370, 66, true, focus == 0);
        pill(c, "Back to lobby", 1290, 953, 330, 66, false, focus == 1);
    }

    // =====================================================================
    // Game paused (Figma frame 12)
    // =====================================================================
    private void drawPaused(Canvas c) {
        p.setColor(0xB3060F0E);
        c.drawRect(0, 0, 1920, 1080, p);

        float x = 490, y = 204, w = 940, h = 676;
        panel(c, x, y, w, h, PANEL_A);
        strokePanel(c, x, y, w, h);

        boolean disconnected = phoneName == null;
        caps(c, "GAME PAUSED", x + 72, y + 92, 26f, CORAL);
        text(c, disconnected ? "Your bat disconnected." : "Game paused.",
                x + 72, y + 158, bold, 56f, WILLOW);

        if (disconnected) {
            text(c, "1.  Check that Wi-Fi is on, on your phone.", x + 96, y + 250, reg, 28f, MUTED);
            text(c, "2.  Reopen the Boundary companion app and it will", x + 96, y + 300, reg, 28f, MUTED);
            text(c, "     reconnect to this TV automatically.", x + 96, y + 344, reg, 28f, MUTED);
        } else {
            text(c, "The delivery in progress was not counted.", x + 96, y + 250, reg, 28f, MUTED);
        }

        // waiting pill with pulse
        float pulse = 0.55f + 0.45f * (0.5f + 0.5f * (float) Math.sin(clockMs / 450.0));
        p.setColor(PANEL2);
        r.set(x + 72, y + 400, x + 72 + 520, y + 400 + 76);
        c.drawRoundRect(r, 38f, 38f, p);
        p.setColor(LIME);
        p.setAlpha((int) (255 * pulse));
        c.drawCircle(x + 116, y + 438, 12f, p);
        p.setAlpha(255);
        text(c, disconnected ? "WAITING FOR YOUR PHONE" : "READY TO RESUME", x + 148, y + 448, semi, 26f, WILLOW);
        text(c, "Play resumes only after you confirm on your phone.", x + 72, y + 528, reg, 24f, MUTED);

        if (disconnected) {
            pill(c, "Back to lobby", x + w / 2f, y + 590, 460, 66, false, focus == 0);
        } else {
            pill(c, "Resume innings", x + w / 2f - 120, y + 590, 460, 66, true, focus == 0);
            pill(c, "Back to lobby", x + w / 2f + 220, y + 590, 400, 66, false, focus == 1);
        }
    }

    // =====================================================================
    // 2D fallback scene (only when OpenGL is unavailable)
    // =====================================================================
    private void draw2DScene(Canvas c) {
        // dusk ground + pitch trapezoid
        p.setColor(0xFF0B2A1E);
        c.drawRect(0, 0, 1920, 1080, p);
        p.setColor(0xFF0F3A28);
        c.drawRect(0, 540, 1920, 1080, p);

        // pitch
        path.reset();
        path.moveTo(860, 540);
        path.lineTo(1060, 540);
        path.lineTo(1520, 1010);
        path.lineTo(400, 1010);
        path.close();
        p.setColor(0xFFC2A568);
        c.drawPath(path, p);
        p.setColor(0x66FFFFFF);
        c.drawRect(430, 940, 1490, 952, p);

        if (model == null) {
            return;
        }
        // bowler (simple, far end)
        float bz = proj(BoundaryModel.RELEASE_Z * 0.35f);
        drawStickFigure(c, 960 + 40, 560, 30, 0xFFD33B40);
        // batsman (near, right-handed)
        drawStickFigure(c, 1040 - model.batWorldX() * 60, 950, 120, 0xFF2F6FB5);
        // bat blade angled by line + height
        float batX = 1040 - model.batWorldX() * 60;
        float batY = 950 - (model.batWorldY() - 0.74f) * 160;
        p.setStrokeWidth(16);
        p.setColor(0xFFF6F8EE);
        float ang = (float) Math.toRadians(90 - model.getBatAngle());
        c.drawLine(batX, batY, batX + (float) Math.cos(ang) * 130,
                batY - (float) Math.sin(ang) * 130, p);

        BoundaryModel.Phase ph = model.getPhase();
        if (ph == BoundaryModel.Phase.BOWLING || ph == BoundaryModel.Phase.BOWLED) {
            float prog = Math.max(0f, Math.min(1.15f, model.deliveryProgress()));
            float wx = model.ballWorldX(Math.min(1f, prog));
            float wy = model.ballWorldY(Math.min(1f, prog));
            float wz = model.ballWorldZ(Math.min(1f, prog));
            float d = 3.6f - wz;
            float s = 760f / d;
            float sx = 960 + wx * s + 80 * (1 - s / 211f) * 0;
            float sy = 540 + (1.65f - wy) * s;
            // shadow
            p.setColor(0x40000000);
            c.drawOval(sx - 14, 540 + 1.65f * s - 6, sx + 14, 540 + 1.65f * s + 6, p);
            // ball
            p.setColor(BALLRED);
            c.drawCircle(sx, sy, 12, p);
        }
        if (ph == BoundaryModel.Phase.FLYING) {
            float q = Math.max(0f, Math.min(1f, model.flyT));
            float fx = 960 + model.flyX * 90;
            float fy = 700 - model.flyY * 260 - q * 380;
            p.setColor(BALLRED);
            c.drawCircle(fx, Math.max(40, fy), 14, p);
        }
    }

    private float proj(float z) {
        return 540 + (z + 20f) * 12f;
    }

    private void drawStickFigure(Canvas c, float x, float y, float h, int col) {
        p.setStrokeWidth(Math.max(4, h / 12f));
        p.setColor(col);
        c.drawLine(x, y - h * 0.45f, x, y, p);                       // body
        c.drawLine(x - h * 0.16f, y, x + h * 0.16f, y, p);           // legs
        c.drawLine(x - h * 0.28f, y - h * 0.25f, x + h * 0.28f, y - h * 0.25f, p); // arms
        p.setColor(0xFFE8B98A);
        c.drawCircle(x, y - h * 0.58f, h * 0.13f, p);
    }
}

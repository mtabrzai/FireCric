package com.example.boundaryremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * The Figma-exact phone companion UI (v2.1), drawn on a 390x844 design canvas
 * and scaled to the physical screen. Screens: Connect (6-box pairing code),
 * Calibrate (hold-still ring), Play (live 120° hitting zone, height gauge,
 * Smart Assist toggle, giant SWING button, context-aware primary pill) and
 * Reconnect. All touch targets report through {@link Tap}.
 */
public class PhoneUiView extends View {

    public interface Tap {
        void onTap(String action);
    }

    // Figma palette
    private static final int BG = 0xFF060F0E;
    private static final int PANEL = 0xFF091C19;
    private static final int PANEL2 = 0xFF132D25;
    private static final int LIME = 0xFFD7FF67;
    private static final int WILLOW = 0xFFF6F8EE;
    private static final int MUTED = 0xFFAEBFB7;
    private static final int DIV = 0xFF33463E;
    private static final int CORAL = 0xFFFF895E;

    public static final String SCREEN_CONNECT = "CONNECT";
    public static final String SCREEN_CALIBRATE = "CALIBRATE";
    public static final String SCREEN_PLAY = "PLAY";
    public static final String SCREEN_RECONNECT = "RECONNECT";

    private String screen = SCREEN_CONNECT;
    private final Tap tap;

    // connect screen data
    private String tvName = "Searching…";
    private boolean tvFound;
    private String code = "";
    private boolean codeError;
    private String codeErrorText = "That code doesn't match the TV. Check the number on screen.";
    private long errMs;

    // calibrate data
    private float calProgress;

    // play data
    private String scoreLine = "0 / 0";
    private String overLine = "OVER 1 · PACE";
    private String phaseHint = "";
    private String primaryLabel = "START INNINGS";
    private boolean primaryLime = true;
    private float batLine, batHeight;
    private boolean assistOn = true;
    private boolean handLeft;
    private String secondLabel = "PAUSE GAME";
    private boolean hasSecond = true;

    // reconnect data
    private String savedTv = "Fire TV";
    private String savedScore = "0 / 0";

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();
    private Typeface reg, semi, bold;

    public PhoneUiView(Context ctx, Tap t) {
        super(ctx);
        tap = t;
        try {
            reg = Typeface.createFromAsset(ctx.getAssets(), "fonts/Inter-Regular.ttf");
            semi = Typeface.createFromAsset(ctx.getAssets(), "fonts/Inter-SemiBold.ttf");
            bold = Typeface.createFromAsset(ctx.getAssets(), "fonts/Inter-Bold.ttf");
        } catch (Exception e) {
            reg = Typeface.SANS_SERIF;
            semi = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
            bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
        }
    }

    // ------------------------------------------------------------------ state
    public void setScreen(String s) {
        screen = s;
        invalidate();
    }

    public void setTv(String name, boolean found) {
        tvName = name == null || name.isEmpty() ? "Searching…" : name;
        tvFound = found;
        invalidate();
    }

    public void setCode(String digits, boolean error) {
        code = digits == null ? "" : digits;
        if (error && !codeError) {
            errMs = android.os.SystemClock.uptimeMillis();
        }
        codeError = error;
        invalidate();
    }

    /** Optional copy under the boxes while codeError (version vs code). */
    public void setCodeError(String message) {
        codeErrorText = message == null ? "" : message;
        invalidate();
    }

    public void setCalProgress(float q) {
        calProgress = Math.max(0f, Math.min(1f, q));
        invalidate();
    }

    public void setPlayHud(String score, String over, String hint) {
        scoreLine = score;
        overLine = over;
        phaseHint = hint == null ? "" : hint;
        invalidate();
    }

    public void setPrimary(String label, boolean lime) {
        primaryLabel = label;
        primaryLime = lime;
        invalidate();
    }

    public void setSecondary(String label, boolean show) {
        secondLabel = label;
        hasSecond = show;
        invalidate();
    }

    public void setPose(float lineDeg, float heightNorm) {
        batLine = lineDeg;
        batHeight = heightNorm;
        invalidate();
    }

    public void setAssist(boolean on) {
        assistOn = on;
        invalidate();
    }

    public void setSavedTv(String name, String score) {
        savedTv = name == null ? "Fire TV" : name;
        savedScore = score == null ? "0 / 0" : score;
        invalidate();
    }

    public String getCurrentScreen() {
        return screen;
    }

    public String getPrimaryLabel() {
        return primaryLabel;
    }

    public String getSecondaryLabel() {
        return secondLabel;
    }

    public void setHand(boolean left) {
        handLeft = left;
    }

    public boolean isAssistOn() {
        return assistOn;
    }

    // ------------------------------------------------------------------ draw
    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(BG);
        float sx = getWidth() / 390f;
        float sy = getHeight() / 844f;
        c.scale(sx, sy);
        switch (screen) {
            case SCREEN_CONNECT:
                drawConnect(c);
                break;
            case SCREEN_CALIBRATE:
                drawCalibrate(c);
                break;
            case SCREEN_PLAY:
                drawPlay(c);
                break;
            case SCREEN_RECONNECT:
                drawReconnect(c);
                break;
        }
    }

    private void text(Canvas c, String t, float x, float y, Typeface f, float size, int col) {
        p.setTypeface(f);
        p.setTextSize(size);
        p.setColor(col);
        c.drawText(t, x, y, p);
    }

    private void caps(Canvas c, String t, float x, float y, float size, int col) {
        text(c, t.toUpperCase(Locale.ROOT), x, y, semi, size, col);
    }

    private void panel(Canvas c, float x, float y, float w, float h, int fill) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(fill);
        r.set(x, y, x + w, y + h);
        c.drawRoundRect(r, 16f, 16f, p);
    }

    private RectF pillRect(Canvas c, String label, float x, float y, float w, float h,
                           boolean lime, boolean enabled) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(enabled ? (lime ? LIME : PANEL) : (lime ? 0x66D7FF67 : 0x66091C19));
        r.set(x, y, x + w, y + h);
        c.drawRoundRect(r, Math.min(28f, h / 2f), Math.min(28f, h / 2f), p);
        textCenter(c, label, x + w / 2f, y + h / 2f + 10f, semi, 17f, lime && enabled ? BG : WILLOW);
        return new RectF(x, y, x + w, y + h);
    }

    private void textCenter(Canvas c, String t, float cx, float y, Typeface f, float size, int col) {
        p.setTypeface(f);
        p.setTextSize(size);
        p.setColor(col);
        c.drawText(t, cx - p.measureText(t) / 2f, y, p);
    }

    // ---------------------------------------------------------------- connect
    private void drawConnect(Canvas c) {
        caps(c, "01 / CONNECT YOUR BAT", 24, 92, 12f, LIME);
        text(c, "Big screen.", 24, 136, bold, 34f, WILLOW);
        text(c, "Bigger innings.", 24, 178, bold, 34f, LIME);
        text(c, "Find your TV on the same Wi-Fi.", 24, 212, reg, 15f, MUTED);

        // TV card
        panel(c, 24, 236, 342, 88, PANEL2);
        caps(c, "FIRE TV", 44, 268, 11f, tvFound ? LIME : MUTED);
        text(c, tvName, 44, 298, semi, 20f, WILLOW);
        p.setColor(tvFound ? LIME : 0x6633463E);
        c.drawCircle(344, 280, 8f, p);

        // 6 code boxes (52x64, gap 8) starting after the label
        caps(c, "ENTER THE CODE ON YOUR TV", 24, 372, 11f, MUTED);
        float shake = 0f;
        if (codeError) {
            long dt = android.os.SystemClock.uptimeMillis() - errMs;
            if (dt < 500) {
                shake = (float) Math.sin(dt / 40.0) * 7f * (1f - dt / 500f);
            }
        }
        float bx = 24 + shake;
        for (int i = 0; i < 6; i++) {
            float x = bx + i * 60;
            boolean filled = i < code.length();
            boolean focus = i == code.length();
            p.setStyle(Paint.Style.FILL);
            p.setColor(codeError ? 0x33FF895E : (filled ? PANEL2 : PANEL));
            r.set(x, 396, x + 52, 460);
            c.drawRoundRect(r, 12f, 12f, p);
            if (focus && !codeError) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2.5f);
                p.setColor(LIME);
                c.drawRoundRect(r, 12f, 12f, p);
                p.setStyle(Paint.Style.FILL);
            }
            if (filled) {
                textCenter(c, String.valueOf(code.charAt(i)), x + 26, 442, bold, 30f,
                        codeError ? CORAL : WILLOW);
            }
        }
        if (codeError) {
            String[] lines = codeErrorText.split("\n");
            for (int i = 0; i < lines.length; i++) {
                text(c, lines[i], 24, 492 + i * 18, reg, 13f, CORAL);
            }
        }

        boolean ready = tvFound && code.length() == 6;
        pillRect(c, "CONNECT TO TV", 24, 540, 342, 56, true, ready);
        text(c, "Need help connecting?", 24, 632, semi, 15f, MUTED);
        text(c, "Boundary on Fire TV · protocol v3", 24, 800, reg, 12f, 0x80AEBFB7);
    }

    // --------------------------------------------------------------- calibrate
    private void drawCalibrate(Canvas c) {
        caps(c, "02 / SET YOUR STANCE", 24, 92, 12f, LIME);
        text(c, "Set your stance.", 24, 136, bold, 34f, WILLOW);
        text(c, "Hold your phone like a bat handle and keep still.", 24, 170, reg, 15f, MUTED);

        // hold-still ring (162x162)
        float cx = 195, cy = 320, rad = 81;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(10f);
        p.setColor(DIV);
        c.drawCircle(cx, cy, rad, p);
        p.setStrokeWidth(10f);
        p.setColor(LIME);
        r.set(cx - rad, cy - rad, cx + rad, cy + rad);
        c.drawArc(r, -90f, -90f + 360f * calProgress, false, p);
        p.setStyle(Paint.Style.FILL);

        // phone glyph inside the ring
        p.setColor(WILLOW);
        r.set(cx - 16, cy - 34, cx + 16, cy + 34);
        c.drawRoundRect(r, 6f, 6f, p);
        p.setColor(0x33000000);
        c.drawCircle(cx, cy - 22, 3f, p);

        caps(c, "MOTION ACCESS · " + (calProgress >= 1f ? "CALIBRATED" : "ENABLED"),
                24, 462, 12f, LIME);
        text(c, "Calibrating your neutral stance…", 24, 494, reg, 14f, MUTED);

        pillRect(c, calProgress >= 1f ? "READY" : "CALIBRATING…", 24, 700, 342, 56,
                true, calProgress >= 1f);
    }

    // -------------------------------------------------------------------- play
    private void drawPlay(Canvas c) {
        // score strip
        panel(c, 24, 24, 342, 64, PANEL2);
        text(c, scoreLine, 40, 56, bold, 20f, WILLOW);
        text(c, overLine, 40, 76, semi, 12f, LIME);

        // hitting zone panel (120° wedge + height gauge)
        panel(c, 24, 104, 342, 290, PANEL);
        caps(c, "120° FORWARD HITTING ZONE", 40, 134, 11f, LIME);

        float cx = 130, cy = 340, rad = 118;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x2ED7FF67);
        path.reset();
        path.moveTo(cx, cy);
        path.arcTo(new RectF(cx - rad, cy - rad, cx + rad, cy + rad), 210f, 120f);
        path.close();
        c.drawPath(path, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3f);
        p.setColor(DIV);
        c.drawArc(new RectF(cx - rad, cy - rad, cx + rad, cy + rad), 210f, 120f, false, p);
        p.setStyle(Paint.Style.FILL);

        // bat-line needle: -60..60 maps onto the 210..330 arc
        float ang = 270f - batLine; // 270 = straight ahead
        double a = Math.toRadians(ang);
        float nx = cx + (float) Math.cos(a) * (rad - 14);
        float ny = cy + (float) Math.sin(a) * (rad - 14);
        p.setStrokeWidth(5f);
        p.setColor(WILLOW);
        c.drawLine(cx, cy, nx, ny, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(LIME);
        c.drawCircle(nx, ny, 7f, p);
        textCenter(c, Math.round(batLine) + "°", cx, cy + 24, semi, 13f, MUTED);

        // height gauge (right side of the panel)
        float gx = 300, gy = 170, gw = 26, gh = 170;
        p.setColor(PANEL2);
        r.set(gx, gy, gx + gw, gy + gh);
        c.drawRoundRect(r, 13f, 13f, p);
        float q = (batHeight + 1f) / 2f; // 0 low .. 1 high
        float markerY = gy + gh - 10 - q * (gh - 20);
        p.setColor(LIME);
        r.set(gx + 3, markerY - 12, gx + gw - 3, markerY + 12);
        c.drawRoundRect(r, 10f, 10f, p);
        caps(c, "HIGH · PULL", 252, gy + 14, 10f, batHeight > 0.33f ? LIME : MUTED);
        caps(c, "MID · DRIVE", 252, gy + 88, 10f, Math.abs(batHeight) <= 0.33f ? LIME : MUTED);
        caps(c, "LOW · YORKER", 246, gy + 166, 10f, batHeight < -0.33f ? LIME : MUTED);
        text(c, "Swing through the ball", 40, 374, reg, 13f, MUTED);

        // row: handedness toggle (left) + Smart Assist switch (right)
        panel(c, 24, 412, 342, 48, PANEL);
        caps(c, handLeft ? "HAND: L" : "HAND: R", 40, 442, 12f, handLeft ? CORAL : LIME);
        caps(c, "ASSIST", 218, 442, 12f, MUTED);
        p.setColor(assistOn ? LIME : DIV);
        r.set(312, 426, 350, 446);
        c.drawRoundRect(r, 10f, 10f, p);
        p.setColor(assistOn ? BG : MUTED);
        c.drawCircle(assistOn ? 340f : 322f, 436, 7f, p);

        if (!phaseHint.isEmpty()) {
            text(c, phaseHint, 24, 500, med2(), 14f, MUTED);
        }

        // giant primary pill (thumb zone) + secondary row
        pillRect(c, primaryLabel, 24, 660, 342, 96, primaryLime, true);
        if (hasSecond) {
            pillRect(c, secondLabel, 24, 772, 342, 52, false, true);
        }
    }

    private Typeface med2() {
        return reg;
    }

    // --------------------------------------------------------------- reconnect
    private void drawReconnect(Canvas c) {
        caps(c, "RECONNECT", 24, 92, 12f, CORAL);
        text(c, "Let's reconnect.", 24, 136, bold, 34f, WILLOW);
        text(c, "Your TV is still waiting for you.", 24, 170, reg, 15f, MUTED);

        panel(c, 24, 210, 342, 108, PANEL2);
        caps(c, "LAST SCORE", 44, 242, 11f, MUTED);
        text(c, "YOU " + savedScore, 44, 286, bold, 30f, WILLOW);

        text(c, "1.  Check Wi-Fi is on, on both devices.", 24, 366, reg, 14f, MUTED);
        text(c, "2.  Keep the Boundary app open on the TV.", 24, 396, reg, 14f, MUTED);
        text(c, "3.  We retry automatically every few seconds.", 24, 426, reg, 14f, MUTED);

        pillRect(c, "RECONNECT TO " + savedTv.toUpperCase(Locale.ROOT), 24, 620, 342, 56, true, true);
        pillRect(c, "PAIR A DIFFERENT TV", 24, 692, 342, 52, false, true);
    }

    // ------------------------------------------------------------------- taps
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_DOWN) {
            return true;
        }
        float x = e.getX() / (getWidth() / 390f);
        float y = e.getY() / (getHeight() / 844f);
        String action = hit(x, y);
        if (action != null && tap != null) {
            tap.onTap(action);
        }
        return true;
    }

    private String hit(float x, float y) {
        if (SCREEN_CONNECT.equals(screen)) {
            if (y >= 396 && y <= 460 && x >= 24 && x <= 384) {
                return "codeBoxes";
            }
            if (y >= 236 && y <= 324 && x >= 24 && x <= 366) {
                return "tvCard";
            }
            if (y >= 540 && y <= 596 && x >= 24 && x <= 366) {
                return tvFound && code.length() == 6 ? "connect" : "connectDisabled";
            }
            if (y >= 616 && y <= 648 && x >= 24 && x <= 260) {
                return "help";
            }
        } else if (SCREEN_CALIBRATE.equals(screen)) {
            if (y >= 700 && y <= 756 && x >= 24 && x <= 366 && calProgress >= 1f) {
                return "calibrated";
            }
        } else if (SCREEN_PLAY.equals(screen)) {
            if (y >= 412 && y <= 460 && x >= 24 && x <= 210) {
                return "hand";
            }
            if (y >= 412 && y <= 460 && x >= 210 && x <= 366) {
                return "assist";
            }
            if (y >= 660 && y <= 756 && x >= 24 && x <= 366) {
                return "primary";
            }
            if (hasSecond && y >= 772 && y <= 824 && x >= 24 && x <= 366) {
                return "second";
            }
        } else if (SCREEN_RECONNECT.equals(screen)) {
            if (y >= 620 && y <= 676 && x >= 24 && x <= 366) {
                return "reconnectNow";
            }
            if (y >= 692 && y <= 744 && x >= 24 && x <= 366) {
                return "differentTv";
            }
        }
        return null;
    }
}

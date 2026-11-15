package com.example.boundary;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Boundary TV (v3.1) — the live 3D scene under the Figma-exact HUD.
 *
 * Layers a {@link GlSceneView} (procedural floodlit stadium, bowler, batsman,
 * pitching ball) beneath a {@link HudView} overlay, runs the fixed-step game
 * loop, hosts the pairing {@link SessionServer} (6-digit code, persistent
 * TCP, state pushes), reacts to phone commands and the Fire TV D-pad, and
 * broadcasts EVENTs to the phone on delivery/hit/wicket moments.
 */
public class MainActivity extends Activity implements SessionServer.Listener {

    private BoundaryModel model;
    private GlSceneView gl;
    private HudView hud;
    private SessionServer server;

    private final ConcurrentLinkedQueue<String[]> commands = new ConcurrentLinkedQueue<String[]>();
    private Thread loop;
    private volatile boolean running;

    private BoundaryModel.Phase lastPhase = BoundaryModel.Phase.LOBBY;
    private boolean lastBouncedForEvent;

    private String demoState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        model = new BoundaryModel(1920f, 1080f);
        model.reset();

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        hud = new HudView(this);
        hud.setModel(model);

        try {
            gl = new GlSceneView(this);
            gl.setModel(model);
            root.addView(gl);
        } catch (Throwable t) {
            gl = null; // fall back to the 2D scene inside HudView
        }
        hud.setField2D(gl == null);
        root.addView(hud);
        hud.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
        setContentView(root);

        // pairing: stable 6-digit code in preferences, friendly TV name
        SharedPreferences prefs = getSharedPreferences("boundary", MODE_PRIVATE);
        String code = prefs.getString("code", null);
        if (code == null) {
            code = PairingCode.newCode(new java.util.Random());
            prefs.edit().putString("code", code).apply();
        }
        hud.setPairing(PairingCode.display(code), tvName());

        try {
            server = new SessionServer(Protocol.DEFAULT_TCP_PORT, Protocol.DEFAULT_UDP_PORT,
                    code, tvName(), this);
            server.start();
        } catch (Exception e) {
            server = null; // TV-only play still works with the D-pad
        }

        Intent i = getIntent();
        if (i != null && i.getStringExtra("demo") != null) {
            demoState = i.getStringExtra("demo");
            hud.setDemo(demoState);
            applyDemo(demoState);
        }
    }

    private String tvName() {
        try {
            String n = Settings.Global.getString(getContentResolver(), "device_name");
            if (n != null && !n.trim().isEmpty()) {
                return n.trim();
            }
        } catch (Throwable ignored) {
        }
        String m = Build.MODEL;
        return m == null || m.isEmpty() ? "Fire TV" : m;
    }

    @Override
    protected void onResume() {
        super.onResume();
        running = true;
        if (gl != null) {
            gl.onResume();
        }
        startLoop();
    }

    @Override
    protected void onPause() {
        super.onPause();
        running = false;
        if (gl != null) {
            gl.onPause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        running = false;
        if (server != null) {
            server.stop();
        }
    }

    // =====================================================================
    // Game loop: fixed-ish tick, command drain, event pushes
    // =====================================================================
    private void startLoop() {
        if (loop != null) {
            return;
        }
        loop = new Thread(new Runnable() {
            @Override
            public void run() {
                long last = android.os.SystemClock.uptimeMillis();
                while (running) {
                    long now = android.os.SystemClock.uptimeMillis();
                    long dt = Math.min(64, now - last);
                    last = now;

                    drainCommands();

                    boolean frozen = demoState != null && demoFrozen(demoState);
                    if (demoState == null || !frozen) {
                        model.update(dt);
                        pushEvents();
                    }
                    if (server != null) {
                        server.markStateDirty();
                    }
                    hud.postInvalidate();

                    // tick at ~60 Hz
                    try {
                        Thread.sleep(16);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                loop = null;
            }
        }, "boundary-loop");
        loop.start();
    }

    private void drainCommands() {
        String[] msg;
        while ((msg = commands.poll()) != null) {
            handle(msg);
        }
    }

    private void handle(String[] msg) {
        if (msg == null || msg.length == 0) {
            return;
        }
        String verb = msg[0];
        if (Protocol.isPing(verb)) {
            return; // the session layer answers PING itself
        }
        if (Protocol.isTilt(verb) && msg.length >= 2) {
            float line = clampLine(Float.parseFloat(msg[1]));
            float height = msg.length >= 3 ? clampHeight(Float.parseFloat(msg[2])) : 0f;
            model.setBatPose(line, height);
            return;
        }
        if (Protocol.isSwing(verb)) {
            if (inPlay()) {
                model.strike();
            } else {
                activateFocused();
            }
            return;
        }
        if (Protocol.isStart(verb)) {
            if (model.getPhase() == BoundaryModel.Phase.LOBBY
                    || model.getPhase() == BoundaryModel.Phase.READY) {
                model.startInnings();
            }
            return;
        }
        if (Protocol.isNext(verb)) {
            if (model.getPhase() == BoundaryModel.Phase.RESULT) {
                model.nextBall();
            }
            return;
        }
        if (Protocol.isPause(verb)) {
            if (inPlay()) {
                model.pause();
            }
            return;
        }
        if (Protocol.isResume(verb)) {
            if (model.getPhase() == BoundaryModel.Phase.PAUSED) {
                model.resume();
            }
            return;
        }
        if (Protocol.isAgain(verb)) {
            model.reset();
            model.startInnings();
            return;
        }
        if (Protocol.isLobby(verb)) {
            model.backToLobby();
            return;
        }
        if (Protocol.isAssist(verb) && msg.length >= 2) {
            model.setAssist(!"0".equals(msg[1]));
            return;
        }
        // CALIBRATING / CALIBRATED: no TV-side action needed beyond state push
    }

    private boolean inPlay() {
        BoundaryModel.Phase p = model.getPhase();
        return p == BoundaryModel.Phase.RUNUP || p == BoundaryModel.Phase.BOWLING
                || p == BoundaryModel.Phase.FLYING;
    }

    private static float clampLine(float d) {
        return Math.max(-60f, Math.min(60f, d));
    }

    private static float clampHeight(float h) {
        return Math.max(-1f, Math.min(1f, h));
    }

    /** Push EVENT lines to the phone on game transitions. */
    private void pushEvents() {
        BoundaryModel.Phase p = model.getPhase();
        if (p != lastPhase) {
            if (p == BoundaryModel.Phase.BOWLING) {
                sendEvent("RELEASE\t" + model.getDelivery());
            } else if (p == BoundaryModel.Phase.RESULT) {
                if (model.lastWicket) {
                    sendEvent("WICKET\t" + (model.lastBowled ? "BOWLED" : "CAUGHT"));
                } else if (model.lastRuns > 0) {
                    sendEvent("HIT\t" + model.lastRuns + "\t" + model.getTimingLabel());
                } else {
                    sendEvent("DOT");
                }
            }
            lastPhase = p;
        }
    }

    private void sendEvent(String line) {
        if (server != null) {
            server.sendEvent(line);
        }
    }

    // =====================================================================
    // SessionServer.Listener
    // =====================================================================
    @Override
    public void onCommand(String[] msg) {
        commands.offer(msg);
    }

    @Override
    public void onBatConnected(String phoneName) {
        hud.setPhone(phoneName);
        if (model.getPhase() == BoundaryModel.Phase.LOBBY) {
            model.ready();
        }
        if (model.getPhase() == BoundaryModel.Phase.PAUSED) {
            model.resume();
        }
        hud.postInvalidate();
    }

    @Override
    public void onBatLost() {
        hud.setPhone(null);
        if (inPlay()) {
            model.pause(); // current delivery is not counted
        }
        hud.postInvalidate();
    }

    @Override
    public String statePayload() {
        Map<String, String> kv = model.stateMap();
        kv.put("tvName", tvName());
        return Protocol.stateMessage(kv);
    }

    // =====================================================================
    // Fire TV remote (D-pad): the TV is fully usable without the phone
    // =====================================================================
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (demoState != null) {
            return super.onKeyDown(keyCode, event);
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (inPlay()) {
                    model.setBatPose(clampLine(model.getBatAngle() - 6f), model.getBatHeight());
                    return true;
                }
                hud.focusPrev();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (inPlay()) {
                    model.setBatPose(clampLine(model.getBatAngle() + 6f), model.getBatHeight());
                    return true;
                }
                hud.focusNext();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (inPlay()) {
                    model.setBatPose(model.getBatAngle(), clampHeight(model.getBatHeight() + 0.15f));
                    return true;
                }
                hud.focusPrev();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (inPlay()) {
                    model.setBatPose(model.getBatAngle(), clampHeight(model.getBatHeight() - 0.15f));
                    return true;
                }
                hud.focusNext();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (inPlay()) {
                    model.strike();
                } else {
                    activateFocused();
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_BUTTON_START:
                if (inPlay()) {
                    model.pause();
                } else if (model.getPhase() == BoundaryModel.Phase.PAUSED) {
                    model.resume();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                BoundaryModel.Phase p = model.getPhase();
                if (inPlay()) {
                    model.pause();
                    return true;
                }
                if (p == BoundaryModel.Phase.RESULT || p == BoundaryModel.Phase.GAME_OVER
                        || p == BoundaryModel.Phase.PAUSED) {
                    model.backToLobby();
                    return true;
                }
                break;
            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** Activate the currently focused HUD pill (OK on the remote). */
    private void activateFocused() {
        int n = hud.pillCount();
        if (n <= 0) {
            return;
        }
        String action = hud.pillAction(hud.focusIndex());
        if ("start".equals(action)) {
            model.startInnings();
        } else if ("next".equals(action)) {
            model.nextBall();
        } else if ("again".equals(action)) {
            model.reset();
            model.startInnings();
        } else if ("lobby".equals(action)) {
            model.backToLobby();
        } else if ("resume".equals(action)) {
            model.resume();
        }
        hud.resetFocus();
    }

    // =====================================================================
    // Debug demo states (adb shell am start ... --es demo SIX)
    // =====================================================================
    private void applyDemo(String state) {
        model.demo(state);
    }

    /** Demo states that must not tick (frozen visual-QA frames). */
    private static boolean demoFrozen(String state) {
        return state.equals("SIX") || state.equals("DOT") || state.equals("CAUGHT")
                || state.equals("BOWLED") || state.equals("INNINGS") || state.equals("PAUSED")
                || state.equals("LOBBY") || state.equals("FLIGHT");
    }
}

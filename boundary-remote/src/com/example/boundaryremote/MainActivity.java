package com.example.boundaryremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Vibrator;
import android.text.InputType;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;

import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Boundary Companion v2.1 — the phone is the bat.
 *
 * Screen flow: auto-reconnect to a saved TV (or Connect with auto-discovery
 * + 6-digit code), a 3-second hold-still calibration that freezes the
 * neutral stance, then live play: absolute drift-free 2-axis tilt (line ±60°,
 * height across ±35°) pushed to the TV at up to 30 Hz, a giant SWING button
 * plus flick-to-swing, a live 120° hitting-zone view, Smart Assist toggle,
 * haptics for release / hit / boundary / wicket, and auto-reconnect with
 * backoff if the session drops.
 */
public class MainActivity extends Activity
        implements SessionClient.Listener, TiltTracker.Listener, PhoneUiView.Tap {

    private PhoneUiView ui;
    private SessionClient client;
    private Discovery discovery;
    private PairingPrefs prefs;
    private TiltTracker tracker;

    private List<Discovery.TvInfo> tvs;
    private Discovery.TvInfo chosenTv;
    private String code = "";
    private String connectedTvName = "Fire TV";

    private long calStartMs;
    private Timer calTimer;
    private long lastStateMs;
    private Vibrator vibrator;

    // last pushed state for the reconnect card
    private String lastScore = "0 / 0";
    private String lastScreen = "LOBBY";

    private String demoState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        ui = new PhoneUiView(this, this);
        setContentView(ui);

        prefs = new PairingPrefs(this);
        discovery = new Discovery(this);
        client = new SessionClient(this);
        tracker = new TiltTracker(this, this);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        String demo = getIntent() == null ? null : getIntent().getStringExtra("demo");
        if (demo != null) {
            demoState = demo;
            applyDemo(demo);
            return;
        }

        // saved TV -> auto-connect; otherwise start discovery
        PairingPrefs.SavedTv saved = prefs.load();
        if (saved != null) {
            chosenTv = new Discovery.TvInfo(saved.ip, saved.port, saved.name);
            code = saved.code;
            ui.setSavedTv(saved.name, "0 / 0");
            ui.setScreen(PhoneUiView.SCREEN_RECONNECT);
            connectTo(chosenTv, code);
        } else {
            ui.setScreen(PhoneUiView.SCREEN_CONNECT);
            startDiscoveryLoop();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (demoState == null) {
            tracker.start();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        tracker.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        client.disconnect();
        stopDiscoveryLoop();
    }

    // =====================================================================
    // Demo states for visual QA (adb --es demo CONNECT|CALIBRATE|PLAY|RECONNECT)
    // =====================================================================
    private void applyDemo(String s) {
        if ("CALIBRATE".equals(s)) {
            ui.setScreen(PhoneUiView.SCREEN_CALIBRATE);
            ui.setCalProgress(0.6f);
        } else if ("PLAY".equals(s)) {
            ui.setScreen(PhoneUiView.SCREEN_PLAY);
            ui.setPlayHud("18 / 0", "OVER 1 · PACE", "Watch the ball. Time your swing.");
            ui.setPrimary("SWING", true);
            ui.setSecondary("PAUSE GAME", true);
        } else if ("RECONNECT".equals(s)) {
            ui.setScreen(PhoneUiView.SCREEN_RECONNECT);
            ui.setSavedTv("Living room", "12 / 0 · 0.3 / 5");
        } else {
            ui.setScreen(PhoneUiView.SCREEN_CONNECT);
            ui.setTv("Living room", true);
            ui.setCode("48261", false);
        }
    }

    // =====================================================================
    // Discovery
    // =====================================================================
    private Timer discoverTimer;

    private void startDiscoveryLoop() {
        stopDiscoveryLoop();
        discoverTimer = new Timer();
        discoverTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                discovery.probe(new Discovery.Callback() {
                    @Override
                    public void onTvsFound(final List<Discovery.TvInfo> found) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                tvs = found;
                                if (chosenTv == null) {
                                    if (!found.isEmpty()) {
                                        chosenTv = found.get(0);
                                        ui.setTv(chosenTv.name, true);
                                    } else {
                                        ui.setTv(null, false);
                                    }
                                }
                            }
                        });
                    }
                });
            }
        }, 0, 3000);
    }

    private void stopDiscoveryLoop() {
        if (discoverTimer != null) {
            discoverTimer.cancel();
            discoverTimer = null;
        }
    }

    // =====================================================================
    // Connecting
    // =====================================================================
    private void connectTo(Discovery.TvInfo tv, String code) {
        client.connect(tv.ip, tv.port, code, phoneName());
    }

    private String phoneName() {
        String n = Build.MODEL;
        return n == null || n.isEmpty() ? "Phone" : n;
    }

    // =====================================================================
    // SessionClient.Listener
    // =====================================================================
    @Override
    public void onConnected(final String tvName) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                connectedTvName = tvName;
                stopDiscoveryLoop();
                ui.setSavedTv(tvName, lastScore);
                startCalibration();
            }
        });
    }

    @Override
    public void onState(final Map<String, String> kv) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                lastStateMs = android.os.SystemClock.uptimeMillis();
                String screen = kv.get("screen");
                if (screen != null) {
                    lastScreen = screen;
                }
                String score = kv.get("score");
                if (score != null) {
                    lastScore = score;
                }
                ui.setSavedTv(connectedTvName, score);
                String over = kv.get("over");
                ui.setPlayHud(score == null ? "0 / 0" : "YOU " + score,
                        over == null ? "" : over, kv.get("hint"));
                String assist = kv.get("assist");
                if (assist != null) {
                    ui.setAssist(!"false".equals(assist));
                }

                // primary + secondary pills follow the TV state
                if ("LOBBY".equals(lastScreen)) {
                    ui.setPrimary("START INNINGS", true);
                    ui.setSecondary("RECALIBRATE", true);
                } else if ("READY".equals(lastScreen)) {
                    ui.setPrimary("START INNINGS", true);
                    ui.setSecondary("RECALIBRATE", true);
                } else if ("RESULT".equals(lastScreen)) {
                    ui.setPrimary("NEXT DELIVERY", true);
                    ui.setSecondary("RECALIBRATE", true);
                } else if ("GAME_OVER".equals(lastScreen)) {
                    ui.setPrimary("PLAY AGAIN", true);
                    ui.setSecondary("BACK TO LOBBY", true);
                } else if ("PAUSED".equals(lastScreen)) {
                    ui.setPrimary("RESUME INNINGS", true);
                    ui.setSecondary("BACK TO LOBBY", true);
                } else {
                    ui.setPrimary("SWING", true);
                    ui.setSecondary("PAUSE GAME", true);
                }
            }
        });
    }

    @Override
    public void onEvent(final String[] f) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (f.length < 2) {
                    return;
                }
                if ("ERR".equals(f[1])) {
                    ui.setCode(code, true); // shake the boxes, show the mismatch copy
                    if ("VERSION".equals(f.length > 2 ? f[2] : "")) {
                        ui.setCode(code, true);
                    }
                    return;
                }
                String kind = f[1];
                if ("RELEASE".equals(kind)) {
                    buzz(30); // short tick on ball release
                } else if ("HIT".equals(kind)) {
                    int runs = f.length > 2 ? parseIntSafe(f[2]) : 0;
                    if (runs >= 4) {
                        buzz(60);
                        try {
                            Thread.sleep(90);
                        } catch (InterruptedException ignored) {
                        }
                        buzz(60); // double pulse on a boundary
                    } else {
                        buzz(40);
                    }
                } else if ("WICKET".equals(kind)) {
                    buzz(300); // long buzz on a wicket
                } else if ("DOT".equals(kind)) {
                    buzz(20);
                }
            }
        });
    }

    private int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public void onDisconnected() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (demoState != null) {
                    return;
                }
                ui.setSavedTv(connectedTvName, lastScore);
                ui.setScreen(PhoneUiView.SCREEN_RECONNECT);
            }
        });
    }

    // =====================================================================
    // Calibration: 3 s of stillness freezes the neutral stance
    // =====================================================================
    private void startCalibration() {
        ui.setScreen(PhoneUiView.SCREEN_CALIBRATE);
        calStartMs = android.os.SystemClock.uptimeMillis();
        if (calTimer != null) {
            calTimer.cancel();
        }
        calTimer = new Timer();
        calTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        float q = (android.os.SystemClock.uptimeMillis() - calStartMs) / 3000f;
                        ui.setCalProgress(q);
                        if (q >= 1f) {
                            calTimer.cancel();
                            calTimer = null;
                            tracker.calibrate();
                            client.send("CALIBRATED");
                            ui.setScreen(PhoneUiView.SCREEN_PLAY);
                            ui.setPrimary("START INNINGS", true);
                        }
                    }
                });
            }
        }, 50, 50);
    }

    // =====================================================================
    // TiltTracker.Listener: live 2-axis pose -> TILT (up to 30 Hz, gated in
    // the tracker) + flick -> SWING
    // =====================================================================
    @Override
    public void onPose(float lineDeg, float heightNorm) {
        ui.setPose(lineDeg, heightNorm);
        client.send(Protocol.join("TILT",
                String.format(java.util.Locale.US, "%.1f", lineDeg),
                String.format(java.util.Locale.US, "%.2f", heightNorm)));
    }

    @Override
    public void onFlick() {
        client.send("SWING");
        buzz(25);
    }

    // =====================================================================
    // PhoneUiView.Tap
    // =====================================================================
    @Override
    public void onTap(String action) {
        if ("codeBoxes".equals(action)) {
            openCodeInput();
        } else if ("tvCard".equals(action) && tvs != null && tvs.size() > 1) {
            showTvChooser();
        } else if ("connect".equals(action)) {
            stopDiscoveryLoop();
            connectTo(chosenTv, code);
        } else if ("help".equals(action)) {
            showManualIp();
        } else if ("calibrated".equals(action)) {
            // ring already complete; nothing to do
        } else if ("assist".equals(action)) {
            ui.setAssist(!ui.isAssistOn());
            client.send(ui.isAssistOn() ? "ASSIST\t1" : "ASSIST\t0");
        } else if ("primary".equals(action)) {
            sendPrimary();
        } else if ("second".equals(action)) {
            sendSecondary();
        } else if ("reconnectNow".equals(action)) {
            if (chosenTv != null) {
                connectTo(chosenTv, code);
            }
        } else if ("differentTv".equals(action)) {
            prefs.clear();
            chosenTv = null;
            code = "";
            ui.setCode("", false);
            ui.setScreen(PhoneUiView.SCREEN_CONNECT);
            startDiscoveryLoop();
        }
    }

    private void sendPrimary() {
        String label = ui.getPrimaryLabel();
        if ("SWING".equals(label)) {
            client.send("SWING");
            buzz(25);
        } else if ("START INNINGS".equals(label)) {
            client.send("START");
        } else if ("NEXT DELIVERY".equals(label)) {
            client.send("NEXT");
        } else if ("PLAY AGAIN".equals(label)) {
            client.send("AGAIN");
        } else if ("RESUME INNINGS".equals(label)) {
            client.send("RESUME");
        }
    }

    private void sendSecondary() {
        String label = ui.getSecondaryLabel();
        if ("PAUSE GAME".equals(label)) {
            client.send("PAUSE");
        } else if ("RECALIBRATE".equals(label)) {
            startCalibration();
        } else if ("BACK TO LOBBY".equals(label)) {
            client.send("LOBBY");
        }
    }

    private void showTvChooser() {
        final CharSequence[] names = new CharSequence[tvs.size()];
        for (int i = 0; i < tvs.size(); i++) {
            names[i] = tvs.get(i).name;
        }
        new AlertDialog.Builder(this)
                .setTitle("Choose your TV")
                .setItems(names, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        chosenTv = tvs.get(which);
                        ui.setTv(chosenTv.name, true);
                    }
                })
                .show();
    }

    private void showManualIp() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("192.168.1.42");
        new AlertDialog.Builder(this)
                .setTitle("Manual IP")
                .setMessage("Make sure both devices are on the same Wi-Fi, and the "
                        + "Boundary app is open on the TV.")
                .setView(input)
                .setPositiveButton("Connect", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        String ip = input.getText().toString().trim();
                        if (!ip.isEmpty() && code.length() == 6) {
                            chosenTv = new Discovery.TvInfo(ip, Protocol.DEFAULT_TCP_PORT, "Fire TV");
                            connectTo(chosenTv, code);
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void buzz(int ms) {
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(ms);
        }
    }

    // digits for the 6-box code come from a hidden input surfaced on card tap
    @Override
    public void onBackPressed() {
        if (PhoneUiView.SCREEN_CONNECT.equals(currentScreen())
                && code.length() > 0 && code.length() < 6) {
            code = code.substring(0, code.length() - 1);
            ui.setCode(code, false);
            return;
        }
        super.onBackPressed();
    }

    private String currentScreen() {
        return ui.getCurrentScreen();
    }

    /** Opens the soft keyboard with a hidden digit collector for the code boxes. */
    private void openCodeInput() {
        final EditText digits = new EditText(this);
        digits.setInputType(InputType.TYPE_CLASS_NUMBER);
        digits.setVisibility(android.view.View.INVISIBLE);
        addContentView(digits, new FrameLayout.LayoutParams(1, 1));
        digits.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                String d = s.toString().replaceAll("\\D", "");
                if (d.length() > 6) {
                    d = d.substring(0, 6);
                    s.replace(0, s.length(), d);
                    return;
                }
                code = d;
                ui.setCode(code, false);
            }
        });
        digits.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(digits, InputMethodManager.SHOW_IMPLICIT);
        }
    }

}

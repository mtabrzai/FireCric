package com.example.boundaryremote;

import android.app.Activity;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

public class MainActivity extends Activity implements View.OnClickListener, SensorEventListener {

    private static final int TCP_PORT = 8769;
    private static final int UDP_PORT = 8770;

    private View panelConnect;
    private View panelCalibrate;
    private View panelPlay;
    private EditText ipInput;
    private TextView connectStatus;
    private TextView calibText;
    private ProgressBar calibBar;
    private TextView playStatus;
    private TextView playScore;
    private TextView playHint;

    private SensorManager sensorManager;
    private Sensor gyro;
    private Sensor accel;
    private final MotionMapper mapper = new MotionMapper();
    private final Handler handler = new Handler();

    private String host = "";
    private int calibLeft = 3;
    private float baseX;
    private float baseY;
    private float baseZ;
    private boolean calibrated;
    private long lastAccel;
    private float lastAccelMag;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        panelConnect = findViewById(R.id.panel_connect);
        panelCalibrate = findViewById(R.id.panel_calibrate);
        panelPlay = findViewById(R.id.panel_play);
        ipInput = (EditText) findViewById(R.id.ip_input);
        connectStatus = (TextView) findViewById(R.id.connect_status);
        calibText = (TextView) findViewById(R.id.calib_text);
        calibBar = (ProgressBar) findViewById(R.id.calib_bar);
        playStatus = (TextView) findViewById(R.id.play_status);
        playScore = (TextView) findViewById(R.id.play_score);
        playHint = (TextView) findViewById(R.id.play_hint);
        findViewById(R.id.btn_find).setOnClickListener(this);
        findViewById(R.id.btn_connect).setOnClickListener(this);
        findViewById(R.id.btn_swing).setOnClickListener(this);
        findViewById(R.id.btn_start).setOnClickListener(this);
        findViewById(R.id.btn_pause).setOnClickListener(this);
        findViewById(R.id.btn_reset).setOnClickListener(this);
        showPanel(panelConnect);
    }

    private void showPanel(View p) {
        panelConnect.setVisibility(p == panelConnect ? View.VISIBLE : View.GONE);
        panelCalibrate.setVisibility(p == panelCalibrate ? View.VISIBLE : View.GONE);
        panelPlay.setVisibility(p == panelPlay ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (gyro != null) {
                sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME);
            }
            if (accel != null) {
                sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
            }
        }
    }

    @Override
    protected void onPause() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        handler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            if (!calibrated && panelCalibrate.getVisibility() == View.VISIBLE) {
                baseX = baseX * 0.9f + event.values[0] * 0.1f;
                baseY = baseY * 0.9f + event.values[1] * 0.1f;
                baseZ = baseZ * 0.9f + event.values[2] * 0.1f;
                return;
            }
            if (panelPlay.getVisibility() != View.VISIBLE) {
                return;
            }
            int[] d = mapper.feedGyro(event.values[0] - baseX, event.values[1] - baseY, event.values[2] - baseZ, event.timestamp);
            if (d != null && (d[0] != 0 || d[1] != 0)) {
                send("MOVE " + d[0] + " " + d[1]); // tilt = bat angle only
            }
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            long t = event.timestamp;
            if (panelPlay.getVisibility() == View.VISIBLE && t - lastAccel > 350_000_000L) {
                float mag = (float) Math.sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2]);
                if (lastAccelMag > 0 && mag - lastAccelMag > 18f) {
                    send("SWING"); // sharp flick = deliberate shot
                }
                lastAccelMag = mag;
                lastAccel = t;
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btn_find) {
            discover();
        } else if (id == R.id.btn_connect) {
            host = ipInput.getText().toString().trim();
            connectStatus.setText("Connecting to " + host + "…");
            send("PING");
        } else if (id == R.id.btn_swing) {
            send("SWING");
        } else if (id == R.id.btn_start) {
            send("S");
        } else if (id == R.id.btn_pause) {
            send("P");
        } else if (id == R.id.btn_reset) {
            send("C");
            showPanel(panelConnect);
        }
    }

    private void discover() {
        connectStatus.setText("Searching for Fire TV on Wi-Fi…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String ip = RemoteClient.discover(UDP_PORT, 2500);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            ipInput.setText(ip);
                            host = ip;
                            connectStatus.setText("Found Fire TV at " + ip);
                            send("PING");
                        }
                    });
                } catch (Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            connectStatus.setText("No Fire TV found — enter its IP manually (shown on the TV)");
                        }
                    });
                }
            }
        }).start();
    }

    private void onPong() {
        showPanel(panelCalibrate);
        calibLeft = 3;
        calibBar.setMax(3);
        calibBar.setProgress(0);
        calibrated = false;
        baseX = baseY = baseZ = 0;
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (panelCalibrate.getVisibility() != View.VISIBLE) {
                    return;
                }
                calibLeft--;
                if (calibLeft <= 0) {
                    calibrated = true;
                    mapper.reset();
                    playStatus.setText("CONNECTED TO " + host.toUpperCase());
                    playHint.setText("01 Calibration complete — Neutral position saved. Ready for your first ball.\nYour bat is ready. Eyes on the TV. Phone held securely.\nTilt the phone left/right to angle the bat (120° forward zone). The shot fires automatically when the bat meets the ball. SWING button / flick = deliberate shot.");
                    showPanel(panelPlay);
                    send("PING");
                    return;
                }
                calibBar.setProgress(3 - calibLeft);
                calibText.setText("HOLD STILL\n" + calibLeft + " second" + (calibLeft > 1 ? "s" : "") + " remaining");
                handler.postDelayed(this, 1000);
            }
        };
        calibText.setText("HOLD STILL\n3 seconds remaining");
        handler.postDelayed(tick, 1000);
    }

    private void send(final String token) {
        if (host.length() == 0) {
            connectStatus.setText("Enter the Fire TV IP or tap Find TV");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String[] r = RemoteClient.command(host, TCP_PORT, token);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (r[0].equals("PONG")) {
                                if (panelConnect.getVisibility() == View.VISIBLE && token.equals("PING")) {
                                    onPong();
                                } else {
                                    connectStatus.setText("Connected to " + host + ":" + TCP_PORT);
                                    playStatus.setText("CONNECTED TO " + host.toUpperCase());
                                }
                                return;
                            }
                            playScore.setText(r[0]);
                            playHint.setText(r[1] + "\nTilt phone = bat angle (±60°) · auto-fires on contact · SWING button/flick = deliberate shot");
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            TextView where = panelConnect.getVisibility() == View.VISIBLE ? connectStatus : playStatus;
                            where.setText("Connection failed: " + e.getMessage() + " — check same Wi-Fi");
                        }
                    });
                }
            }
        }).start();
    }
}

package com.example.boundaryremote;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;

/**
 * Drift-free absolute 2-axis bat tracker.
 *
 * Uses TYPE_GAME_ROTATION_VECTOR (drift-free, no magnetic interference),
 * falling back to TYPE_ROTATION_VECTOR, then to accelerometer gravity
 * tilt for phones without a gyro. Computes the absolute line/height from
 * the calibrated neutral pose via {@link TiltMath} and emits samples at up
 * to 30 Hz, only when the pose changed meaningfully. Flick detection uses
 * the accelerometer (a sharp |a| spike = a physical swing).
 */
public class TiltTracker implements SensorEventListener {

    public interface Listener {
        void onPose(float lineDeg, float heightNorm);

        void onFlick();
    }

    private final SensorManager sm;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private float neutralRoll, neutralPitch;
    private boolean calibrated;

    private float sentLine = Float.MAX_VALUE;
    private float sentHeight = Float.MAX_VALUE;
    private long lastSendMs;
    private long lastFlickMs;
    private boolean usingAccelOnly;

    public TiltTracker(Context ctx, Listener l) {
        sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        listener = l;
    }

    /** Starts listening on the best available orientation source. */
    public void start() {
        Sensor s = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (s == null) {
            s = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }
        if (s != null) {
            sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME);
        } else {
            usingAccelOnly = true;
        }
        Sensor acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (acc != null) {
            sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    public void stop() {
        sm.unregisterListener(this);
    }

    public boolean isCalibrated() {
        return calibrated;
    }

    public boolean isUsingAccelOnly() {
        return usingAccelOnly;
    }

    /** Freezes the current orientation as the neutral batting stance. */
    public void calibrate() {
        calibrated = true;
        sentLine = Float.MAX_VALUE;
        sentHeight = Float.MAX_VALUE;
    }

    void setNeutralForTest(float roll, float pitch) {
        neutralRoll = roll;
        neutralPitch = pitch;
        calibrated = true;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        long now = android.os.SystemClock.uptimeMillis();
        if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float ax = e.values[0], ay = e.values[1], az = e.values[2];
            float mag = (float) Math.sqrt(ax * ax + ay * ay + az * az);
            // flick: a sharp total-acceleration spike = a physical swing
            if (Math.abs(mag - 9.81f) > 18f && now - lastFlickMs > 350) {
                lastFlickMs = now;
                listener.onFlick();
            }
            if (usingAccelOnly) {
                float rollDeg = (float) Math.toDegrees(Math.atan2(-ax, Math.sqrt(ay * ay + az * az)));
                float pitchDeg = (float) Math.toDegrees(Math.atan2(ay, Math.sqrt(ax * ax + az * az)));
                emit(rollDeg, pitchDeg, now);
            }
            return;
        }
        // rotation-vector family: build the quaternion (zxy screen layout)
        float[] q = new float[4];
        SensorManager.getQuaternionFromVector(q, e.values);
        float[] eu = TiltMath.toEuler(q[1], q[2], q[3], q[0]); // roll about the screen long axis
        // screen-reframe: the phone is held portrait like a bat handle
        float rollDeg = eu[0];
        float pitchDeg = eu[1];
        emit(rollDeg, pitchDeg, now);
    }

    private void emit(float rollDeg, float pitchDeg, long now) {
        if (!calibrated) {
            neutralRoll = rollDeg;
            neutralPitch = pitchDeg;
            return;
        }
        final float line = TiltMath.batLine(rollDeg, neutralRoll);
        final float height = TiltMath.batHeight(pitchDeg, neutralPitch);
        if (!TiltMath.shouldSend(line, height, sentLine, sentHeight)) {
            return;
        }
        if (now - lastSendMs < 33) { // cap at ~30 Hz
            return;
        }
        sentLine = line;
        sentHeight = height;
        lastSendMs = now;
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onPose(line, height);
            }
        });
    }

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) {
    }
}

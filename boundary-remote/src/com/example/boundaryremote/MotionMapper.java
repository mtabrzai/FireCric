package com.example.boundaryremote;

public class MotionMapper {

    public static final float GYRO_K = 900f;
    public static final float DEADZONE = 0.08f;
    public static final float ACCEL_K = 260f;
    public static final long FLUSH_NS = 50_000_000L;
    public static final int BASELINE_SAMPLES = 30;

    private float accX;
    private float accY;
    private long lastTs = -1;
    private long lastFlushNs = -1;
    private boolean hasBaseline;
    private float baseX;
    private float baseY;
    private float baseZ;
    private int baselineSamples;

    public synchronized void reset() {
        accX = 0;
        accY = 0;
        lastTs = -1;
        lastFlushNs = -1;
        hasBaseline = false;
        baselineSamples = 0;
    }

    public synchronized int[] feedGyro(float rx, float ry, float rz, long tsNs) {
        if (lastTs < 0) {
            lastTs = tsNs;
            lastFlushNs = tsNs;
            return null;
        }
        float dt = (tsNs - lastTs) / 1e9f;
        lastTs = tsNs;
        if (dt <= 0 || dt > 0.5f) {
            return null;
        }
        float vx = (Math.abs(ry) < DEADZONE ? 0f : ry * GYRO_K) + (Math.abs(rz) < DEADZONE ? 0f : rz * GYRO_K);
        float vy = Math.abs(rx) < DEADZONE ? 0f : -rx * GYRO_K;
        accX += vx * dt;
        accY += vy * dt;
        return flush(tsNs);
    }

    public synchronized int[] feedAccel(float ax, float ay, float az, long tsNs) {
        if (!hasBaseline) {
            if (baselineSamples == 0) {
                baseX = ax;
                baseY = ay;
                baseZ = az;
            } else {
                baseX += (ax - baseX) / (baselineSamples + 1);
                baseY += (ay - baseY) / (baselineSamples + 1);
                baseZ += (az - baseZ) / (baselineSamples + 1);
            }
            baselineSamples++;
            lastTs = tsNs;
            lastFlushNs = tsNs;
            if (baselineSamples >= BASELINE_SAMPLES) {
                hasBaseline = true;
            }
            return null;
        }
        if (lastTs < 0) {
            lastTs = tsNs;
            lastFlushNs = tsNs;
            return null;
        }
        float dt = (tsNs - lastTs) / 1e9f;
        lastTs = tsNs;
        if (dt <= 0 || dt > 0.5f) {
            return null;
        }
        float vx = (ax - baseX) * ACCEL_K;
        float vy = (az - baseZ) * ACCEL_K;
        accX += vx * dt;
        accY += vy * dt;
        return flush(tsNs);
    }

    private int[] flush(long tsNs) {
        if (tsNs - lastFlushNs < FLUSH_NS) {
            return null;
        }
        lastFlushNs = tsNs;
        int dx = Math.round(accX);
        int dy = Math.round(accY);
        accX -= dx;
        accY -= dy;
        if (dx == 0 && dy == 0) {
            return null;
        }
        return new int[]{dx, dy};
    }
}

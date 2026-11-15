package com.example.boundaryremote;

/**
 * Pure 2-axis tilt math for the phone-bat (protocol v3.1).
 *
 * The tracker reads the device orientation as a quaternion (game rotation
 * vector, rotation vector, or accelerometer gravity as a fallback), holds a
 * calibrated neutral pose, and derives ABSOLUTE roll (bat line, ±60°) and
 * pitch (bat height, ±1 normalised across ±35°) relative to that neutral.
 * Because every sample is computed from the absolute orientation, gyro
 * drift never accumulates. A 1.5° deadzone keeps the bat still when the
 * hand trembles slightly.
 */
public final class TiltMath {

    public static final float LINE_SPAN = 60f;   // ±60° hitting zone
    public static final float PITCH_SPAN = 35f;  // phone pitch mapped to the height axis
    public static final float DEADZONE = 1.5f;   // degrees of no-response around neutral

    /** Euler angles (deg) of a rotation vector / quaternion sample (x,y,z,w). */
    public static float[] toEuler(float x, float y, float z, float w) {
        float sqw = w * w, sqx = x * x, sqy = y * y, sqz = z * z;
        float rollDeg = (float) Math.toDegrees(Math.atan2(2f * (w * x + y * z), sqw - sqx - sqy + sqz));
        float pitchDeg = (float) Math.toDegrees(Math.atan2(2f * (w * y - z * x), sqw + sqz - sqx - sqy));
        return new float[]{rollDeg, pitchDeg};
    }

    /** Maps a raw absolute angle through the deadzone onto ±limit, clamped. */
    public static float throughDeadzone(float rawDeg, float neutralDeg, float limitDeg) {
        float rel = wrap180(rawDeg - neutralDeg);
        float sign = rel < 0f ? -1f : 1f;
        float mag = Math.abs(rel);
        if (mag <= DEADZONE) {
            return 0f;
        }
        float beyond = mag - DEADZONE;
        float span = limitDeg - DEADZONE;
        float norm = Math.min(1f, beyond / span);
        return sign * norm * limitDeg;
    }

    /** Bat line in degrees (±60) from the absolute roll around the neutral. */
    public static float batLine(float rollDeg, float neutralRollDeg) {
        return throughDeadzone(rollDeg, neutralRollDeg, LINE_SPAN);
    }

    /** Bat height in [-1, +1] from the absolute pitch around the neutral. */
    public static float batHeight(float pitchDeg, float neutralPitchDeg) {
        return throughDeadzone(pitchDeg, neutralPitchDeg, PITCH_SPAN) / PITCH_SPAN;
    }

    /** Shortest signed difference a-b in [-180, 180]. */
    public static float wrap180(float a) {
        float d = a % 360f;
        if (d > 180f) {
            d -= 360f;
        }
        if (d < -180f) {
            d += 360f;
        }
        return d;
    }

    /** True when the sample changed enough to be worth sending (> 0.5°). */
    public static boolean shouldSend(float lineDeg, float heightNorm,
                                     float sentLineDeg, float sentHeightNorm) {
        return Math.abs(lineDeg - sentLineDeg) > 0.5f
                || Math.abs(heightNorm - sentHeightNorm) > 0.02f;
    }

    private TiltMath() {
    }
}

package com.example.boundary;

import java.util.Random;

/**
 * Pure pairing-code logic (unit-tested). The TV keeps one stable 6-digit code
 * per install; the phone must type it to confirm it found the right TV.
 */
public final class PairingCode {

    /** A stable random 6-digit code in [100000, 999999]. */
    public static String newCode(Random r) {
        return String.format("%06d", 100000 + r.nextInt(900000));
    }

    /** Digits-only comparison: tolerant of spaces typed by the player. */
    public static boolean matches(String stored, String given) {
        String a = digits(stored);
        String b = digits(given);
        return a.length() == 6 && a.equals(b);
    }

    /** "123456" -> "123 456" for the big lobby display. */
    public static String display(String code) {
        String d = digits(code);
        return d.length() == 6 ? d.substring(0, 3) + " " + d.substring(3) : "··· ···";
    }

    /** Strips everything that is not a digit. */
    public static String digits(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c >= '0' && c <= '9') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private PairingCode() {
    }
}

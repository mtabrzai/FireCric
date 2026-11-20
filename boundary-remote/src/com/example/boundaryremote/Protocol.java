package com.example.boundaryremote;

import java.util.HashMap;
import java.util.Map;

/**
 * Boundary wire protocol v3 (shared verbatim between the TV app and the phone
 * companion; the regression suite asserts the two copies stay identical below
 * the package line).
 *
 * Transport: one persistent TCP connection (default port 8771), UTF-8,
 * '\n'-terminated, tab-separated messages. Discovery: UDP broadcast on port
 * 8772. The TV and the phone must run matching protocol versions; a mismatch
 * gets a clear ERR VERSION reply.
 *
 * Phone -> TV
 *   HELLO\t<version>\t<code>\t<phoneName>   first line of a new connection
 *   TILT\t<lineDeg>\t<heightNorm>            2-axis bat pose, up to 30 Hz
 *   SWING | START | NEXT | PAUSE | RESUME | AGAIN | LOBBY
 *   CALIBRATING | CALIBRATED                 calibration lifecycle
 *   ASSIST\t<0|1>                            Smart Assist toggle
 *   PING                                    heartbeat, ~1 Hz
 *
 * TV -> Phone
 *   WELCOME\t<tvName>                       pairing accepted
 *   ERR\tCODE | ERR\tVERSION                pairing rejected, then close
 *   PONG                                    heartbeat reply
 *   STATE\tk=v\tk=v...                      pushed on change, >= 1 Hz
 *   EVENT\tRELEASE | HIT\t<runs>\t<timing> | WICKET | DOT | BOWL
 */
public final class Protocol {

    public static final int VERSION = 3;
    public static final int DEFAULT_TCP_PORT = 8771;
    public static final int DEFAULT_UDP_PORT = 8772;

    public static final String DISCOVER_REQUEST = "BOUNDARY_DISCOVER " + VERSION;
    public static final String DISCOVER_REPLY = "BOUNDARY";

    /** Message field separator and line terminator helpers. */
    public static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('\t');
            }
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    /** Splits a wire line into fields (tabs and the 1-arg TILT fallback ok). */
    public static String[] parse(String line) {
        if (line == null) {
            return new String[0];
        }
        return line.split("\t", -1);
    }

    /** True when the line is the v3 discovery probe. */
    public static boolean isDiscover(String line) {
        return line != null && line.startsWith(DISCOVER_REQUEST);
    }

    /** Discovery reply: BOUNDARY <version> <ip> <tcpPort> <urlEncodedTvName>. */
    public static String discoverReply(String ip, int tcpPort, String tvName) {
        return DISCOVER_REPLY + " " + VERSION + " " + (ip == null ? "" : ip)
                + " " + tcpPort + " " + urlEncode(tvName == null ? "Fire TV" : tvName);
    }

    /** Parsed discovery reply: {version, ip, tcpPort, tvName}. */
    public static String[] parseDiscoverReply(String line) {
        if (line == null || !line.startsWith(DISCOVER_REPLY + " ")) {
            return null;
        }
        String[] p = line.split(" ", 5);
        if (p.length < 4) {
            return null;
        }
        String name = p.length > 4 ? urlDecode(p[4]) : "Fire TV";
        return new String[]{p[1], p[2], p[3], name};
    }

    public static String stateMessage(Map<String, String> kv) {
        StringBuilder sb = new StringBuilder("STATE");
        for (Map.Entry<String, String> e : kv.entrySet()) {
            sb.append('\t').append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** Parses a STATE message body into a map (unknown lines -> empty map). */
    public static Map<String, String> parseState(String line) {
        Map<String, String> out = new HashMap<String, String>();
        String[] fields = parse(line);
        for (int i = 1; i < fields.length; i++) {
            int eq = fields[i].indexOf('=');
            if (eq > 0) {
                out.put(fields[i].substring(0, eq), fields[i].substring(eq + 1));
            }
        }
        return out;
    }

    /** Minimal URL encoding for the TV name in the discovery reply. */
    public static String urlEncode(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(java.nio.charset.Charset.forName("UTF-8"))) {
            char c = (char) (b & 0xFF);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c);
            } else {
                sb.append('%').append(hex((b >> 4) & 0xF)).append(hex(b & 0xF));
            }
        }
        return sb.toString();
    }

    public static String urlDecode(String s) {
        if (s == null) {
            return "";
        }
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    bytes.write((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            bytes.write((byte) c);
        }
        return new String(bytes.toByteArray(), java.nio.charset.Charset.forName("UTF-8"));
    }

    private static char hex(int nibble) {
        return "0123456789ABCDEF".charAt(nibble);
    }

    // ---- command verb classification (shared by both apps) ---------------

    public static boolean isPing(String verb) {
        return "PING".equals(verb);
    }

    public static boolean isTilt(String verb) {
        return "TILT".equals(verb);
    }

    public static boolean isSwing(String verb) {
        return "SWING".equals(verb);
    }

    public static boolean isStart(String verb) {
        return "START".equals(verb);
    }

    public static boolean isNext(String verb) {
        return "NEXT".equals(verb);
    }

    public static boolean isPause(String verb) {
        return "PAUSE".equals(verb);
    }

    public static boolean isResume(String verb) {
        return "RESUME".equals(verb);
    }

    public static boolean isAgain(String verb) {
        return "AGAIN".equals(verb);
    }

    public static boolean isLobby(String verb) {
        return "LOBBY".equals(verb);
    }

    public static boolean isAssist(String verb) {
        return "ASSIST".equals(verb);
    }

    /** RUN: attempt one more run between the wickets while the ball is in play. */
    public static boolean isRun(String verb) {
        return "RUN".equals(verb);
    }

    /** HAND\tL|R: choose left- or right-handed batsman. */
    public static boolean isHand(String verb) {
        return "HAND".equals(verb);
    }

    private Protocol() {
    }
}

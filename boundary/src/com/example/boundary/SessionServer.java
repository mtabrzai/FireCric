package com.example.boundary;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Boundary pairing + session server (protocol v3). One persistent TCP
 * connection carries the single active bat: pairing-checked HELLO, then
 * commands streamed to the game through the listener, STATE pushed at 1 Hz or
 * on demand, EVENT lines sent the instant something happens, and a 3 s silence
 * watchdog that drops the bat mid-delivery so the game pauses safely.
 */
public class SessionServer {

    public interface Listener {
        /** A validated command from the active bat (msg[0] is the verb). */
        void onCommand(String[] msg);

        /** A phone just paired (or took over as the active bat). */
        void onBatConnected(String phoneName);

        /** The active bat went silent / disconnected. */
        void onBatLost();

        /** STATE body payload (k=v fields, no prefix). */
        String statePayload();

        // ---- two-player variants (default bridge to the single-bat methods) ----
        /** A validated command from bat `slot` (0 = P1/1P, 1 = P2). */
        default void onCommand(String[] msg, int slot) {
            onCommand(msg);
        }

        default void onBatConnected(String phoneName, int slot) {
            onBatConnected(phoneName);
        }

        default void onBatLost(int slot) {
            onBatLost();
        }
    }

    private final int tcpPort;
    private final int udpPort;
    private final String code;
    private final String tvName;
    private final Listener listener;

    private volatile boolean running;
    private ServerSocket serverSocket;
    private DatagramSocket udpSocket;

    // up to two bats: slot 0 = P1 (or the single 1P bat), slot 1 = P2
    private final Socket[] batSockets = new Socket[2];
    private final BufferedWriter[] batWriters = new BufferedWriter[2];
    private final String[] batNames = new String[]{"", ""};
    private final long[] lastRx = new long[]{0L, 0L};
    private volatile int mode = 0; // 0 = unset, 1 = one player, 2 = two players
    private volatile boolean hadBat = false;
    private volatile boolean stateDirty = true;

    public SessionServer(int tcpPort, int udpPort, String code, String tvName, Listener listener) {
        this.tcpPort = tcpPort;
        this.udpPort = udpPort;
        this.code = code == null ? "000000" : PairingCode.digits(code);
        this.tvName = tvName == null ? "Fire TV" : tvName;
        this.listener = listener;
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        running = true;
        serverSocket = new ServerSocket(tcpPort);
        udpSocket = new DatagramSocket(udpPort);
        startThread("session-accept", acceptLoop);
        startThread("session-udp", udpLoop);
        startThread("session-watchdog", watchdogLoop);
        startThread("session-pusher", pusherLoop);
    }

    public synchronized void stop() {
        running = false;
        closeBat(0);
        closeBat(1);
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (udpSocket != null) {
                udpSocket.close();
            }
        } catch (Exception ignored) {
        }
    }

    /** Ask the pusher thread to send a STATE line immediately. */
    public void markStateDirty() {
        stateDirty = true;
    }

    /** Sends an EVENT line to all connected bats right away (no-op when none). */
    public void sendEvent(String eventLine) {
        sendLine("EVENT\t" + eventLine);
    }

    public boolean hasBat() {
        return batSockets[0] != null || batSockets[1] != null;
    }

    public boolean hasSlot(int slot) {
        return slot >= 0 && slot < 2 && batSockets[slot] != null;
    }

    /** True when the configured mode is satisfied (1P: any bat; 2P: both slots). */
    public boolean modeSatisfied() {
        return mode == 2 ? (hasSlot(0) && hasSlot(1)) : hasSlot(0);
    }

    public int mode() {
        return mode;
    }

    public String batName() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2; i++) {
            if (batSockets[i] != null && batNames[i] != null && !batNames[i].isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(" · ");
                }
                sb.append(mode == 2 ? "P" + (i + 1) + " " : "").append(batNames[i]);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ loops

    private final Runnable acceptLoop = new Runnable() {
        @Override
        public void run() {
            while (running) {
                try {
                    final Socket s = serverSocket.accept();
                    startThread("session-conn", new Runnable() {
                        @Override
                        public void run() {
                            serve(s);
                        }
                    });
                } catch (IOException e) {
                    if (running) {
                        e.printStackTrace();
                    }
                }
            }
        }
    };

    private final Runnable udpLoop = new Runnable() {
        @Override
        public void run() {
            byte[] buf = new byte[512];
            while (running) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    udpSocket.receive(p);
                    String msg = new String(p.getData(), 0, p.getLength(), "UTF-8");
                    if (Protocol.isDiscover(msg.trim())) {
                        // the pairing code is NEVER broadcast
                        String ip = getLocalIp();
                        byte[] reply = Protocol.discoverReply(ip, tcpPort, tvName).getBytes("UTF-8");
                        udpSocket.send(new DatagramPacket(reply, reply.length, p.getAddress(), p.getPort()));
                    }
                } catch (IOException e) {
                    if (running) {
                        e.printStackTrace();
                    }
                }
            }
        }
    };

    private final Runnable watchdogLoop = new Runnable() {
        @Override
        public void run() {
            while (running) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < 2; i++) {
                    if (batSockets[i] != null
                            && System.currentTimeMillis() - lastRx[i] > 3000) {
                        closeBat(i);
                        listener.onBatLost(i);
                        stateDirty = true;
                    }
                }
            }
        }
    };

    private final Runnable pusherLoop = new Runnable() {
        @Override
        public void run() {
            while (running) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
                if (!hasBat()) {
                    continue;
                }
                if (stateDirty || anySlotIdle(1000)) {
                    String payload = listener.statePayload();
                    if (payload != null && sendLine("STATE\t" + payload)) {
                        stateDirty = false;
                    }
                }
            }
        }
    };

    // ------------------------------------------------------------------ serve

    private void serve(Socket s) {
        int slot = -1;
        boolean active = false;
        try {
            s.setTcpNoDelay(true);
            s.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"));
            String line;
            while ((line = in.readLine()) != null) {
                String[] msg = Protocol.parse(line.trim());
                if (msg.length == 0) {
                    continue;
                }
                String verb = msg[0];
                if ("PING".equals(verb)) {
                    write(out, "PONG");
                    continue;
                }
                if ("HELLO".equals(verb)) {
                    slot = helloOk(msg, s, out); // -1 = rejected + closed
                    if (slot < 0) {
                        return;
                    }
                    active = true;
                    continue;
                }
                if (!active) { // commands before a valid HELLO
                    write(out, "ERR\tVERSION");
                    return;
                }
                lastRx[slot] = System.currentTimeMillis();
                if ("TILT".equals(verb)) {
                    listener.onCommand(msg, slot); // high rate: no per-line state push
                } else {
                    listener.onCommand(msg, slot);
                    stateDirty = true; // discrete command -> refresh STATE soon
                }
            }
        } catch (IOException ignored) {
        } finally {
            if (active && slot >= 0 && batSockets[slot] == s) {
                closeBat(slot);
                listener.onBatLost(slot);
                stateDirty = true;
            }
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** HELLO\t<version>\t<code>\t<phoneName>[\t<role 1P|P1|P2>] -> slot, or -1 if rejected. */
    private int helloOk(String[] msg, Socket s, BufferedWriter out) throws IOException {
        int version = msg.length > 1 ? parseInt(msg[1]) : -1;
        if (version != Protocol.VERSION) {
            write(out, "ERR\tVERSION");
            return -1;
        }
        if (msg.length < 3 || !PairingCode.matches(code, msg[2])) {
            write(out, "ERR\tCODE");
            return -1;
        }
        String name = msg.length > 3 && msg[3].length() > 0 ? msg[3] : "Phone";
        String role = msg.length > 4 ? msg[4].trim().toUpperCase() : "1P";
        boolean twoP = "P1".equals(role) || "P2".equals(role);
        int wantSlot;
        synchronized (this) {
            if (mode == 0) {
                mode = twoP ? 2 : 1;
            }
            if (twoP && mode != 2) {
                write(out, "ERR\tMODE"); // TV is in 1-player mode
                return -1;
            }
            if (!twoP && mode != 1) {
                write(out, "ERR\tMODE"); // TV is in 2-player mode
                return -1;
            }
            wantSlot = "P2".equals(role) ? 1 : 0;
            if (!twoP) {
                role = "1P";
            }
        }
        Socket prev = batSockets[wantSlot]; // a new HELLO on a slot takes over
        if (prev != null && prev != s) {
            try {
                prev.close();
            } catch (IOException ignored) {
            }
        }
        batSockets[wantSlot] = s;
        batWriters[wantSlot] = out;
        batNames[wantSlot] = name;
        lastRx[wantSlot] = System.currentTimeMillis();
        hadBat = true;
        write(out, Protocol.join("WELCOME", tvName, role));
        listener.onBatConnected(name, wantSlot);
        stateDirty = true;
        return wantSlot;
    }

    private boolean anySlotIdle(long ms) {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 2; i++) {
            if (batSockets[i] != null && now - lastRx[i] > ms) {
                return true;
            }
        }
        return false;
    }

    /** Broadcast a line to every connected bat. */
    private boolean sendLine(String line) {
        boolean any = false;
        for (int i = 0; i < 2; i++) {
            BufferedWriter w = batWriters[i];
            if (w == null) {
                continue;
            }
            synchronized (w) {
                try {
                    w.write(line);
                    w.newLine();
                    w.flush();
                    any = true;
                } catch (IOException e) {
                    closeBat(i);
                }
            }
        }
        return any;
    }

    private void closeBat(int slot) {
        if (slot < 0 || slot > 1) {
            return;
        }
        Socket s;
        synchronized (this) {
            s = batSockets[slot];
            batSockets[slot] = null;
            batWriters[slot] = null;
        }
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        if (!hasBat()) {
            hadBat = false;
        }
    }

    private static void write(BufferedWriter out, String line) throws IOException {
        out.write(line);
        out.newLine();
        out.flush();
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void startThread(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    public static String getLocalIp() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a instanceof Inet4Address) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (SocketException ignored) {
        }
        return null;
    }
}

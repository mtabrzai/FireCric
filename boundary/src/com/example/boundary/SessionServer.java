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
    }

    private final int tcpPort;
    private final int udpPort;
    private final String code;
    private final String tvName;
    private final Listener listener;

    private volatile boolean running;
    private ServerSocket serverSocket;
    private DatagramSocket udpSocket;

    // the one active bat
    private final AtomicReference<Socket> batSocket = new AtomicReference<Socket>();
    private final AtomicReference<BufferedWriter> batWriter = new AtomicReference<BufferedWriter>();
    private final AtomicReference<String> batName = new AtomicReference<String>("");
    private final AtomicLong lastRxMs = new AtomicLong(0);
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
        closeBat();
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

    /** Sends an EVENT line to the active bat right away (no-op when none). */
    public void sendEvent(String eventLine) {
        sendLine("EVENT\t" + eventLine);
    }

    public boolean hasBat() {
        return batSocket.get() != null;
    }

    public String batName() {
        return batName.get();
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
                if (hadBat && batSocket.get() != null
                        && System.currentTimeMillis() - lastRxMs.get() > 3000) {
                    closeBat();
                    listener.onBatLost();
                    stateDirty = true;
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
                if (batSocket.get() == null) {
                    continue;
                }
                if (stateDirty || System.currentTimeMillis() - lastRxMs.get() > 1000) {
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
                    if (!helloOk(msg, s, out)) {
                        return; // rejected + closed
                    }
                    active = true;
                    continue;
                }
                if (!active) { // commands before a valid HELLO
                    write(out, "ERR\tVERSION");
                    return;
                }
                lastRxMs.set(System.currentTimeMillis());
                if ("TILT".equals(verb)) {
                    listener.onCommand(msg); // high rate: no per-line state push
                } else {
                    listener.onCommand(msg);
                    stateDirty = true; // discrete command -> refresh STATE soon
                }
            }
        } catch (IOException ignored) {
        } finally {
            if (active && batSocket.compareAndSet(s, null)) {
                batWriter.set(null);
                hadBat = false;
                listener.onBatLost();
                stateDirty = true;
            }
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private boolean helloOk(String[] msg, Socket s, BufferedWriter out) throws IOException {
        // HELLO\t<version>\t<code>\t<phoneName>
        int version = msg.length > 1 ? parseInt(msg[1]) : -1;
        if (version != Protocol.VERSION) {
            write(out, "ERR\tVERSION");
            return false;
        }
        if (msg.length < 3 || !PairingCode.matches(code, msg[2])) {
            write(out, "ERR\tCODE");
            return false;
        }
        String name = msg.length > 3 && msg[3].length() > 0 ? msg[3] : "Phone";
        Socket prev = batSocket.get(); // single active bat: a new HELLO takes over
        if (prev != null && prev != s) {
            try {
                prev.close();
            } catch (IOException ignored) {
            }
        }
        batSocket.set(s);
        batWriter.set(out);
        batName.set(name);
        hadBat = true;
        lastRxMs.set(System.currentTimeMillis());
        write(out, "WELCOME\t" + tvName);
        listener.onBatConnected(name);
        stateDirty = true;
        return true;
    }

    private boolean sendLine(String line) {
        BufferedWriter w = batWriter.get();
        if (w == null) {
            return false;
        }
        synchronized (w) {
            try {
                w.write(line);
                w.newLine();
                w.flush();
                return true;
            } catch (IOException e) {
                closeBat();
                return false;
            }
        }
    }

    private void closeBat() {
        Socket s = batSocket.getAndSet(null);
        batWriter.set(null);
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
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

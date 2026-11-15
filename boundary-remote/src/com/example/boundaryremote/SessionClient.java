package com.example.boundaryremote;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The persistent phone-side session (protocol v3).
 *
 * One long-lived TCP connection with a writer queue (no per-message socket,
 * no latency), a 1 Hz PING heartbeat, main-thread callbacks, and automatic
 * reconnect with backoff. Parses WELCOME/ERR on connect, STATE pushes and
 * EVENT notifications from the TV.
 */
public class SessionClient {

    public interface Listener {
        void onConnected(String tvName);

        void onState(Map<String, String> state);

        void onEvent(String[] fields);

        void onDisconnected();
    }

    private final Listener listener;
    /** Main-thread poster; null off-device, callbacks then run inline (tests). */
    private final Handler main = makeMainHandler();
    private final ConcurrentLinkedQueue<String> outbox = new ConcurrentLinkedQueue<String>();

    private Socket socket;
    private BufferedWriter writer;
    private volatile boolean wantConnected;
    private volatile boolean helloSent;
    private volatile boolean welcomed;
    private String host;
    private int port;
    private String code;
    private String phoneName = "Phone";
    private int backoffMs = 1000;
    private Thread netThread;
    private Thread pingThread;
    private long lastRecvMs;

    public SessionClient(Listener l) {
        listener = l;
    }

    private static Handler makeMainHandler() {
        try {
            return new Handler(Looper.getMainLooper());
        } catch (Throwable t) {
            return null; // plain JVM (regression tests): callbacks run inline
        }
    }

    /** Runs the callback on the main thread, or inline when there is none. */
    private void post(Runnable r) {
        if (main != null) {
            main.post(r);
        } else {
            r.run();
        }
    }

    /** Connect (or reconnect) to a TV. Safe to call again; replaces the session. */
    public synchronized void connect(String host, int port, String code, String phoneName) {
        this.host = host;
        this.port = port;
        this.code = code;
        if (phoneName != null && !phoneName.isEmpty()) {
            this.phoneName = phoneName;
        }
        wantConnected = true;
        helloSent = false;
        welcomed = false;
        restartThreads();
    }

    public synchronized void disconnect() {
        wantConnected = false;
        closeSocket();
    }

    public boolean isConnected() {
        return welcomed;
    }

    /** Queue a wire line; dropped silently when offline. */
    public void send(String line) {
        outbox.offer(line);
    }

    private void restartThreads() {
        if (netThread != null && netThread.isAlive()) {
            return; // the running loop reconnects with the fresh host/code
        }
        netThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runNet();
            }
        }, "boundary-session");
        netThread.start();
    }

    private void runNet() {
        while (wantConnected) {
            try {
                openSocket();
                readLoop();
            } catch (IOException e) {
                // fall through to reconnect
            }
            closeSocket();
            if (!wantConnected) {
                break;
            }
            welcomed = false;
            helloSent = false;
            post(new Runnable() {
                @Override
                public void run() {
                    listener.onDisconnected();
                }
            });
            sleep(backoffMs);
            backoffMs = Math.min(5000, (int) (backoffMs * 1.6f));
        }
        synchronized (this) {
            if (netThread == Thread.currentThread()) {
                netThread = null;
            }
        }
    }

    private void openSocket() throws IOException {
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        s.setSoTimeout(5000);
        s.connect(new InetSocketAddress(host, port), 4000);
        socket = s;
        writer = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"));
        BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
        this.reader = reader;
        // HELLO is always the first line of a new connection
        outbox.clear();
        writeLine(Protocol.join("HELLO", String.valueOf(Protocol.VERSION), code, phoneName));
        helloSent = true;
        startWriter();
        startPing();
        lastRecvMs = System.currentTimeMillis();
        readerLoop(reader);
    }

    private volatile BufferedReader reader;

    /** Dedicated writer loop: drains queued lines at ~60 Hz for a responsive bat. */
    private void startWriter() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (wantConnected && socket != null) {
                    drainOutbox();
                    sleep(16);
                }
            }
        }, "boundary-writer");
        t.start();
    }

    private void readLoop() throws IOException {
        // kept for symmetry; openSocket runs the reader loop
    }

    private void readerLoop(final BufferedReader reader) throws IOException {
        String line;
        while (wantConnected && (line = reader.readLine()) != null) {
            lastRecvMs = System.currentTimeMillis();
            handleLine(line);
        }
        if (wantConnected && System.currentTimeMillis() - lastRecvMs > 5000) {
            throw new SocketTimeoutException("session stalled");
        }
    }

    private void handleLine(String line) {
        final String[] f = Protocol.parse(line);
        if (f.length == 0) {
            return;
        }
        if ("WELCOME".equals(f[0])) {
            welcomed = true;
            backoffMs = 1000;
            final String tv = f.length > 1 ? f[1] : "Fire TV";
            post(new Runnable() {
                @Override
                public void run() {
                    listener.onConnected(tv);
                }
            });
        } else if ("ERR".equals(f[0])) {
            wantConnected = false; // bad code / version: stop retrying
            final String why = f.length > 1 ? f[1] : "CODE";
            post(new Runnable() {
                @Override
                public void run() {
                    listener.onEvent(new String[]{"ERR", why});
                }
            });
        } else if ("PONG".equals(f[0])) {
            // heartbeat only
        } else if ("STATE".equals(f[0])) {
            final Map<String, String> kv = Protocol.parseState(line);
            post(new Runnable() {
                @Override
                public void run() {
                    listener.onState(kv);
                }
            });
        } else if ("EVENT".equals(f[0])) {
            post(new Runnable() {
                @Override
                public void run() {
                    listener.onEvent(f);
                }
            });
        }
    }

    private void startPing() {
        if (pingThread != null) {
            return;
        }
        pingThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (wantConnected && welcomed) {
                    outbox.offer("PING");
                    // the TV pauses the game after 3 s of silence
                    if (System.currentTimeMillis() - lastRecvMs > 3000) {
                        closeSocket(); // force reconnect path
                        break;
                    }
                    sleep(1000);
                }
                pingThread = null;
            }
        }, "boundary-ping");
        pingThread.start();
    }

    /** Flush queued lines to the socket (called from the ping loop). */
    private void drainOutbox() {
        BufferedWriter w = writer;
        if (w == null) {
            return;
        }
        String line;
        while ((line = outbox.poll()) != null) {
            try {
                writeLine(line);
            } catch (IOException e) {
                closeSocket();
                return;
            }
        }
        try {
            w.flush();
        } catch (IOException e) {
            closeSocket();
        }
    }

    private void writeLine(String line) throws IOException {
        BufferedWriter w = writer;
        if (w == null) {
            throw new IOException("closed");
        }
        w.write(line);
        w.write('\n');
        w.flush();
    }

    private void closeSocket() {
        Socket s = socket;
        socket = null;
        writer = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

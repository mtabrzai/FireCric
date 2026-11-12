package com.example.boundary;

import android.app.Activity;
import android.os.Bundle;
import android.widget.FrameLayout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private static final int TCP_PORT = 8771;
    private static final int UDP_PORT = 8772;
    private static final long PHONE_TIMEOUT_MS = 6000;

    private GlSceneView glView;
    private HudView hudView;
    private BoundaryModel model;
    private Thread loopThread;
    private volatile boolean running;
    private RemoteServer server;
    private String remoteBase = "";
    private int rxCount;
    private volatile long lastRxTime;
    private volatile boolean everRx;
    private long lastStatusUpdate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        model = new BoundaryModel(1920, 1080);
        hudView = new HudView(this);
        hudView.setModel(model);
        FrameLayout root = new FrameLayout(this);
        try {
            glView = new GlSceneView(this);
            glView.setModel(model);
            root.addView(glView, new FrameLayout.LayoutParams(-1, -1));
        } catch (Throwable t) {
            glView = null; // GL unavailable -> 2D fallback field in HudView
            hudView.field2D = true;
        }
        root.addView(hudView, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        startLoop();
        startWatchdog();
    }

    private void startLoop() {
        running = true;
        loopThread = new Thread(new Runnable() {
            @Override
            public void run() {
                long last = System.currentTimeMillis();
                while (running) {
                    try {
                        long now = System.currentTimeMillis();
                        model.update(now - last);
                        last = now;
                        hudView.postInvalidate();
                    } catch (Throwable ignored) {
                    }
                    try {
                        Thread.sleep(16);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "boundary-loop");
        loopThread.setDaemon(true);
        loopThread.start();
    }

    @Override
    protected void onStart() {
        super.onStart();
        startRemote();
    }

    @Override
    protected void onStop() {
        if (server != null) {
            server.stop();
            server = null;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        running = false;
        if (server != null) {
            server.stop();
        }
        super.onDestroy();
    }

    private void startWatchdog() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        return;
                    }
                    boolean connected = !everRx || (System.currentTimeMillis() - lastRxTime) < PHONE_TIMEOUT_MS;
                    hudView.phoneConnected = connected;
                    hudView.everConnected = everRx;
                }
            }
        }, "watchdog").start();
    }

    static String pairingCode(String ip) {
        if (ip == null) {
            return "000 000";
        }
        String[] p = ip.split("\\.");
        int a = p.length >= 4 ? Integer.parseInt(p[2]) : 0;
        int b = p.length >= 4 ? Integer.parseInt(p[3]) : 0;
        return String.format("%03d %03d", a % 1000, b % 1000);
    }

    private void startRemote() {
        if (server != null) {
            return;
        }
        final String ip = RemoteServer.getLocalIp();
        remoteBase = ip != null ? ip : "unknown";
        hudView.pairingCode = pairingCode(ip);
        hudView.ipText = remoteBase;
        server = new RemoteServer(TCP_PORT, UDP_PORT, new RemoteServer.CommandHandler() {
            @Override
            public String[] handle(final String token) {
                final CountDownLatch latch = new CountDownLatch(1);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        handleRemoteToken(token);
                        latch.countDown();
                    }
                });
                try {
                    latch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                BoundaryModel m = model;
                if (m == null) {
                    return new String[]{"…", ""};
                }
                return new String[]{m.display(), m.history()};
            }
        });
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    server.start();
                } catch (Exception e) {
                    final String msg = e.getMessage();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            hudView.serverError = msg;
                        }
                    });
                }
            }
        }, "remote-start").start();
    }

    private void handleRemoteToken(String token) {
        rxCount++;
        lastRxTime = System.currentTimeMillis();
        everRx = true;
        long now = System.currentTimeMillis();
        if (now - lastStatusUpdate > 500) {
            lastStatusUpdate = now;
            hudView.rxCount = rxCount;
        }
        BoundaryModel m = model;
        if (m == null) {
            return;
        }
        if (token.startsWith("MOVE ")) {
            String[] p = token.split(" ");
            if (p.length == 3) {
                try {
                    m.moveBat(Float.parseFloat(p[1]), Float.parseFloat(p[2]));
                } catch (NumberFormatException ignored) {
                }
            }
        } else if (token.equals("CLICK") || token.equals("=") || token.equals("SWING")) {
            m.strike();
        } else if (token.equals("S")) {
            m.startInnings();
        } else if (token.equals("C")) {
            m.reset();
        }
    }
}

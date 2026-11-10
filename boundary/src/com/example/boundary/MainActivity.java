package com.example.boundary;

import android.app.Activity;
import android.os.Bundle;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private static final int TCP_PORT = 8769;
    private static final int UDP_PORT = 8770;
    private static final long PHONE_TIMEOUT_MS = 6000;

    private BoundaryView gameView;
    private RemoteServer server;
    private int rxCount;
    private volatile long lastRxTime;
    private volatile boolean everRx;
    private long lastStatusUpdate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        gameView = new BoundaryView(this);
        setContentView(gameView);
        startWatchdog();
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
                    gameView.phoneConnected = connected;
                    gameView.everConnected = everRx;
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
        String ip = RemoteServer.getLocalIp();
        gameView.pairingCode = pairingCode(ip);
        gameView.ipText = ip != null ? ip : "unknown";
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
                BoundaryModel m = gameView.getModel();
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
                            gameView.serverError = msg;
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
            gameView.rxCount = rxCount;
        }
        BoundaryModel m = gameView.getModel();
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

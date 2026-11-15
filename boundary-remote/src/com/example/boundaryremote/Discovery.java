package com.example.boundaryremote;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

/**
 * UDP discovery of Boundary TVs on the local Wi-Fi.
 *
 * Broadcasts the v3 probe on port 8772 (with a MulticastLock held for the
 * probe, because many devices filter broadcast traffic otherwise) and
 * collects `BOUNDARY 3 <ip> <port> <tvName>` replies. The pairing code is
 * never broadcast; it is only exchanged over the TCP session.
 */
public class Discovery {

    /** One discovered TV. */
    public static class TvInfo {
        public final String ip;
        public final int port;
        public final String name;

        public TvInfo(String ip, int port, String name) {
            this.ip = ip;
            this.port = port;
            this.name = name;
        }
    }

    public interface Callback {
        void onTvsFound(List<TvInfo> tvs);
    }

    private final WifiManager wifi;

    public Discovery(Context ctx) {
        wifi = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    /**
     * Runs one discovery sweep (blocking; call from a background thread).
     * Replies are de-duplicated by ip. Always invokes cb (possibly empty).
     */
    public void probe(final Callback cb) {
        WifiManager.MulticastLock lock = null;
        try {
            if (wifi != null) {
                lock = wifi.createMulticastLock("boundary-discovery");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
            List<TvInfo> found = new ArrayList<TvInfo>();
            DatagramSocket sock = new DatagramSocket();
            sock.setBroadcast(true);
            sock.setSoTimeout(900);
            byte[] req = Protocol.DISCOVER_REQUEST.getBytes("UTF-8");
            InetAddress bcast = InetAddress.getByName("255.255.255.255");
            sock.send(new DatagramPacket(req, req.length, bcast, Protocol.DEFAULT_UDP_PORT));
            byte[] buf = new byte[256];
            long deadline = System.currentTimeMillis() + 1200;
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(p);
                } catch (SocketTimeoutException ste) {
                    break;
                }
                String line = new String(p.getData(), 0, p.getLength(), "UTF-8").trim();
                String[] f = Protocol.parseDiscoverReply(line);
                if (f == null || f.length < 4) {
                    continue;
                }
                try {
                    TvInfo tv = new TvInfo(f[1], Integer.parseInt(f[2]), f[3]);
                    boolean dup = false;
                    for (TvInfo t : found) {
                        if (t.ip.equals(tv.ip)) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) {
                        found.add(tv);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            sock.close();
            cb.onTvsFound(found);
        } catch (IOException e) {
            cb.onTvsFound(new ArrayList<TvInfo>());
        } finally {
            if (lock != null && lock.isHeld()) {
                lock.release();
            }
        }
    }
}

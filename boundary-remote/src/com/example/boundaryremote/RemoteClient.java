package com.example.boundaryremote;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

public class RemoteClient {

    public static final String DISCOVER_REQUEST = "FIRECALC_DISCOVER";
    public static final String DISCOVER_REPLY = "FIRECALC";

    public static String[] command(String host, int port, String token) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 3000);
            s.setSoTimeout(5000);
            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"));
            w.write(token);
            w.newLine();
            w.flush();
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            String line = r.readLine();
            if (line == null) {
                throw new IOException("empty response");
            }
            if (line.equals("PONG")) {
                return new String[]{"PONG", ""};
            }
            if (line.startsWith("OK\t")) {
                String[] p = line.split("\t", -1);
                return new String[]{p.length > 1 ? p[1] : "", p.length > 2 ? p[2] : ""};
            }
            throw new IOException(line.startsWith("ERR\t") ? line.substring(4) : line);
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static String discover(int udpPort, int timeoutMs) throws IOException {
        DatagramSocket s = new DatagramSocket();
        try {
            s.setBroadcast(true);
            s.setSoTimeout(timeoutMs);
            byte[] req = DISCOVER_REQUEST.getBytes("UTF-8");
            List<InetAddress> targets = new ArrayList<InetAddress>();
            targets.add(InetAddress.getByName("255.255.255.255"));
            String local = localIp();
            if (local != null) {
                int dot = local.lastIndexOf('.');
                if (dot > 0) {
                    targets.add(InetAddress.getByName(local.substring(0, dot + 1) + "255"));
                }
            }
            for (InetAddress t : targets) {
                s.send(new DatagramPacket(req, req.length, t, udpPort));
            }
            long end = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < end) {
                byte[] buf = new byte[256];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (SocketTimeoutException e) {
                    break;
                }
                String msg = new String(p.getData(), 0, p.getLength(), "UTF-8");
                if (msg.startsWith(DISCOVER_REPLY + " ")) {
                    String[] parts = msg.split(" ");
                    if (parts.length >= 3 && parts[1].length() > 0) {
                        return parts[1];
                    }
                }
            }
            throw new IOException("no Fire TV found on the network");
        } finally {
            s.close();
        }
    }

    public static String localIp() {
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
        } catch (Exception ignored) {
        }
        return null;
    }
}

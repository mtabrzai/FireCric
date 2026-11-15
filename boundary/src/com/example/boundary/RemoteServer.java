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

public class RemoteServer {

    public interface CommandHandler {
        String[] handle(String token);
    }

    public static final String DISCOVER_REQUEST = "FIRECALC_DISCOVER";
    public static final String DISCOVER_REPLY = "FIRECALC";

    private final int tcpPort;
    private final int udpPort;
    private final CommandHandler handler;

    private volatile boolean running;
    private ServerSocket serverSocket;
    private DatagramSocket udpSocket;
    private Thread acceptThread;
    private Thread udpThread;

    public RemoteServer(int tcpPort, int udpPort, CommandHandler handler) {
        this.tcpPort = tcpPort;
        this.udpPort = udpPort;
        this.handler = handler;
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        running = true;
        serverSocket = new ServerSocket(tcpPort);
        udpSocket = new DatagramSocket(udpPort);
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "remote-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        udpThread = new Thread(new Runnable() {
            @Override
            public void run() {
                udpLoop();
            }
        }, "remote-udp");
        udpThread.setDaemon(true);
        udpThread.start();
    }

    public synchronized void stop() {
        running = false;
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

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = serverSocket.accept();
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        serve(s);
                    }
                }, "remote-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void serve(Socket s) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"));
            String line;
            while ((line = in.readLine()) != null) {
                String token = line.trim();
                if (token.length() == 0) {
                    continue;
                }
                String resp;
                if (token.equals("PING")) {
                    resp = "PONG";
                } else {
                    try {
                        String[] r = handler.handle(token);
                        resp = "OK\t" + r[0] + "\t" + (r.length > 1 ? r[1] : "");
                    } catch (Exception e) {
                        resp = "ERR\t" + e.getMessage();
                    }
                }
                out.write(resp);
                out.newLine();
                out.flush();
            }
            s.close();
        } catch (IOException ignored) {
        }
    }

    private void udpLoop() {
        byte[] buf = new byte[256];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                udpSocket.receive(p);
                String msg = new String(p.getData(), 0, p.getLength(), "UTF-8");
                if (msg.startsWith(DISCOVER_REQUEST)) {
                    String ip = getLocalIp();
                    byte[] reply = (DISCOVER_REPLY + " " + (ip == null ? "" : ip) + " " + tcpPort).getBytes("UTF-8");
                    udpSocket.send(new DatagramPacket(reply, reply.length, p.getAddress(), p.getPort()));
                }
            } catch (IOException e) {
                if (running) {
                    e.printStackTrace();
                }
            }
        }
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

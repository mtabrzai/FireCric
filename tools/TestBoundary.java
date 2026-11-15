import com.example.boundary.BoundaryModel;
import com.example.boundary.RemoteServer;
import com.example.boundaryremote.MotionMapper;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;
import java.util.Random;

/** Regression suite for the pure-Java model + protocol (README "Tests" section). */
public class TestBoundary {

    static int passed = 0, failed = 0;

    static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("PASS " + name); }
        else { failed++; System.out.println("FAIL " + name); }
    }

    static BoundaryModel playFullInnings(Random r) {
        BoundaryModel m = new BoundaryModel(1920, 1080, r);
        m.startInnings();
        long guard = 0;
        while (!m.isGameOver() && guard++ < 20000) {
            m.update(16);
            if (m.getPhase() == BoundaryModel.Phase.BOWLING && m.isBallLive()) {
                // randomly angle the bat so some balls are hit, some missed
                if (r.nextBoolean()) {
                    m.moveBat(r.nextFloat() * 40 - 20, 0);
                }
            }
        }
        return m;
    }

    public static void main(String[] args) throws Exception {
        Random r = new Random(42);

        // run bands
        check("bandRuns 1/2/3/4/6", BoundaryModel.bandRuns(100) == 1 && BoundaryModel.bandRuns(200) == 2
                && BoundaryModel.bandRuns(350) == 3 && BoundaryModel.bandRuns(500) == 4
                && BoundaryModel.bandRuns(700) == 6);

        // wobble bounds: pitch marker stays near the centre line
        boolean wobbleOk = true;
        for (int i = 0; i < 500; i++) {
            BoundaryModel m = new BoundaryModel(1920, 1080, new Random(i));
            m.startInnings();
            float dx = Math.abs(m.pitchX - 960f);
            if (dx > 1920f * 0.03f + 1f) wobbleOk = false;
        }
        check("pitch wobble within ±3% width", wobbleOk);

        // 1.5 s ball travel release -> bat
        {
            BoundaryModel m = new BoundaryModel(1920, 1080, new Random(7));
            m.startInnings();
            long ms = 0;
            while (m.getPhase() == BoundaryModel.Phase.RUNUP && ms < 5000) { m.update(16); ms += 16; }
            ms = 0;
            while (m.getPhase() == BoundaryModel.Phase.BOWLING && ms < 5000) { m.update(16); ms += 16; }
            check("ball travel ~1.5 s (run-up 900ms + flight 1500ms±50)", ms >= 1450 && ms <= 1550);
        }

        // ±60° clamp, no-shake EMA glide
        {
            BoundaryModel m = new BoundaryModel(1920, 1080, new Random(7));
            for (int i = 0; i < 200; i++) m.moveBat(20, 0);
            for (int i = 0; i < 40; i++) m.update(16);
            check("bat angle clamped to +60", Math.abs(m.getBatAngle() - 60f) < 0.5f);
            for (int i = 0; i < 200; i++) m.moveBat(-40, 0);
            for (int i = 0; i < 40; i++) m.update(16);
            check("bat angle clamped to -60", Math.abs(m.getBatAngle() + 60f) < 0.5f);
            float a0 = m.getBatAngle();
            m.moveBat(0, 0); m.update(16);
            check("no-shake: angle barely moves on zero input", Math.abs(m.getBatAngle() - a0) < 0.5f);
            check("batsman fixed at crease centre", m.getBatX() == 960f);
        }

        // auto-hit / auto-dot / 12-ball innings -> GAME_OVER
        {
            BoundaryModel m = playFullInnings(new Random(99));
            // let the final RESULT phase elapse into GAME_OVER
            long guard = 0;
            while (m.getPhase() != BoundaryModel.Phase.GAME_OVER && guard++ < 2000) {
                m.update(16);
            }
            List<String> log = m.getLastBalls(12);
            check("12 balls logged", log.size() == 12);
            check("innings reaches GAME_OVER", m.isGameOver()
                    && m.getPhase() == BoundaryModel.Phase.GAME_OVER);
            int dots = 0, hits = 0, wkts = 0;
            for (String s : log) { if (s.equals("0")) dots++; else if (s.equals("W")) wkts++; else hits++; }
            check("mix of dots and runs (auto-contact + auto-dot)", dots + hits + wkts == 12 && hits > 0 && dots > 0);
        }
        {
            // deliberate dot: hold the bat far outside the ±18° contact tolerance
            BoundaryModel m = new BoundaryModel(1920, 1080, new Random(3));
            m.startInnings();
            for (int i = 0; i < 60; i++) { m.moveBat(20, 0); m.update(16); }  // peg at +60°
            long guard = 0;
            boolean sawDot = false;
            while (!m.isGameOver() && guard++ < 20000) {
                m.update(16);
                for (int i = 0; i < 8; i++) { m.moveBat(10, 0); }  // keep it pegged at the limit
                if (m.getPhase() == BoundaryModel.Phase.RESULT
                        && !m.getLastBalls(1).isEmpty()
                        && m.getLastBalls(1).get(0).equals("0")) sawDot = true;
            }
            check("dot ball when angle misses the line", sawDot);
        }

        // wire protocol: TCP command -> response, UDP discovery
        {
            final String[] got = {null};
            RemoteServer srv = new RemoteServer(18771, 18772, new RemoteServer.CommandHandler() {
                public String[] handle(String token) {
                    got[0] = token;
                    return new String[]{"YOU: 1/0", "0.0 / 2"};
                }
            });
            new Thread(new Runnable() { public void run() { try { srv.start(); } catch (Exception ignored) {} }}).start();
            Thread.sleep(300);
            // UDP discovery probe (FIRECALC_DISCOVER -> FIRECALC <ip> <tcpPort>)
            DatagramSocket udp = new DatagramSocket();
            byte[] probe = RemoteServer.DISCOVER_REQUEST.getBytes("UTF-8");
            udp.send(new DatagramPacket(probe, probe.length, InetAddress.getByName("127.0.0.1"), 18772));
            udp.setSoTimeout(1500);
            byte[] buf = new byte[256];
            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            boolean discovered = false;
            try {
                udp.receive(resp);
                String s = new String(resp.getData(), 0, resp.getLength(), "UTF-8");
                discovered = s.startsWith(RemoteServer.DISCOVER_REPLY) && s.endsWith("18771");
            } catch (Exception ignored) {}
            udp.close();
            check("UDP discovery announces the TV", discovered);

            java.net.Socket tcp = new java.net.Socket("127.0.0.1", 18771);
            tcp.setSoTimeout(2000);
            java.io.OutputStream os = tcp.getOutputStream();
            os.write("PING\n".getBytes("UTF-8")); os.flush();
            java.io.BufferedReader rd = new java.io.BufferedReader(new java.io.InputStreamReader(tcp.getInputStream()));
            String line = rd.readLine();
            check("TCP PING -> PONG/status reply", line != null && (line.contains("PONG") || line.contains("YOU")));
            os.write("S\n".getBytes("UTF-8")); os.flush();
            Thread.sleep(200);
            check("TCP command reaches handler", "S".equals(got[0]));
            tcp.close();
            srv.stop();
        }

        // MotionMapper sanity (phone side): deadzone + proportional mapping
        {
            MotionMapper mm = new MotionMapper();
            mm.feedGyro(0f, 0f, 0f, 0L);                        // first call: init only
            int[] z = mm.feedGyro(0.01f, 0f, 0f, 25_000_000L);  // under deadzone
            int[] big = mm.feedGyro(0.5f, 0f, 0f, 85_000_000L); // real tilt, past the 50ms flush
            boolean dz = z == null || (z[0] == 0 && z[1] == 0);
            boolean mv = big != null && (Math.abs(big[0]) > 0 || Math.abs(big[1]) > 0);
            check("MotionMapper deadzone ignores micro-tilts", dz);
            check("MotionMapper maps real tilt to movement", mv);
        }

        System.out.println("\n" + passed + " passed, " + failed + " failed, " + (passed + failed) + " total");
        if (failed > 0) System.exit(1);
    }
}

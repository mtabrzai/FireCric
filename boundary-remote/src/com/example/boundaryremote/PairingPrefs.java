package com.example.boundaryremote;

import android.content.Context;
import android.content.SharedPreferences;

/** Remembers the last paired TV so the app auto-reconnects without the code. */
public class PairingPrefs {

    public static class SavedTv {
        public final String name;
        public final String ip;
        public final int port;
        public final String code;

        public SavedTv(String name, String ip, int port, String code) {
            this.name = name;
            this.ip = ip;
            this.port = port;
            this.code = code;
        }
    }

    private final SharedPreferences prefs;

    public PairingPrefs(Context ctx) {
        prefs = ctx.getSharedPreferences("boundary_remote", Context.MODE_PRIVATE);
    }

    public void save(SavedTv tv) {
        prefs.edit()
                .putString("name", tv.name)
                .putString("ip", tv.ip)
                .putInt("port", tv.port)
                .putString("code", tv.code)
                .apply();
    }

    public SavedTv load() {
        String ip = prefs.getString("ip", null);
        if (ip == null || ip.isEmpty()) {
            return null;
        }
        return new SavedTv(
                prefs.getString("name", "Fire TV"),
                ip,
                prefs.getInt("port", Protocol.DEFAULT_TCP_PORT),
                prefs.getString("code", ""));
    }

    public void clear() {
        prefs.edit().clear().apply();
    }

    /** Remember the player-mode choice: mode 1|2 and slot 1|2. */
    public void saveRole(int mode, int slot) {
        prefs.edit().putInt("mode", mode).putInt("slot", slot).apply();
    }

    /** @return int[]{mode, slot}, defaults {1, 1}. */
    public int[] loadRole() {
        return new int[]{prefs.getInt("mode", 1) == 2 ? 2 : 1, prefs.getInt("slot", 1) == 2 ? 2 : 1};
    }
}

package dev.hjih.trapbot;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    static SharedPreferences sp(Context c) { return c.getSharedPreferences("bot", Context.MODE_PRIVATE); }

    static boolean white(Context c) { return sp(c).getBoolean("white", true); }
    static int depth(Context c) { return Math.max(1, Math.min(100, sp(c).getInt("depth", 8))); }
    static float timeSec(Context c) { return Math.max(0.1f, Math.min(100f, sp(c).getFloat("time", 3f))); }
    static int cun(Context c) { return sp(c).getInt("cun", 150); }
    static boolean adapt(Context c) { return sp(c).getBoolean("adapt", true); }
    static boolean drag(Context c) { return sp(c).getBoolean("drag", false); }

    /** видимость плавающих кнопок, % (0 = полностью невидимы, но нажимаются) */
    static int btnAlpha(Context c) { return Math.max(0, Math.min(100, sp(c).getInt("balpha", 0))); }

    /** положение плавающей кнопки (пиксели экрана) или null */
    static int[] btnPos(Context c, String name) {
        SharedPreferences p = sp(c);
        if (!p.contains("fx_" + name)) return null;
        return new int[]{p.getInt("fx_" + name, 0), p.getInt("fy_" + name, 0)};
    }
    static void setBtnPos(Context c, String name, int x, int y) {
        sp(c).edit().putInt("fx_" + name, x).putInt("fy_" + name, y).apply();
    }

    static String tpl(Context c) { return sp(c).getString("tpl", null); }
    static void setTpl(Context c, String s) { sp(c).edit().putString("tpl", s).apply(); }

    /** x, y, size доски в пикселях экрана, либо null */
    static int[] board(Context c) {
        SharedPreferences p = sp(c);
        int s = p.getInt("bs", 0);
        if (s <= 0) return null;
        return new int[]{p.getInt("bx", 0), p.getInt("by", 0), s};
    }
    static void setBoard(Context c, int x, int y, int s) {
        sp(c).edit().putInt("bx", x).putInt("by", y).putInt("bs", s).apply();
    }
}

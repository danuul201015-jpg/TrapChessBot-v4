package dev.hjih.trapbot;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Шахматный движок «Ловушки v3» — чистая Java, без WebView и JS.
 * Интерфейс прежний: think(...) → колбэк на главном потоке с JSON
 * (ff, fr, tf, tr, pr, info | none, over | error).
 */
final class EngineBridge {
    interface CB { void done(JSONObject o); }

    private final TrapEngine eng;
    private final ExecutorService ex;
    private final Handler h = new Handler(Looper.getMainLooper());
    private CB cb;
    private Runnable wd;
    private int seq;

    EngineBridge(android.content.Context c) {
        int cores = Runtime.getRuntime().availableProcessors();
        eng = new TrapEngine(Math.max(1, Math.min(6, cores)));
        ex = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "trap-engine");
                t.setDaemon(true);
                return t;
            }
        });
    }

    void think(final String fen, final int depth, final double sec, final int cun, final boolean adapt,
               JSONArray ban, CB callback) {
        cb = callback;
        final int id = ++seq;
        final int[][] banList = new int[ban == null ? 0 : ban.length()][];
        for (int i = 0; i < banList.length; i++) {
            JSONArray a = ban.optJSONArray(i);
            banList[i] = a == null || a.length() < 4 ? new int[]{-1, -1, -1, -1}
                    : new int[]{a.optInt(0), a.optInt(1), a.optInt(2), a.optInt(3)};
        }
        h.removeCallbacks(wd);
        wd = new Runnable() {
            @Override public void run() {
                if (cb != null && id == seq) {
                    CB c2 = cb;
                    cb = null;
                    eng.cancel();
                    JSONObject e = new JSONObject();
                    try { e.put("error", "движок не ответил (таймаут)"); } catch (JSONException ignored) { }
                    c2.done(e);
                }
            }
        };
        h.postDelayed(wd, Math.round(sec * 1000) + 12000);
        ex.execute(new Runnable() {
            @Override public void run() {
                TrapEngine.Result r;
                try {
                    r = eng.handle(fen, depth, Math.round(sec * 1000), cun, adapt, banList);
                } catch (Throwable t) {
                    r = new TrapEngine.Result();
                    r.error = String.valueOf(t);
                }
                final JSONObject o = toJson(r);
                h.post(new Runnable() {
                    @Override public void run() {
                        if (id != seq) return;
                        h.removeCallbacks(wd);
                        CB c = cb;
                        cb = null;
                        if (c != null) c.done(o);
                    }
                });
            }
        });
    }

    private static JSONObject toJson(TrapEngine.Result r) {
        JSONObject o = new JSONObject();
        try {
            if (r.error != null) o.put("error", r.error);
            else if (r.none) { o.put("none", true); o.put("over", r.over == null ? "Нет ходов" : r.over); }
            else {
                o.put("ff", r.ff).put("fr", r.fr).put("tf", r.tf).put("tr", r.tr).put("pr", r.pr);
                o.put("info", r.info);
            }
        } catch (JSONException ignored) { }
        return o;
    }

    void destroy() {
        h.removeCallbacks(wd);
        cb = null;
        seq++;
        eng.shutdown();
        ex.shutdownNow();
    }
}

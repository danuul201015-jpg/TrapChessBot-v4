package dev.hjih.trapbot;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;

/** Шахматный движок (JS из Trap_Chess.html) в скрытом WebView, внутри Web Worker. */
final class EngineBridge {
    interface CB { void done(JSONObject o); }

    private static final String HOST = "appassets.androidplatform.net";

    private final WebView wv;
    private final Handler h = new Handler(Looper.getMainLooper());
    private CB cb;
    private boolean loaded;
    private String queued;
    private Runnable wd;
    private int seq;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    EngineBridge(final Context c) {
        wv = new WebView(c);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(false);
        wv.addJavascriptInterface(new Bridge(), "Android");
        wv.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return asset(c, r.getUrl());
            }
            @Override public void onPageFinished(WebView v, String u) {
                loaded = true;
                if (queued != null) { String q = queued; queued = null; run(q); }
            }
        });
        wv.loadUrl("https://" + HOST + "/index.html");
    }

    private static WebResourceResponse asset(Context c, Uri u) {
        if (!HOST.equals(u.getHost()))
            return new WebResourceResponse("text/plain", "utf-8", 403, "Blocked",
                    new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
        String p = u.getPath();
        if (p == null || p.equals("/")) p = "/index.html";
        try {
            InputStream in = c.getAssets().open(p.substring(1));
            String mime = p.endsWith(".html") ? "text/html" : p.endsWith(".js") ? "application/javascript" : "text/plain";
            return new WebResourceResponse(mime, "utf-8", in);
        } catch (IOException e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                    new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
        }
    }

    void think(String fen, int depth, double sec, int cun, boolean adapt, CB callback) {
        cb = callback;
        final int id = ++seq;
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("fen", fen);
            o.put("depth", depth);
            o.put("ms", Math.round(sec * 1000));
            o.put("cun", cun);
            o.put("adapt", adapt);
        } catch (JSONException ignored) { }
        h.removeCallbacks(wd);
        wd = new Runnable() {
            @Override public void run() {
                if (cb != null && id == seq) {
                    CB c2 = cb;
                    cb = null;
                    JSONObject e = new JSONObject();
                    try { e.put("error", "движок не ответил (таймаут)"); } catch (JSONException ignored) { }
                    c2.done(e);
                }
            }
        };
        h.postDelayed(wd, Math.round(sec * 1000) + 12000);
        String js = "run(" + o + ")";
        if (!loaded) queued = js; else run(js);
    }

    private void run(String js) { wv.evaluateJavascript(js, null); }

    void destroy() {
        h.removeCallbacks(wd);
        cb = null;
        try { wv.destroy(); } catch (Exception ignored) { }
    }

    final class Bridge {
        @JavascriptInterface public void onResult(final String s) {
            h.post(new Runnable() {
                @Override public void run() {
                    h.removeCallbacks(wd);
                    JSONObject o = null;
                    try { o = new JSONObject(s); } catch (Exception ignored) { }
                    CB c = cb;
                    cb = null;
                    if (c != null && o != null) c.done(o);
                }
            });
        }
        @JavascriptInterface public void onLog(String s) { Log.d("TrapBot", s); }
    }
}

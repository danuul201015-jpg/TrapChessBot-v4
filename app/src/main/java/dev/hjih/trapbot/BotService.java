package dev.hjih.trapbot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Служба доступа: рисует плавающие кнопки и рамку поверх экрана, нажимает на клетки (ходит),
 * читает доску из трансляции экрана и спрашивает ход у движка.
 */
public class BotService extends AccessibilityService {
    static volatile BotService inst;

    private WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private EngineBridge eng;
    private FrameView frame;
    private WindowManager.LayoutParams flp;
    private final ArrayList<View> floats = new ArrayList<>();
    private GradientDrawable autoBg;
    private Rect board;
    private Recognizer.Templates tpl;
    private boolean running, autoOn, busy, wb = true;
    private String blocked, prevKey;
    private int rights = 15;
    private int sw, sh;

    @Override protected void onServiceConnected() {
        inst = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }

    @Override public boolean onUnbind(Intent i) {
        stopBot();
        inst = null;
        return super.onUnbind(i);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ------------------------------------------------------------ запуск / остановка

    @SuppressWarnings("deprecation")
    void startBot() {
        if (running) return;
        if (CaptureService.inst == null) { toast("Нет доступа к экрану — нажмите «Запустить» в приложении"); return; }
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        sw = dm.widthPixels;
        sh = dm.heightPixels;
        running = true;
        autoOn = false;
        busy = false;
        blocked = null;
        prevKey = null;
        rights = 15;
        wb = Prefs.white(this);
        tpl = Recognizer.Templates.parse(Prefs.tpl(this));
        int[] b = Prefs.board(this);
        board = b == null ? null : new Rect(b[0], b[1], b[0] + b[2], b[1] + b[2]);
        eng = new EngineBridge(getApplicationContext());
        addFloats();
        showFrame();
        toast(tpl == null ? "Наведите рамку на доску (начальная позиция) → «Запомнить» → «Готово»"
                : "Проверьте рамку и нажмите «Готово»");
    }

    void stopBot() {
        if (!running) return;
        running = false;
        autoOn = false;
        busy = false;
        h.removeCallbacksAndMessages(null);
        for (View v : floats) removeView(v);
        floats.clear();
        hideFrame();
        if (eng != null) { eng.destroy(); eng = null; }
        autoBg = null;
    }

    private void removeView(View v) {
        try { wm.removeView(v); } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ рамка

    private WindowManager.LayoutParams overlayParams(int w, int hgt, int x, int y) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, hgt,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = x;
        lp.y = y;
        return lp;
    }

    private void showFrame() {
        if (frame != null) return;
        int x, y, s;
        if (board != null) { x = board.left; y = board.top; s = board.width(); }
        else { s = (int) (sw * 0.92f); x = (sw - s) / 2; y = (sh - s) / 2; }
        flp = overlayParams(s, s, x, y);
        frame = new FrameView(this, new FrameView.Cb() {
            @Override public void done() { finishFraming(); }
            @Override public void learn() { learnNow(); }
            @Override public void drag(int dx, int dy) {
                flp.x += dx;
                flp.y += dy;
                wm.updateViewLayout(frame, flp);
            }
            @Override public void resize(int ds) {
                int s2 = Math.max(240, Math.min(sw, flp.width + ds));
                flp.width = s2;
                flp.height = s2;
                wm.updateViewLayout(frame, flp);
            }
        });
        wm.addView(frame, flp);
    }

    private void hideFrame() {
        if (frame != null) { removeView(frame); frame = null; }
    }

    private Rect currentRect() {
        int[] l = new int[2];
        frame.getLocationOnScreen(l);
        return new Rect(l[0], l[1], l[0] + frame.getWidth(), l[1] + frame.getHeight());
    }

    private void finishFraming() {
        if (frame == null) return;
        board = currentRect();
        Prefs.setBoard(this, board.left, board.top, board.width());
        hideFrame();
        toast(tpl == null ? "Фигуры ещё не запомнены — откройте «▣» и нажмите «Запомнить»" : "Готово. A — авто, 1 — один ход");
    }

    private void learnNow() {
        if (frame == null || CaptureService.inst == null) return;
        final Rect r = currentRect();
        board = r;
        Prefs.setBoard(this, r.left, r.top, r.width());
        frame.setVisibility(View.INVISIBLE); // чтобы рамка не попала в кадр
        toast("Запоминаю фигуры…");
        h.postDelayed(new Runnable() {
            @Override public void run() {
                CaptureService cs = CaptureService.inst;
                Recognizer.Frame f = cs == null ? null : cs.grab(r);
                if (frame != null) frame.setVisibility(View.VISIBLE);
                if (f == null) { toast("Не удалось получить кадр экрана"); return; }
                String[] err = new String[1];
                Recognizer.Templates t = Recognizer.learn(f, wb, err);
                if (t == null) { toast(err[0]); return; }
                tpl = t;
                Prefs.setTpl(BotService.this, t.ser());
                toast("Фигуры запомнены ✓ Нажмите «Готово»");
            }
        }, 500);
    }

    // ------------------------------------------------------------ плавающие кнопки

    private void addFloats() {
        int size = dp(54);
        int x = sw - size - dp(10), y = (int) (sh * 0.25f), gap = dp(64);
        TextView a = floatBtn("A", x, y, size, new Runnable() { @Override public void run() { setAuto(!autoOn); } });
        autoBg = (GradientDrawable) a.getBackground();
        floatBtn("1", x, y + gap, size, new Runnable() { @Override public void run() { oneMove(); } });
        floatBtn("▣", x, y + 2 * gap, size, new Runnable() { @Override public void run() { showFrame(); } });
    }

    private TextView floatBtn(String txt, int x, int y, int size, final Runnable click) {
        final TextView t = new TextView(this);
        t.setText(txt);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setAlpha(0.75f);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0x66202428);
        g.setStroke(dp(2), 0x99FFFFFF);
        t.setBackground(g);
        final WindowManager.LayoutParams lp = overlayParams(size, size, x, y);
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        t.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy;
            int sx, sy;
            boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = e.getRawX(); dy = e.getRawY(); sx = lp.x; sy = lp.y; moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float mx = e.getRawX() - dx, my = e.getRawY() - dy;
                        if (!moved && Math.hypot(mx, my) > slop) moved = true;
                        if (moved) {
                            lp.x = sx + (int) mx;
                            lp.y = sy + (int) my;
                            wm.updateViewLayout(t, lp);
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (!moved) click.run();
                        return true;
                    default:
                        return false;
                }
            }
        });
        wm.addView(t, lp);
        floats.add(t);
        return t;
    }

    // ------------------------------------------------------------ логика игры

    private boolean ready() {
        if (frame != null) { toast("Сначала нажмите «Готово» на рамке"); return false; }
        if (board == null || tpl == null || !tpl.valid) {
            toast("Сначала «▣» → рамка на доску → «Запомнить» в начальной позиции");
            return false;
        }
        return true;
    }

    private Recognizer.Res read() {
        CaptureService cs = CaptureService.inst;
        if (cs == null || board == null || tpl == null) return null;
        Recognizer.Frame f = cs.grab(board);
        if (f == null) return null;
        return Recognizer.recognize(f, tpl, wb);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running || !autoOn) return;
            if (!busy) autoStep();
            h.postDelayed(this, 600);
        }
    };

    private void setAuto(boolean on) {
        if (on && !ready()) return;
        autoOn = on;
        if (autoBg != null) autoBg.setColor(on ? 0xCC2E7D32 : 0x66202428);
        h.removeCallbacks(tick);
        if (on) { prevKey = null; h.postDelayed(tick, 300); }
        toast(on ? "Авто-режим включён" : "Авто-режим выключен");
    }

    private void autoStep() {
        Recognizer.Res r = read();
        if (r == null || !r.ok) { prevKey = null; return; }
        if (r.key.equals(Recognizer.START)) { rights = 15; blocked = wb ? null : r.key; }
        boolean stable = r.key.equals(prevKey);
        prevKey = r.key;
        if (!stable) return;                  // фигуры ещё двигаются
        if (r.key.equals(blocked)) return;    // ждём хода соперника
        act(r);
    }

    private void oneMove() {
        if (busy) { toast("Движок ещё думает…"); return; }
        if (!ready()) return;
        Recognizer.Res r = read();
        if (r == null) { toast("Не удалось прочитать экран"); return; }
        if (!r.ok) { toast("Доска не распознана: " + r.msg); return; }
        if (r.key.equals(Recognizer.START)) rights = 15;
        act(r);
    }

    private void act(final Recognizer.Res r) {
        busy = true;
        rights &= Recognizer.possibleRights(r.b);
        final String oldKey = r.key;
        final char[][] bd = r.b;
        String fen = r.key + " " + (wb ? "w" : "b") + " " + Recognizer.castle(rights) + " - 0 1";
        eng.think(fen, Prefs.depth(this), Prefs.timeSec(this), Prefs.cun(this), Prefs.adapt(this), new EngineBridge.CB() {
            @Override public void done(JSONObject o) {
                if (!running) { busy = false; return; }
                if (o.has("error")) { toast("Движок: " + o.optString("error")); busy = false; setAuto(false); return; }
                if (o.optBoolean("none")) {
                    toast(o.optString("over", "Нет ходов"));
                    blocked = oldKey;
                    busy = false;
                    setAuto(false);
                    return;
                }
                int ff = o.optInt("ff"), fr = o.optInt("fr"), tf = o.optInt("tf"), tr = o.optInt("tr"), pr = o.optInt("pr");
                String info = o.optString("info", "");
                if (!info.isEmpty()) toast(info);
                final String expKey = Recognizer.place(Recognizer.apply(bd, ff, fr, tf, tr, pr));
                doMove(ff, fr, tf, tr, pr > 0, new Runnable() {
                    @Override public void run() {
                        h.postDelayed(new Runnable() {
                            @Override public void run() {
                                Recognizer.Res r2 = read();
                                if (r2 != null && r2.ok && r2.key.equals(oldKey)) {
                                    blocked = oldKey; // ход не прошёл: не повторяем на той же позиции
                                    toast("Ход не выполнился (не мой ход или нажатие не сработало)");
                                } else {
                                    blocked = expKey;
                                }
                                prevKey = null;
                                busy = false;
                            }
                        }, 900);
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------ нажатия

    private PointF center(int file, int rank) {
        int col = wb ? file : 7 - file, row = wb ? 7 - rank : rank;
        return new PointF(board.left + (col + .5f) * board.width() / 8f, board.top + (row + .5f) * board.height() / 8f);
    }

    private void doMove(int ff, int fr, int tf, int tr, final boolean promo, final Runnable done) {
        final PointF a = center(ff, fr), b = center(tf, tr);
        final Runnable fin = new Runnable() {
            @Override public void run() {
                if (promo) h.postDelayed(new Runnable() { @Override public void run() { tap(b.x, b.y, done); } }, 450); // ферзь
                else done.run();
            }
        };
        if (Prefs.drag(this)) swipe(a, b, fin);
        else tap(a.x, a.y, new Runnable() { @Override public void run() { tap(b.x, b.y, fin); } });
    }

    private void tap(float x, float y, Runnable then) {
        Path p = new Path();
        p.moveTo(x, y);
        gesture(new GestureDescription.StrokeDescription(p, 0, 60), then);
    }

    private void swipe(PointF a, PointF b, Runnable then) {
        Path p = new Path();
        p.moveTo(a.x, a.y);
        p.lineTo(b.x, b.y);
        gesture(new GestureDescription.StrokeDescription(p, 0, 260), then);
    }

    private void gesture(GestureDescription.StrokeDescription sd, final Runnable then) {
        GestureDescription g = new GestureDescription.Builder().addStroke(sd).build();
        boolean ok = dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription d) { if (then != null) h.postDelayed(then, 250); }
            @Override public void onCancelled(GestureDescription d) { if (then != null) h.postDelayed(then, 250); }
        }, null);
        if (!ok && then != null) h.postDelayed(then, 250);
    }
}

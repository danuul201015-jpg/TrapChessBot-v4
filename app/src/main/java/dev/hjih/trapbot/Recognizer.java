package dev.hjih.trapbot;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Распознавание шахматной доски по скриншоту без заранее заготовленных картинок:
 * фигуры «запоминаются» с начальной позиции (кнопка «Запомнить»), после этого
 * каждая клетка сравнивается с запомненными силуэтами.
 */
final class Recognizer {
    static final int N = 28;          // сетка силуэта N x N на клетку
    static final int TH = 70;         // порог отличия пикселя от фона клетки
    static final float INSET = 0.08f; // отступ от краёв клетки
    static final String ORDER = "PNBRQK";
    static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR";

    static final class Frame {
        final int[] px; final int w, h;
        Frame(int[] px, int w, int h) { this.px = px; this.w = w; this.h = h; }
    }

    static final class Templates {
        @SuppressWarnings("unchecked")
        final ArrayList<boolean[]>[][] list = new ArrayList[2][6];
        float lum0, lum1, emptyThr;
        boolean valid;

        Templates() {
            for (int c = 0; c < 2; c++) for (int t = 0; t < 6; t++) list[c][t] = new ArrayList<>();
        }

        String ser() {
            StringBuilder sb = new StringBuilder();
            sb.append(lum0).append(';').append(lum1).append(';').append(emptyThr);
            for (int c = 0; c < 2; c++) for (int t = 0; t < 6; t++)
                for (boolean[] m : list[c][t]) {
                    sb.append(';').append(c).append(t).append(':');
                    for (boolean b : m) sb.append(b ? '1' : '0');
                }
            return sb.toString();
        }

        static Templates parse(String s) {
            if (s == null) return null;
            try {
                String[] p = s.split(";");
                Templates t = new Templates();
                t.lum0 = Float.parseFloat(p[0]);
                t.lum1 = Float.parseFloat(p[1]);
                t.emptyThr = Float.parseFloat(p[2]);
                for (int i = 3; i < p.length; i++) {
                    int c = p[i].charAt(0) - '0', ty = p[i].charAt(1) - '0';
                    String bits = p[i].substring(3);
                    boolean[] m = new boolean[N * N];
                    for (int j = 0; j < N * N; j++) m[j] = bits.charAt(j) == '1';
                    t.list[c][ty].add(m);
                }
                t.valid = true;
                return t;
            } catch (Exception e) {
                return null;
            }
        }
    }

    static final class Res {
        final char[][] b = new char[8][8]; // b[rank][file], rank 0 = первая горизонталь белых
        boolean ok;
        String msg = "";
        String key = "";
    }

    // ---------- низкий уровень ----------

    private static int px(Frame f, int x, int y) {
        x = Math.max(0, Math.min(f.w - 1, x));
        y = Math.max(0, Math.min(f.h - 1, y));
        return f.px[y * f.w + x];
    }

    /** цвет фона клетки: медиана по четырём точкам у середин краёв */
    private static int[] bg(Frame f, float x0, float y0, float sw, float sh) {
        float[][] pts = {{.5f, .04f}, {.5f, .96f}, {.04f, .5f}, {.96f, .5f}};
        int[][] c = new int[3][4];
        for (int i = 0; i < 4; i++) {
            int cx = (int) (x0 + pts[i][0] * sw), cy = (int) (y0 + pts[i][1] * sh);
            int sr = 0, sg = 0, sb = 0, n = 0;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                int p = px(f, cx + dx, cy + dy);
                sr += (p >> 16) & 255; sg += (p >> 8) & 255; sb += p & 255; n++;
            }
            c[0][i] = sr / n; c[1][i] = sg / n; c[2][i] = sb / n;
        }
        int[] o = new int[3];
        for (int k = 0; k < 3; k++) {
            int[] a = c[k].clone();
            Arrays.sort(a);
            o[k] = (a[1] + a[2]) / 2;
        }
        return o;
    }

    /** закрашивает «дырки» внутри контура: силуэт не зависит от того, совпадает ли заливка с фоном */
    private static boolean[] fill(boolean[] m) {
        boolean[] vis = new boolean[N * N];
        int[] q = new int[N * N];
        int qh = 0, qt = 0;
        for (int i = 0; i < N; i++) {
            int[] border = {i, (N - 1) * N + i, i * N, i * N + N - 1};
            for (int idx : border) if (!m[idx] && !vis[idx]) { vis[idx] = true; q[qt++] = idx; }
        }
        while (qh < qt) {
            int idx = q[qh++], x = idx % N, y = idx / N;
            if (x > 0 && !m[idx - 1] && !vis[idx - 1]) { vis[idx - 1] = true; q[qt++] = idx - 1; }
            if (x < N - 1 && !m[idx + 1] && !vis[idx + 1]) { vis[idx + 1] = true; q[qt++] = idx + 1; }
            if (y > 0 && !m[idx - N] && !vis[idx - N]) { vis[idx - N] = true; q[qt++] = idx - N; }
            if (y < N - 1 && !m[idx + N] && !vis[idx + N]) { vis[idx + N] = true; q[qt++] = idx + N; }
        }
        boolean[] r = new boolean[N * N];
        for (int i = 0; i < N * N; i++) r[i] = m[i] || !vis[i];
        return r;
    }

    /** силуэт клетки; st[0] = доля закрашенных ячеек, st[1] = средняя яркость внутри силуэта */
    static boolean[] sil(Frame f, int col, int row, float[] st) {
        float sw = f.w / 8f, sh = f.h / 8f, x0 = col * sw, y0 = row * sh;
        int[] bg = bg(f, x0, y0, sw, sh);
        float ix = sw * INSET, iy = sh * INSET, cw = (sw - 2 * ix) / N, ch = (sh - 2 * iy) / N;
        boolean[] m = new boolean[N * N];
        float[] ox = {0, -1 / 3f, 1 / 3f, -1 / 3f, 1 / 3f}, oy = {0, -1 / 3f, -1 / 3f, 1 / 3f, 1 / 3f};
        for (int j = 0; j < N; j++) for (int i = 0; i < N; i++) {
            float cx = x0 + ix + (i + .5f) * cw, cy = y0 + iy + (j + .5f) * ch;
            for (int s = 0; s < 5; s++) {
                int p = px(f, (int) (cx + ox[s] * cw), (int) (cy + oy[s] * ch));
                int d = Math.abs(((p >> 16) & 255) - bg[0]) + Math.abs(((p >> 8) & 255) - bg[1]) + Math.abs((p & 255) - bg[2]);
                if (d > TH) { m[j * N + i] = true; break; }
            }
        }
        boolean[] fm = fill(m);
        int cnt = 0;
        double lum = 0;
        for (int j = 0; j < N; j++) for (int i = 0; i < N; i++) if (fm[j * N + i]) {
            cnt++;
            int p = px(f, (int) (x0 + ix + (i + .5f) * cw), (int) (y0 + iy + (j + .5f) * ch));
            lum += 0.299 * ((p >> 16) & 255) + 0.587 * ((p >> 8) & 255) + 0.114 * (p & 255);
        }
        st[0] = cnt / (float) (N * N);
        st[1] = cnt > 0 ? (float) (lum / cnt) : 0;
        return fm;
    }

    static float iou(boolean[] a, boolean[] b) {
        int in = 0, un = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] && b[i]) in++;
            if (a[i] || b[i]) un++;
        }
        return un == 0 ? 0 : in / (float) un;
    }

    // ---------- обучение и распознавание ----------

    /** какая фигура должна стоять на экранной клетке (col,row) в начальной позиции */
    static char expected(int col, int row, boolean wb) {
        int file = wb ? col : 7 - col, rank = wb ? 7 - row : row;
        String back = "RNBQKBNR";
        if (rank == 0) return back.charAt(file);
        if (rank == 1) return 'P';
        if (rank == 6) return 'p';
        if (rank == 7) return Character.toLowerCase(back.charAt(file));
        return ' ';
    }

    static Templates learn(Frame f, boolean wb, String[] err) {
        Templates t = new Templates();
        float[] st = new float[2];
        float emptyMax = 0, pieceMin = 1;
        double[] ls = new double[2];
        int[] ln = new int[2];
        for (int row = 0; row < 8; row++) for (int col = 0; col < 8; col++) {
            char ex = expected(col, row, wb);
            boolean[] m = sil(f, col, row, st);
            if (ex == ' ') {
                emptyMax = Math.max(emptyMax, st[0]);
            } else {
                pieceMin = Math.min(pieceMin, st[0]);
                int c = Character.isUpperCase(ex) ? 0 : 1;
                int ty = ORDER.indexOf(Character.toUpperCase(ex));
                t.list[c][ty].add(m);
                ls[c] += st[1];
                ln[c]++;
            }
        }
        if (emptyMax >= pieceMin * 0.8f) {
            err[0] = "Не вижу разницу между пустыми клетками и фигурами. Проверьте рамку (она должна точно совпадать с доской) и что на доске начальная позиция.";
            return null;
        }
        t.lum0 = (float) (ls[0] / ln[0]);
        t.lum1 = (float) (ls[1] / ln[1]);
        if (Math.abs(t.lum0 - t.lum1) < 25) {
            err[0] = "Белые и чёрные фигуры не отличаются по яркости. Проверьте, что выбрано верное «белыми/чёрными снизу».";
            return null;
        }
        t.emptyThr = Math.max(0.04f, Math.min(0.25f, emptyMax + 0.6f * (pieceMin - emptyMax)));
        t.valid = true;
        return t;
    }

    static Res recognize(Frame f, Templates t, boolean wb) {
        Res r = new Res();
        float[] st = new float[2];
        int unk = 0, wk = 0, bk = 0;
        boolean badPawn = false;
        for (int row = 0; row < 8; row++) for (int col = 0; col < 8; col++) {
            boolean[] m = sil(f, col, row, st);
            char ch;
            if (st[0] < t.emptyThr) {
                ch = ' ';
            } else {
                int c = Math.abs(st[1] - t.lum0) <= Math.abs(st[1] - t.lum1) ? 0 : 1;
                int bt = -1;
                float bs = 0;
                for (int ty = 0; ty < 6; ty++) for (boolean[] tm : t.list[c][ty]) {
                    float s = iou(m, tm);
                    if (s > bs) { bs = s; bt = ty; }
                }
                if (bt < 0 || bs < 0.45f) { ch = '?'; unk++; }
                else { ch = ORDER.charAt(bt); if (c == 1) ch = Character.toLowerCase(ch); }
            }
            int file = wb ? col : 7 - col, rank = wb ? 7 - row : row;
            r.b[rank][file] = ch;
            if (ch == 'K') wk++;
            if (ch == 'k') bk++;
            if ((ch == 'P' || ch == 'p') && (rank == 0 || rank == 7)) badPawn = true;
        }
        r.key = place(r.b);
        r.ok = unk == 0 && wk == 1 && bk == 1 && !badPawn;
        if (!r.ok) r.msg = unk > 0 ? "Не распознано клеток: " + unk : "Позиция выглядит неверно (короли/пешки)";
        return r;
    }

    // ---------- вспомогательное ----------

    static String place(char[][] b) {
        StringBuilder sb = new StringBuilder();
        for (int rank = 7; rank >= 0; rank--) {
            int e = 0;
            for (int file = 0; file < 8; file++) {
                char c = b[rank][file];
                if (c == ' ') e++;
                else { if (e > 0) { sb.append(e); e = 0; } sb.append(c); }
            }
            if (e > 0) sb.append(e);
            if (rank > 0) sb.append('/');
        }
        return sb.toString();
    }

    /** какие права на рокировку вообще возможны при такой расстановке */
    static int possibleRights(char[][] b) {
        int r = 0;
        if (b[0][4] == 'K') { if (b[0][7] == 'R') r |= 1; if (b[0][0] == 'R') r |= 2; }
        if (b[7][4] == 'k') { if (b[7][7] == 'r') r |= 4; if (b[7][0] == 'r') r |= 8; }
        return r;
    }

    static String castle(int rights) {
        String s = "";
        if ((rights & 1) != 0) s += "K";
        if ((rights & 2) != 0) s += "Q";
        if ((rights & 4) != 0) s += "k";
        if ((rights & 8) != 0) s += "q";
        return s.isEmpty() ? "-" : s;
    }

    /** позиция после хода (с рокировкой, взятием на проходе и превращением) */
    static char[][] apply(char[][] b, int ff, int fr, int tf, int tr, int promo) {
        char[][] n = new char[8][8];
        for (int i = 0; i < 8; i++) n[i] = b[i].clone();
        char p = n[fr][ff];
        if ((p == 'P' || p == 'p') && ff != tf && n[tr][tf] == ' ') n[fr][tf] = ' ';
        if ((p == 'K' || p == 'k') && Math.abs(tf - ff) == 2) {
            if (tf > ff) { n[tr][5] = n[tr][7]; n[tr][7] = ' '; }
            else { n[tr][3] = n[tr][0]; n[tr][0] = ' '; }
        }
        n[tr][tf] = p;
        n[fr][ff] = ' ';
        if (promo > 0) {
            char pc = "..NBRQ".charAt(promo);
            n[tr][tf] = Character.isUpperCase(p) ? pc : Character.toLowerCase(pc);
        }
        return n;
    }
}

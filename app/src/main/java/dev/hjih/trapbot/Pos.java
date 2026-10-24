package dev.hjih.trapbot;

import java.util.Arrays;

/**
 * Шахматная позиция (доска 0x88) + генератор ходов + оценка + поиск.
 * Один экземпляр — один поток. Таблица транспозиций {@link TT} общая.
 * Ход кодируется так: from | to<<7 | promo<<14 | flags<<17 (1 — е.п., 2 — рокировка, 4 — двойной ход пешки).
 */
final class Pos {
    static final class Stop extends RuntimeException {
        Stop() { super(null, null, false, false); }
    }
    static final Stop STOP = new Stop();

    /** Управление остановкой: общий для всех потоков одной фазы. deadline — System.nanoTime(). */
    static final class Ctl {
        volatile boolean stop;
        volatile long deadline = Long.MAX_VALUE;
    }

    static final int[] KN = {-33, -31, -18, -14, 14, 18, 31, 33};
    static final int[] KG = {-17, -16, -15, -1, 1, 15, 16, 17};
    static final int[] BD = {-17, -15, 15, 17};
    static final int[] RD = {-16, -1, 1, 16};
    static final int[] VAL = {0, 100, 320, 330, 500, 900, 20000};
    static final int INF = 1_000_000_000, MATE = 100_000, NA = 1_000_000_000;
    static final int SZ = 1400;
    static final int[] PP = {0, 0, 5, 10, 22, 38, 60, 0};

    static final long[] Z = new long[16 * 128], ZC = new long[16], ZE = new long[8];
    static final long ZS;
    static final int[] CR = new int[128];
    static final int[] CEN = new int[128];
    static final int[][] LMR = new int[64][64];

    static {
        long[] st = {0x1234567L};
        for (int i = 0; i < Z.length; i++) Z[i] = smix(st);
        for (int i = 0; i < 16; i++) ZC[i] = smix(st);
        for (int i = 0; i < 8; i++) ZE[i] = smix(st);
        ZS = smix(st);
        Arrays.fill(CR, 15);
        CR[0] = 13; CR[4] = 12; CR[7] = 14; CR[112] = 7; CR[116] = 3; CR[119] = 11;
        for (int s = 0; s < 128; s++) if ((s & 0x88) == 0) {
            int f = s & 7, r = s >> 4;
            CEN[s] = Math.min(f, 7 - f) + Math.min(r, 7 - r);
        }
        for (int d = 1; d < 64; d++) for (int m = 1; m < 64; m++)
            LMR[d][m] = (int) (0.35 + Math.log(d) * Math.log(m) / 2.4);
    }

    private static long smix(long[] st) {
        long z = (st[0] += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // ---- состояние ----
    final int[] b = new int[128];
    int side, cast = 15, ep = -1, hm, sp;
    long hash;
    final int[] ksq = {4, 116};
    final int[] npc = new int[2];
    final int[] sM = new int[SZ], sCp = new int[SZ], sC = new int[SZ], sEp = new int[SZ], sHm = new int[SZ];
    final long[] sH = new long[SZ];

    // ---- поиск ----
    final TT tt;
    Ctl ctl = new Ctl();
    long nodes;
    final int[][] MV = new int[130][256];
    final int[][] SC = new int[130][256];
    final int[] K1 = new int[130], K2 = new int[130];
    final int[] hist = new int[2048];
    int rootBest, rootScore;

    // буферы оценки
    private final int[] wpf = new int[8], bpf = new int[8], rk = new int[10], pq = new int[20];
    private final int[] wmin = new int[8], bmax = new int[8];

    Pos(TT tt) { this.tt = tt; }

    void chk() {
        if (ctl.stop || System.nanoTime() > ctl.deadline) throw STOP;
    }

    // ================= позиция =================
    void hashAll() {
        hash = 0;
        for (int s = 0; s < 128; s++) {
            if ((s & 0x88) != 0) { s += 7; continue; }
            if (b[s] != 0) hash ^= Z[b[s] * 128 + s];
        }
        if (side != 0) hash ^= ZS;
        hash ^= ZC[cast];
        if (ep >= 0) hash ^= ZE[ep & 7];
    }

    void setFen(String fen) {
        String[] p = fen.split(" ");
        Arrays.fill(b, 0);
        npc[0] = npc[1] = 0;
        int r = 7, f = 0;
        for (int i = 0; i < p[0].length(); i++) {
            char ch = p[0].charAt(i);
            if (ch == '/') { r--; f = 0; }
            else if (ch >= '1' && ch <= '8') f += ch - '0';
            else {
                int k = "PNBRQKpnbrqk".indexOf(ch);
                int pc = k < 6 ? k + 1 : k - 6 + 9, s = r * 16 + f;
                b[s] = pc;
                if ((pc & 7) == 6) ksq[pc >> 3] = s;
                else if ((pc & 7) > 1) npc[pc >> 3]++;
                f++;
            }
        }
        side = p.length > 1 && p[1].equals("b") ? 1 : 0;
        cast = 0;
        String cs = p.length > 2 ? p[2] : "-";
        for (int i = 0; i < cs.length(); i++) {
            int x = "KQkq".indexOf(cs.charAt(i));
            if (x >= 0) cast |= 1 << x;
        }
        ep = -1;
        if (p.length > 3 && p[3].length() == 2 && p[3].charAt(0) >= 'a' && p[3].charAt(0) <= 'h')
            ep = (p[3].charAt(1) - '1') * 16 + (p[3].charAt(0) - 'a');
        hm = p.length > 4 ? parseIntSafe(p[4]) : 0;
        sp = 0;
        Arrays.fill(hist, 0);
        Arrays.fill(K1, 0);
        Arrays.fill(K2, 0);
        hashAll();
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    void copyFrom(Pos o) {
        System.arraycopy(o.b, 0, b, 0, 128);
        side = o.side; cast = o.cast; ep = o.ep; hm = o.hm; sp = o.sp; hash = o.hash;
        ksq[0] = o.ksq[0]; ksq[1] = o.ksq[1]; npc[0] = o.npc[0]; npc[1] = o.npc[1];
        int n = o.sp;
        System.arraycopy(o.sM, 0, sM, 0, n);
        System.arraycopy(o.sCp, 0, sCp, 0, n);
        System.arraycopy(o.sC, 0, sC, 0, n);
        System.arraycopy(o.sEp, 0, sEp, 0, n);
        System.arraycopy(o.sHm, 0, sHm, 0, n);
        System.arraycopy(o.sH, 0, sH, 0, n);
    }

    boolean sameGrid(int[] g) {
        for (int r = 0; r < 8; r++) for (int f = 0; f < 8; f++) if (b[r * 16 + f] != g[r * 8 + f]) return false;
        return true;
    }

    // ================= атаки =================
    /** Самая дешёвая атакующая фигура стороны by на поле sq (all=true), либо любая найденная (all=false); NA — не атаковано. */
    int att(int sq, int by, boolean all) {
        int best = NA;
        final int c8 = by << 3;
        for (int k = 0; k < 2; k++) {
            int s = sq + (by != 0 ? (k != 0 ? 17 : 15) : (k != 0 ? -17 : -15));
            if ((s & 0x88) == 0 && b[s] == (1 | c8)) { if (!all) return 100; best = 100; }
        }
        for (int k = 0; k < 8; k++) {
            int s = sq + KN[k];
            if ((s & 0x88) == 0 && b[s] == (2 | c8)) { if (!all) return 320; if (best > 320) best = 320; }
        }
        for (int k = 0; k < 8; k++) {
            int s = sq + KG[k];
            if ((s & 0x88) == 0 && b[s] == (6 | c8)) { if (!all) return 20000; if (best > 20000) best = 20000; }
        }
        for (int k = 0; k < 4; k++) {
            int d = BD[k], s = sq + d;
            while ((s & 0x88) == 0) {
                int p = b[s];
                if (p != 0) {
                    if (p == (3 | c8) || p == (5 | c8)) { int v = VAL[p & 7]; if (!all) return v; if (v < best) best = v; }
                    break;
                }
                s += d;
            }
        }
        for (int k = 0; k < 4; k++) {
            int d = RD[k], s = sq + d;
            while ((s & 0x88) == 0) {
                int p = b[s];
                if (p != 0) {
                    if (p == (4 | c8) || p == (5 | c8)) { int v = VAL[p & 7]; if (!all) return v; if (v < best) best = v; }
                    break;
                }
                s += d;
            }
        }
        return best;
    }

    boolean isAtt(int sq, int by) { return att(sq, by, false) < NA; }

    boolean inCheck() { return isAtt(ksq[side], side ^ 1); }

    // ================= генерация ходов =================
    private static int add(int[] L, int n, int f, int t, int fl, int pr) {
        L[n] = f | (t << 7) | (pr << 14) | (fl << 17);
        return n + 1;
    }

    int gen(int[] L, boolean caps) {
        int n = 0;
        final int us = side << 3;
        for (int s = 0; s < 128; s++) {
            if ((s & 0x88) != 0) { s += 7; continue; }
            int p = b[s];
            if (p == 0 || (p & 8) != us) continue;
            int pt = p & 7;
            if (pt == 1) {
                int d = side != 0 ? -16 : 16, r = s >> 4, t1 = s + d;
                boolean pr = r == (side != 0 ? 1 : 6);
                if ((t1 & 0x88) != 0) continue;
                if (b[t1] == 0) {
                    if (pr) {
                        n = add(L, n, s, t1, 0, 5);
                        if (!caps) { n = add(L, n, s, t1, 0, 2); n = add(L, n, s, t1, 0, 4); n = add(L, n, s, t1, 0, 3); }
                    } else if (!caps) {
                        n = add(L, n, s, t1, 0, 0);
                        if (r == (side != 0 ? 6 : 1) && b[t1 + d] == 0) n = add(L, n, s, t1 + d, 4, 0);
                    }
                }
                for (int k = -1; k <= 1; k += 2) {
                    int t = s + d + k;
                    if ((t & 0x88) != 0) continue;
                    int c = b[t];
                    if (c != 0 && (c & 8) != us) {
                        if (pr) {
                            n = add(L, n, s, t, 0, 5);
                            if (!caps) { n = add(L, n, s, t, 0, 2); n = add(L, n, s, t, 0, 4); n = add(L, n, s, t, 0, 3); }
                        } else n = add(L, n, s, t, 0, 0);
                    } else if (c == 0 && t == ep) n = add(L, n, s, t, 1, 0);
                }
            } else if (pt == 2 || pt == 6) {
                int[] D = pt == 2 ? KN : KG;
                for (int k = 0; k < 8; k++) {
                    int t = s + D[k];
                    if ((t & 0x88) != 0) continue;
                    int c = b[t];
                    if (c == 0) { if (!caps) n = add(L, n, s, t, 0, 0); }
                    else if ((c & 8) != us) n = add(L, n, s, t, 0, 0);
                }
                if (pt == 6 && !caps && s == (side != 0 ? 116 : 4)) {
                    int th = side ^ 1, kb = side != 0 ? 4 : 1, qb = side != 0 ? 8 : 2, rook = 4 | us;
                    if ((cast & kb) != 0 && b[s + 3] == rook && b[s + 1] == 0 && b[s + 2] == 0
                            && !isAtt(s, th) && !isAtt(s + 1, th) && !isAtt(s + 2, th)) n = add(L, n, s, s + 2, 2, 0);
                    if ((cast & qb) != 0 && b[s - 4] == rook && b[s - 1] == 0 && b[s - 2] == 0 && b[s - 3] == 0
                            && !isAtt(s, th) && !isAtt(s - 1, th) && !isAtt(s - 2, th)) n = add(L, n, s, s - 2, 2, 0);
                }
            } else {
                for (int g = 0; g < 2; g++) {
                    if (g == 0 && pt == 4) continue;
                    if (g == 1 && pt == 3) continue;
                    int[] D = g != 0 ? RD : BD;
                    for (int k = 0; k < 4; k++) {
                        int d = D[k], t = s + d;
                        while ((t & 0x88) == 0) {
                            int c = b[t];
                            if (c == 0) { if (!caps) n = add(L, n, s, t, 0, 0); }
                            else { if ((c & 8) != us) n = add(L, n, s, t, 0, 0); break; }
                            t += d;
                        }
                    }
                }
            }
        }
        return n;
    }

    // ================= сделать / отменить ход =================
    void make(int m) {
        int f = m & 127, t = (m >> 7) & 127, pr = (m >> 14) & 7, fl = m >> 17, p = b[f], cp = b[t];
        sM[sp] = m; sCp[sp] = cp; sC[sp] = cast; sEp[sp] = ep; sHm[sp] = hm; sH[sp] = hash;
        if (ep >= 0) hash ^= ZE[ep & 7];
        hash ^= ZC[cast];
        hash ^= Z[p * 128 + f];
        hm++;
        if ((p & 7) == 1 || cp != 0) hm = 0;
        b[f] = 0;
        if (cp != 0) {
            hash ^= Z[cp * 128 + t];
            int c = cp & 7;
            if (c > 1 && c < 6) npc[cp >> 3]--;
        }
        if ((fl & 1) != 0) {
            int cs = t + (side != 0 ? 16 : -16), c2 = b[cs];
            hash ^= Z[c2 * 128 + cs];
            b[cs] = 0;
        }
        int np = p;
        if (pr != 0) { np = pr | (side << 3); npc[side]++; }
        b[t] = np;
        hash ^= Z[np * 128 + t];
        if ((fl & 2) != 0) {
            int rf = t > f ? f + 3 : f - 4, rt = t > f ? f + 1 : f - 1, r = b[rf];
            b[rf] = 0; b[rt] = r;
            hash ^= Z[r * 128 + rf] ^ Z[r * 128 + rt];
        }
        if ((p & 7) == 6) ksq[side] = t;
        cast &= CR[f] & CR[t];
        ep = (fl & 4) != 0 ? (f + t) >> 1 : -1;
        if (ep >= 0) hash ^= ZE[ep & 7];
        hash ^= ZC[cast];
        side ^= 1;
        hash ^= ZS;
        sp++;
    }

    void unmake() {
        sp--;
        int m = sM[sp];
        side ^= 1;
        if (m != 0) {
            int f = m & 127, t = (m >> 7) & 127, pr = (m >> 14) & 7, fl = m >> 17, p = b[t];
            if (pr != 0) { p = 1 | (side << 3); npc[side]--; }
            b[f] = p;
            int cp = sCp[sp];
            b[t] = cp;
            int c = cp & 7;
            if (cp != 0 && c > 1 && c < 6) npc[cp >> 3]++;
            if ((fl & 1) != 0) { b[t] = 0; b[t + (side != 0 ? 16 : -16)] = 1 | ((side ^ 1) << 3); }
            if ((fl & 2) != 0) {
                int rf = t > f ? f + 3 : f - 4, rt = t > f ? f + 1 : f - 1;
                b[rf] = b[rt]; b[rt] = 0;
            }
            if ((p & 7) == 6) ksq[side] = f;
        }
        cast = sC[sp]; ep = sEp[sp]; hm = sHm[sp]; hash = sH[sp];
    }

    void makeNull() {
        sM[sp] = 0; sC[sp] = cast; sEp[sp] = ep; sHm[sp] = hm; sH[sp] = hash;
        if (ep >= 0) hash ^= ZE[ep & 7];
        ep = -1;
        side ^= 1;
        hash ^= ZS;
        hm++;
        sp++;
    }

    int[] legalMoves() {
        int[] L = MV[128];
        int n = gen(L, false), c = 0;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            make(L[i]);
            if (!isAtt(ksq[side ^ 1], side)) out[c++] = L[i];
            unmake();
        }
        return Arrays.copyOf(out, c);
    }

    // ================= оценка =================
    /** Связки и сквозные удары слайдера q цвета w (w=true — белые). Используется только в тактике корня. */
    int pinScan(int q, boolean w) {
        int p = b[q], pt = p & 7, me = w ? 0 : 1, op = w ? 1 : 0, sc = 0;
        for (int g = 0; g < 2; g++) {
            if (g == 0 && pt == 4) continue;
            if (g == 1 && pt == 3) continue;
            int[] D = g != 0 ? RD : BD;
            for (int k = 0; k < 4; k++) {
                int d = D[k], s = q + d, a = 0, as = 0;
                while ((s & 0x88) == 0) {
                    int x = b[s];
                    if (x != 0) {
                        boolean en = ((x & 8) != 0) == w;
                        if (a == 0) { if (!en) break; a = x & 7; as = s; }
                        else {
                            if (en) {
                                int B = x & 7;
                                if (a == 6) { if (VAL[B] >= 500) sc += 26; }
                                else if (B == 6) {
                                    sc += 16 + (a > 2 ? 10 : 0);
                                    if (att(as, me, true) < VAL[a]) sc += VAL[a] / 18;
                                    if (att(as, op, false) >= NA) sc += VAL[a] / 14;
                                } else if (VAL[B] > VAL[a] + 50) {
                                    int v = Math.min(30, (VAL[B] - VAL[a]) / 14);
                                    if (att(as, me, true) < VAL[a] || att(as, op, false) >= NA) v += 12;
                                    sc += v;
                                } else if (VAL[a] > VAL[B] + 100 && (VAL[pt] < VAL[a] || att(as, op, false) >= NA))
                                    sc += Math.min(22, (VAL[a] - VAL[B]) / 25);
                            }
                            break;
                        }
                    }
                    s += d;
                }
            }
        }
        return sc;
    }

    int pinsOf(boolean w) {
        int t = 0;
        for (int q = 0; q < 128; q++) {
            if ((q & 0x88) != 0) { q += 7; continue; }
            int p = b[q];
            if (p == 0) continue;
            int pt = p & 7;
            if (pt >= 3 && pt <= 5 && ((p & 8) == 0) == w) t += pinScan(q, w);
        }
        return t;
    }

    int evaluate() {
        double s = 0;
        int np = 0, wb = 0, bb = 0, nr = 0, npw = 0;
        Arrays.fill(wpf, 0); Arrays.fill(bpf, 0); Arrays.fill(wmin, 9); Arrays.fill(bmax, -1);
        for (int q = 0; q < 128; q++) {
            if ((q & 0x88) != 0) { q += 7; continue; }
            int p = b[q];
            if (p == 0) continue;
            int pt = p & 7, f = q & 7;
            boolean w = (p & 8) == 0;
            int rel = w ? (q >> 4) : 7 - (q >> 4), c = CEN[q];
            double v = VAL[pt];
            if (pt == 1) {
                v += rel * rel * 2 + rel * 3 + (c >= 5 && rel >= 2 ? 8 : 0);
                if (w) { wpf[f]++; if ((q >> 4) < wmin[f]) wmin[f] = q >> 4; }
                else { bpf[f]++; if ((q >> 4) > bmax[f]) bmax[f] = q >> 4; }
                if (npw < 20) pq[npw++] = q;
            } else if (pt == 2) { v += (c - 3) * 9; np += 320; }
            else if (pt == 3) { v += (c - 3) * 5; np += 330; if (w) wb++; else bb++; }
            else if (pt == 4) { np += 500; if (rel == 6) v += 14; if (nr < 10) rk[nr++] = q; }
            else if (pt == 5) np += 900;
            else v = 0;
            s += w ? v : -v;
        }
        double mg = Math.min(1, Math.max(0, (np - 1500) / 3000.0));
        for (int c = 0; c < 2; c++) {
            int k = ksq[c], ce = CEN[k];
            boolean home = (k >> 4) == (c != 0 ? 7 : 0) && ((k & 7) < 3 || (k & 7) > 5);
            double kt = mg * (-ce * 7 + (home ? 25 : 0)) + (1 - mg) * ce * 9;
            s += c != 0 ? -kt : kt;
            if (mg > 0 && (k >> 4) == (c != 0 ? 7 : 0)) {
                int kf = k & 7, dr = c != 0 ? -16 : 16, pc = 1 | (c << 3), sh = 0;
                for (int df = -1; df <= 1; df++) {
                    int ff = kf + df;
                    if (ff < 0 || ff > 7) continue;
                    int s1 = k + dr + df;
                    if (b[s1] == pc) sh += 9;
                    else if ((s1 + dr & 0x88) == 0 && b[s1 + dr] == pc) sh += 4;
                    else sh -= 9;
                }
                s += c != 0 ? -mg * sh : mg * sh;
            }
        }
        for (int i = 0; i < npw; i++) {
            int q = pq[i], r = q >> 4, f = q & 7;
            boolean w = (b[q] & 8) == 0, ps = true;
            for (int df = -1; df <= 1 && ps; df++) {
                int ff = f + df;
                if (ff < 0 || ff > 7) continue;
                if (w) { if (bmax[ff] > r) ps = false; }
                else if (wmin[ff] < r) ps = false;
            }
            if (ps) {
                int bv = (int) (PP[w ? r : 7 - r] * (0.6 + 0.4 * (1 - mg)));
                s += w ? bv : -bv;
            }
        }
        for (int f = 0; f < 8; f++) {
            if (wpf[f] > 1) s -= 12 * (wpf[f] - 1);
            if (bpf[f] > 1) s += 12 * (bpf[f] - 1);
            if (wpf[f] != 0 && !(f > 0 && wpf[f - 1] != 0) && !(f < 7 && wpf[f + 1] != 0)) s -= 10;
            if (bpf[f] != 0 && !(f > 0 && bpf[f - 1] != 0) && !(f < 7 && bpf[f + 1] != 0)) s += 10;
        }
        for (int i = 0; i < nr; i++) {
            int q = rk[i], f = q & 7;
            boolean w = (b[q] & 8) == 0;
            int[] own = w ? wpf : bpf, opp = w ? bpf : wpf;
            int v = 0;
            if (own[f] == 0) { v += 12; if (opp[f] == 0) v += 10; }
            s += w ? v : -v;
        }
        if (wb > 1) s += 30;
        if (bb > 1) s -= 30;
        s += side != 0 ? -10 : 10;
        return (int) Math.round(side != 0 ? -s : s);
    }

    // ================= поиск =================
    /** Поиск взятий; при шахе перебирает все уклонения (первые 6 полуходов) — иначе пропускал мат и «шахи-вилки». */
    int qs(int a, int be, int pl, int qd) {
        if ((++nodes & 1023) == 0) chk();
        boolean inChk = qd < 6 && pl <= 120 && isAtt(ksq[side], side ^ 1);
        int[] L = MV[pl], S = SC[pl];
        int best, n, st = 0;
        if (inChk) {
            best = -INF;
            n = gen(L, false);
            for (int i = 0; i < n; i++) {
                int m = L[i], t = (m >> 7) & 127;
                S[i] = b[t] != 0 ? 10000 + (b[t] & 7) * 100 - (b[m & 127] & 7) : (((m >> 14) & 7) != 0 ? 9000 : 0);
            }
        } else {
            st = evaluate();
            if (st >= be || pl > 120) return st;
            if (st > a) a = st;
            best = st;
            n = gen(L, true);
            for (int i = 0; i < n; i++) {
                int m = L[i], t = (m >> 7) & 127;
                S[i] = (b[t] != 0 ? (b[t] & 7) * 100 : 100) * 10 - (b[m & 127] & 7) + (((m >> 14) & 7) != 0 ? 5000 : 0);
            }
        }
        int legal = 0;
        for (int i = 0; i < n; i++) {
            int j = i;
            for (int k = i + 1; k < n; k++) if (S[k] > S[j]) j = k;
            if (j != i) { int x = L[i]; L[i] = L[j]; L[j] = x; x = S[i]; S[i] = S[j]; S[j] = x; }
            int m = L[i], t = (m >> 7) & 127;
            if (!inChk && ((m >> 14) & 7) == 0 && st + VAL[(b[t] & 7) != 0 ? (b[t] & 7) : 1] + 200 < a) continue;
            make(m);
            if (isAtt(ksq[side ^ 1], side)) { unmake(); continue; }
            legal++;
            int v = -qs(-be, -a, pl + 1, qd + 1);
            unmake();
            if (v > best) { best = v; if (v > a) { a = v; if (a >= be) break; } }
        }
        if (inChk && legal == 0) return -MATE + pl;
        return best;
    }

    int search(int d, int a, int be, int pl, boolean nullOk) {
        if ((++nodes & 1023) == 0) chk();
        if (pl > 0) {
            if (hm >= 100) return 0;
            for (int i = sp - 2; i >= 0 && i >= sp - hm; i -= 2) if (sH[i] == hash) return 0;
        }
        boolean inChk = isAtt(ksq[side], side ^ 1);
        if (inChk && pl < 60) d++;
        if (d <= 0) return qs(a, be, pl, 0);
        if (pl > 120) return evaluate();
        long e = tt.probe(hash);
        int ttm = 0;
        if (e != 0) {
            ttm = TT.move(e);
            if (TT.depth(e) >= d && pl > 0) {
                int v = TT.val(e);
                if (v > MATE - 200) v -= pl; else if (v < -MATE + 200) v += pl;
                int f = TT.flag(e);
                if (f == 0 || (f == 1 && v >= be) || (f == 2 && v <= a)) return v;
            }
        }
        boolean pv = be - a > 1;
        int se = 0;
        if (!pv && !inChk) {
            se = evaluate();
            if (d <= 3 && be < MATE / 2 && be > -MATE / 2 && se - 130 * d >= be) return se;
            if (nullOk && d >= 3 && npc[side] > 0 && se >= be) {
                makeNull();
                int v = -search(d - 3 - (d > 6 ? 1 : 0), -be, -be + 1, pl + 1, false);
                unmake();
                if (v >= be) return be;
            }
        }
        boolean fut = !pv && !inChk && d <= 2 && a > -MATE / 2 && se + 110 * d <= a;
        int[] L = MV[pl], S = SC[pl];
        int n = gen(L, false);
        for (int i = 0; i < n; i++) {
            int m = L[i], t = (m >> 7) & 127, sc;
            if (m == ttm) sc = 10_000_000;
            else if (b[t] != 0 || ((m >> 17) & 1) != 0) sc = 1_000_000 + (b[t] != 0 ? (b[t] & 7) : 1) * 100 - (b[m & 127] & 7);
            else if (((m >> 14) & 7) != 0) sc = 900_000;
            else if (m == K1[pl]) sc = 800_000;
            else if (m == K2[pl]) sc = 700_000;
            else sc = hist[(b[m & 127] & 15) * 128 + t];
            S[i] = sc;
        }
        int best = -INF, bm = 0, legal = 0, a0 = a;
        for (int i = 0; i < n; i++) {
            int j = i;
            for (int k = i + 1; k < n; k++) if (S[k] > S[j]) j = k;
            if (j != i) { int x = L[i]; L[i] = L[j]; L[j] = x; x = S[i]; S[i] = S[j]; S[j] = x; }
            int m = L[i], t = (m >> 7) & 127;
            boolean quiet = b[t] == 0 && ((m >> 17) & 1) == 0 && ((m >> 14) & 7) == 0;
            int hp = (b[m & 127] & 15) * 128 + t;
            make(m);
            if (isAtt(ksq[side ^ 1], side)) { unmake(); continue; }
            boolean gaveChk = isAtt(ksq[side], side ^ 1);
            if (fut && quiet && legal > 0 && !gaveChk) { unmake(); continue; }
            legal++;
            int v;
            if (legal == 1) v = -search(d - 1, -be, -a, pl + 1, true);
            else {
                int red = 0;
                if (quiet && d >= 3 && !inChk && !gaveChk && legal > 3) {
                    red = LMR[Math.min(d, 63)][Math.min(legal, 63)];
                    if (pv && red > 0) red--;
                    if (red > d - 2) red = d - 2;
                    if (red < 0) red = 0;
                }
                v = -search(d - 1 - red, -a - 1, -a, pl + 1, true);
                if (v > a && red > 0) v = -search(d - 1, -a - 1, -a, pl + 1, true);
                if (v > a && v < be) v = -search(d - 1, -be, -a, pl + 1, true);
            }
            unmake();
            if (v > best) {
                best = v; bm = m;
                if (v > a) {
                    a = v;
                    if (a >= be) {
                        if (quiet) { K2[pl] = K1[pl]; K1[pl] = m; hist[hp] += d * d; }
                        break;
                    }
                }
            }
        }
        if (legal == 0) return inChk ? -MATE + pl : 0;
        int sv = best;
        if (sv > MATE - 200) sv += pl; else if (sv < -MATE + 200) sv -= pl;
        tt.store(hash, bm, sv, d, best <= a0 ? 2 : best >= be ? 1 : 0);
        return best;
    }

    /** Поиск в корне по списку rl (на месте переставляется лучший ход вперёд); результат — rootBest/rootScore. */
    void rootSearch(int d, int[] rl, int alpha, int beta) {
        int a = alpha, best = -INF, bm = rl[0];
        for (int i = 0; i < rl.length; i++) {
            make(rl[i]);
            int v;
            if (i == 0) v = -search(d - 1, -beta, -a, 1, true);
            else {
                v = -search(d - 1, -a - 1, -a, 1, true);
                if (v > a && v < beta) v = -search(d - 1, -beta, -a, 1, true);
            }
            unmake();
            if (v > best) { best = v; bm = rl[i]; }
            if (v > a) a = v;
            if (a >= beta) break;
        }
        rootBest = bm;
        rootScore = best;
    }

    // ================= вспомогательное =================
    long perft(int d) {
        if (d == 0) return 1;
        int[] L = new int[256];
        int n = gen(L, false);
        long c = 0;
        for (int i = 0; i < n; i++) {
            make(L[i]);
            if (!isAtt(ksq[side ^ 1], side)) c += perft(d - 1);
            unmake();
        }
        return c;
    }

    static String sqn(int s) { return "" + (char) ('a' + (s & 7)) + ((s >> 4) + 1); }

    String name(int m) {
        int f = m & 127, t = (m >> 7) & 127, p = b[f] & 7, pr = (m >> 14) & 7;
        boolean cap = b[t] != 0 || ((m >> 17) & 1) != 0;
        if (((m >> 17) & 2) != 0) return t > f ? "O-O" : "O-O-O";
        String head = p > 1 ? String.valueOf(" PNBRQK".charAt(p)) : (cap ? String.valueOf((char) ('a' + (f & 7))) : "");
        return head + (cap ? "x" : "") + sqn(t) + (pr != 0 ? "=" + "..NBRQ".charAt(pr) : "");
    }

    String over() {
        int[] lm = legalMoves();
        if (lm.length == 0) return inCheck() ? (side == 0 ? "Мат. Победили чёрные" : "Мат. Победили белые") : "Пат. Ничья";
        if (hm >= 100) return "Ничья по правилу 50 ходов";
        int rep = 0;
        for (int i = sp - 2; i >= 0 && i >= sp - hm; i -= 2) if (sH[i] == hash) rep++;
        if (rep >= 2) return "Ничья повторением";
        int minors = 0;
        boolean heavy = false;
        for (int s = 0; s < 128; s++) {
            if ((s & 0x88) != 0) { s += 7; continue; }
            int t = b[s] & 7;
            if (t == 1 || t == 4 || t == 5) heavy = true;
            else if (t == 2 || t == 3) minors++;
        }
        return !heavy && minors <= 1 ? "Ничья: недостаточно материала" : "";
    }

    String sane() {
        int[] nk = new int[2], np = new int[2], tot = new int[2];
        for (int s = 0; s < 128; s++) {
            if ((s & 0x88) != 0) { s += 7; continue; }
            int p = b[s];
            if (p == 0) continue;
            int c = p >> 3, t = p & 7;
            tot[c]++;
            if (t == 6) nk[c]++;
            if (t == 1) { np[c]++; if ((s >> 4) == 0 || (s >> 4) == 7) return "пешка на крайней горизонтали"; }
        }
        if (nk[0] != 1 || nk[1] != 1) return "королей: белых " + nk[0] + ", чёрных " + nk[1];
        if (np[0] > 8 || np[1] > 8 || tot[0] > 16 || tot[1] > 16) return "слишком много фигур";
        if (Math.abs((ksq[0] & 7) - (ksq[1] & 7)) < 2 && Math.abs((ksq[0] >> 4) - (ksq[1] >> 4)) < 2) return "короли рядом";
        if (isAtt(ksq[side ^ 1], side)) return "король стороны без хода под шахом";
        return "";
    }
}

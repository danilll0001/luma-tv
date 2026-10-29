package de.hamid.lumatv.stage;

import android.graphics.Canvas;
import android.text.TextPaint;
import java.util.List;

/** Draws the quick panel that slides in from the right edge: header, tiles and a wide bar. */
final class PanelPainter {
    static final float W = 660, PAD = 36, TILE_H = 116, GAP = 14, TOP = 236;

    static final class Tile {
        String label = "", value = "", glyph = "settings";
        boolean on, wide, bar;
        float x, y, w, h;
        Runnable action;
    }

    private final Gfx g;
    private final Type type;
    private final Marks marks;

    PanelPainter(Gfx g, Type type, Marks marks) {
        this.g = g;
        this.type = type;
        this.marks = marks;
    }

    /** Two tiles per row, wide tiles alone, the bar at the bottom. */
    static void layout(List<Tile> tiles) {
        float colW = (W - 2 * PAD - GAP) / 2, y = TOP;
        int col = 0;
        for (Tile t : tiles) {
            t.h = TILE_H;
            if (t.bar) {
                t.x = PAD;
                t.w = W - 2 * PAD;
                t.h = 92;
                t.y = 1080 - 56 - t.h;
                continue;
            }
            if (t.wide) {
                if (col != 0) { col = 0; y += TILE_H + GAP; }
                t.x = PAD;
                t.w = W - 2 * PAD;
                t.y = y;
                y += TILE_H + GAP;
                continue;
            }
            t.w = colW;
            t.x = PAD + col * (colW + GAP);
            t.y = y;
            if (++col == 2) { col = 0; y += TILE_H + GAP; }
        }
    }

    /**
     * @param x0 left edge of the panel (it slides in from 1920)
     * @param shade 0..1 strength of the dimming left of the panel
     * @param since milliseconds since the panel opened (drives the staggered tile entrance)
     * @param level animation level: 0 calm, 1 normal, 2 ultra
     */
    void draw(Canvas c, Look look, List<Tile> tiles, int focus, float x0, float shade, long since, int level,
              String clock, String date, int popIndex, float popT) {
        int ink = 0xFFF6F1E7;
        c.drawColor(Color2.rgba(0, 0, 0, .46f * shade));
        g.round(c, x0, 20, W + 80, 1040, 44, look.glassUi ? 0xDB161820 : 0xF5161820);
        g.hairline(c, x0, 20, W + 80, 1040, 44, 0x2EFFFFFF);

        TextPaint p = g.font(type.home(look.font, Type.CLOCK), 76, 0f, ink);
        c.drawText(clock, x0 + PAD, g.baseline(p, 54, 100), p);
        if (date != null && !date.isEmpty()) {
            TextPaint dp = g.font(type.home(look.font, Type.PRIMARY), 26, 0f, Color2.fade(ink, .72f));
            c.drawText(g.ellipsize(date, W - 2 * PAD), x0 + PAD, g.baseline(dp, 156, 36), dp);
        }

        for (int i = 0; i < tiles.size(); i++) {
            Tile t = tiles.get(i);
            float appear = level == 0 ? 1f : Math.max(0f, Math.min(1f, (since - 120 - i * 35f) / 240f));
            float ease = 1f - (1f - appear) * (1f - appear);
            if (ease <= 0f) continue;
            float pop = 1f;
            if (i == popIndex && popT < 1f) pop = 1f + .10f * (float) Math.sin(Math.PI * popT);
            boolean focused = i == focus;
            float left = x0 + t.x, top = t.y + (1f - ease) * 26f;
            int save = c.save();
            float scale = (focused ? 1.04f : 1f) * pop;
            c.scale(scale, scale, left + t.w / 2, top + t.h / 2);
            int fill;
            if (focused) fill = look.accentOn() ? look.accentColor(false) : 0xFFF6F1E7;
            else if (t.on) fill = look.accentOn() ? ((look.accentColor(false) & 0x00FFFFFF) | 0x77000000) : 0x59FFFFFF;
            else fill = 0x26FFFFFF;
            g.round(c, left, top, t.w, t.h, 26, Color2.fade(fill, ease));
            int tileInk = Color2.fade(focused ? 0xFF141618 : ink, ease);
            if (t.bar) {
                marks.glyph(c, t.glyph, left + 30, top + t.h / 2 - 22, 44, tileInk);
                TextPaint bp = g.font(type.home(look.font, Type.PRIMARY), 30, 0f, tileInk);
                c.drawText(g.ellipsize(t.label, t.w - 120), left + 94, g.baseline(bp, top + t.h / 2 - 22, 44), bp);
            } else {
                marks.glyph(c, t.glyph, left + 22, top + 16, 40, tileInk);
                TextPaint lp = g.font(type.home(look.font, Type.PRIMARY), 27, 0f, tileInk);
                c.drawText(g.ellipsize(t.label, t.w - 40), left + 22, g.baseline(lp, top + 58, 32), lp);
                if (t.value != null && !t.value.isEmpty()) {
                    TextPaint vp = g.font(type.home(look.font, Type.PRIMARY), 22, 0f, Color2.fade(tileInk, .72f));
                    c.drawText(g.ellipsize(t.value, t.w - 40), left + 22, g.baseline(vp, top + 86, 26), vp);
                }
            }
            c.restoreToCount(save);
        }
    }
}

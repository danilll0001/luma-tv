package de.hamid.lumatv.stage;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import java.util.ArrayList;
import java.util.List;

/** Draws the introduction: a small orange companion with a speech bubble, text that types itself, and choices. */
final class TourPainter {
    static final class Option {
        String label = "";
        boolean on;
        Runnable action;
    }

    private final Gfx g;
    private final Type type;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    TourPainter(Gfx g, Type type) {
        this.g = g;
        this.type = type;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
    }

    private static List<String> wrap(TextPaint p, String text, float width) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            String test = current.length() == 0 ? word : current + " " + word;
            if (p.measureText(test) > width && current.length() > 0) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(test);
            }
        }
        if (current.length() > 0) lines.add(current.toString());
        return lines;
    }

    /**
     * @param since milliseconds since the step began
     * @param shown number of characters of title and body to show (typing effect)
     * @param talking true while the text is still typing
     */
    void draw(Canvas c, Look look, long now, long since, String title, String body, int shown, boolean talking,
              List<Option> options, int focus, int step, int steps) {
        float enter = Math.min(1f, since / 380f);
        float ease = 1f - (1f - enter) * (1f - enter) * (1f - enter);
        c.drawColor(Color2.rgba(0, 0, 0, .58f * Math.min(1f, now == 0 ? 1f : ease + .2f)));

        // companion
        float cx = 330, cy = 640 + (1f - ease) * 220f;
        drawCompanion(c, cx, cy, 118, now, since, talking);

        // speech bubble
        float bx = 540, by = 270, bw = 1240, bh = 600;
        int save = c.save();
        c.translate((1f - ease) * 80f, 0);
        g.round(c, bx, by, bw, bh, 44, 0xF01B1D24);
        g.hairline(c, bx, by, bw, bh, 44, 0x30FFFFFF);
        fill.setColor(0xF01B1D24);
        android.graphics.Path tail = new android.graphics.Path();
        tail.moveTo(bx + 2, by + 330);
        tail.lineTo(bx - 46, by + 372);
        tail.lineTo(bx + 2, by + 410);
        tail.close();
        c.drawPath(tail, fill);

        // progress dots
        for (int i = 0; i < steps; i++) {
            fill.setColor(i <= step ? 0xFFFF9A3C : 0x40FFFFFF);
            c.drawCircle(bx + bw - 48 - (steps - 1 - i) * 22, by + 44, i == step ? 7 : 5, fill);
        }

        int ink = 0xFFF6F1E7;
        TextPaint tp = g.font(type.home(look.font, Type.PRIMARY), 44, 0f, ink);
        String shownTitle = title.substring(0, Math.min(shown, title.length()));
        c.drawText(shownTitle, bx + 56, g.baseline(tp, by + 32, 60), tp);
        TextPaint bp = g.font(type.home(look.font, Type.PRIMARY), 30, 0f, Color2.fade(ink, .9f));
        int remaining = Math.max(0, shown - title.length());
        List<String> lines = wrap(bp, body, bw - 112);
        float y = by + 118;
        for (String text : lines) {
            if (remaining <= 0) break;
            String part = text.substring(0, Math.min(remaining, text.length()));
            c.drawText(part, bx + 56, g.baseline(bp, y, 42), bp);
            remaining -= text.length() + 1;
            y += 44;
        }

        // choices, wrapped in rows
        float ox = bx + 56, oy = by + bh - 190, rowH = 70;
        TextPaint op = g.font(type.home(look.font, Type.PRIMARY), 28, 0f, ink);
        float x = ox;
        for (int i = 0; i < options.size(); i++) {
            Option o = options.get(i);
            float w = op.measureText(o.label) + 60;
            if (x + w > bx + bw - 56) {
                x = ox;
                oy += rowH;
            }
            boolean f = i == focus;
            int pill = f ? (look.accentOn() ? look.accentColor(false) : 0xFFF6F1E7)
                    : o.on ? 0x59FF9A3C : 0x26FFFFFF;
            g.round(c, x, oy, w, 58, 29, pill);
            TextPaint lp = g.font(type.home(look.font, Type.PRIMARY), 28, 0f, f ? 0xFF141618 : ink);
            c.drawText(o.label, x + 30, g.baseline(lp, oy + 11, 36), lp);
            x += w + 14;
        }
        c.restoreToCount(save);
    }

    /** An original little character: an orange round body with eyes, a smile and two waving hands. */
    private void drawCompanion(Canvas c, float cx, float cy, float r, long now, long since, boolean talking) {
        float bob = (float) Math.sin(now / 420.0) * 8f;
        float squash = talking ? 1f + .035f * (float) Math.sin(now / 90.0) : 1f + .012f * (float) Math.sin(now / 420.0);
        cy += bob;
        // shadow
        fill.setColor(0x33000000);
        oval.set(cx - r * .8f, cy + r * 1.08f - bob * .4f, cx + r * .8f, cy + r * 1.22f - bob * .4f);
        c.drawOval(oval, fill);
        int save = c.save();
        c.scale(1f / squash, squash, cx, cy + r);
        // body
        fill.setColor(0xFFFF8A2B);
        c.drawCircle(cx, cy, r, fill);
        fill.setColor(0xFFFFB45C);
        c.drawCircle(cx - r * .22f, cy - r * .3f, r * .62f, fill);
        fill.setColor(0xFFFF9A3C);
        c.drawCircle(cx + r * .08f, cy + r * .06f, r * .9f, fill);
        // cheeks
        fill.setColor(0x66FF5A5A);
        c.drawCircle(cx - r * .55f, cy + r * .22f, r * .13f, fill);
        c.drawCircle(cx + r * .55f, cy + r * .22f, r * .13f, fill);
        // eyes: they blink every few seconds
        long cycle = now % 3300;
        float open = cycle < 140 ? Math.abs(cycle - 70) / 70f : 1f;
        fill.setColor(0xFF2B1A12);
        oval.set(cx - r * .38f - r * .09f, cy - r * .12f - r * .14f * open, cx - r * .38f + r * .09f, cy - r * .12f + r * .14f * open);
        c.drawOval(oval, fill);
        oval.set(cx + r * .38f - r * .09f, cy - r * .12f - r * .14f * open, cx + r * .38f + r * .09f, cy - r * .12f + r * .14f * open);
        c.drawOval(oval, fill);
        fill.setColor(0xFFFFFFFF);
        c.drawCircle(cx - r * .35f, cy - r * .18f, r * .03f, fill);
        c.drawCircle(cx + r * .41f, cy - r * .18f, r * .03f, fill);
        // mouth: a smile, open while talking
        if (talking) {
            float mouth = .05f + .05f * Math.abs((float) Math.sin(now / 85.0));
            fill.setColor(0xFF7A2B14);
            oval.set(cx - r * .13f, cy + r * .2f, cx + r * .13f, cy + r * (.2f + mouth * 4f));
            c.drawOval(oval, fill);
        } else {
            line.setColor(0xFF7A2B14);
            line.setStrokeWidth(r * .06f);
            oval.set(cx - r * .22f, cy + r * .06f, cx + r * .22f, cy + r * .42f);
            c.drawArc(oval, 20, 140, false, line);
        }
        c.restoreToCount(save);
        // hands: the right one waves when the step starts
        float wave = since < 1500 ? (float) Math.sin(since / 110.0) * r * .22f : 0f;
        fill.setColor(0xFFFF8A2B);
        c.drawCircle(cx - r * 1.02f, cy + r * .45f + (float) Math.sin(now / 520.0) * 5f, r * .2f, fill);
        c.drawCircle(cx + r * 1.02f, cy + r * .1f - Math.abs(wave) * 1.1f, r * .2f, fill);
    }
}

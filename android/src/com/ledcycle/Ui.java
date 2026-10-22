package com.ledcycle;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * SignalRGB-style visual language, programmatic (there is no res/ folder in
 * this build): near-black background, violet→cyan gradient accents, rounded
 * cards, pill buttons, bold condensed-ish labels.
 */
final class Ui {

    static final int BG     = 0xFF08080D;   // window background
    static final int CARD   = 0xFF14141C;   // card surface
    static final int CARD2  = 0xFF1E1E29;   // secondary surface (inner panels, pills)
    static final int ACC1   = 0xFF7C4DFF;   // accent gradient start (violet)
    static final int ACC2   = 0xFF00D3FF;   // accent gradient end (cyan)
    static final int GREEN  = 0xFF00E676;
    static final int RED    = 0xFFFF5252;
    static final int TXT    = 0xFFF4F4FA;
    static final int MUTED  = 0xFF9A9AAB;
    static final int TRACK  = 0xFF26262F;   // slider tracks
    static final int IDLE   = 0xFF5A5A68;   // "off" state dot/label

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Rounded solid fill (radius in dp). Mutate the colour later via setColor. */
    static GradientDrawable fill(int color, int radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static GradientDrawable accent(Context c) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{ACC1, ACC2});
        g.setCornerRadius(dp(c, 16));
        return g;
    }

    static GradientDrawable header(Context c, int bottomRadiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{ACC1, ACC2});
        g.setCornerRadii(new float[]{0, 0, 0, 0,
                dp(c, bottomRadiusDp), dp(c, bottomRadiusDp),
                dp(c, bottomRadiusDp), dp(c, bottomRadiusDp)});
        return g;
    }

    /** Dark rounded card (vertical LinearLayout). */
    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(fill(CARD, 18, c));
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        return l;
    }

    static TextView text(Context c, String s, float size, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (bold) t.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        t.setLetterSpacing(0.06f);
        return t;
    }

    /** Section label — tiny, spaced-out, muted (SIGNALRGB-style tags). */
    static TextView tag(Context c, String s) {
        return text(c, s.toUpperCase(), 11f, MUTED, true);
    }

    /**
     * Pill button. {@code style}: 0 = accent gradient, 1 = red, 2 = dark
     * secondary (all white text).
     */
    static Button pill(Context c, String label, int style, View.OnClickListener onClick) {
        Button b = new Button(c);
        b.setText(label);
        b.setTextColor(TXT);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setSingleLine(true);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setLetterSpacing(0.02f);
        b.setBackground(style == 0 ? accent(c) : style == 1
                ? fill(RED, 16, c) : fill(CARD2, 14, c));
        b.setPadding(dp(c, 10), dp(c, 12), dp(c, 10), dp(c, 12));
        b.setStateListAnimator(null);
        b.setOnClickListener(onClick);
        return b;
    }

    static LinearLayout.LayoutParams grow() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }
}

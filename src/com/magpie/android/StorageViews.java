package com.magpie.android;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Small hand-drawn views for 空间管理: a donut chart and a segmented progress bar. */
final class StorageViews {
    private StorageViews() {}

    /** Stable colour per category, warm palette built around the app's terracotta accent. */
    static int color(Context c, String key) {
        boolean night = (c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        String[] day = {"#c4512a", "#d99a3e", "#6f9a74", "#4f7ca8", "#8e6aa8", "#4f9690", "#d0786b", "#b59a3f", "#8a6e5a", "#6d7f92", "#a3876f", "#9c9184", "#b7aca0"};
        String[] dark = {"#f08a5b", "#efb65f", "#8fc095", "#7fa8d4", "#b394cc", "#74bcb5", "#ee9c90", "#d6bd62", "#b8957d", "#94a7ba", "#c9ab92", "#8f857a", "#6e665e"};
        int i;
        switch (key) {
            case Storage.CLI: i = 0; break;
            case Storage.APK: i = 1; break;
            case Storage.DATA: i = 2; break;
            case Storage.CLOUDFLARED: i = 3; break;
            case Storage.TAILSCALE: i = 4; break;
            case Storage.WEB_DATA: i = 5; break;
            case Storage.WEB_CACHE: i = 6; break;
            case Storage.CACHE: i = 7; break;
            case Storage.LOGS: i = 8; break;
            case Storage.ROLLBACK: i = 9; break;
            case Storage.EXPORTS: i = 10; break;
            case Storage.TMP: i = 11; break;
            default: i = 12;
        }
        return android.graphics.Color.parseColor((night ? dark : day)[i]);
    }

    static final class Donut extends View {
        private long[] values = new long[0];
        private int[] colors = new int[0];
        private float progress = 1f;
        private String center = "", caption = "";
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();

        Donut(Context c) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeCap(Paint.Cap.BUTT);
            track.setStyle(Paint.Style.STROKE);
            track.setColor(c.getColor(R.color.app_surface_alt));
            big.setColor(c.getColor(R.color.text_primary));
            big.setTextAlign(Paint.Align.CENTER);
            big.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            big.setTextSize(19 * c.getResources().getDisplayMetrics().scaledDensity);
            small.setColor(c.getColor(R.color.text_muted));
            small.setTextAlign(Paint.Align.CENTER);
            small.setTextSize(11.5f * c.getResources().getDisplayMetrics().scaledDensity);
            ring.setStrokeWidth(16 * d);
            track.setStrokeWidth(16 * d);
        }

        void set(long[] v, int[] col, String centerText, String captionText, boolean animate) {
            values = v;
            colors = col;
            center = centerText;
            caption = captionText;
            if (animate) {
                ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(650);
                a.setInterpolator(new DecelerateInterpolator(1.6f));
                a.addUpdateListener(x -> {
                    progress = (float) x.getAnimatedValue();
                    invalidate();
                });
                a.start();
            } else {
                progress = 1f;
                invalidate();
            }
        }

        @Override
        protected void onMeasure(int w, int h) {
            int s = Math.round(132 * getResources().getDisplayMetrics().density);
            setMeasuredDimension(s, s);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float sw = ring.getStrokeWidth();
            box.set(sw / 2 + 2, sw / 2 + 2, getWidth() - sw / 2 - 2, getHeight() - sw / 2 - 2);
            cv.drawArc(box, 0, 360, false, track);
            long total = 0;
            for (long v : values) total += v;
            if (total > 0) {
                float start = -90f, gap = values.length > 1 ? 1.6f : 0f;
                float sweepAll = 360f * progress;
                for (int i = 0; i < values.length; i++) {
                    float sweep = 360f * values[i] / total;
                    if (sweep <= 0) continue;
                    float drawn = Math.min(sweep, Math.max(0, sweepAll - (start + 90f)));
                    if (drawn > 0) {
                        ring.setColor(colors[i]);
                        float g = sweep > gap * 2 ? gap : 0;
                        cv.drawArc(box, start + g / 2, Math.max(0.6f, drawn - g), false, ring);
                    }
                    start += sweep;
                }
            }
            float cy = getHeight() / 2f;
            cv.drawText(center, getWidth() / 2f, cy + big.getTextSize() * 0.15f, big);
            cv.drawText(caption, getWidth() / 2f, cy + big.getTextSize() * 0.15f + small.getTextSize() * 1.6f, small);
        }
    }

    /** A rounded progress bar with one or more coloured segments over a track. */
    static final class Bar extends View {
        private float[] parts = new float[0];
        private int[] colors = new int[0];
        private float progress = 1f;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int trackColor;
        private final int heightPx;
        private final RectF r = new RectF();
        private final android.graphics.Path clip = new android.graphics.Path();

        Bar(Context c, int heightDp) {
            super(c);
            trackColor = c.getColor(R.color.app_surface_alt);
            heightPx = Math.round(heightDp * c.getResources().getDisplayMetrics().density);
        }

        /** parts are fractions of the full width (0..1), drawn left to right. */
        void set(float[] fractions, int[] cols, boolean animate) {
            parts = fractions;
            colors = cols;
            if (animate) {
                ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(600);
                a.setInterpolator(new DecelerateInterpolator(1.6f));
                a.addUpdateListener(x -> {
                    progress = (float) x.getAnimatedValue();
                    invalidate();
                });
                a.start();
            } else {
                progress = 1f;
                invalidate();
            }
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), heightPx);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float rad = getHeight() / 2f;
            r.set(0, 0, getWidth(), getHeight());
            clip.reset();
            clip.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW);
            cv.save();
            cv.clipPath(clip);
            p.setColor(trackColor);
            cv.drawRect(r, p);
            float x = 0;
            for (int i = 0; i < parts.length; i++) {
                float w = Math.max(0, parts[i]) * getWidth() * progress;
                if (w <= 0) continue;
                if (w < rad && parts.length == 1) w = rad;
                p.setColor(colors[i]);
                cv.drawRect(x, 0, x + w, getHeight(), p);
                x += w;
            }
            cv.restore();
        }
    }
}

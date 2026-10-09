package com.whalepet;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

/**
 * 自绘的小动效：情绪符号、睡觉的 Zzz、睡觉时垫在身下的云。
 *
 * 参考图里那些飘在两人身边的小爱心 / 星星 / 魔法星芒，全是"现有素材 + 简单光效"
 * 就能做出来的，所以这里全部用 Path 直接画，不新增任何素材文件。
 */
final class Effects {

    static final int MOOD_NONE = 0;
    static final int MOOD_HAPPY = 1;     // 开心：金色小星星
    static final int MOOD_LOVE = 2;      // 喜欢 / 撒娇 / 贴贴：粉色爱心
    static final int MOOD_ANGER = 3;     // 生气：红色气鼓鼓
    static final int MOOD_SAD = 4;       // 难过：蓝色小泪滴
    static final int MOOD_MAGIC = 5;     // 施法：紫色星芒 + 光圈
    static final int MOOD_SPARKLE = 6;   // 得意 / 夸奖：白色闪光
    static final int MOOD_DREAM = 7;     // 梦境：星星 + 泡泡

    private static final int C_STAR = 0xFFFFD25E;
    private static final int C_HEART = 0xFFFF7BAE;
    private static final int C_ANGER = 0xFFFF5A5A;
    private static final int C_DROP = 0xFF6FB3FF;
    private static final int C_MAGIC = 0xFFB98CFF;
    private static final int C_SPARKLE = 0xFFEAF1FF;
    private static final int C_ZZZ = 0xFF2B3550;

    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    private int mood = MOOD_NONE;
    private long moodStartNs;
    private long zzzStartNs;

    Effects(float density) { this.density = density; }

    int mood() { return mood; }

    void setMood(int value, long nowNs) {
        if (mood == value) return;
        mood = value;
        moodStartNs = nowNs;
    }

    /**
     * 画情绪符号。
     *
     * @param cx     角色中心
     * @param baseY  符号从这里往上飘（一般是头顶）
     * @param unit   角色高度，符号大小按它缩放
     */
    void draw(Canvas canvas, float cx, float baseY, float unit, long nowNs) {
        if (mood == MOOD_NONE || unit <= 0f) return;
        float t = (nowNs - moodStartNs) / 1_000_000_000f;
        for (int i = 0; i < 3; i++) {
            float k = ((t + i * 0.72f) % 2.5f) / 2.5f;
            float alpha = (float) Math.sin(Math.PI * Math.min(1f, Math.max(0f, k)));
            if (alpha <= 0.02f) continue;
            float sway = (float) Math.sin((t * 1.4f + i * 2.1f)) * unit * 0.09f;
            float x = cx + (i - 1) * unit * 0.20f + sway;
            int a = Math.round(255 * alpha);
            switch (mood) {
                case MOOD_LOVE: {
                    float y = baseY - k * unit * 0.34f;
                    drawHeart(canvas, x, y, unit * 0.055f, a, C_HEART);
                    break;
                }
                case MOOD_HAPPY: {
                    float y = baseY - k * unit * 0.30f;
                    drawStar(canvas, x, y, unit * 0.050f * (0.7f + 0.3f * alpha), t * 1.2f + i, a, C_STAR);
                    break;
                }
                case MOOD_SPARKLE: {
                    float y = baseY - k * unit * 0.28f;
                    drawSparkle(canvas, x, y, unit * 0.052f * (0.6f + 0.4f * alpha), a, C_SPARKLE);
                    break;
                }
                case MOOD_MAGIC: {
                    float y = baseY - k * unit * 0.36f;
                    drawSparkle(canvas, x, y, unit * 0.075f * (0.55f + 0.45f * alpha), a, C_MAGIC);
                    if (i == 1) {
                        // 外扩的光圈：越飘越大、越淡
                        float r = unit * (0.12f + 0.30f * k);
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setStrokeWidth(dp(1.6f));
                        paint.setColor(C_MAGIC);
                        paint.setAlpha(Math.round(a * 0.55f));
                        canvas.drawCircle(cx, baseY + unit * 0.10f, r, paint);
                    }
                    break;
                }
                case MOOD_ANGER: {
                    // 气鼓鼓：贴在头顶一抽一抽，不往上飘
                    float pop = 0.75f + 0.25f * (float) Math.abs(Math.sin(t * 6f));
                    float y = baseY + unit * 0.02f;
                    drawAnger(canvas, x, y, unit * 0.055f * pop, a, C_ANGER);
                    break;
                }
                case MOOD_SAD: {
                    float y = baseY + k * unit * 0.22f;     // 眼泪往下掉
                    drawDrop(canvas, x, y, unit * 0.045f, a, C_DROP);
                    break;
                }
                default: {   // MOOD_DREAM
                    float y = baseY - k * unit * 0.30f;
                    int pick = i % 3;
                    if (pick == 0) drawStar(canvas, x, y, unit * 0.045f, t, a, C_STAR);
                    else if (pick == 1) drawBubble(canvas, x, y, unit * 0.050f, a);
                    else drawHeart(canvas, x, y, unit * 0.042f, a, C_HEART);
                    break;
                }
            }
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
    }

    /** 睡觉的 Zzz：三个"Z"依次飘起来、变大、淡出。 */
    void drawZzz(Canvas canvas, float baseX, float baseY, float unit, long nowNs) {
        if (zzzStartNs == 0L) zzzStartNs = nowNs;
        float t = (nowNs - zzzStartNs) / 1_000_000_000f;
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        for (int i = 0; i < 3; i++) {
            float k = ((t + i * 0.62f) % 1.86f) / 1.86f;
            float alpha = (1f - k) * (k < 0.15f ? k / 0.15f : 1f);
            paint.setTextSize(dp(11f) * (1f + 0.75f * k));
            paint.setColor(C_ZZZ);
            paint.setAlpha(Math.round(220 * Math.max(0f, Math.min(1f, alpha))));
            canvas.drawText("Z", baseX + unit * 0.05f * k, baseY - unit * 0.09f * k, paint);
        }
        paint.setAlpha(255);
    }

    /** 睡觉时垫在身下的云：几个白团子叠成一条，直接画，不用素材。 */
    static void drawCloud(Canvas canvas, Paint paint, float left, float right, float topY, float h) {
        float w = Math.max(1f, right - left);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x1A2A3550);
        paint.setAlpha(26);
        canvas.drawRoundRect(left, topY + h * 0.44f, right, topY + h, h * 0.5f, h * 0.5f, paint);
        paint.setAlpha(255);
        paint.setColor(0xF7FFFFFF);
        canvas.drawRoundRect(left, topY + h * 0.42f, right, topY + h, h * 0.5f, h * 0.5f, paint);
        float[] frac = {0.13f, 0.33f, 0.55f, 0.77f, 0.94f};
        float[] rr = {0.15f, 0.21f, 0.18f, 0.23f, 0.14f};
        float[] dy = {0.40f, 0.28f, 0.24f, 0.32f, 0.44f};
        for (int i = 0; i < frac.length; i++) {
            float cx = left + w * frac[i];
            float r = w * rr[i];
            float cy = topY + h * dy[i] + r * 0.55f;
            canvas.drawCircle(cx, cy, r, paint);
        }
        paint.setColor(0x55CFE3FF);
        canvas.drawRoundRect(left + w * 0.07f, topY + h * 0.74f, right - w * 0.07f, topY + h * 0.84f,
                h * 0.05f, h * 0.05f, paint);
    }

    // ---------------- 符号 ----------------

    private void drawHeart(Canvas canvas, float x, float y, float r, int alpha, int color) {
        path.reset();
        path.moveTo(x, y + r * 0.95f);
        path.cubicTo(x - r * 1.55f, y - r * 0.30f, x - r * 0.60f, y - r * 1.25f, x, y - r * 0.35f);
        path.cubicTo(x + r * 0.60f, y - r * 1.25f, x + r * 1.55f, y - r * 0.30f, x, y + r * 0.95f);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setAlpha(alpha);
        canvas.drawPath(path, paint);
    }

    private void drawStar(Canvas canvas, float x, float y, float r, float rot, int alpha, int color) {
        path.reset();
        for (int i = 0; i < 10; i++) {
            double a = rot + Math.PI / 2 + i * Math.PI / 5;
            float rr = (i % 2 == 0) ? r : r * 0.44f;
            float px = x + (float) Math.cos(a) * rr;
            float py = y - (float) Math.sin(a) * rr;
            if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setAlpha(alpha);
        canvas.drawPath(path, paint);
    }

    private void drawSparkle(Canvas canvas, float x, float y, float r, int alpha, int color) {
        path.reset();
        path.moveTo(x, y - r);
        path.quadTo(x + r * 0.20f, y - r * 0.20f, x + r, y);
        path.quadTo(x + r * 0.20f, y + r * 0.20f, x, y + r);
        path.quadTo(x - r * 0.20f, y + r * 0.20f, x - r, y);
        path.quadTo(x - r * 0.20f, y - r * 0.20f, x, y - r);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setAlpha(alpha);
        canvas.drawPath(path, paint);
    }

    private void drawBubble(Canvas canvas, float x, float y, float r, int alpha) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.4f));
        paint.setColor(0xFF9FC6FF);
        paint.setAlpha(alpha);
        canvas.drawCircle(x, y, r, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x66FFFFFF);
        canvas.drawCircle(x - r * 0.3f, y - r * 0.3f, r * 0.28f, paint);
    }

    private void drawAnger(Canvas canvas, float x, float y, float r, int alpha, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(r * 0.34f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(color);
        paint.setAlpha(alpha);
        canvas.drawLine(x - r, y - r, x - r * 0.30f, y - r * 0.30f, paint);
        canvas.drawLine(x + r, y - r, x + r * 0.30f, y - r * 0.30f, paint);
        canvas.drawLine(x - r, y + r, x - r * 0.30f, y + r * 0.30f, paint);
        canvas.drawLine(x + r, y + r, x + r * 0.30f, y + r * 0.30f, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawDrop(Canvas canvas, float x, float y, float r, int alpha, int color) {
        path.reset();
        path.moveTo(x, y - r);
        path.quadTo(x + r * 0.85f, y + r * 0.40f, x, y + r);
        path.quadTo(x - r * 0.85f, y + r * 0.40f, x, y - r);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setAlpha(alpha);
        canvas.drawPath(path, paint);
    }

    private float dp(float value) { return value * density; }
}

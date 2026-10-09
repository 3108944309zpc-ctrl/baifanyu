package com.whalepet;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

/**
 * 参考图里的对话气泡：白底、宝蓝描边、椭圆外形、说话人那一侧的小尾巴、深蓝居中字。
 *
 * 用法分两步：先 prepare() 量好位置（量完可以读 halfW/halfH/cx/cy 看看会不会跟别的气泡撞上），
 * 再 drawPrepared() 画出来。也可以直接 draw() 一步到位（角色窗口内部自己画时用这个）。
 */
final class SpeechBubble {

    /** 尾巴朝左 / 朝右（都指说话人那一边）。 */
    static final int TAIL_LEFT = -1;
    static final int TAIL_RIGHT = 1;

    private static final float TEXT_DP = 12f;
    private static final float MIN_TEXT_DP = 10f;
    private static final float LINE_GAP_DP = 2f;
    private static final float PAD_X_DP = 6f;
    private static final float PAD_Y_DP = 5f;
    private static final float SPEED_DP = 34f;      // 流动播放：每秒 34dp
    private static final float GAP_DP = 26f;        // 走完一遍隔多远再来一次
    private static final float STROKE_DP = 2f;
    private static final float TAIL_W_DP = 8f;
    private static final float TAIL_H_DP = 13f;
    private static final int FILL_COLOR = 0xFBFFFFFF;
    private static final int STROKE_COLOR = 0xFF3E5CD8;
    private static final int TEXT_COLOR = 0xFF1F2A44;
    private static final float MAX_TEXT_W_DP = 104f;
    private static final float MIN_HALF_W_DP = 30f;
    private static final float MIN_HALF_H_DP = 18f;
    /** 字装进椭圆：宽只占 62%、高只占 72%，四周才留得出弧线。 */
    private static final float W_FIT = 0.62f;
    private static final float H_FIT = 0.72f;

    private final float density;
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path tailPath = new Path();
    private final Path outlinePath = new Path();
    private final Path ovalPath = new Path();
    private final RectF oval = new RectF();
    private final RectF shadow = new RectF();

    private String text;
    private long startNs, untilNs;
    private int tailSide = TAIL_RIGHT;
    private StaticLayout layout;
    private float layoutW;
    private boolean marquee;
    private float textSizeDp = TEXT_DP;

    // prepare() 量出来的几何：圆心 + 半轴
    private float cx, cy, halfW, halfH;
    private boolean prepared;

    SpeechBubble(float density) {
        this.density = density;
        textPaint.setColor(TEXT_COLOR);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setColor(STROKE_COLOR);
        strokePaint.setStrokeWidth(dp(STROKE_DP));
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
    }

    boolean isActive(long nowNs) {
        if (text == null) return false;
        if (nowNs >= untilNs) { clear(); return false; }
        return true;
    }

    String text() { return text; }

    /** 位置定下来之后才知道尾巴该朝哪边（说话人在气泡下侧的哪一边）。 */
    void setTailSide(int side) { tailSide = side >= 0 ? TAIL_RIGHT : TAIL_LEFT; }

    void clear() {
        text = null;
        untilNs = 0L;
        layout = null;
        prepared = false;
    }

    /**
     * 说一句话。
     *
     * @param side    尾巴朝哪边（说话人在屏幕左半边就朝右、右半边就朝左）
     * @param availW  能用的宽度
     */
    void show(String value, int side, float availW, long nowNs) {
        if (value == null || value.length() == 0) { clear(); return; }
        text = value;
        tailSide = side >= 0 ? TAIL_RIGHT : TAIL_LEFT;
        startNs = nowNs;
        textSizeDp = TEXT_DP;
        marquee = false;
        prepared = false;
        build(availW);
        long ms;
        if (marquee) {
            float cycle = textPaint.measureText(text) + dp(GAP_DP);
            ms = (long) (cycle / dp(SPEED_DP) * 1000f) + 900L;
        } else {
            ms = 1500L + layout.getLineCount() * 800L;
        }
        untilNs = startNs + ms * 1_000_000L;
    }

    /** 排版：先用满字号折行，行数超了就把字缩小一点，还超就退化成横向流动播放。 */
    private void build(float availW) {
        float maxTextW = Math.min(dp(MAX_TEXT_W_DP),
                Math.max(dp(36f), availW * W_FIT - dp(PAD_X_DP) * 2f));
        int maxLines = availW < dp(120f) ? 4 : 3;
        float size = TEXT_DP;
        while (true) {
            textPaint.setTextSize(dp(size));
            layout = new StaticLayout(text, textPaint, Math.max(1, Math.round(maxTextW)),
                    Layout.Alignment.ALIGN_CENTER, 1f, dp(LINE_GAP_DP), false);
            if (layout.getLineCount() <= maxLines || size <= MIN_TEXT_DP) break;
            size -= 0.5f;
        }
        textSizeDp = size;
        layoutW = maxTextW;
        marquee = layout.getLineCount() > maxLines;
    }

    // ---------------- 位置 ----------------

    /**
     * 量尺寸、定位。返回 false 表示现在没气泡可画。
     * 量完之后可以读 halfW()/halfH()/cx()/cy() 判断有没有跟别人的气泡挤在一起。
     */
    boolean prepare(float centerX, float topY, float availW, long nowNs) {
        if (!isActive(nowNs)) { prepared = false; return false; }
        textPaint.setTextSize(dp(textSizeDp));
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float blockW, blockH;
        if (marquee) {
            blockW = textPaint.measureText(text);
            blockH = fm.descent - fm.ascent;
        } else {
            blockW = 0f;
            for (int i = 0; i < layout.getLineCount(); i++) {
                blockW = Math.max(blockW, layout.getLineWidth(i));
            }
            blockH = layout.getHeight();
        }
        halfW = Math.max(dp(MIN_HALF_W_DP), (blockW + dp(PAD_X_DP) * 2f) / W_FIT / 2f);
        halfH = Math.max(dp(MIN_HALF_H_DP), (blockH + dp(PAD_Y_DP) * 2f) / H_FIT / 2f);
        float maxHalfW = Math.max(dp(MIN_HALF_W_DP), availW / 2f - dp(1f));
        if (halfW > maxHalfW) halfW = maxHalfW;
        cx = Math.max(halfW + dp(1f), Math.min(availW - halfW - dp(1f), centerX));
        cy = topY + halfH + dp(1f);
        prepared = true;
        return true;
    }

    /** 把 prepare 量好的位置直接画出来（中间没有再改几何）。 */
    void drawPrepared(Canvas canvas, long nowNs) {
        if (!prepared || !isActive(nowNs)) return;
        textPaint.setTextSize(dp(textSizeDp));
        paint(canvas, nowNs);
    }

    /**
     * 画出来。
     *
     * @param centerX 想让气泡中心对准哪（会按可用宽度夹住，不会画出去）
     * @param topY    气泡上边缘
     */
    void draw(Canvas canvas, float centerX, float topY, float availW, long nowNs) {
        if (!prepare(centerX, topY, availW, nowNs)) return;
        paint(canvas, nowNs);
    }

    float halfW() { return halfW; }
    float halfH() { return halfH; }
    float cx() { return cx; }
    float cy() { return cy; }
    /** 含尾巴的整高，用来把它整个摆在头顶之上。 */
    float totalHeight() { return halfH * 2f + dp(TAIL_H_DP); }
    boolean isPrepared() { return prepared; }

    private void paint(Canvas canvas, long nowNs) {
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        oval.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);

        // 柔影：浮在桌面上的感觉（画两层，别太黑）
        shadow.set(oval);
        shadow.inset(-dp(1.4f), -dp(1.4f));
        shadow.offset(0f, dp(2.6f));
        fillPaint.setColor(0x140F1830);
        canvas.drawOval(shadow, fillPaint);
        shadow.inset(-dp(1.4f), -dp(1.4f));
        fillPaint.setColor(0x0A0F1830);
        canvas.drawOval(shadow, fillPaint);

        fillPaint.setColor(FILL_COLOR);
        canvas.drawOval(oval, fillPaint);
        canvas.drawOval(oval, strokePaint);

        // 小尾巴：先在椭圆里印一块白，把那段描边盖掉，再补两条边
        float ax = cx + tailSide * halfW * 0.46f;
        float ay = cy + halfH * 0.80f;
        tailPath.reset();
        tailPath.moveTo(ax - dp(TAIL_W_DP), ay - dp(4f));
        tailPath.lineTo(ax + tailSide * dp(5f), ay + dp(TAIL_H_DP));
        tailPath.lineTo(ax + dp(TAIL_W_DP), ay - dp(2f));
        tailPath.close();
        fillPaint.setColor(FILL_COLOR);
        canvas.drawPath(tailPath, fillPaint);
        outlinePath.reset();
        outlinePath.moveTo(ax - dp(TAIL_W_DP), ay - dp(4f));
        outlinePath.lineTo(ax + tailSide * dp(5f), ay + dp(TAIL_H_DP));
        outlinePath.lineTo(ax + dp(TAIL_W_DP), ay - dp(2f));
        canvas.drawPath(outlinePath, strokePaint);

        canvas.save();
        ovalPath.reset();
        ovalPath.addOval(oval, Path.Direction.CW);
        canvas.clipPath(ovalPath);
        if (marquee) {
            float blockW = textPaint.measureText(text);
            float cycle = blockW + dp(GAP_DP);
            float elapsed = (nowNs - startNs) / 1_000_000_000f;
            float off = (elapsed * dp(SPEED_DP)) % cycle;
            float x0 = cx + halfW - dp(PAD_X_DP) - off;
            float baseY = cy - fm.ascent - (fm.descent - fm.ascent) / 2f;
            canvas.drawText(text, x0, baseY, textPaint);
            canvas.drawText(text, x0 + cycle, baseY, textPaint);
        } else {
            canvas.translate(cx - layoutW / 2f, cy - layout.getHeight() / 2f);
            layout.draw(canvas);
        }
        canvas.restore();
    }

    private float dp(float value) { return value * density; }
}

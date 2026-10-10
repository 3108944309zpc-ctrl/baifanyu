package com.whalepet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.io.IOException;
import java.io.InputStream;

/** 独立的 GPT 小龙女悬浮角色。 */
public final class DragonView extends View {
    public interface Listener {
        void onDragonDragStart();
        void onDragonDrag(float dx, float dy);
        void onDragonDragEnd(float rawX, float rawY);
        void onDragonTap();
        void onDragonLongPress();
    }

    public static final int STAND = 0;
    public static final int HAPPY = 1;
    public static final int SAD = 2;
    public static final int ANGRY = 3;
    public static final int SURPRISED = 4;
    public static final int SHY = 5;
    public static final int SLEEPY = 6;
    public static final int STATE_HOVER = 0;
    public static final int STATE_PERCH = 1;
    /** 扒在哪条边上 —— 和大肥鱼 PetView 用同一套取值，方便 Service 两边同一套判断。 */
    public static final int EDGE_LEFT = 0;
    public static final int EDGE_RIGHT = 1;
    public static final int EDGE_BOTTOM = 2;
    private static final String DIR = "characters/gpt_dragon_girl/";
    private static final long LONG_PRESS_MS = 800L;
    /**
     * 趴边时露出来的那一块 = 立绘里的"脑袋"。
     *
     * 这几个比例是照着她的素材量出来的，不能想当然：
     * 她的脸（肤色像素）落在整幅图的 35%~60% 之间，最早 183、最晚 282 行，
     * 脑袋连头发大概到 290 行左右。之前只取到 42%，正好把脸切在外面 ——
     * 扒在边上只能看到一个后脑勺，这就是"脑袋没露出来"的原因。
     */
    private static final float HEAD_Y0 = 0.02f;   // 头顶（跳过最上面那几行透明边）
    private static final float HEAD_Y1 = 0.62f;   // 下巴再往下一点，保证整张脸在里面
    private static final float HEAD_X0 = 0.03f;   // 左右留够 —— 她的角/头发最宽处到 0.05~0.945，收太多会切掉角
    private static final float HEAD_X1 = 0.97f;

    // 气泡画法统一在 SpeechBubble 里，两个角色共用一套，才不会两边不一样
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint cloudPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int touchSlop;
    private Listener listener;
    private Bitmap current;
    private int expression = STAND;
    private long startNs, tapNs;
    private boolean dragging, longPressed, visible = true;
    private boolean walking;
    private int interactionPhase;
    private boolean sleeping;
    private long lastTouchMs;
    private float downX, downY, lastX, lastY;
    private int state = STATE_HOVER;
    private boolean mirror;
    // 小剧场姿态通道：倾斜（绕立绘中心转）、压扁回弹、临时抬升、整体缩放。
    // 只做整体变换，不改立绘本身 —— 用来演"靠、探、蹦、被推、被撞、贴住"。
    private float poseLean, poseLift, poseSquash = 1f, poseScale = 1f;
    /** 演小剧场时才把窗口横向留宽一点，给倾斜的头顶留地方；平时是 1。 */
    private float poseSlack = 1f;
    private float perchWidthDp = 86f;
    /** 当前趴在哪条边（只在 STATE_PERCH 时有意义）。 */
    private int perchEdge = EDGE_LEFT;
    private Bitmap rotated;
    private float hoverHeightDp = 190f;
    private float ampScale = 0.75f;
    /** 气泡和情绪动效两个角色共用同一套画法（SpeechBubble / Effects）。 */
    private SpeechBubble bubble;
    private Effects fx;
    private final int[] screenLoc = new int[2];

    private final Runnable longPress = new Runnable() {
        @Override public void run() {
            longPressed = true;
            if (listener != null) listener.onDragonLongPress();
        }
    };

    public DragonView(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        float density = context.getResources().getDisplayMetrics().density;
        bubble = new SpeechBubble(density);
        fx = new Effects(density);
        cloudPaint.setAntiAlias(true);
        cloudPaint.setFilterBitmap(true);
        setExpression(STAND);
    }

    public void setListener(Listener value) { listener = value; }
    public float getHoverHeightDp() { return hoverHeightDp; }

    public void setHoverHeightDp(float value) {
        hoverHeightDp = Math.max(120f, Math.min(360f, value));
        requestLayout();
        invalidate();
    }

    public void setAmpScale(float value) {
        ampScale = Math.max(0.1f, Math.min(1.5f, value));
        invalidate();
    }

    /**
     * 悬空尺寸。
     *
     * 宽度不能正好等于立绘宽度：她一直有个 ±2.5° 的轻微摇晃，
     * 量出来最宽的表情（sleepy / angry）摆动时需要 0.412·h 的半宽，
     * 而 0.4·h 会把两边的头发切掉几个像素。留 6% 余量就够了。
     */
    private static final float HOVER_W_SLACK = 1.06f;

    public int windowW() {
        return state == STATE_PERCH ? Math.round(perchWindowW())
                : Math.round(dp(hoverHeightDp) * artW() / artH() * HOVER_W_SLACK * poseSlack);
    }
    public int windowH() {
        return state == STATE_PERCH ? Math.round(perchWindowH())
                : Math.round(dp(hoverHeightDp) * 1.12f);
    }

    /** 立绘尺寸。跟着素材走，和趴边的算法保持同一个来源。 */
    private float artW() { return current == null ? 384f : current.getWidth(); }
    private float artH() { return current == null ? 480f : current.getHeight(); }
    public int expression() { return expression; }
    public int getState() { return state; }
    public void setState(int value) {
        state = value;
        if (rotated != null && !rotated.isRecycled()) { rotated.recycle(); rotated = null; }
        requestLayout();
        invalidate();
    }
    public void setMirror(boolean value) { mirror = value; invalidate(); }

    /** 小剧场姿态：leanDeg 倾斜角度，squash 压扁（1=不变），liftUnit 抬升（以身高为单位），scale 整体缩放。 */
    public void setPose(float leanDeg, float squash, float liftUnit, float scale) {
        poseLean = leanDeg;
        poseSquash = squash <= 0.3f ? 1f : squash;
        poseLift = liftUnit;
        poseScale = scale <= 0.3f ? 1f : scale;
        invalidate();
    }

    /** 演出、收工时开关窗口横向余量（只在互动期间打开，平时不留空白触摸区）。 */
    public void setPoseSlack(boolean on) {
        float v = on ? 1.22f : 1f;
        if (v == poseSlack) return;
        poseSlack = v;
        requestLayout();
        invalidate();
    }
    /**
     * 趴边时露出来的那一块。
     *
     * 左右边：滑块 = 脑袋那条的**宽度**，高按旋转后的长宽比推。
     * 下边：滑块 = 脑袋的**高度**（和大肥鱼趴下边时同一个含义），宽按立绘里脑袋框的长宽比推 ——
     * 两个角色趴下边的"滑块管多大"手感就一致了。
     */
    public float perchWindowW() {
        return perchEdge == EDGE_BOTTOM
                ? dp(perchWidthDp) * headAspect() * 1.10f
                : dp(perchWidthDp) * 1.10f;
    }
    public float perchWindowH() {
        return perchEdge == EDGE_BOTTOM
                ? dp(perchWidthDp) * 1.14f + perchBottomTopPad()
                : dp(perchWidthDp) * perchStripRatio() * 1.16f;
    }
    /** 趴屏幕下边时露在屏幕上方的高度（= 滑块）。Service 用它把窗口钉在屏幕下沿。 */
    public float perchVisibleH() { return dp(perchWidthDp); }
    /**
     * 趴屏幕下边时，窗户比脑袋再往上多留一条 —— 留给气泡。
     *
     * 不加这条的话，气泡只能画在她脸上（窗口就脑袋那么高）。
     * 注意它只把窗口往上撑，脑袋的位置不动：Service 钉下沿时把这个值一起加回去，
     * 露在屏幕上方的脑袋高度仍然是 perchVisibleH()，不会因为气泡变矮。
     */
    public float perchBottomTopPad() { return perchEdge == EDGE_BOTTOM ? dp(38f) : 0f; }
    public void setPerchEdge(int value) {
        if (perchEdge == value) return;
        perchEdge = value;
        requestLayout();
        invalidate();
    }
    public int getPerchEdge() { return perchEdge; }
    /** 不旋转时脑袋框的长宽比（给趴下边算宽度用）。 */
    private float headAspect() {
        Bitmap b = current;
        float sw = b == null ? 384f : b.getWidth();
        float sh = b == null ? 480f : b.getHeight();
        float headW = Math.max(1f, (HEAD_X1 - HEAD_X0) * sw);
        float headH = Math.max(1f, (HEAD_Y1 - HEAD_Y0) * sh);
        return headW / headH;
    }
    public void setPerchWidthDp(float value) {
        perchWidthDp = Math.max(56f, Math.min(160f, value));
        requestLayout();
        invalidate();
    }

    /** 旋转后脑袋那一段的高宽比（跟素材和上面的比例走，不写死）。 */
    private float perchStripRatio() {
        Bitmap b = current;
        float sw = b == null ? 384f : b.getWidth();     // 立绘宽
        float sh = b == null ? 480f : b.getHeight();    // 立绘高
        float bandW = Math.max(1f, (HEAD_Y1 - HEAD_Y0) * sh);   // 旋转后横着的那条
        float bandH = Math.max(1f, (HEAD_X1 - HEAD_X0) * sw);   // 旋转后竖着的那条
        return bandH / bandW;
    }

    public void setExpression(int next) {
        if (next < STAND || next > SLEEPY) next = STAND;
        Bitmap decoded = decode(expressionName(next));
        if (decoded == null && next != STAND) decoded = decode("stand.png");
        if (decoded == null) return;
        if (current != null && !current.isRecycled()) current.recycle();
        if (rotated != null && !rotated.isRecycled()) rotated.recycle();
        rotated = null;
        current = decoded;
        expression = next;
        invalidate();
    }

    public void nudge() {
        tapNs = System.nanoTime();
        invalidate();
    }

    // ---- 自动溜达（和大肥鱼同一套：Service 挪窗口，View 只负责走路的样子）----

    public void setWalking(boolean value) {
        if (walking != value) { walking = value; invalidate(); }
    }
    public boolean isWalking() { return walking; }
    public void setInteractionPhase(int value) { interactionPhase = value; invalidate(); }
    public void setSleeping(boolean value) {
        if (sleeping != value) { sleeping = value; invalidate(); }
    }
    public boolean isDragging() { return dragging; }
    public long msSinceTouch() { return System.currentTimeMillis() - lastTouchMs; }

    public void setWindowVisible(boolean value) {
        visible = value;
        if (!value) handler.removeCallbacks(longPress);
        invalidate();
    }

    public void showBubble(String text) {
        if (bubble == null) return;
        bubble.show(text, bubbleSide(), Math.max(dp(40f), getWidth() - dp(6f)), System.nanoTime());
        invalidate();
    }

    public void clearBubble() {
        if (bubble != null) bubble.clear();
        invalidate();
    }

    public boolean hasBubble() {
        return bubble != null && bubble.isActive(System.nanoTime());
    }

    /** 情绪符号：爱心 / 星星 / 魔法星芒 / 气鼓鼓 / 眼泪 / 梦境（和大肥鱼共用 Effects）。 */
    public void setMood(int mood) {
        if (fx != null) {
            fx.setMood(mood, System.nanoTime());
            invalidate();
        }
    }

    /**
     * 气泡的小尾巴朝哪边：人在屏幕左半边就朝右、右半边就朝左 ——
     * 两个人靠近时两根尾巴正好朝中间，跟参考图里一样。
     */
    private int bubbleSide() {
        getLocationOnScreen(screenLoc);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        float center = screenLoc[0] + getWidth() / 2f;
        return center * 2f < screenW ? SpeechBubble.TAIL_RIGHT : SpeechBubble.TAIL_LEFT;
    }

    private String expressionName(int value) {
        switch (value) {
            case HAPPY: return "happy.png";
            case SAD: return "sad_teary.png";
            case ANGRY: return "angry.png";
            case SURPRISED: return "surprised.png";
            case SHY: return "shy.png";
            case SLEEPY: return "sleepy_confused.png";
            default: return "stand.png";
        }
    }

    private Bitmap decode(String file) {
        try (InputStream input = getContext().getAssets().open(DIR + file)) {
            return BitmapFactory.decodeStream(input);
        } catch (IOException ignored) {
            return null;
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(windowW(), windowH());
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!visible || current == null) return;
        long now = System.nanoTime();
        if (startNs == 0L) startNs = now;
        float t = (now - startNs) / 1_000_000_000f;
        float tau = (float) (Math.PI * 2.0);
        float dy = 0.018f * ampScale * (float) Math.sin(tau * t / 2.7f);
        float sy = 1f + 0.014f * ampScale * (float) Math.sin(tau * t / 1.35f);
        float tilt = dragging ? 0f : 2.5f * (float) Math.sin(tau * t / 2.1f);
        if (walking) {
            // 挪动时左右摇 + 上下颠，跟大肥鱼一个调子；只平移的话像贴纸在滑
            float wk = tau * t * 1.9f;
            dy += 0.016f * ampScale * Math.abs((float) Math.sin(wk));
            sy *= 1f + 0.018f * ampScale * (float) Math.sin(wk * 2f);
            tilt = 5.5f * (float) Math.sin(wk);
        }
        // 互动期间的第二层动作：打闹抖一下、魔法闪动、睡觉慢慢呼吸。
        if (interactionPhase == 2 || interactionPhase == 3) {
            float j = tau * t * 3.2f;
            dy += 0.026f * ampScale * (float) Math.sin(j);
            tilt += 4.5f * (float) Math.sin(j);
        } else if (interactionPhase == 4) {
            sy *= 1f + 0.025f * ampScale * (float) Math.sin(tau * t * 4f);
            tilt += 1.5f * (float) Math.sin(tau * t * 2f);
        } else if (interactionPhase == 5 || interactionPhase == 6) {
            dy += 0.010f * ampScale * (float) Math.sin(tau * t / 1.8f);
            sy *= 1f + 0.020f * ampScale * (float) Math.sin(tau * t / 2.1f);
        } else if (interactionPhase == 7) {
            // 依偎：两只同一个频率轻轻晃，看起来像靠在一起
            dy += 0.014f * ampScale * (float) Math.sin(tau * t / 2.4f);
            sy *= 1f + 0.012f * ampScale * (float) Math.sin(tau * t / 2.4f);
            tilt += 1.8f * (float) Math.sin(tau * t / 2.4f);
        } else if (interactionPhase == 8) {
            tilt += 6.5f * (float) Math.sin(tau * t * 5f) * (1f - Math.min(1f, t % 1f));
        } else if (interactionPhase == 9) {
            dy -= 0.030f * ampScale * Math.abs((float) Math.sin(tau * t / 1.6f));
        }
        if (sleeping) {
            // 睡着：呼吸变慢变深
            dy += 0.012f * ampScale * (float) Math.sin(tau * t / 3.4f);
            sy *= 1f + 0.026f * ampScale * (float) Math.sin(tau * t / 3.4f);
        }
        if (tapNs != 0L) {
            float k = (now - tapNs) / 300_000_000f;
            if (k >= 1f) tapNs = 0L;
            else sy *= 1f - 0.08f * (float) Math.sin(Math.PI * k);
        }
        float h = dp(hoverHeightDp);
        float w = h * artW() / artH();
        float cx = getWidth() / 2f;
        // 睡着的时候整个人往下沉一点，好让云朵把下半身盖住（参考图里是躺在云上）
        float bottom = getHeight() - h * 0.04f + (dy - poseLift) * h + (sleeping ? h * 0.16f : 0f);
        float span = h * sy * poseSquash * poseScale;
        float top = bottom - span;
        float halfW = w * poseScale / poseSquash / 2f;
        RectF dst = new RectF(cx - halfW, top, cx + halfW, bottom);
        canvas.save();
        if (state == STATE_PERCH) {
            drawPerch(canvas, t, dy, sy);
        } else {
            // 以立绘中心为支点倾斜：窗口只留了 22% 横向余量，绕脚转会甩出窗口被裁掉
            canvas.rotate(tilt + poseLean, cx, bottom - span / 2f);
            if (mirror) canvas.scale(-1f, 1f, cx, 0f);
            canvas.drawBitmap(current, null, dst, paint);
        }
        canvas.restore();
        if (sleeping && state == STATE_HOVER) {
            // 躺进云里：身子已经往下沉了，这里补一朵云把下半身盖住
            Effects.drawCloud(canvas, cloudPaint, 0f, getWidth(),
                    getHeight() - h * 0.30f, h * 0.32f);
        }
        if (fx != null) {
            if (state == STATE_PERCH) {
                fx.draw(canvas, getWidth() * 0.55f, getHeight() * 0.22f, getHeight(), now);
            } else {
                fx.draw(canvas, getWidth() / 2f, getHeight() - h * 0.94f, h, now);
            }
        }
        drawBubble(canvas, now);
        if (sleeping) drawZzz(canvas, now);
        postInvalidateOnAnimation();
    }

    /**
     * 睡觉动效：三个"Z"从脑袋旁边一个个飘起来、变大、淡出。
     * 趴边时窗口很窄，所以起点贴着脑袋上缘、飘的幅度也小一点，保证在窗口里看得见。
     */
    private void drawZzz(Canvas canvas, long now) {
        float t = (now - startNs) / 1_000_000_000f;
        float baseX, baseY;
        if (state == STATE_PERCH) {
            baseX = getWidth() * 0.42f;
            baseY = getHeight() * 0.20f;
        } else {
            float h = dp(hoverHeightDp);
            baseX = getWidth() / 2f + h * 0.22f;
            baseY = Math.max(dp(20f), getHeight() - h * 1.04f);
        }
        if (fx != null) {
            fx.drawZzz(canvas, baseX, baseY,
                    state == STATE_PERCH ? getHeight() : dp(hoverHeightDp), now);
        }
    }

    /**
     * 趴边：把立绘转 90°，只切出「脑袋」那一段贴在屏幕边上 ——
     * 大肥鱼那套就是这个做法，比例按小龙女自己的素材量出来。
     *
     * 动作幅度和大肥鱼一样也是活的：
     *   peek  脑袋在边上探进探出（横向，和屏幕边缘垂直）
     *   dy    上下轻晃（自带 0.018、走动时再加 0.016，都乘动作幅度）
     *   sy    呼吸缩放（点她一下还会有一个压扁的反馈）
     * 这三样都跟着她自己的"动作幅度"滑块走，调到最小就几乎不动。
     */
    private void drawPerch(Canvas canvas, float t, float dy, float sy) {
        // 趴屏幕下边是另一套画法：不旋转，直接让她从下沿探出头来
        if (perchEdge == EDGE_BOTTOM) { drawPerchBottom(canvas, t, sy); return; }
        if (rotated == null || rotated.isRecycled()) rotated = rotate90(current);
        if (rotated == null) return;
        Rect src = headSrc(rotated);
        float w = dp(perchWidthDp);
        float h = w * src.height() / (float) src.width();
        float tau = (float) (Math.PI * 2.0);
        float peek = w * 0.02f * (1f + (float) Math.sin(tau * t / 2.4f - 1.2f));
        float hh = h * sy;
        float top = (getHeight() - hh) / 2f + dy * h;
        RectF dst = new RectF(peek, top, peek + w, top + hh);
        canvas.save();
        if (mirror) canvas.scale(-1f, 1f, getWidth() / 2f, 0f);
        canvas.drawBitmap(rotated, src, dst, paint);
        canvas.restore();
    }

    /**
     * 趴屏幕下边：不旋转，把立绘里的脑袋框整块往下沿放，下巴贴着窗口下沿 ——
     * 窗口下沿就是屏幕下沿（窗口比脑袋高 14%，多出来的那截露在屏幕外，
     * 所以实际露在屏幕上方的正好是滑块的 86%，跟大肥鱼趴下边一样的比例）。
     *
     * 呼吸（sy）只往上长，下巴一直贴着下沿，看起来才像"从屏幕下边探出头"，
     * 而不是整块上下平移。
     */
    private void drawPerchBottom(Canvas canvas, float t, float sy) {
        if (current == null) return;
        Rect src = headSrcUpright(current);
        float h = dp(perchWidthDp);
        float w = h * src.width() / (float) src.height();
        float tau = (float) (Math.PI * 2.0);
        // 左右轻轻探头，和大肥鱼趴下边一样有个"活的"感觉
        float peek = dp(perchWidthDp) * 0.02f * (1f + (float) Math.sin(tau * t / 2.4f - 1.2f));
        float hh = h * sy;
        float top = getHeight() - hh;
        RectF dst = new RectF(peek, top, peek + w, top + hh);
        canvas.save();
        if (mirror) canvas.scale(-1f, 1f, getWidth() / 2f, 0f);
        canvas.drawBitmap(current, src, dst, paint);
        canvas.restore();
    }

    /** 不旋转的立绘里，"脑袋那一块"是哪个矩形（趴屏幕下边用）。 */
    private Rect headSrcUpright(Bitmap b) {
        int bw = b.getWidth(), bh = b.getHeight();
        int x0 = Math.round(HEAD_X0 * bw);
        int x1 = Math.round(HEAD_X1 * bw);
        int y0 = Math.round(HEAD_Y0 * bh);
        int y1 = Math.round(HEAD_Y1 * bh);
        x0 = Math.max(0, Math.min(bw - 1, x0));
        x1 = Math.max(x0 + 1, Math.min(bw, x1));
        y0 = Math.max(0, Math.min(bh - 1, y0));
        y1 = Math.max(y0 + 1, Math.min(bh, y1));
        return new Rect(x0, y0, x1, y1);
    }

    /**
     * 旋转之后的图上，"脑袋那一段"是哪个矩形。
     *
     * 顺时针转 90° 后，立绘的竖直方向变成横轴、水平方向变成竖轴，
     * 所以：横坐标来自脑袋的上下比例，纵坐标来自脑袋的左右比例。
     * 大肥鱼那边是同一套换算（headX0 = srcH-1-hy1*srcH）。
     */
    private Rect headSrc(Bitmap rot) {
        int rw = rot.getWidth(), rh = rot.getHeight();
        int x0 = Math.round(rw - 1 - HEAD_Y1 * rw);
        int x1 = Math.round(rw - 1 - HEAD_Y0 * rw);
        int y0 = Math.round(HEAD_X0 * rh);
        int y1 = Math.round(HEAD_X1 * rh);
        x0 = Math.max(0, Math.min(rw - 1, x0));
        x1 = Math.max(x0 + 1, Math.min(rw, x1));
        y0 = Math.max(0, Math.min(rh - 1, y0));
        y1 = Math.max(y0 + 1, Math.min(rh, y1));
        return new Rect(x0, y0, x1, y1);
    }

    private static Bitmap rotate90(Bitmap src) {
        if (src == null) return null;
        Matrix m = new Matrix();
        m.postRotate(90);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    /**
     * 画气泡。
     *
     * 只有一行：放得下就居中静态显示；放不下就从右边进来、往左流过去，
     * 一遍走完气泡收起（Duration 在 showBubble 里按文字长度算好）。
     * 之前是按宽度折行 + 省略号，人在小尺寸时一句话被截成"你呢…"，
     * 等于没说完整 —— 流动播放能保证整句话都过一遍。
     */
    private void drawBubble(Canvas canvas, long now) {
        if (bubble == null) return;
        bubble.draw(canvas, getWidth() / 2f, dp(4f), Math.max(dp(40f), getWidth() - dp(6f)), now);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        float x = event.getRawX(), y = event.getRawY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = x;
                downY = lastY = y;
                dragging = false;
                longPressed = false;
                lastTouchMs = System.currentTimeMillis();
                handler.postDelayed(longPress, LONG_PRESS_MS);
                return true;
            case MotionEvent.ACTION_MOVE:
                lastTouchMs = System.currentTimeMillis();
                if (!dragging && (Math.abs(x - downX) > touchSlop || Math.abs(y - downY) > touchSlop)) {
                    handler.removeCallbacks(longPress);
                    dragging = true;
                    if (listener != null) listener.onDragonDragStart();
                }
                if (dragging && listener != null) listener.onDragonDrag(x - lastX, y - lastY);
                lastX = x;
                lastY = y;
                return true;
            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPress);
                if (dragging) {
                    dragging = false;
                    if (listener != null) listener.onDragonDragEnd(x, y);
                } else if (!longPressed) {
                    nudge();
                    if (listener != null) listener.onDragonTap();
                }
                longPressed = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress);
                dragging = false;
                longPressed = false;
                return true;
            default:
                return true;
        }
    }

    @Override protected void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        if (current != null && !current.isRecycled()) current.recycle();
        if (rotated != null && !rotated.isRecycled()) rotated.recycle();
        current = null;
        rotated = null;
        super.onDetachedFromWindow();
    }
}

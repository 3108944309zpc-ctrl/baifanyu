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
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.io.IOException;
import java.io.InputStream;

/**
 * 宠物的绘制、动作、皮肤与交互。
 *
 * 皮肤 = 一套放在 assets/skins/&lt;名字&gt;/ 里的立绘：
 *   一张基准正面立绘 pet_stand.png + 若干表情 pet_face_*.png
 * 加一套皮肤只要在 SKINS 里加一行 + 往 assets/skins/ 放图。
 *
 * 表情是"整张换图"，不是贴一块脸上去 ——
 * 表情图和底图是两次独立生成的，五官位置和缩放都有偏差，
 * 任何"贴片"方案都会在「露接缝」和「露重影」之间二选一。
 * 整张换掉就没这个问题：每张图内部是自洽的，身体也不会跳。
 */
public class PetView extends View implements Choreographer.FrameCallback {

    public static final int STATE_HOVER = 0;
    public static final int STATE_PERCH = 1;

    /** 扒在哪条边上 */
    public static final int EDGE_LEFT = 0, EDGE_RIGHT = 1, EDGE_BOTTOM = 2;

    private static final String DIR_MAID = "skins/maid/";
    private static final String DIR_BASIN = "skins/basin/";

    // 头部区块的默认比例（女仆装那套调的）
    private static final float DEF_HX0 = 35f / 1191f;
    private static final float DEF_HX1 = 985f / 1191f;
    private static final float DEF_HY0 = 30f / 1514f;
    private static final float DEF_HY1 = 800f / 1514f;

    /**
     * 一套皮肤。
     *
     * hx0/hx1/hy0/hy1 = 趴边时显示素材的哪一块（占素材宽高的比例）。
     * 必须每套皮肤单独给 —— 饭盆头那顶盆横向撑得比女仆装宽得多，
     * 沿用同一套比例会把盆的右边切掉一块。
     */
    public static final class Skin {
        public final String name, base;
        public final String[] faces;
        public final float hx0, hx1, hy0, hy1;

        Skin(String name, String base, String[] faces) {
            this(name, base, faces, DEF_HX0, DEF_HX1, DEF_HY0, DEF_HY1);
        }

        Skin(String name, String base, String[] faces,
             float hx0, float hx1, float hy0, float hy1) {
            this.name = name;
            this.base = base;
            this.faces = faces;
            this.hx0 = hx0;
            this.hx1 = hx1;
            this.hy0 = hy0;
            this.hy1 = hy1;
        }
    }

    // 第一套是默认皮肤
    public static final Skin[] SKINS = {
            new Skin("饭盆头", DIR_BASIN + "pet_stand.png", new String[]{
                    DIR_BASIN + "pet_face_happy.png",
                    DIR_BASIN + "pet_face_sad.png",
                    DIR_BASIN + "pet_face_angry.png",
                    DIR_BASIN + "pet_face_surprised.png",
                    DIR_BASIN + "pet_face_shy.png",
                    DIR_BASIN + "pet_face_confused.png",
            }, 0.020f, 0.985f, 0.015f, 0.560f),
            new Skin("女仆装", DIR_MAID + "pet_stand.png", new String[]{
                    DIR_MAID + "pet_face_happy.png",
                    DIR_MAID + "pet_face_sad.png",
                    DIR_MAID + "pet_face_angry.png",
                    DIR_MAID + "pet_face_surprised.png",
                    DIR_MAID + "pet_face_shy.png",
                    DIR_MAID + "pet_face_confused.png",
            }),
    };

    private static final float NS = 1_000_000_000f;
    private static final long LONG_PRESS_MS = 800;

    // 气泡画法统一在 SpeechBubble 里，两个角色共用一套，才不会两边不一样

    public interface Listener {
        void onDragStart();
        void onDrag(float dx, float dy);
        void onDragEnd(float rawX, float rawY);
        void onTap();
        void onLongPress();
    }

    private Context ctx;
    private Bitmap baseBmp;      // 基准立绘（跟皮肤走）
    private Bitmap faceBmp;      // 当前表情（需要回收）
    private Bitmap cur;          // 当前显示的那张
    private Bitmap rotCache;     // cur 的 90° 版本，趴边用，按需生成

    private int skinIdx = 0, faceCur = 0;
    private boolean[] faceOk = new boolean[0];

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint cloudPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 气泡和情绪动效两个角色共用同一套画法（SpeechBubble / Effects）。 */
    private SpeechBubble bubble;
    private Effects fx;
    private final int[] screenLoc = new int[2];

    private int srcW = 944, srcH = 1200;
    private int headX0, headX1, headY0, headY1;
    /** 同一块裁剪区，但换算成「没旋转的原图」坐标（趴屏幕下边时用） */
    private int headBX0, headBX1, headBY0, headBY1;

    private int state = STATE_HOVER;
    private boolean mirrorX = false;
    // 两边都留：本分支的小剧场姿态通道（上游没有）
    // 小剧场姿态通道：倾斜（绕立绘中心转）、压扁回弹、临时抬升、整体缩放。
    // 只做整体变换，不改立绘本身 —— 用来演"靠、探、蹦、被推、被撞、贴住"。
    private float poseLean, poseLift, poseSquash = 1f, poseScale = 1f;
    /** 演小剧场时才把窗口横向留宽一点，给倾斜的头顶留地方；平时是 1。 */
    private float poseSlack = 1f;
    // 两边都留：上游新增的「扒在哪条边」（左侧/右侧/下沿）
    private int perchEdge = EDGE_LEFT;
    private float hoverHeightDp = 240f, perchWidthDp = 96f, ampScale = 0.75f;

    private float pDy, pSx = 1f, pSy = 1f;

    private float dragTilt;
    private long startNs, lastFrameNs, tapNs;
    private long frameIntervalNs = (long) (NS / 30);
    private boolean windowVisible = true, looping = false;
    private int interactionPhase;
    private boolean sleeping;

    private Listener listener;
    private float downRawX, downRawY, lastRawX, lastRawY;
    private boolean dragging, longPressed;
    private int touchSlop;

    private final Runnable longPressRun = new Runnable() {
        @Override public void run() {
            longPressed = true;
            if (listener != null) listener.onLongPress();
        }
    };

    public PetView(Context c) { super(c); init(c); }
    public PetView(Context c, AttributeSet a) { super(c, a); init(c); }

    private void init(Context c) {
        ctx = c;
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        paint.setFilterBitmap(true);
        paint.setDither(true);
        float density = c.getResources().getDisplayMetrics().density;
        bubble = new SpeechBubble(density);
        fx = new Effects(density);
        cloudPaint.setAntiAlias(true);
        cloudPaint.setFilterBitmap(true);
        applySkin(0);
    }

    private Bitmap decode(String name) {
        if (name == null) return null;
        try {
            InputStream is = ctx.getAssets().open(name);
            Bitmap b = BitmapFactory.decodeStream(is);
            is.close();
            return b;
        } catch (IOException e) {
            return null;
        }
    }

    private boolean assetExists(String name) {
        try { ctx.getAssets().open(name).close(); return true; }
        catch (Exception e) { return false; }
    }

    private static Bitmap rotate90(Bitmap src) {
        Matrix m = new Matrix();
        m.postRotate(90);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    private void clearRot() {
        if (rotCache != null && !rotCache.isRecycled()) rotCache.recycle();
        rotCache = null;
    }

    // ---------------- 皮肤 ----------------

    public void applySkin(int idx) {
        if (idx < 0 || idx >= SKINS.length) return;
        Skin s = SKINS[idx];
        if (faceBmp != null && !faceBmp.isRecycled()) faceBmp.recycle();
        faceBmp = null;
        clearRot();
        if (baseBmp != null && !baseBmp.isRecycled()) baseBmp.recycle();

        skinIdx = idx;
        faceCur = 0;
        baseBmp = decode(s.base);

        // 只认真正存在的表情文件 —— 这样素材没到齐也不会切到空白
        faceOk = new boolean[s.faces.length];
        for (int i = 0; i < s.faces.length; i++) faceOk[i] = assetExists(s.faces[i]);

        cur = baseBmp;
        if (cur != null) {
            srcW = cur.getWidth();
            srcH = cur.getHeight();
            // ↓ 这组是「旋转 90° 之后的图」坐标：x 用 srcH 且翻转、y 用 srcW
            headX0 = Math.round(srcH - 1 - s.hy1 * srcH);
            headX1 = Math.round(srcH - 1 - s.hy0 * srcH);
            headY0 = Math.round(s.hx0 * srcW);
            headY1 = Math.round(s.hx1 * srcW);

            // ↓ 同一块区域换算回「原图坐标」，趴屏幕下边（不旋转）时必须用这组。
            //   否则矩形会冲出原图右边界（basin: x 要到 1181，原图只有 992 宽），
            //   画出来只剩左边一半。
            headBX0 = Math.round(s.hx0 * srcW);
            headBX1 = Math.round(s.hx1 * srcW);
            headBY0 = Math.round(s.hy0 * srcH);
            headBY1 = Math.round(s.hy1 * srcH);
        }
        requestLayout();
        invalidate();
    }

    public void cycleSkin() { applySkin((skinIdx + 1) % SKINS.length); }

    public int skinCount() { return SKINS.length; }
    public int currentSkin() { return skinIdx; }
    public String skinName() { return SKINS[skinIdx].name; }
    public String skinName(int i) { return (i >= 0 && i < SKINS.length) ? SKINS[i].name : ""; }

    // ---------------- 表情 ----------------

    public void setFace(int idx) {
        Skin s = SKINS[skinIdx];
        if (idx < 0 || idx > s.faces.length || idx == faceCur) return;
        if (idx == 0) {
            if (faceBmp != null && !faceBmp.isRecycled()) faceBmp.recycle();
            faceBmp = null;
            cur = baseBmp;
        } else {
            if (!faceOk[idx - 1]) return;
            Bitmap b = decode(s.faces[idx - 1]);
            if (b == null) return;
            if (faceBmp != null && !faceBmp.isRecycled()) faceBmp.recycle();
            faceBmp = b;
            cur = b;
        }
        faceCur = idx;
        clearRot();
        invalidate();
    }

    public void cycleFace() {
        int n = SKINS[skinIdx].faces.length;
        for (int i = 1; i <= n; i++) {
            int idx = (faceCur + i) % (n + 1);
            if (idx == 0 || faceOk[idx - 1]) { setFace(idx); return; }
        }
    }

    public int faceCount() {
        int n = 0;
        for (boolean b : faceOk) if (b) n++;
        return n;
    }

    public int currentFace() { return faceCur; }

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

    /** 情绪符号：爱心 / 星星 / 魔法星芒 / 气鼓鼓 / 眼泪 / 梦境（和另一个角色共用 Effects）。 */
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

    public void pokeFeedback() { tapNs = System.nanoTime(); invalidate(); }

    public void setListener(Listener l) { this.listener = l; }

    // ---- 自动溜达：Service 挪窗口，这里只管"走路的样子" ----
    private boolean walking = false;
    private long lastTouchMs = System.currentTimeMillis();

    public void setWalking(boolean w) {
        if (walking != w) { walking = w; invalidate(); }
    }

    public void setInteractionPhase(int value) { interactionPhase = value; invalidate(); }

    public void setSleeping(boolean value) {
        if (sleeping != value) { sleeping = value; invalidate(); }
    }

    /** 距离上一次碰她过了多久（毫秒）—— 刚碰过就先别乱跑 */
    public long msSinceTouch() { return System.currentTimeMillis() - lastTouchMs; }

    public boolean isDragging() { return dragging; }

    public void setState(int s) {
        if (state != s) {
            state = s;
            frameIntervalNs = (long) (NS / (s == STATE_PERCH ? 20 : 30));
            requestLayout();
            invalidate();
        }
    }

    public int getState() { return state; }
    public void setMirror(boolean m) { mirrorX = m; invalidate(); }

    // 两边都留：本分支的小剧场姿态 API
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

    // 两边都留：上游新增的扒边方向 API
    public void setPerchEdge(int e) { perchEdge = e; requestLayout(); invalidate(); }
    public int getPerchEdge() { return perchEdge; }

    public void setHoverHeightDp(float v) { hoverHeightDp = v; requestLayout(); invalidate(); }
    public void setPerchWidthDp(float v) { perchWidthDp = v; requestLayout(); invalidate(); }
    public float getHoverHeightDp() { return hoverHeightDp; }
    public float getPerchWidthDp() { return perchWidthDp; }
    public void setAmpScale(float v) { ampScale = Math.max(0.1f, v); invalidate(); }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    public float windowW() { return dp(hoverHeightDp) * srcW / (float) srcH * poseSlack; }
    public float windowH() { return dp(hoverHeightDp) * 1.16f; }
    public float perchWindowW() {
        // 趴下边：滑块给的是脑袋"高"，宽度由长宽比推出来
        if (perchEdge == EDGE_BOTTOM) {
            float a = (headBX1 - headBX0) / (float) (headBY1 - headBY0);
            return dp(perchWidthDp) * a * 1.10f;
        }
        return dp(perchWidthDp) * 1.10f;
    }

    /** 趴下边时她实际占的高度（用来把下巴精确落在屏幕下沿） */
    public float perchVisibleH() { return dp(perchWidthDp); }
    public float perchWindowH() {
        // 趴下边：滑块＝脑袋高度，窗口只需容下"呼吸"那点伸缩
        if (perchEdge == EDGE_BOTTOM) return dp(perchWidthDp) * 1.14f;
        // 趴左右：图转了 90°，用旋转坐标的长宽比
        return dp(perchWidthDp) * (headY1 - headY0) / (float) (headX1 - headX0) * 1.16f;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        if (state == STATE_HOVER) setMeasuredDimension(Math.round(windowW()), Math.round(windowH()));
        else setMeasuredDimension(Math.round(perchWindowW()), Math.round(perchWindowH()));
    }

    // ---------------- 绘制 ----------------

    @Override
    protected void onDraw(Canvas canvas) {
        if (cur == null) return;
        long now = System.nanoTime();
        if (startNs == 0) { startNs = now; lastFrameNs = now; }
        float t = (now - startNs) / NS;

        final float tau = (float) (2 * Math.PI);
        pDy = 0.022f * ampScale * (float) Math.sin(tau * t / 2.4f);
        pSx = 1f;
        pSy = 1f + 0.014f * ampScale * (float) Math.sin(tau * 2 * t / 2.4f);

        if (dragging) {
            pSx += 0.05f * Math.abs(dragTilt) / 9f;
            pSy -= 0.02f * Math.abs(dragTilt) / 9f;
        }
        if (walking) {
            // 挪动时左右摇 + 上下颠。只平移不摇的话，看着像贴纸在滑，不像在走。
            float wk = tau * t * 1.9f;
            pDy += 0.016f * ampScale * Math.abs((float) Math.sin(wk));
            pSy *= 1f + 0.018f * ampScale * (float) Math.sin(wk * 2f);
            dragTilt = 5.5f * (float) Math.sin(wk);
        } else if (!dragging) {
            dragTilt = 0f;
        }
        if (interactionPhase == 2 || interactionPhase == 3) {
            float j = tau * t * 3.2f;
            pDy += 0.026f * ampScale * (float) Math.sin(j);
            dragTilt += 4.5f * (float) Math.sin(j);
        } else if (interactionPhase == 4) {
            pSy *= 1f + 0.025f * ampScale * (float) Math.sin(tau * t * 4f);
            dragTilt += 1.5f * (float) Math.sin(tau * t * 2f);
        } else if (interactionPhase == 5 || interactionPhase == 6) {
            pDy += 0.010f * ampScale * (float) Math.sin(tau * t / 1.8f);
            pSy *= 1f + 0.020f * ampScale * (float) Math.sin(tau * t / 2.1f);
        } else if (interactionPhase == 7) {
            // 依偎：和小龙女同一个频率，看起来像靠在一起
            pDy += 0.014f * ampScale * (float) Math.sin(tau * t / 2.4f);
            pSy *= 1f + 0.012f * ampScale * (float) Math.sin(tau * t / 2.4f);
            dragTilt += 1.8f * (float) Math.sin(tau * t / 2.4f);
        } else if (interactionPhase == 8) {
            dragTilt += 6.5f * (float) Math.sin(tau * t * 5f) * (1f - Math.min(1f, t % 1f));
        } else if (interactionPhase == 9) {
            pDy -= 0.030f * ampScale * Math.abs((float) Math.sin(tau * t / 1.6f));
        }
        if (sleeping) {
            pDy += 0.012f * ampScale * (float) Math.sin(tau * t / 3.4f);
            pSy *= 1f + 0.026f * ampScale * (float) Math.sin(tau * t / 3.4f);
        }
        if (tapNs != 0) {
            float k = (now - tapNs) / (0.30f * NS);
            if (k >= 1f) tapNs = 0;
            else pSy *= 1f - 0.10f * (float) Math.sin(Math.PI * k);
        }

        if (state == STATE_HOVER) drawHover(canvas);
        else drawPerch(canvas, t);
        if (sleeping && state == STATE_HOVER) {
            // 躺进云里：身子已经往下沉了，这里补一朵云把下半身盖住（参考图里的睡姿）
            float h = dp(hoverHeightDp);
            Effects.drawCloud(canvas, cloudPaint, 0f, getWidth(), getHeight() - h * 0.30f, h * 0.32f);
        }
        if (fx != null) {
            if (state == STATE_HOVER) {
                float h = dp(hoverHeightDp);
                fx.draw(canvas, getWidth() / 2f, getHeight() - h * 0.92f, h, now);
            } else {
                fx.draw(canvas, getWidth() * 0.55f, getHeight() * 0.22f, getHeight(), now);
            }
        }
        drawBubble(canvas, now);
        if (sleeping) drawZzz(canvas, t);
    }

    /** 睡觉动效：三个"Z"依次飘起、变大、淡出（画法在 Effects 里，两个角色共用）。 */
    private void drawZzz(Canvas canvas, float t) {
        if (fx == null) return;
        float h = dp(hoverHeightDp);
        float baseX, baseY, unit;
        if (state == STATE_HOVER) {
            baseX = getWidth() / 2f + h * srcW / (float) srcH * 0.30f;
            baseY = Math.max(dp(18f), getHeight() - h * 0.96f);
            unit = h;
        } else {
            baseX = getWidth() * 0.55f;
            baseY = getHeight() * 0.22f;
            unit = getHeight();
        }
        fx.drawZzz(canvas, baseX, baseY, unit, System.nanoTime());
    }

    private void drawBubble(Canvas canvas, long now) {
        if (bubble == null) return;
        bubble.draw(canvas, getWidth() / 2f, dp(4f), Math.max(dp(40f), getWidth() - dp(6f)), now);
    }

    private void drawHover(Canvas canvas) {
        float h = dp(hoverHeightDp);
        float w = h * srcW / (float) srcH;
        float ww = getWidth(), hh = getHeight();
        float cx = ww / 2f;
        // 睡着的时候整个人往下沉一点，好让云朵把下半身盖住（参考图里是躺在云上）
        float bottom = hh - h * 0.08f + (pDy - poseLift) * h + (sleeping ? h * 0.16f : 0f);
        float span = h * pSy * poseSquash * poseScale;
        float top = bottom - span;
        float halfW = w * pSx * poseScale / poseSquash / 2f;

        RectF dst = new RectF(cx - halfW, top, cx + halfW, bottom);
        canvas.save();
        // 以立绘中心为支点倾斜：窗口只留了 22% 横向余量，绕脚转会甩出窗口被裁掉
        canvas.rotate((dragging ? dragTilt : 0f) + poseLean, cx, bottom - span / 2f);
        if (mirrorX) canvas.scale(-1f, 1f, cx, 0f);
        canvas.drawBitmap(cur, null, dst, paint);
        canvas.restore();
    }

    private void drawPerch(Canvas canvas, float t) {
        if (cur == null) return;

        int bw0 = headBX1 - headBX0, bh0 = headBY1 - headBY0;
        // ---- 趴屏幕下边：不旋转，脑袋从下沿探出来 ----
        if (perchEdge == EDGE_BOTTOM) {
            // 滑块值＝脑袋高度（和趴左右时同一视觉尺寸），宽度按长宽比推
            float h0 = dp(perchWidthDp) * pSy;
            float w0 = h0 * bw0 / (float) bh0;
            float left = (getWidth() - w0) / 2f;
            float top = pDy * h0;                        // 贴着窗口顶（窗口顶＝屏幕下沿往上一点）
            canvas.drawBitmap(cur, new Rect(headBX0, headBY0, headBX1, headBY1),
                    new RectF(left, top, left + w0, top + h0), paint);
            return;
        }
        if (rotCache == null || rotCache.isRecycled()) rotCache = rotate90(cur);
        int bw = headX1 - headX0, bh = headY1 - headY0;
        float w = dp(perchWidthDp);
        float h = w * bh / (float) bw;
        final float tau = (float) (2 * Math.PI);
        float peek = w * 0.02f * (1f + (float) Math.sin(tau * t / 2.4f - 1.2f));
        float hh2 = h * pSy;
        float top = (getHeight() - hh2) / 2f + pDy * h;

        Rect src = new Rect(headX0, headY0, headX1, headY1);
        RectF dst = new RectF(peek, top, peek + w, top + hh2);

        canvas.save();
        if (mirrorX) canvas.scale(-1f, 1f, getWidth() / 2f, 0f);
        canvas.drawBitmap(rotCache, src, dst, paint);
        canvas.restore();
    }

    // ---------------- 渲染循环：不可见时真的停 ----------------

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!windowVisible) { looping = false; return; }
        if (frameTimeNanos - lastFrameNs >= frameIntervalNs) {
            lastFrameNs = frameTimeNanos;
            invalidate();
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); startLoop(); }
    @Override protected void onDetachedFromWindow() { looping = false; super.onDetachedFromWindow(); }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        windowVisible = (visibility == VISIBLE);
        if (windowVisible) startLoop();
    }

    public void onScreenOff() { windowVisible = false; looping = false; }
    public void onScreenOn() { windowVisible = true; startLoop(); }

    private void startLoop() {
        if (looping || !windowVisible) return;
        looping = true;
        lastFrameNs = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    // ---------------- 触摸 ----------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float rx = e.getRawX(), ry = e.getRawY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = lastRawX = rx;
                downRawY = lastRawY = ry;
                dragging = false;
                longPressed = false;
                dragTilt = 0f;
                lastTouchMs = System.currentTimeMillis();
                handler.postDelayed(longPressRun, LONG_PRESS_MS);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (Math.abs(rx - downRawX) > touchSlop || Math.abs(ry - downRawY) > touchSlop) {
                    handler.removeCallbacks(longPressRun);
                }
                if (!dragging && (Math.abs(rx - downRawX) > touchSlop
                        || Math.abs(ry - downRawY) > touchSlop)) {
                    dragging = true;
                    if (listener != null) listener.onDragStart();
                }
                if (dragging) {
                    lastTouchMs = System.currentTimeMillis();
                    dragTilt = Math.max(-9f, Math.min(9f, dragTilt + (rx - lastRawX) * 0.35f));
                    dragTilt *= 0.85f;
                    if (listener != null) listener.onDrag(rx - lastRawX, ry - lastRawY);
                    lastRawX = rx;
                    lastRawY = ry;
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPressRun);
                if (dragging) {
                    dragging = false;
                    dragTilt = 0f;
                    if (e.getActionMasked() == MotionEvent.ACTION_UP && listener != null) listener.onDragEnd(rx, ry);
                } else if (e.getActionMasked() == MotionEvent.ACTION_UP && !longPressed) {
                    cycleFace();
                    tapNs = System.nanoTime();
                    invalidate();
                    if (listener != null) listener.onTap();
                }
                longPressed = false;
                return true;

            default:
                return super.onTouchEvent(e);
        }
    }
}

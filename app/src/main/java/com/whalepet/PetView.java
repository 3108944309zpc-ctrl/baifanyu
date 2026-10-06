package com.whalepet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
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
 * 宠物的绘制、动作与表情。
 *
 * 表情是"整张换图"，不是贴一块脸上去。
 * 原因：表情图和底图是两次独立生成的，脸相对身体的位置、缩放都有偏差，
 * 任何"贴片"方案都会在"露接缝"和"露重影"之间二选一（试过六种遮罩策略，都不成立）。
 * 整张换掉就没有这个问题 —— 每张图内部是自洽的，身体也不会跳。
 *
 * 两个状态共用同一套图：
 *   悬空态 —— 直接画整张
 *   趴边态 —— 把图顺时针转 90°，只画"头部区块"，贴在屏幕边缘
 */
public class PetView extends View implements Choreographer.FrameCallback {

    public static final int STATE_HOVER = 0;
    public static final int STATE_PERCH = 1;

    // 表情索引。0 是原图
    public static final int FACE_NORMAL = 0;
    public static final int FACE_HAPPY = 1;
    public static final int FACE_SAD = 2;
    public static final int FACE_ANGRY = 3;
    public static final int FACE_SURPRISED = 4;
    public static final int FACE_SHY = 5;
    public static final int FACE_CONFUSED = 6;

    private static final String[] FACE_FILES = {
            "pet_stand.png",
            "pet_face_happy.png",
            "pet_face_sad.png",
            "pet_face_angry.png",
            "pet_face_surprised.png",
            "pet_face_shy.png",
            "pet_face_confused.png",
    };

    // 头部区块的位置，用"占素材宽高的比例"表示，这样换素材尺寸也不用改代码
    private static final float HEAD_FX0 = 35f / 1191f;
    private static final float HEAD_FX1 = 985f / 1191f;
    private static final float HEAD_FY0 = 30f / 1514f;
    private static final float HEAD_FY1 = 800f / 1514f;

    private static final float NS = 1_000_000_000f;
    private static final long LONG_PRESS_MS = 800;

    public interface Listener {
        void onDragStart();
        void onDrag(float dx, float dy);
        void onDragEnd(float rawX, float rawY);
        void onTap();
        void onLongPress();
    }

    private Context ctx;
    private Bitmap cur;          // 当前显示的那张完整立绘
    private Bitmap curRot;       // 它的顺时针 90° 版本（趴边用）
    private boolean ownsCur;     // cur 是不是我们自己解码的（不是基准图）

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int srcW = 944, srcH = 1200;
    private int headX0, headX1, headY0, headY1;

    private int state = STATE_HOVER;
    private boolean mirrorX = false;
    private float hoverHeightDp = 240f;
    private float perchWidthDp = 96f;
    private float ampScale = 0.75f;

    private float pDy, pSx = 1f, pSy = 1f;

    private boolean sleepy = false;
    private long lastTouchNs;
    private float dragTilt;

    private int faceCur = FACE_NORMAL;
    private boolean[] faceOk = new boolean[FACE_FILES.length];

    private long startNs, lastFrameNs, tapNs;
    private long frameIntervalNs = (long) (NS / 30);
    private boolean windowVisible = true, looping = false;

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
        for (int i = 0; i < FACE_FILES.length; i++) {
            faceOk[i] = assetExists(FACE_FILES[i]);
        }
        cur = decode(FACE_FILES[0]);
        ownsCur = false;
        if (cur != null) {
            srcW = cur.getWidth();
            srcH = cur.getHeight();
            headX0 = Math.round(srcH - 1 - HEAD_FY1 * srcH);
            headX1 = Math.round(srcH - 1 - HEAD_FY0 * srcH);
            headY0 = Math.round(HEAD_FX0 * srcW);
            headY1 = Math.round(HEAD_FX1 * srcW);
            curRot = rotate90(cur);
        }
        paint.setFilterBitmap(true);
        paint.setDither(true);
    }

    private boolean assetExists(String name) {
        try { ctx.getAssets().open(name).close(); return true; }
        catch (Exception e) { return false; }
    }

    private Bitmap decode(String name) {
        try {
            InputStream is = ctx.getAssets().open(name);
            Bitmap b = BitmapFactory.decodeStream(is);
            is.close();
            return b;
        } catch (IOException e) {
            return null;
        }
    }

    private static Bitmap rotate90(Bitmap src) {
        Matrix m = new Matrix();
        m.postRotate(90);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    public void setListener(Listener l) { this.listener = l; }

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

    public void setHoverHeightDp(float v) { hoverHeightDp = v; requestLayout(); invalidate(); }
    public void setPerchWidthDp(float v) { perchWidthDp = v; requestLayout(); invalidate(); }
    public float getHoverHeightDp() { return hoverHeightDp; }
    public float getPerchWidthDp() { return perchWidthDp; }
    public void setAmpScale(float v) { ampScale = Math.max(0.1f, v); invalidate(); }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    public float windowW() { return dp(hoverHeightDp) * srcW / (float) srcH; }
    public float windowH() { return dp(hoverHeightDp) * 1.16f; }
    public float perchWindowW() { return dp(perchWidthDp) * 1.10f; }
    public float perchWindowH() {
        return dp(perchWidthDp) * (headY1 - headY0) / (float) (headX1 - headX0) * 1.16f;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        if (state == STATE_HOVER) setMeasuredDimension(Math.round(windowW()), Math.round(windowH()));
        else setMeasuredDimension(Math.round(perchWindowW()), Math.round(perchWindowH()));
    }

    // ---------------- 表情：整张换图 ----------------

    public void setFace(int idx) {
        if (idx < 0 || idx >= FACE_FILES.length || idx == faceCur) return;
        if (!faceOk[idx]) return;
        if (idx == FACE_NORMAL) {
            if (ownsCur && cur != null) cur.recycle();
            cur = decode(FACE_FILES[0]);
            ownsCur = false;
        } else {
            Bitmap b = decode(FACE_FILES[idx]);
            if (b == null) return;
            if (ownsCur && cur != null) cur.recycle();
            cur = b;
            ownsCur = true;
        }
        if (cur == null) return;
        if (curRot != null) curRot.recycle();
        curRot = rotate90(cur);
        faceCur = idx;
        invalidate();
    }

    public void cycleFace() {
        for (int i = 1; i <= FACE_FILES.length; i++) {
            int idx = (faceCur + i) % FACE_FILES.length;
            if (faceOk[idx]) { setFace(idx); return; }
        }
    }

    public int faceCount() {
        int n = 0;
        for (int i = 1; i < faceOk.length; i++) if (faceOk[i]) n++;
        return n;
    }

    public int currentFace() { return faceCur; }

    public void pokeFeedback() { tapNs = System.nanoTime(); invalidate(); }

    // ---------------- 绘制 ----------------

    @Override
    protected void onDraw(Canvas canvas) {
        if (cur == null) return;
        long now = System.nanoTime();
        if (startNs == 0) { startNs = now; lastTouchNs = now; }
        float t = (now - startNs) / NS;

        final float tau = (float) (2 * Math.PI);
        pDy = 0.022f * ampScale * (float) Math.sin(tau * t / 2.4f);
        pSx = 1f;
        pSy = 1f + 0.014f * ampScale * (float) Math.sin(tau * 2 * t / 2.4f);

        if (sleepy) pDy += 0.045f;
        if (dragging) {
            pSx += 0.05f * Math.abs(dragTilt) / 9f;
            pSy -= 0.02f * Math.abs(dragTilt) / 9f;
        }
        if (tapNs != 0) {
            float k = (now - tapNs) / (0.30f * NS);
            if (k >= 1f) tapNs = 0;
            else pSy *= 1f - 0.10f * (float) Math.sin(Math.PI * k);
        }

        if (state == STATE_HOVER) drawHover(canvas);
        else drawPerch(canvas, t);
    }

    private void drawHover(Canvas canvas) {
        float h = dp(hoverHeightDp);
        float w = h * srcW / (float) srcH;
        float ww = getWidth(), hh = getHeight();
        float cx = ww / 2f;
        float bottom = hh - h * 0.08f + pDy * h;
        float top = bottom - h * pSy;
        float left = cx - w * pSx / 2f;
        RectF dst = new RectF(left, top, left + w * pSx, bottom);

        canvas.save();
        canvas.rotate(dragging ? dragTilt : 0f, cx, hh - h * 0.10f);
        canvas.drawBitmap(cur, null, dst, paint);
        canvas.restore();
    }

    private void drawPerch(Canvas canvas, float t) {
        if (curRot == null) return;
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
        canvas.drawBitmap(curRot, src, dst, paint);
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

    private void wake() {
        lastTouchNs = System.nanoTime();
        if (sleepy) sleepy = false;
    }

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
                wake();
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
                wake();
                if (dragging) {
                    dragging = false;
                    dragTilt = 0f;
                    if (listener != null) listener.onDragEnd(rx, ry);
                } else {
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

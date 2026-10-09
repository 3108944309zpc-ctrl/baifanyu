package com.whalepet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.Choreographer;
import android.view.View;

/**
 * 台词气泡专用的一层 —— **盖在两个角色窗口之上**。
 *
 * 为什么非得单独一层：以前气泡画在角色自己的窗口里，而角色立绘是填满整个窗口的，
 * 两个角色一靠近（拥抱、打闹、贴贴），上面那个窗口的不透明像素就会把另一个人的
 * 气泡、甚至自己的脑袋一起盖掉，看起来"只剩最上面那个"。
 *
 * 现在气泡飘在角色头顶之外的空域，而且是全屏的、不可触摸的一层：
 *  - 谁的气泡都不会被别人的身体挡住；
 *  - 两个气泡实在挤在一起时，先把她们往各自外侧推开，推不动就把右边那位抬高一层；
 *  - 头顶没地方（趴在屏幕上沿）就改摆到角色朝屏幕内侧的那一边。
 */
final class BubbleLayerView extends View implements Choreographer.FrameCallback {

    interface Source {
        /** 把角色的窗口矩形填进 out；该角色没显示就返回 false。 */
        boolean rectOf(boolean dragon, Rect out);
    }

    private final Source source;
    private final SpeechBubble dragonBubble;
    private final SpeechBubble fishBubble;
    private final Rect dragonRect = new Rect();
    private final Rect fishRect = new Rect();
    private final RectF dragonBox = new RectF();
    private final RectF fishBox = new RectF();

    private float dragonAnchorX, dragonTop, fishAnchorX, fishTop;
    private boolean looping;

    BubbleLayerView(Context context, Source source) {
        super(context);
        this.source = source;
        float d = context.getResources().getDisplayMetrics().density;
        dragonBubble = new SpeechBubble(d);
        fishBubble = new SpeechBubble(d);
    }

    void show(String actor, String text) {
        long now = System.nanoTime();
        float availW = availW();
        if ("dragon".equals(actor)) {
            dragonBubble.show(text, SpeechBubble.TAIL_RIGHT, availW, now);
        } else {
            fishBubble.show(text, SpeechBubble.TAIL_LEFT, availW, now);
        }
        startLoop();
        invalidate();
    }

    void clearActive() {
        dragonBubble.clear();
        fishBubble.clear();
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopLoop();
    }

    private float availW() {
        float w = getWidth() - dp(8f);
        return w > dp(60f) ? w : Math.max(dp(120f), getResources().getDisplayMetrics().widthPixels - dp(8f));
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    // ---------------- 动画循环 ----------------

    private void startLoop() {
        if (!looping) {
            looping = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    private void stopLoop() {
        if (looping) {
            looping = false;
            Choreographer.getInstance().removeFrameCallback(this);
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!looping) return;
        invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    // ---------------- 画 ----------------

    @Override
    protected void onDraw(Canvas canvas) {
        long now = System.nanoTime();
        boolean wantDragon = dragonBubble.isActive(now);
        boolean wantFish = fishBubble.isActive(now);
        if (!wantDragon && !wantFish) {
            stopLoop();
            return;
        }
        float availW = availW();
        boolean hasDragon = wantDragon && source != null && source.rectOf(true, dragonRect);
        boolean hasFish = wantFish && source != null && source.rectOf(false, fishRect);

        if (hasDragon) {
            anchor(dragonBubble, dragonRect, now, availW, true);
            dragonBubble.prepare(dragonAnchorX, dragonTop, availW, now);
        }
        if (hasFish) {
            anchor(fishBubble, fishRect, now, availW, false);
            fishBubble.prepare(fishAnchorX, fishTop, availW, now);
        }
        if (hasDragon && hasFish) resolveOverlap(now, availW);

        if (hasDragon) {
            frame(dragonBox, dragonBubble);
            dragonBubble.setTailSide(dragonBubble.cx() <= dragonRect.centerX()
                    ? SpeechBubble.TAIL_RIGHT : SpeechBubble.TAIL_LEFT);
            dragonBubble.drawPrepared(canvas, now);
        }
        if (hasFish) {
            frame(fishBox, fishBubble);
            fishBubble.setTailSide(fishBubble.cx() <= fishRect.centerX()
                    ? SpeechBubble.TAIL_RIGHT : SpeechBubble.TAIL_LEFT);
            fishBubble.drawPrepared(canvas, now);
        }
    }

    private void frame(RectF out, SpeechBubble b) {
        out.set(b.cx() - b.halfW(), b.cy() - b.halfH(), b.cx() + b.halfW(), b.cy() + b.halfH());
    }

    /** 默认摆在头顶正上方（略微朝外），头顶没地方就摆到角色朝屏幕内侧的那一边。 */
    private void anchor(SpeechBubble bubble, Rect rect, long now, float availW, boolean dragon) {
        float gap = dp(3f);
        float margin = dp(4f);
        boolean leftHalf = rect.centerX() < getWidth() / 2f;
        float cx = rect.centerX() + (leftHalf ? -rect.width() * 0.07f : rect.width() * 0.07f);
        bubble.prepare(cx, 0f, availW, now);
        float top = rect.top - gap - bubble.totalHeight();
        if (top < margin) {
            // 扒在屏幕上沿（趴边）时头顶没空，改摆到角色旁边
            float y = rect.top + rect.height() * 0.12f;
            cx = rect.centerX() > getWidth() / 2f
                    ? rect.left - dp(4f) - bubble.halfW()
                    : rect.right + dp(4f) + bubble.halfW();
            top = y;
        }
        if (dragon) {
            dragonAnchorX = cx;
            dragonTop = top;
        } else {
            fishAnchorX = cx;
            fishTop = top;
        }
    }

    /** 两个气泡撞上了 → 先往外推，推不动就给大肥鱼的气泡抬一层，保证两个都看得见。 */
    private void resolveOverlap(long now, float availW) {
        frame(dragonBox, dragonBubble);
        frame(fishBox, fishBubble);
        if (!RectF.intersects(dragonBox, fishBox)) return;

        float overlap = Math.min(dragonBox.right, fishBox.right) - Math.max(dragonBox.left, fishBox.left);
        float push = Math.max(0f, overlap) / 2f + dp(5f);
        float savedDragonX = dragonAnchorX, savedFishX = fishAnchorX;
        dragonAnchorX -= push;
        fishAnchorX += push;
        dragonBubble.prepare(dragonAnchorX, dragonTop, availW, now);
        fishBubble.prepare(fishAnchorX, fishTop, availW, now);
        frame(dragonBox, dragonBubble);
        frame(fishBox, fishBubble);
        if (!RectF.intersects(dragonBox, fishBox)) return;

        // 推不动（贴屏幕边了）：大肥鱼的气泡抬到上面一层
        float lift = fishBubble.totalHeight() + dp(4f);
        float lifted = Math.max(dp(4f), fishTop - lift);
        fishBubble.prepare(fishAnchorX, lifted, availW, now);
        frame(fishBox, fishBubble);
        if (!RectF.intersects(dragonBox, fishBox)) {
            fishTop = lifted;
            return;
        }
        // 还是撞：那就把小龙女的气泡降下来，形成上下两排
        dragonBubble.prepare(savedDragonX, Math.min(dragonTop + lift,
                dragonRect.top + dragonRect.height() * 0.35f), availW, now);
        frame(dragonBox, dragonBubble);
        if (!RectF.intersects(dragonBox, fishBox)) return;
        // 最后兜底：回到最初的横向位置，至少两只都在同一条水平线上
        dragonAnchorX = savedDragonX;
        fishAnchorX = savedFishX;
        dragonBubble.prepare(dragonAnchorX, dragonTop, availW, now);
        fishBubble.prepare(fishAnchorX, fishTop, availW, now);
    }
}

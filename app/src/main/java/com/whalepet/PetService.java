package com.whalepet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Random;

/**
 * 前台服务 + 悬浮窗。
 *
 * 三条硬规则（做错整个功能就废了）：
 *  1. 窗口必须小 —— 只包住宠物本身，绝不做全屏透明层。
 *  2. 始终带 FLAG_NOT_FOCUSABLE —— 否则抖音会失焦，评论时输入法弹不出来。
 *  3. 不可见时真的停止渲染（见 PetView.onWindowVisibilityChanged / onScreenOff）。
 */
public class PetService extends Service implements PetView.Listener {

    public static final String ACTION_SHOW = "com.whalepet.SHOW";
    public static final String ACTION_HIDE = "com.whalepet.HIDE";
    public static final String ACTION_REFRESH = "com.whalepet.REFRESH";
    public static final String ACTION_NEXT_FACE = "com.whalepet.NEXT_FACE";
    public static final String ACTION_FACE_COUNT = "com.whalepet.FACE_COUNT";
    public static final String ACTION_SET_HIDDEN = "com.whalepet.SET_HIDDEN";
    public static final String EXTRA_COUNT = "count";
    public static final String EXTRA_HIDDEN = "hidden";

    /** 设置页打开期间她会被收起来，期间换的表情记在这里，回来时补上 */
    private int pendingFace = 0;
    private boolean hiddenForSettings = false;

    // 小黄鸭音效。用 SoundPool 而不是 MediaPlayer —— 短音效的延迟低得多，连点也不会卡。
    private SoundPool soundPool;
    private final int[] duckIds = new int[3];
    private boolean soundOn = true;
    private final Random rnd = new Random();

    public static final String PREFS = "whalepet";
    public static final String KEY_HOVER_H = "hover_height_dp";
    public static final String KEY_PERCH_W = "perch_width_dp";
    public static final String KEY_AMP = "amp_scale";
    public static final String KEY_SOUND = "sound_on";
    public static final String KEY_RUNNING = "running";
    public static final String KEY_EDGE = "edge";
    public static final String KEY_PERCHED = "perched";
    public static final String KEY_Y = "last_y";

    public static final float DEFAULT_HOVER_H = 240f;
    public static final float DEFAULT_PERCH_W = 96f;
    public static final float DEFAULT_AMP = 0.75f;
    private static final float SNAP_DP = 56f;

    private static final String CHANNEL = "pet";
    private static final int NOTI_ID = 1001;
    private static final long MENU_AUTO_HIDE_MS = 6000;

    private WindowManager wm;
    private PetView view;
    private WindowManager.LayoutParams lp;
    private View menuView;
    private WindowManager.LayoutParams menuLp;
    private SharedPreferences prefs;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable menuAutoHide = new Runnable() {
        @Override public void run() { hideMenu(); }
    };

    private int screenW, screenH;

    private final BroadcastReceiver screenRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (view == null) return;
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) view.onScreenOff();
            else if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) view.onScreenOn();
        }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
        createChannel();

        soundOn = prefs.getBoolean(KEY_SOUND, true);
        soundPool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()).build();
        duckIds[0] = soundPool.load(this, R.raw.duck1, 1);
        duckIds[1] = soundPool.load(this, R.raw.duck2, 1);
        duckIds[2] = soundPool.load(this, R.raw.duck3, 1);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenRx, f);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_SHOW : intent.getAction();
        startForeground(NOTI_ID, buildNotification());

        if (ACTION_HIDE.equals(action)) {
            prefs.edit().putBoolean(KEY_RUNNING, false).apply();
            removePet();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_REFRESH.equals(action)) {
            applySizes();
            return START_STICKY;
        }
        if (ACTION_NEXT_FACE.equals(action)) {
            if (view != null) {
                view.cycleFace();
                view.pokeFeedback();
            } else {
                // 设置页打开时她不在，先记住，回来再补
                pendingFace = (pendingFace + 1) % 7;
            }
            return START_STICKY;
        }
        if (ACTION_SET_HIDDEN.equals(action)) {
            // 我们自己的设置页在前台时，必须把她收起来 ——
            // 悬浮窗永远在最上层，不收起来就会盖住自己的界面。
            boolean hide = intent.getBooleanExtra(EXTRA_HIDDEN, false);
            hiddenForSettings = hide;
            if (hide) {
                removePet();
            } else if (prefs.getBoolean(KEY_RUNNING, false)) {
                addPet();
            }
            return START_STICKY;
        }
        if (ACTION_FACE_COUNT.equals(action)) {
            int n = view == null ? -1 : view.faceCount();
            sendBroadcast(new Intent(ACTION_FACE_COUNT).putExtra(EXTRA_COUNT, n)
                    .setPackage(getPackageName()));
            return START_STICKY;
        }

        prefs.edit().putBoolean(KEY_RUNNING, true).apply();
        if (!addPet()) {
            prefs.edit().putBoolean(KEY_RUNNING, false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (prefs.getBoolean(KEY_PERCHED, false)) {
            enterPerch(prefs.getInt(KEY_EDGE, 0) == 1, false);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        hideMenu();
        removePet();
        if (soundPool != null) { soundPool.release(); soundPool = null; }
        try { unregisterReceiver(screenRx); } catch (Exception ignored) { }
        super.onDestroy();
    }

    // ---- 悬浮窗 ----

    private boolean addPet() {
        if (view != null) return true;
        if (!canDrawOverlays()) return false;

        view = new PetView(this);
        view.setListener(this);
        view.setHoverHeightDp(prefs.getFloat(KEY_HOVER_H, DEFAULT_HOVER_H));
        view.setPerchWidthDp(prefs.getFloat(KEY_PERCH_W, DEFAULT_PERCH_W));
        view.setAmpScale(prefs.getFloat(KEY_AMP, DEFAULT_AMP));

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        lp = new WindowManager.LayoutParams(
                Math.round(view.windowW()), Math.round(view.windowH()), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = (screenW - lp.width) / 2;
        lp.y = Math.max(0, prefs.getInt(KEY_Y, (int) (screenH * 0.42f)));

        try {
            wm.addView(view, lp);
        } catch (Exception e) {
            Toast.makeText(this, "悬浮窗添加失败，请确认已授予「显示在其他应用上层」权限",
                    Toast.LENGTH_LONG).show();
            view = null;
            return false;
        }
        if (pendingFace != 0) {
            view.setFace(pendingFace);
            pendingFace = 0;
        }
        return true;
    }

    private void removePet() {
        if (view != null && wm != null) {
            try { wm.removeView(view); } catch (Exception ignored) { }
        }
        view = null;
    }

    private boolean canDrawOverlays() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return android.provider.Settings.canDrawOverlays(this);
        }
        return true;
    }

    private void applySizes() {
        if (view == null || lp == null) return;
        view.setHoverHeightDp(prefs.getFloat(KEY_HOVER_H, DEFAULT_HOVER_H));
        view.setPerchWidthDp(prefs.getFloat(KEY_PERCH_W, DEFAULT_PERCH_W));
        view.setAmpScale(prefs.getFloat(KEY_AMP, DEFAULT_AMP));
        soundOn = prefs.getBoolean(KEY_SOUND, true);
        applyLayout();
    }

    private void applyLayout() {
        if (view == null || lp == null) return;
        if (view.getState() == PetView.STATE_HOVER) {
            lp.width = Math.round(view.windowW());
            lp.height = Math.round(view.windowH());
        } else {
            lp.width = Math.round(view.perchWindowW());
            lp.height = Math.round(view.perchWindowH());
        }
        clamp();
        try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
    }

    private void clamp() {
        int w = lp.width, h = lp.height;
        if (lp.x < 0) lp.x = 0;
        if (lp.x > screenW - w) lp.x = Math.max(0, screenW - w);
        int topLimit = -Math.round(h * 0.12f);
        int bottomLimit = screenH - Math.round(h * 0.88f);
        if (lp.y < topLimit) lp.y = topLimit;
        if (lp.y > bottomLimit) lp.y = bottomLimit;
    }

    // ---- 长按菜单 ----

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override
    public void onLongPress() {
        if (menuView != null) { hideMenu(); return; }
        if (view == null) return;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF01B2033);
        bg.setCornerRadius(dp(16));
        bg.setStroke(Math.round(dp(1)), 0x55FFFFFF);
        row.setBackground(bg);
        int pad = Math.round(dp(5));
        row.setPadding(pad, pad, pad, pad);
        row.setElevation(dp(6));

        row.addView(menuButton("设置", new View.OnClickListener() {
            @Override public void onClick(View v) { hideMenu(); openSettings(); }
        }));
        row.addView(menuButton("关闭", new View.OnClickListener() {
            @Override public void onClick(View v) {
                hideMenu();
                prefs.edit().putBoolean(KEY_RUNNING, false).apply();
                removePet();
                stopSelf();
            }
        }));

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        menuLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        menuLp.gravity = Gravity.TOP | Gravity.START;
        menuLp.x = 0;
        menuLp.y = 0;
        menuView = row;

        try {
            wm.addView(menuView, menuLp);
        } catch (Exception e) {
            menuView = null;
            return;
        }
        menuView.post(new Runnable() {
            @Override public void run() { placeMenu(); }
        });
        handler.postDelayed(menuAutoHide, MENU_AUTO_HIDE_MS);
    }

    private TextView menuButton(String text, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(15);
        tv.setPadding(Math.round(dp(18)), Math.round(dp(10)),
                Math.round(dp(18)), Math.round(dp(10)));
        tv.setClickable(true);
        tv.setOnClickListener(l);
        return tv;
    }

    private void placeMenu() {
        if (menuView == null || view == null || lp == null) return;
        int mw = menuView.getWidth(), mh = menuView.getHeight();
        int gap = Math.round(dp(8));
        int x = lp.x + lp.width + gap;
        if (x + mw > screenW) x = lp.x - mw - gap;
        if (x < 0) x = Math.max(0, (screenW - mw) / 2);
        int y = lp.y + (lp.height - mh) / 2;
        if (y < 0) y = 0;
        if (y + mh > screenH) y = screenH - mh;
        menuLp.x = x;
        menuLp.y = y;
        try { wm.updateViewLayout(menuView, menuLp); } catch (Exception ignored) { }
    }

    private void hideMenu() {
        handler.removeCallbacks(menuAutoHide);
        if (menuView != null && wm != null) {
            try { wm.removeView(menuView); } catch (Exception ignored) { }
        }
        menuView = null;
    }

    private void openSettings() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception ignored) { }
    }

    // ---- 状态切换 ----

    private void enterPerch(boolean right, boolean persist) {
        if (view == null) return;
        view.setState(PetView.STATE_PERCH);
        view.setMirror(right);
        lp.width = Math.round(view.perchWindowW());
        lp.height = Math.round(view.perchWindowH());
        lp.x = right ? screenW - lp.width : 0;
        clamp();
        try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
        if (persist) {
            prefs.edit().putBoolean(KEY_PERCHED, true).putInt(KEY_EDGE, right ? 1 : 0).apply();
        }
    }

    private void exitPerch() {
        if (view == null) return;
        view.setState(PetView.STATE_HOVER);
        view.setMirror(false);
        applyLayout();
        prefs.edit().putBoolean(KEY_PERCHED, false).apply();
    }

    // ---- PetView.Listener ----

    @Override
    public void onDragStart() { hideMenu(); }

    @Override
    public void onDrag(float dx, float dy) {
        if (view == null) return;
        lp.x += Math.round(dx);
        lp.y += Math.round(dy);
        if (view.getState() == PetView.STATE_PERCH) {
            if (lp.x > screenW * 0.15f && lp.x < screenW * 0.85f) {
                exitPerch();
                return;
            }
        } else {
            clamp();
        }
        try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
    }

    @Override
    public void onDragEnd(float rawX, float rawY) {
        if (view == null) return;
        prefs.edit().putInt(KEY_Y, lp.y).apply();
        if (view.getState() == PetView.STATE_PERCH) return;

        // 吸附判定不能用一个固定像素值：她调小之后窗口本身就窄，
        // 固定的 56dp 会占掉她宽度的一大半，导致"稍微靠近边缘"就被吸走。
        // 所以取"固定值和窗口宽度 25% 里更小的那个"。
        float snapDp = SNAP_DP * getResources().getDisplayMetrics().density;
        float snap = Math.min(snapDp, lp.width * 0.25f);
        if (lp.x <= snap) enterPerch(false, true);
        else if (lp.x + lp.width >= screenW - snap) enterPerch(true, true);
        else applyLayout();
    }

    /** 单击换表情在 PetView 里做；这里负责音效和缺素材时的兜底提示 */
    @Override
    public void onTap() {
        if (soundOn && soundPool != null) {
            int id = duckIds[rnd.nextInt(duckIds.length)];
            if (id != 0) soundPool.play(id, 1f, 1f, 1, 0, 1f);
        }
        if (view != null && view.faceCount() == 0) {
            Toast.makeText(this, "表情素材没加载成功", Toast.LENGTH_SHORT).show();
        }
    }

    // ---- 通知 ----

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "鲸宠",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        Intent hide = new Intent(this, PetService.class).setAction(ACTION_HIDE);
        PendingIntent piHide = PendingIntent.getService(this, 1, hide, piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle("白饭鱼")
                .setContentText("正在你桌面上吃白饭 · 长按她可以调整或关闭")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pi)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "收回", piHide).build());
        return b.build();
    }
}

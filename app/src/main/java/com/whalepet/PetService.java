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
import android.media.AudioManager;
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
    public static final String ACTION_SOUND_DIAG = "com.whalepet.SOUND_DIAG";
    public static final String ACTION_SET_HIDDEN = "com.whalepet.SET_HIDDEN";
    public static final String ACTION_NEXT_SKIN = "com.whalepet.NEXT_SKIN";
    public static final String EXTRA_COUNT = "count";
    public static final String EXTRA_HIDDEN = "hidden";

    /** 设置页打开期间她会被收起来，期间换的表情记在这里，回来时补上 */
    private int pendingFace = 0;
    private boolean hiddenForSettings = false;

    // 小黄鸭音效。用 SoundPool 而不是 MediaPlayer —— 短音效的延迟低得多，连点也不会卡。
    private SoundPool soundPool;
    private final int[] duckIds = new int[3];
    private boolean soundOn = true;
    private final int[] duckLoadStatus = new int[] { -999, -999, -999 };
    private final Random rnd = new Random();
    /** 静音提示的节流时间戳，避免连点时刷屏 */
    private long lastSilentHintMs = 0L;

    /**
     * 音效走哪条音频流 —— 必须走媒体音量（STREAM_MUSIC）。
     *
     * 以前这里是 USAGE_ASSISTANCE_SONIFICATION，它在 AudioService 里被映射成
     * STREAM_SYSTEM（"系统音效"）。MIUI / HyperOS 上那条通道在静音模式下会被压到 0，
     * 用户把"系统音效/触摸提示音"关掉后它也是 0 —— 结果就是 SoundPool.play()
     * 返回成功、但一点声音都没有，而媒体音量明明是正常的。
     * 表现完全就是"点她有反应、就是不出声"。
     */
    private static final int SOUND_STREAM = AudioManager.STREAM_MUSIC;

    public static final String PREFS = "whalepet";
    public static final String KEY_HOVER_H = "hover_height_dp";
    public static final String KEY_PERCH_W = "perch_width_dp";
    public static final String KEY_AMP = "amp_scale";
    public static final String KEY_SOUND = "sound_on";
    public static final String KEY_SKIN = "skin_index";
    public static final String KEY_X = "pos_x";
    public static final String KEY_WANDER = "wander_on";
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
    /** 趴边时累计"往外拉了多少"。位置会一直贴回边缘，但拉出量必须留着， */
    /** 否则一松手就归零，她永远脱离不了边缘（上一版就栽在这）。 */
    private int perchPull = 0;

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
        measureScreen();
        createChannel();

        soundOn = prefs.getBoolean(KEY_SOUND, true);
        createSoundPool();
        loadDuckSounds();

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
        if (ACTION_SOUND_DIAG.equals(action)) {
            runSoundDiagnostic();
            return START_STICKY;
        }
        if (ACTION_NEXT_SKIN.equals(action)) {
            int n = (prefs.getInt(KEY_SKIN, 0) + 1) % PetView.SKINS.length;
            prefs.edit().putInt(KEY_SKIN, n).apply();
            if (view != null) view.applySkin(n);
            else if (prefs.getBoolean(KEY_RUNNING, false)) addPet();
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
        wanderHandler.removeCallbacks(wanderTick);
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
        // 恢复上次的样子。不恢复的话，从设置页回来她会变成一个
        // "站在屏幕正中间、默认皮肤、默认表情"的新人 —— 趴边状态也会丢。
        int sk = prefs.getInt(KEY_SKIN, 0);
        if (sk > 0 && sk < PetView.SKINS.length) view.applySkin(sk);
        if (pendingFace != 0) {
            view.setFace(pendingFace);
            pendingFace = 0;
        }
        if (prefs.getBoolean(KEY_PERCHED, false)) {
            enterPerch(prefs.getInt(KEY_EDGE, 0) == 1, false);   // 她本来是扒在边上的
        } else {
            lp.x = prefs.getInt(KEY_X, lp.x);
            applyLayout();
        }
        return true;
    }

    private void removePet() {
        if (view != null && wm != null) {
            try { wm.removeView(view); } catch (Exception ignored) { }
        }
        view = null;
        // 不重置的话，她回来之后自动溜达的定时器永远不会重新挂上
        wanderStarted = false;
    }

    private boolean canDrawOverlays() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return android.provider.Settings.canDrawOverlays(this);
        }
        return true;
    }

    private void applySizes() {
        // 这两个开关跟"她在不在窗口里"无关，必须先更新。
        // 设置页打开时她已经从窗口移除（view == null），以前的写法会在这里提前
        // return，于是拨动音效开关后 soundOn 一直是旧值 —— 开关看着像失灵。
        soundOn = prefs.getBoolean(KEY_SOUND, true);
        wanderOn = prefs.getBoolean(KEY_WANDER, true);
        if (view == null || lp == null) return;
        view.setHoverHeightDp(prefs.getFloat(KEY_HOVER_H, DEFAULT_HOVER_H));
        view.setPerchWidthDp(prefs.getFloat(KEY_PERCH_W, DEFAULT_PERCH_W));
        view.setAmpScale(prefs.getFloat(KEY_AMP, DEFAULT_AMP));
        int sk = prefs.getInt(KEY_SKIN, 0);
        if (sk != view.currentSkin()) view.applySkin(sk);
        applyLayout();
    }

    private void applyLayout() {
        if (view == null || lp == null) return;
        if (!wanderStarted) {                 // 窗口一就位就把溜达的定时器挂上
            wanderStarted = true;
            wanderHandler.postDelayed(wanderTick, 4000);
        }
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

    /**
     * 量真实的显示区域。
     *
     * 不能用 getResources().getDisplayMetrics().heightPixels —— 它不含导航栏，
     * 于是她最多只能停在导航栏上方，怎么拖都到不了屏幕最底部（用户反馈过）。
     */
    private void measureScreen() {
        try {
            WindowManager w = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Rect b = w.getCurrentWindowMetrics().getBounds();
                screenW = b.width();
                screenH = b.height();
            } else {
                android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
                w.getDefaultDisplay().getRealMetrics(dm);
                screenW = dm.widthPixels;
                screenH = dm.heightPixels;
            }
        } catch (Exception e) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            screenW = dm.widthPixels;
            screenH = dm.heightPixels;
        }
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

        row.addView(menuButton("皮肤：" + PetView.SKINS[prefs.getInt(KEY_SKIN, 0) % PetView.SKINS.length].name,
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                int n = (prefs.getInt(KEY_SKIN, 0) + 1) % PetView.SKINS.length;
                prefs.edit().putInt(KEY_SKIN, n).apply();
                ((TextView) v).setText("皮肤：" + PetView.SKINS[n].name);
                applySizes();          // 两套皮肤的宽高比不同，换完要重新量窗口
                // 换一次就重新计时，方便连着点着挑
                handler.removeCallbacks(menuAutoHide);
                handler.postDelayed(menuAutoHide, MENU_AUTO_HIDE_MS);
            }
        }));
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
        perchPull = 0;
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
    public void onDragStart() { hideMenu(); perchPull = 0; }

    @Override
    public void onDrag(float dx, float dy) {
        if (view == null) return;
        lp.y += Math.round(dy);

        if (view.getState() == PetView.STATE_PERCH) {
            boolean right = prefs.getInt(KEY_EDGE, 0) == 1;
            // "往外拉"的方向：贴右边时往左拉才算数，贴左边时往右拉才算数
            perchPull += Math.round(right ? -dx : dx);
            if (perchPull < 0) perchPull = 0;             // 往里推不算
            if (perchPull > screenW * 0.12f) {            // 拉够远了 → 脱离边缘
                perchPull = 0;
                lp.x = right ? screenW - lp.width : 0;
                exitPerch();
                return;
            }
            // 没拉够：位置贴回边缘，但 perchPull 留着（关键，别再抹掉）
            lp.x = right ? screenW - lp.width : 0;
        } else {
            lp.x += Math.round(dx);
        }

        clamp();
        try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
    }

    @Override
    public void onDragEnd(float rawX, float rawY) {
        if (view == null) return;
        prefs.edit().putInt(KEY_Y, lp.y).putInt(KEY_X, lp.x).apply();
        if (view.getState() == PetView.STATE_PERCH) {
            // 兜底：松手时还停在趴边态，就确保她贴回边上（上下位置保留）
            perchPull = 0;
            lp.x = (prefs.getInt(KEY_EDGE, 0) == 1) ? screenW - lp.width : 0;
            clamp();
            try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
            return;
        }
        perchPull = 0;

        // 吸附判定不能用一个固定像素值：她调小之后窗口本身就窄，
        // 固定的 56dp 会占掉她宽度的一大半，导致"稍微靠近边缘"就被吸走。
        // 所以取"固定值和窗口宽度 25% 里更小的那个"。
        float snapDp = SNAP_DP * getResources().getDisplayMetrics().density;
        float snap = Math.min(snapDp, lp.width * 0.25f);
        if (lp.x <= snap) enterPerch(false, true);
        else if (lp.x + lp.width >= screenW - snap) enterPerch(true, true);
        else applyLayout();
    }

    /**
     * 单击换表情在 PetView 里做；这里负责音效和缺素材时的兜底提示。
     */
    @Override
    public void onTap() {
        if (view != null && view.faceCount() == 0) {
            Toast.makeText(this, "表情素材没加载成功", Toast.LENGTH_SHORT).show();
        }
        if (!soundOn) return;
        playDuckAndWarn();
    }

    // ---- 音效 ----

    /**
     * 音效池。USAGE_MEDIA → STREAM_MUSIC（媒体音量）。
     *
     * 这里以前是 USAGE_ASSISTANCE_SONIFICATION，它落在 STREAM_SYSTEM。
     * MIUI / HyperOS 上"系统音效"那条通道在静音模式、或用户关掉触摸提示音后是 0，
     * 于是 play() 返回成功却完全没声音 —— 正是"点她有反应、就是不出声"。
     */
    private void createSoundPool() {
        soundPool = new SoundPool.Builder()
                .setMaxStreams(3)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        soundPool.setOnLoadCompleteListener((pool, sampleId, status) -> {
            for (int i = 0; i < duckIds.length; i++) {
                if (duckIds[i] == sampleId) duckLoadStatus[i] = status;
            }
            android.util.Log.d("WhalePetAudio", "loadComplete sample=" + sampleId
                    + " status=" + status);
        });
    }

    /** load() 是异步的，这里返回 0 说明这一步就失败了 —— 下次点击会再试一次 */
    private void loadDuckSounds() {
        if (soundPool == null) return;
        duckIds[0] = soundPool.load(this, R.raw.duck1, 1);
        duckIds[1] = soundPool.load(this, R.raw.duck2, 1);
        duckIds[2] = soundPool.load(this, R.raw.duck3, 1);
        android.util.Log.d("WhalePetAudio", "load ids="
                + duckIds[0] + "," + duckIds[1] + "," + duckIds[2]);
    }

    /**
     * 随机播一声鸭子叫，返回 SoundPool.play() 的 streamId（0 = 没播出去）。
     * 采样还没 load 完时补一次 load —— 否则刚启动那几下点击是哑的。
     */
    private int playDuckSound() {
        if (soundPool == null) return 0;
        int id = duckIds[rnd.nextInt(duckIds.length)];
        if (id == 0) {
            loadDuckSounds();
            return 0;
        }
        int streamId = soundPool.play(id, 1f, 1f, 1, 0, 1f);
        android.util.Log.d("WhalePetAudio", "tap sample=" + id + " streamId=" + streamId
                + " soundOn=" + soundOn + " " + volumeReport());
        if (streamId == 0) loadDuckSounds();
        return streamId;
    }

    private void playDuckAndWarn() {
        int streamId = playDuckSound();
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        int vol = am.getStreamVolume(SOUND_STREAM);
        boolean muted = false;
        try { muted = am.isStreamMute(SOUND_STREAM); } catch (Exception ignored) { }
        // 只有确实查出问题才提示；正常出声时保持安静
        if (streamId != 0 && !muted && vol > 0) return;
        hintSilent(streamId, vol, muted);
    }

    /**
     * 没响的时候把原因直接说清楚，别让用户对着一个哑巴开关发呆。
     * 一分钟最多提示一次，免得连点时刷屏。
     */
    private void hintSilent(int streamId, int vol, boolean muted) {
        long now = System.currentTimeMillis();
        if (now - lastSilentHintMs < 60_000L) return;
        lastSilentHintMs = now;

        String why;
        if (streamId == 0) {
            why = "音效还没加载好，再点一下试试";
        } else if (muted) {
            why = "媒体音量被静音了 —— 关掉静音模式，或关掉 MIUI「静音时同时静音媒体」";
        } else if (vol <= 0) {
            why = "媒体音量是 0，按一下音量键调大就有声了";
        } else {
            why = "声音已经送出去了，检查是不是连着蓝牙耳机";
        }
        Toast.makeText(this, "鸭子叫没响：" + why, Toast.LENGTH_LONG).show();
    }

    private String volumeReport() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return "audio=null";
        StringBuilder sb = new StringBuilder("stream=").append(SOUND_STREAM)
                .append(" vol=").append(am.getStreamVolume(SOUND_STREAM))
                .append('/').append(am.getStreamMaxVolume(SOUND_STREAM));
        try {
            sb.append(" muted=").append(am.isStreamMute(SOUND_STREAM));
        } catch (Exception ignored) { }
        sb.append(" ringer=").append(am.getRingerMode());
        return sb.toString();
    }

    /**
     * 音效诊断：先真的播一声（数字看不出问题，听得到才算数），
     * 再把音频通道 / 音量 / 静音状态 / 本次播放结果一起报出来。
     */
    private void runSoundDiagnostic() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        int streamId = playDuckSound();

        int vol = am == null ? -1 : am.getStreamVolume(SOUND_STREAM);
        int volMax = am == null ? -1 : am.getStreamMaxVolume(SOUND_STREAM);
        boolean muted = false;
        try { muted = am != null && am.isStreamMute(SOUND_STREAM); } catch (Exception ignored) { }
        int ringer = am == null ? -1 : am.getRingerMode();
        String ringerText = ringer == AudioManager.RINGER_MODE_SILENT ? "静音"
                : ringer == AudioManager.RINGER_MODE_VIBRATE ? "震动" : "正常";

        String msg = "音效诊断\n"
                + "sound_on=" + soundOn
                + "　加载=" + duckLoadStatus[0] + "," + duckLoadStatus[1] + "," + duckLoadStatus[2] + "\n"
                + "音频通道=媒体音量(STREAM_MUSIC)\n"
                + "媒体音量=" + vol + "/" + volMax + (muted ? "（已被系统静音）" : "") + "\n"
                + "铃声模式=" + ringerText + "\n"
                + "本次播放=" + (streamId == 0 ? "失败" : "成功 streamId=" + streamId) + "\n"
                + diagAdvice(streamId, vol, muted);
        android.util.Log.d("WhalePetAudio", msg.replace('\n', ' '));
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private String diagAdvice(int streamId, int vol, boolean muted) {
        if (streamId == 0) return "→ 音效没加载完或加载失败，再点一次";
        if (muted) return "→ 媒体通道被静音：关静音模式 / 关 MIUI「静音时同时静音媒体」";
        if (vol <= 0) return "→ 媒体音量是 0：按音量键调大";
        return "→ 通道与音量都正常。若仍听不到，看是否连着蓝牙耳机";
    }

    // ---- 通知 ----

    // ---------------- 自动溜达 ----------------
    // 她的位置是"窗口位置"，只有 Service 能动。所以这里挪窗口，
    // 同时告诉 View "正在走"（由 View 加摇晃和颠簸）。
    private boolean wanderOn = true;
    private final Handler wanderHandler = new Handler(Looper.getMainLooper());
    private float wanderX = 0f, wanderTargetX = -1f;
    private long wanderWaitUntil = 0L;
    private boolean wanderStarted = false;

    private final Runnable wanderTick = new Runnable() {
        @Override public void run() {
            if (!wanderOn || view == null || lp == null) return;
            if (!prefs.getBoolean(KEY_RUNNING, false)) return;
            // 趴边 / 正在被拖 / 刚被碰过 —— 都不动
            if (view.getState() != PetView.STATE_HOVER
                    || view.isDragging() || view.msSinceTouch() < 5000) {
                view.setWalking(false);
                wanderHandler.postDelayed(this, 300);
                return;
            }
            long now = System.currentTimeMillis();
            if (now < wanderWaitUntil) {
                view.setWalking(false);
                wanderHandler.postDelayed(this, 150);
                return;
            }
            if (wanderTargetX < 0f) {                 // 挑一个新目标
                wanderX = lp.x;
                int margin = Math.round(dp(14));
                int hi = Math.max(margin + 1, screenW - lp.width - margin);
                wanderTargetX = margin + rnd.nextInt(hi - margin);
                wanderWaitUntil = now + 800;
                wanderHandler.postDelayed(this, 150);
                return;
            }
            float d = wanderTargetX - wanderX;
            if (Math.abs(d) < 3f) {                   // 到站，歇一会儿
                wanderX = wanderTargetX;
                wanderTargetX = -1f;
                wanderWaitUntil = now + 1200 + rnd.nextInt(2400);
                view.setWalking(false);
                wanderHandler.postDelayed(this, 120);
                return;
            }
            wanderX += Math.signum(d) * Math.min(4f, Math.abs(d));
            lp.x = Math.round(wanderX);
            try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
            view.setWalking(true);
            wanderHandler.postDelayed(this, 16);
        }
    };

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

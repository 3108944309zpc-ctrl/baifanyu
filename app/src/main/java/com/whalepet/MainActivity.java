package com.whalepet;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int MIN_HOVER = 120, MAX_HOVER = 480;
    private static final int MIN_PERCH = 56, MAX_PERCH = 160;
    private static final int MIN_AMP = 30, MAX_AMP = 150;
    private static final int MIN_DRAGON = 120, MAX_DRAGON = 320;

    private SharedPreferences prefs;
    private TextView tvStatus, tvHover, tvPerch, tvAmp;
    private SeekBar sbHover, sbPerch, sbAmp;
    // 取本分支（fork）这份：它是上游那行 `... btnDuck;` / `swSound, swWander;` 的超集，
    // 上游新增的 btnDuck 已经包含在内，另外带上本分支的小龙女/睡觉/活动范围/打闹控件字段。
    private Button btnPerm, btnToggle, btnFace, btnSkin, btnSoundDiag, btnDuck, btnDragonFace;
    private Switch swSound, swWander, swDragon, swFish, swDragonWander;
    private SeekBar sbDragonHeight, sbDragonPerch, sbDragonAmp;
    private TextView tvDragonHeight, tvDragonPerch, tvDragonAmp;
    private SeekBar sbSleep;
    private TextView tvSleep;
    private Spinner spRegion;
    private Switch swPlayfight;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PetService.PREFS, MODE_PRIVATE);

        tvStatus = findViewById(R.id.tvStatus);
        tvHover = findViewById(R.id.tvHover);
        tvPerch = findViewById(R.id.tvPerch);
        tvAmp = findViewById(R.id.tvAmp);
        sbHover = findViewById(R.id.sbHover);
        sbPerch = findViewById(R.id.sbPerch);
        sbAmp = findViewById(R.id.sbAmp);
        btnPerm = findViewById(R.id.btnPerm);
        btnToggle = findViewById(R.id.btnToggle);
        btnFace = findViewById(R.id.btnFace);
        btnFace.setOnClickListener(v -> sendAction(PetService.ACTION_NEXT_FACE));

        btnDuck = findViewById(R.id.btnDuck);
        btnDuck.setOnClickListener(v -> {
            int k = (prefs.getInt(PetService.KEY_DUCK, 0) + 1) % PetService.DUCK_COUNT;
            prefs.edit().putInt(PetService.KEY_DUCK, k).apply();
            btnDuck.setText(duckLabel(k));
            refreshService();
            sendAction(PetService.ACTION_PREVIEW_DUCK);   // 点一下立刻试听
        });

        btnSoundDiag = findViewById(R.id.btnSoundDiag);
        btnSoundDiag.setOnClickListener(v -> sendAction(PetService.ACTION_SOUND_DIAG));

        btnSkin = findViewById(R.id.btnSkin);
        btnSkin.setOnClickListener(v -> {
            int n = (prefs.getInt(PetService.KEY_SKIN, PetService.DEFAULT_SKIN) + 1) % PetView.SKINS.length;
            prefs.edit().putInt(PetService.KEY_SKIN, n).apply();
            btnSkin.setText(skinLabel(n));
            refreshService();
        });

        swWander = findViewById(R.id.swWander);
        swWander.setOnCheckedChangeListener((sw, checked) -> {
            prefs.edit().putBoolean(PetService.KEY_WANDER, checked).apply();
            refreshService();
        });

        swSound = findViewById(R.id.swSound);
        swSound.setOnCheckedChangeListener((sw, checked) -> {
            prefs.edit().putBoolean(PetService.KEY_SOUND, checked).apply();
            refreshService();
        });

        swFish = findViewById(R.id.swFish);
        swFish.setOnCheckedChangeListener((sw, checked) -> {
            prefs.edit().putBoolean(PetService.KEY_FISH_ENABLED, checked).apply();
            sendAction(PetService.ACTION_TOGGLE_FISH);
            tvStatus.postDelayed(this::syncUi, 300);
        });

        swDragon = findViewById(R.id.swDragon);
        swDragon.setOnCheckedChangeListener((sw, checked) -> {
            prefs.edit().putBoolean(PetService.KEY_DRAGON_ENABLED, checked).apply();
            sendAction(PetService.ACTION_TOGGLE_DRAGON);
            // 关掉最后一个的时候服务会自己停下，状态栏得跟着刷新，
            // 否则按钮还写着"收回她"，下一次点击就点错了
            tvStatus.postDelayed(this::syncUi, 300);
        });

        swDragonWander = findViewById(R.id.swDragonWander);
        swDragonWander.setOnCheckedChangeListener((sw, checked) -> {
            prefs.edit().putBoolean(PetService.KEY_DRAGON_WANDER, checked).apply();
            refreshService();
        });

        // 打闹开关：关掉后随机剧情里不再抽到"互相追打"那一段，别的互动照常
        swPlayfight = findViewById(R.id.swPlayfight);
        swPlayfight.setOnCheckedChangeListener((sw, checked) -> {
            prefs.edit().putBoolean(PetService.KEY_PLAYFIGHT, checked).apply();
            refreshService();
        });
        sbDragonHeight = findViewById(R.id.sbDragonHeight);
        tvDragonHeight = findViewById(R.id.tvDragonHeight);
        btnDragonFace = findViewById(R.id.btnDragonFace);
        btnDragonFace.setOnClickListener(v -> sendAction(PetService.ACTION_DRAGON_FACE));
        sbDragonHeight.setMax(MAX_DRAGON - MIN_DRAGON);
        sbDragonHeight.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                int h = MIN_DRAGON + p;
                prefs.edit().putFloat(PetService.KEY_DRAGON_HEIGHT, h).apply();
                tvDragonHeight.setText("小龙女大小：" + h + " dp");
                refreshService();
            }
        });

        tvDragonPerch = findViewById(R.id.tvDragonPerch);
        sbDragonPerch = findViewById(R.id.sbDragonPerch);
        sbDragonPerch.setMax(MAX_PERCH - MIN_PERCH);
        sbDragonPerch.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                int w = MIN_PERCH + p;
                prefs.edit().putFloat(PetService.KEY_DRAGON_PERCH_W, w).apply();
                tvDragonPerch.setText("趴边时脑袋宽度：" + w + " dp");
                refreshService();
            }
        });

        tvDragonAmp = findViewById(R.id.tvDragonAmp);
        sbDragonAmp = findViewById(R.id.sbDragonAmp);
        sbDragonAmp.setMax(MAX_AMP - MIN_AMP);
        sbDragonAmp.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                int pct = MIN_AMP + p;
                prefs.edit().putFloat(PetService.KEY_DRAGON_AMP, pct / 100f).apply();
                tvDragonAmp.setText("动作幅度：" + pct + "%");
                refreshService();
            }
        });

        // 互动范围：8 个预设，选中即写 4 个归一化 pref 并让服务重算
        spRegion = findViewById(R.id.spRegion);
        ArrayAdapter<String> regionAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, REGION_NAMES);
        regionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spRegion.setAdapter(regionAdapter);
        spRegion.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                applyRegion(pos);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        sbHover.setMax(MAX_HOVER - MIN_HOVER);
        sbPerch.setMax(MAX_PERCH - MIN_PERCH);
        sbAmp.setMax(MAX_AMP - MIN_AMP);

        tvSleep = findViewById(R.id.tvSleep);
        sbSleep = findViewById(R.id.sbSleep);
        sbSleep.setMax(PetService.MAX_SLEEP_AFTER_MIN);
        sbSleep.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                prefs.edit().putInt(PetService.KEY_SLEEP_AFTER_MIN, p).apply();
                tvSleep.setText(sleepLabel(p));
            }
        });

        btnPerm.setOnClickListener(v -> requestOverlay());
        btnToggle.setOnClickListener(v -> togglePet());

        sbHover.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                int v = MIN_HOVER + p;
                prefs.edit().putFloat(PetService.KEY_HOVER_H, v).apply();
                tvHover.setText("悬空大小：" + v + " dp");
                refreshService();
            }
        });
        sbPerch.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                int v = MIN_PERCH + p;
                prefs.edit().putFloat(PetService.KEY_PERCH_W, v).apply();
                tvPerch.setText("趴边时脑袋宽度：" + v + " dp");
                refreshService();
            }
        });
        // 大肥鱼的动作幅度：存自己的键，翻动时不影响小龙女
        sbAmp.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                int pct = MIN_AMP + p;
                prefs.edit().putFloat(PetService.KEY_FISH_AMP, pct / 100f).apply();
                tvAmp.setText("动作幅度：" + pct + "%");
                refreshService();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 我们自己的界面在前台时，先把她收起来 —— 否则她会盖在设置页上面。
        // 服务不一定在跑（可能是被这里的开关拉起来的），所以另外记一个记号，
        // 让服务启动时就知道"现在别放出来"。
        prefs.edit().putBoolean(PetService.KEY_SETTINGS_OPEN, true).apply();
        sendBool(PetService.ACTION_SET_HIDDEN, PetService.EXTRA_HIDDEN, true);
        syncUi();
    }

    @Override
    protected void onPause() {
        // 离开设置页就把她放回来
        prefs.edit().putBoolean(PetService.KEY_SETTINGS_OPEN, false).apply();
        sendBool(PetService.ACTION_SET_HIDDEN, PetService.EXTRA_HIDDEN, false);
        super.onPause();
    }

    // 本分支新增（上游没有这段）：界面销毁时清掉「设置页开着」的记号
    @Override
    protected void onDestroy() {
        // 界面没了这个记号就必须清掉，否则服务重启后会一直以为设置页还开着、
        // 于是谁也不显示
        prefs.edit().putBoolean(PetService.KEY_SETTINGS_OPEN, false).apply();
        super.onDestroy();
    }

    private String duckLabel(int idx) {
        return "音效 " + (idx + 1) + " / " + PetService.DUCK_COUNT + "　▸ 点击试听";
    }

    private String skinLabel(int idx) {
        return "大肥鱼皮肤：" + PetView.SKINS[idx % PetView.SKINS.length].name + "　▸ 点击切换";
    }

    private String sleepLabel(int minutes) {
        return minutes <= 0 ? "睡觉待机：关闭" : "趴边 " + minutes + " 分钟后触发睡觉";
    }

    private void sendBool(String action, String key, boolean value) {
        if (!prefs.getBoolean(PetService.KEY_RUNNING, false)) return;
        Intent i = new Intent(this, PetService.class).setAction(action).putExtra(key, value);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
    }

    private void syncUi() {
        float hh = prefs.getFloat(PetService.KEY_HOVER_H, PetService.DEFAULT_HOVER_H);
        float pw = prefs.getFloat(PetService.KEY_PERCH_W, PetService.DEFAULT_PERCH_W);
        // 动作幅度两个人各存各的；老版本共用 KEY_AMP，没设过就沿用它当默认
        float oldAmp = prefs.getFloat(PetService.KEY_AMP, PetService.DEFAULT_AMP);
        float fishAmp = prefs.getFloat(PetService.KEY_FISH_AMP, oldAmp);
        sbHover.setProgress(Math.round(hh) - MIN_HOVER);
        sbPerch.setProgress(Math.round(pw) - MIN_PERCH);
        int ampPct = Math.round(fishAmp * 100);
        sbAmp.setProgress(Math.max(0, Math.min(MAX_AMP - MIN_AMP, ampPct - MIN_AMP)));
        tvAmp.setText("动作幅度：" + ampPct + "%");
        int dragonHeight = Math.round(prefs.getFloat(PetService.KEY_DRAGON_HEIGHT, 190f));
        sbDragonHeight.setProgress(Math.max(0, Math.min(MAX_DRAGON - MIN_DRAGON, dragonHeight - MIN_DRAGON)));
        tvDragonHeight.setText("小龙女大小：" + dragonHeight + " dp");
        int dragonPerch = Math.round(prefs.getFloat(PetService.KEY_DRAGON_PERCH_W,
                PetService.DEFAULT_DRAGON_PERCH_W));
        sbDragonPerch.setProgress(Math.max(0, Math.min(MAX_PERCH - MIN_PERCH, dragonPerch - MIN_PERCH)));
        tvDragonPerch.setText("趴边时脑袋宽度：" + dragonPerch + " dp");
        // 两个人的动作幅度各读各的
        int dragonAmpPct = Math.round(prefs.getFloat(PetService.KEY_DRAGON_AMP, oldAmp) * 100);
        sbDragonAmp.setProgress(Math.max(0, Math.min(MAX_AMP - MIN_AMP, dragonAmpPct - MIN_AMP)));
        tvDragonAmp.setText("动作幅度：" + dragonAmpPct + "%");
        int sleepMin = prefs.getInt(PetService.KEY_SLEEP_AFTER_MIN, PetService.DEFAULT_SLEEP_AFTER_MIN);
        int regionIdx = regionIndexFromPrefs();
        if (spRegion.getSelectedItemPosition() != regionIdx) spRegion.setSelection(regionIdx, false);
        sbSleep.setProgress(Math.max(0, Math.min(PetService.MAX_SLEEP_AFTER_MIN, sleepMin)));
        tvSleep.setText(sleepLabel(sleepMin));
        tvHover.setText("悬空大小：" + Math.round(hh) + " dp");
        tvPerch.setText("趴边时脑袋宽度：" + Math.round(pw) + " dp");
        tvAmp.setText("动作幅度：" + ampPct + "%");

        btnDuck.setText(duckLabel(prefs.getInt(PetService.KEY_DUCK, 0)));
        boolean snd = prefs.getBoolean(PetService.KEY_SOUND, true);
        if (swSound.isChecked() != snd) swSound.setChecked(snd);   // 先判断再设，避免回调绕圈
        boolean wdr = prefs.getBoolean(PetService.KEY_WANDER, true);
        if (swWander.isChecked() != wdr) swWander.setChecked(wdr);
        boolean dragon = prefs.getBoolean(PetService.KEY_DRAGON_ENABLED, true);
        if (swDragon.isChecked() != dragon) swDragon.setChecked(dragon);
        boolean fish = prefs.getBoolean(PetService.KEY_FISH_ENABLED, true);
        if (swFish.isChecked() != fish) swFish.setChecked(fish);
        boolean dragonWander = prefs.getBoolean(PetService.KEY_DRAGON_WANDER, true);
        if (swDragonWander.isChecked() != dragonWander) swDragonWander.setChecked(dragonWander);
        boolean playfight = prefs.getBoolean(PetService.KEY_PLAYFIGHT, true);
        if (swPlayfight.isChecked() != playfight) swPlayfight.setChecked(playfight);

        int ski = prefs.getInt(PetService.KEY_SKIN, PetService.DEFAULT_SKIN);
        btnSkin.setText(skinLabel(ski));

        boolean overlay = hasOverlay();
        boolean running = prefs.getBoolean(PetService.KEY_RUNNING, false);
        btnPerm.setEnabled(!overlay);
        btnPerm.setText(overlay ? "① 权限已授予 ✓" : "① 授予「显示在其他应用上层」");
        btnToggle.setText(running ? "④ 收回她" : "② 让她出现");
        PetView.Skin skin = PetView.SKINS[ski % PetView.SKINS.length];
        int faces = countFaces(ski % PetView.SKINS.length);
        tvStatus.setText((overlay
                ? (running ? "状态：正在陪你。拖到屏幕边缘（左 / 右 / 下边）松手，她会扒在边上。" : "状态：已就绪，点下面的按钮让她出现。")
                : "状态：还差一步 —— 需要「显示在其他应用上层」权限，否则她没法浮在别的应用上面。")
                + "\n当前皮肤：" + skin.name + " · 表情 " + faces + " / " + skin.faces.length
                + (faces < skin.faces.length ? "（素材缺失，重新装一次）" : "")
                // 本分支多一行「显示中：大肥鱼 / 小龙女」，并沿用上游「打开设置时会自动收起」的说明
                + "\n显示中：" + (fish ? "大肥鱼" : "—") + " / " + (dragon ? "小龙女" : "—")
                + "（两个都可以单独出现，全关掉就自动停下）"
                + "\n（打开这个界面时她们会自动收起，免得挡住设置；回到桌面就回来）");
    }

    /** 数当前皮肤真正能加载到的表情数（路径来自 PetView.SKINS，不再写死） */
    private int countFaces(int skinIdx) {
        int n = 0;
        for (String f : PetView.SKINS[skinIdx].faces) {
            try {
                getAssets().open(f).close();
                n++;
            } catch (Exception ignored) { }
        }
        return n;
    }

    private boolean hasOverlay() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private void requestOverlay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            Toast.makeText(this, "请手动到「设置 → 应用 → 显示在其他应用上层」里打开",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void togglePet() {
        boolean running = prefs.getBoolean(PetService.KEY_RUNNING, false);
        Intent i = new Intent(this, PetService.class);
        if (running) {
            i.setAction(PetService.ACTION_HIDE);
        } else {
            if (!hasOverlay()) {
                Toast.makeText(this, "先授予「显示在其他应用上层」权限", Toast.LENGTH_SHORT).show();
                requestOverlay();
                return;
            }
            i.setAction(PetService.ACTION_SHOW);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
        tvStatus.postDelayed(this::syncUi, 400);
    }

    private void refreshService() {
        if (!prefs.getBoolean(PetService.KEY_RUNNING, false)) return;
        sendAction(PetService.ACTION_REFRESH);
    }

    private void sendAction(String action) {
        Intent i = new Intent(this, PetService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
    }

    /** 互动活动范围的预设：显示名 + 归一化的 (l, t, r, b)。存比例不存 dp，横屏/平板换方向自动对。 */
    private static final String[] REGION_NAMES = {
            "全屏", "上半屏", "下半屏", "中间 1/4",
            "左上 1/4", "右上 1/4", "左下 1/4", "右下 1/4"};
    private static final float[][] REGION_VALUES = {
            {0f, 0f, 1f, 1f},
            {0f, 0f, 1f, 0.5f},
            {0f, 0.5f, 1f, 1f},
            {0.25f, 0.25f, 0.75f, 0.75f},
            {0f, 0f, 0.5f, 0.5f},
            {0.5f, 0f, 1f, 0.5f},
            {0f, 0.5f, 0.5f, 1f},
            {0.5f, 0.5f, 1f, 1f}};

    /** 选中一个预设 → 写 4 个归一化 pref，再让服务按当前屏幕尺寸重算范围。 */
    private void applyRegion(int pos) {
        if (pos < 0 || pos >= REGION_VALUES.length) return;
        float[] v = REGION_VALUES[pos];
        prefs.edit()
                .putFloat(PetService.KEY_REGION_L, v[0]).putFloat(PetService.KEY_REGION_T, v[1])
                .putFloat(PetService.KEY_REGION_R, v[2]).putFloat(PetService.KEY_REGION_B, v[3])
                .apply();
        refreshService();
    }

    /** 从 pref 反推是哪个预设（存的是比例，所以只能比回来），对不上就当全屏。 */
    private int regionIndexFromPrefs() {
        float l = prefs.getFloat(PetService.KEY_REGION_L, 0f);
        float t = prefs.getFloat(PetService.KEY_REGION_T, 0f);
        float r = prefs.getFloat(PetService.KEY_REGION_R, 1f);
        float b = prefs.getFloat(PetService.KEY_REGION_B, 1f);
        for (int i = 0; i < REGION_VALUES.length; i++) {
            float[] v = REGION_VALUES[i];
            if (Math.abs(v[0] - l) < 0.001f && Math.abs(v[1] - t) < 0.001f
                    && Math.abs(v[2] - r) < 0.001f && Math.abs(v[3] - b) < 0.001f) return i;
        }
        return 0;
    }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar sb) { }
        @Override public void onStopTrackingTouch(SeekBar sb) { }
    }
}

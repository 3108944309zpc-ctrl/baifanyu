package com.whalepet;

import android.os.Handler;
import android.os.Looper;

import java.util.Random;

/**
 * 双人互动"小剧场"控制器 —— 本地预设，不联网、不调用 AI API。
 *
 * 每一段都是**当成一小段视频来编排**的：走位、姿态（靠/探/蹦/压扁/被推）、
 * 前后层级、接触效果、表情、台词，全部卡在时间轴上，一步一步来。
 * 台词逐字取自「两女互动台词.docx」，情绪符号（爱心/星星/星芒/气鼓鼓/眼泪/梦境）
 * 跟着每句台词走，所以同一段里两个人的情绪是**有变化**的。
 *
 * 走位走的是 PetService 里的**逐帧轨迹播放器**（trackMove/trackPose，16ms 一步）：
 * 位移是连续的曲线（直线/抛物线/连跳/蛇形/绕圈），不是"挪一下停一下"，
 * 而且起点用 {@link Host#actorRatio(String)} 读角色**当前站的地方**，不会跳切。
 * 活动范围由设置页的「互动范围」决定（上半屏 / 1/4 屏之类），换了范围剧情自动跟着走。
 *
 * 两只都是没有骨骼的整张 PNG，所以"演戏"只能靠四件事：
 *   ① 走位（轨迹）② 姿态（倾斜＝靠/探/被吓退，压扁＝落地与被撞，缩放＝蹦）
 *   ③ 层级（raiseActor：抱在一起时谁挡在谁前面）④ 自绘效果（云朵/爱心/星星/Zzz/魔法光效）
 * 倾斜幅度有上限（大肥鱼 12°、小龙女 10°，压扁 ≥0.86，抬升 ≤0.05），
 * 窗口只多留了 22% 横向余量，超了会被自己的窗口裁掉 —— {@link #lean} 会自动配一个缩放。
 */
public final class InteractionController {
    public interface Host {
        void setDragonExpression(int expression);
        void setFishExpression(int faceIndex);
        void setFishSkin(int skinIndex);
        int currentFishSkin();
        /** 情绪符号：Effects.MOOD_*。 */
        void setDragonMood(int mood);
        void setFishMood(int mood);
        void showInteractionLine(String actor, String text);
        void clearBubbles();
        void moveDragonNearFish();
        void moveFishNearDragon();
        void moveActor(String actor, float xRatio, float yRatio, long durationMs);
        void nudgeActor(String actor, int dx, int dy, long durationMs);
        void setSleeping(boolean fishSleeping, boolean dragonSleeping);
        void animateInteraction(int phase);
        /** 姿态：倾斜(度) / 压扁(1=不变) / 抬升(以身高为单位) / 缩放。 */
        void poseActor(String actor, float lean, float squash, float lift, float scale, long durationMs);
        /** 开演时给窗口留出倾斜余量，收工立刻还原。 */
        void setSceneMode(boolean on);
        /** 谁挡在谁前面。 */
        void raiseActor(String actor);
        /** 逐帧轨迹：t0~t1（相对场景开始，毫秒）沿 path 从 (x0,y0) 走到 (x1,y1)，都是范围内比例坐标。 */
        void trackMove(String actor, long t0, long t1, float x0, float y0, float x1, float y1,
                       int path, float ampUnit, float cycles);
        /** 逐帧姿态：t0~t1 之间按 kind 做动作，幅度 amp、循环 cycles。 */
        void trackPose(String actor, long t0, long t1, int kind, float amp, float cycles);
        /** 该角色当前在活动范围里的比例坐标，剧情从"她站的地方"起步。 */
        float[] actorRatio(String actor);
        /** 「打闹」开关：关掉之后随机剧情里不再抽到"互相追打"那一段。 */
        boolean playfightEnabled();
    }

    // 轨迹形状与姿态（常量在 PetService 里定义，剧情这边只引用）
    private static final int LINE = PetService.PATH_LINE, ARC = PetService.PATH_ARC,
            HOPP = PetService.PATH_HOP, SINE = PetService.PATH_SINE, LOOP = PetService.PATH_LOOP;
    private static final int BOUNCE = PetService.POSE_BOUNCE, SHAKE = PetService.POSE_SHAKE,
            LEANP = PetService.POSE_LEAN, SPIN = PetService.POSE_SPIN;

    // 动作阶段（两个 View 里按这个值再叠一层动作）
    public static final int P_IDLE = 0;
    public static final int P_APPROACH = 1;   // 靠近
    public static final int P_TUSSLE = 2;     // 打闹抖动 A
    public static final int P_TUSSLE2 = 3;    // 打闹抖动 B
    public static final int P_MAGIC = 4;      // 魔法闪动
    public static final int P_SLEEP = 5;      // 睡觉呼吸 A
    public static final int P_SLEEP2 = 6;     // 睡觉呼吸 B
    public static final int P_SNUGGLE = 7;    // 依偎：同步轻晃
    public static final int P_SCARED = 8;     // 被吓到：往回一缩
    public static final int P_PROUD = 9;      // 得意：上下弹跳

    // 大肥鱼表情：0 待机 1 开心 2 难过 3 生气 4 惊讶 5 害羞 6 困惑
    private static final int F_STAND = 0, F_HAPPY = 1, F_SAD = 2, F_ANGRY = 3,
            F_SURPRISE = 4, F_SHY = 5, F_CONFUSED = 6;
    // 小龙女表情
    private static final int D_STAND = DragonView.STAND, D_HAPPY = DragonView.HAPPY,
            D_SAD = DragonView.SAD, D_ANGRY = DragonView.ANGRY,
            D_SURPRISE = DragonView.SURPRISED, D_SHY = DragonView.SHY,
            D_SLEEPY = DragonView.SLEEPY;

    private static final int M_NONE = Effects.MOOD_NONE, M_HAPPY = Effects.MOOD_HAPPY,
            M_LOVE = Effects.MOOD_LOVE, M_ANGER = Effects.MOOD_ANGER,
            M_SAD = Effects.MOOD_SAD, M_MAGIC = Effects.MOOD_MAGIC,
            M_SPARKLE = Effects.MOOD_SPARKLE, M_DREAM = Effects.MOOD_DREAM;

    /** 相遇剧情编号：4 = 「⑤互相追打」。设置页关掉「打闹」时跳过它。 */
    private static final int SCENE_PLAYFIGHT = 4;
    /** 收工后各退开的活动范围比例（相遇类剧情收尾时用）。 */
    private static final float PART_APART = 0.18f;

    private static final String DRAGON = "dragon";
    private static final String FISH = "bigfish";

    /** 文档「二、睡觉时的待机台词」四组：[0]=小龙女 [1]=大肥鱼。 */
    private static final String[][] SLEEP_LINES = {
            {"晚安啦……今天也辛苦了。", "嗯……明天还要一起玩……Zzz"},
            {"靠一下下……就一下下……", "早就给你留好位置啦……"},
            {"呼……呼……", "睡得真香……我也睡啦……"},
            {"唔……梦里好像有好多星星……", "嘿嘿……梦里也有好多好吃的……"},
    };

    /** 文档「8. 互动结束 / 回到待机」的两句，收尾时按概率补上。 */
    private static final String[] END_LINES = {"今天玩得好开心呀。", "嗯哼，下次还要一起玩！"};

    /** 睡觉时的表情：大肥鱼用"闭眼+腮红"那张（素材里最像睡脸的），小龙女用她自己的困困脸。 */
    private static final int F_SLEEP = F_SHY;
    private static final int D_SLEEP = D_SLEEPY;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Host host;
    private final Random random = new Random();
    private boolean running;
    private int oldSkin = -1;      // -1 = 没临时改过皮肤（0 是饭盆头，不能当"没改过"）
    private long baseMs;
    private int lastEncounter = -1;   // 上一段相遇剧情：同一组别连着来两次
    private int lastSleep = -1;

    // 剧情设定的静态姿态（倾斜/压扁/抬升/缩放）。轨迹是"叠"在它上面的，
    // 所以要记住，不然落地那一下会把倾斜抹平（看着像忽然站直了）。
    private float dLean, dSquash = 1f, dLift, dScale = 1f;
    private float fLean, fSquash = 1f, fLift, fScale = 1f;

    public InteractionController(Host host) { this.host = host; }

    public boolean isRunning() { return running; }

    public void cancel() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        host.animateInteraction(P_IDLE);
        host.setDragonMood(M_NONE);
        host.setFishMood(M_NONE);
        rest(DRAGON, 1);
        rest(FISH, 1);
        host.setSceneMode(false);       // 收工：停掉所有轨迹、还原窗口余量
        if (oldSkin >= 0) {
            host.setFishSkin(oldSkin);
            oldSkin = -1;
        }
    }

    /** 被碰/被拖 → 醒过来。睡觉动效要在这里收掉。 */
    public void wake() {
        host.setSleeping(false, false);
        if (running) cancel();
    }

    /** 换一个别的相遇剧情编号（+1~+7，保证不等于传进来的那个）。 */
    private int reroll(int pick) { return (pick + 1 + random.nextInt(7)) % 8; }

    public void startRandomEncounter() {
        cancel();
        if (random.nextInt(100) < 14) {
            int pick = random.nextInt(4);
            if (pick == lastSleep) pick = (pick + 1 + random.nextInt(3)) % 4;
            lastSleep = pick;
            switch (pick) {
                case 0: startSleepIdle(); break;
                case 1: startSleepCuddle(); break;
                case 2: startSleepSeparate(); break;
                default: startSleepDream(); break;
            }
        } else {
            int pick = random.nextInt(8);
            // 同一组台词别连着来两遍（文档里明确要求）
            if (pick == lastEncounter) pick = reroll(pick);
            // 设置页把「打闹」关掉时，⑤互相打闹这一段直接跳过 ——
            // 但要换成别的一段，不能什么都不演（reroll 保证不会又抽到 4）。
            if (pick == SCENE_PLAYFIGHT && !host.playfightEnabled()) pick = reroll(pick);
            lastEncounter = pick;
            switch (pick) {
                case 0: startFriendly(); break;
                case 1: startPlayfulArgument(); break;
                case 2: startJealous(); break;
                case 3: startCuddle(); break;
                case 4: startPlayFight(); break;
                case 5: startPotLid(); break;
                case 6: startMagic(); break;
                default: startGoodbye(); break;
            }
        }
    }

    // ================= 1 友好问候 =================

    /** 小龙女连续蹦两下凑过来打招呼，大肥鱼惊喜地蹦着回礼，最后挨到一起轻晃。 */
    public void startFriendly() {
        begin();
        float[] d = host.actorRatio(DRAGON), f = host.actorRatio(FISH);
        at(0, () -> {
            host.setDragonExpression(D_HAPPY);
            host.setFishExpression(F_HAPPY);
            host.setDragonMood(M_HAPPY);
            host.setFishMood(M_HAPPY);
        });
        host.trackMove(DRAGON, 0, 900, d[0], d[1], 0.30f, 0.58f, LINE, 0f, 1f);
        host.trackPose(DRAGON, 950, 1520, BOUNCE, 1f, 2f);       // 连蹦两下 = 打招呼
        at(300, () -> host.animateInteraction(P_APPROACH));
        at(1000, () -> say(DRAGON, "你好呀~大肥鱼！", P_APPROACH, M_HAPPY, M_LOVE));

        host.trackMove(FISH, 1250, 2000, f[0], f[1], 0.56f, 0.60f, LINE, 0f, 1f);
        at(1700, () -> {
            host.setFishExpression(F_SURPRISE);
            host.setFishMood(M_HAPPY);
        });
        host.trackPose(FISH, 2050, 2550, BOUNCE, 1f, 1f);
        at(2350, () -> say(FISH, "哎？小龙女~好久不见！", P_APPROACH, M_HAPPY, M_HAPPY));

        host.trackMove(DRAGON, 2750, 3500, 0.30f, 0.58f, 0.44f, 0.60f, LINE, 0f, 1f);
        host.trackPose(DRAGON, 3300, 5400, LEANP, 10f, 1.6f);    // 靠在一起同步轻晃
        host.trackPose(FISH, 3300, 5400, LEANP, -8f, 1.6f);
        at(2750, () -> {
            host.setFishExpression(F_HAPPY);
            host.setDragonExpression(D_HAPPY);
            host.setDragonMood(M_LOVE);
            host.setFishMood(M_LOVE);
            host.animateInteraction(P_SNUGGLE);
        });
        at(4400, () -> {
            host.nudgeActor(DRAGON, 8, 0, 320);
            host.nudgeActor(FISH, -8, 0, 320);
        });
        end(5700);
    }

    // ================= 2 欢喜斗嘴 =================

    /** 你顶我一下我顶你一下，越顶越近，最后绷不住后仰着笑场。 */
    public void startPlayfulArgument() {
        begin();
        float[] d = host.actorRatio(DRAGON), f = host.actorRatio(FISH);
        at(0, () -> {
            host.setDragonExpression(D_ANGRY);
            host.setFishExpression(F_ANGRY);
            host.setDragonMood(M_ANGER);
            host.setFishMood(M_ANGER);
            lean(DRAGON, 9f, 380);
            lean(FISH, -9f, 380);
        });
        host.trackMove(DRAGON, 0, 800, d[0], d[1], 0.44f, 0.55f, LINE, 0f, 1f);
        host.trackMove(FISH, 0, 850, f[0], f[1], 0.60f, 0.57f, LINE, 0f, 1f);
        at(500, () -> host.animateInteraction(P_TUSSLE));
        at(880, () -> say(DRAGON, "你个吃白饭的蓝色大肥鱼！", P_TUSSLE, M_ANGER, M_ANGER));
        at(1150, () -> {                              // 说完还顶上去一步
            host.nudgeActor(DRAGON, 24, 0, 240);
            lean(DRAGON, 12f, 240);
        });
        at(1650, () -> {
            host.nudgeActor(FISH, -22, 0, 260);       // 顶回去
            lean(FISH, -12f, 260);
            host.animateInteraction(P_TUSSLE2);
        });
        at(1950, () -> {
            say(FISH, "你个臭笨龙！", P_TUSSLE2, M_ANGER, M_ANGER);
            host.nudgeActor(DRAGON, 30, 0, 300);      // 被顶退半步
        });
        at(2450, () -> {
            host.nudgeActor(DRAGON, -18, 0, 260);
            host.nudgeActor(FISH, -10, 0, 260);
            lean(DRAGON, 6f, 300);
        });
        at(3000, () -> {
            host.setDragonExpression(D_SHY);
            host.setFishExpression(F_HAPPY);
            host.setDragonMood(M_SPARKLE);            // 斗完得意一下
            host.setFishMood(M_HAPPY);
            lean(DRAGON, -7f, 400);                   // 绷不住往后仰着笑
            lean(FISH, 6f, 400);
        });
        host.trackPose(FISH, 3450, 3900, BOUNCE, 1f, 1f);   // 得意蹦一下
        end(4800);
    }

    // ================= 3 吃醋 =================

    /** 小龙女扭身走开生闷气，大肥鱼追上去摸头哄，最后她转回身靠一下。 */
    public void startJealous() {
        begin();
        float[] d = host.actorRatio(DRAGON), f = host.actorRatio(FISH);
        at(0, () -> {
            host.setDragonExpression(D_SAD);
            host.setFishExpression(F_CONFUSED);
            host.setDragonMood(M_SAD);
            host.setFishMood(M_SAD);
            lean(DRAGON, -12f, 500);                  // 扭身不看他
        });
        host.trackMove(DRAGON, 0, 900, d[0], d[1], 0.22f, 0.55f, LINE, 0f, 1f);
        at(700, () -> host.animateInteraction(P_SCARED));
        at(1050, () -> say(DRAGON, "你刚才是不是和别的谁说了话？", P_SCARED, M_SAD, M_NONE));
        host.trackMove(FISH, 1250, 2050, f[0], f[1], 0.40f, 0.58f, LINE, 0f, 1f);
        at(2000, () -> {
            host.setFishExpression(F_SHY);
            host.setFishMood(M_LOVE);
            lean(FISH, -8f, 400);
        });
        at(2300, () -> lean(DRAGON, -9f, 400));       // 还别着劲儿
        host.trackPose(FISH, 2650, 3150, BOUNCE, 1f, 1f);   // 摸摸头
        at(2950, () -> say(FISH, "我只和你说话呀~别生气啦！", P_SNUGGLE, M_LOVE, M_SAD));
        host.trackPose(DRAGON, 3600, 4600, LEANP, -6f, 1f);   // 慢慢转回来
        at(4100, () -> {
            host.setDragonExpression(D_HAPPY);
            host.setFishExpression(F_HAPPY);
            host.setDragonMood(M_LOVE);
            host.setFishMood(M_LOVE);
            host.animateInteraction(P_SNUGGLE);
        });
        host.trackMove(DRAGON, 4100, 4900, 0.22f, 0.55f, 0.46f, 0.59f, LINE, 0f, 1f);
        host.trackPose(DRAGON, 4900, 6200, LEANP, 10f, 1.4f);
        end(5900);
    }

    // ================= 4 贴贴 / 拥抱 =================

    /** 真正的"抱一下"：两人各自走到一起 → 一个提到前层挡住 → 头靠头同频晃 → 慢慢松开。 */
    public void startCuddle() {
        begin();
        float[] d = host.actorRatio(DRAGON), f = host.actorRatio(FISH);
        at(0, () -> {
            host.setFishExpression(F_SHY);
            host.setDragonExpression(D_SHY);
            host.setFishMood(M_LOVE);
            host.setDragonMood(M_LOVE);
            host.animateInteraction(P_APPROACH);
        });
        host.trackMove(FISH, 0, 950, f[0], f[1], 0.44f, 0.60f, LINE, 0f, 1f);
        host.trackMove(DRAGON, 150, 1100, d[0], d[1], 0.56f, 0.60f, LINE, 0f, 1f);
        at(1150, () -> {
            raise(DRAGON);                            // 她在前面，看起来是抱住大肥鱼
            host.animateInteraction(P_SNUGGLE);
        });
        host.trackPose(DRAGON, 1200, 5400, LEANP, 11f, 1.6f);   // 头靠头
        host.trackPose(FISH, 1200, 5400, LEANP, -9f, 1.6f);
        at(1500, () -> say(FISH, "好喜欢你~摸摸头~", P_SNUGGLE, M_LOVE, M_LOVE));
        at(2300, () -> {
            host.setDragonExpression(D_HAPPY);
            host.nudgeActor(DRAGON, 0, -12, 420);     // 依偎着蹭一下
        });
        at(2800, () -> host.nudgeActor(DRAGON, 0, 12, 420));
        at(3400, () -> say(DRAGON, "乖~我也喜欢你~", P_SNUGGLE, M_LOVE, M_LOVE));
        at(4500, () -> host.setFishExpression(F_HAPPY));
        host.trackMove(DRAGON, 5400, 6100, 0.56f, 0.60f, 0.48f, 0.58f, LINE, 0f, 1f);   // 松开一点
        end(6600);
    }

    // ================= 5 互相打闹 =================

    /** 追、扑、抓住抖两下、挣脱跳到大范围另一头、再追、折返 —— 位移用足活动范围。 */
    public void startPlayFight() {
        begin();
        float[] d = host.actorRatio(DRAGON), f = host.actorRatio(FISH);
        at(0, () -> {
            host.setDragonExpression(D_SURPRISE);
            host.setFishExpression(F_HAPPY);
            host.setFishMood(M_HAPPY);
            host.setDragonMood(M_HAPPY);
            lean(DRAGON, -10f, 400);
        });
        // 先跑：连跳冲到范围最右边
        host.trackMove(DRAGON, 0, 750, d[0], d[1], 0.88f, 0.52f, HOPP, 0.10f, 2f);
        // 追：蛇形跟在后面
        host.trackMove(FISH, 600, 1350, f[0], f[1], 0.80f, 0.62f, SINE, 0.06f, 3f);
        at(700, () -> host.animateInteraction(P_TUSSLE));
        // 扑上去叠在一起（抛物线）
        host.trackMove(FISH, 1350, 1750, 0.80f, 0.62f, 0.86f, 0.56f, ARC, 0.12f, 1f);
        at(1500, () -> raise(FISH));
        host.trackPose(DRAGON, 1900, 2500, SHAKE, 0.6f, 6f);   // 被抓住：两人一起抖
        host.trackPose(FISH, 1900, 2500, SHAKE, 0.6f, 6f);
        at(1600, () -> say(DRAGON, "放开我啦！", P_TUSSLE, M_ANGER, M_HAPPY));
        at(2300, () -> say(FISH, "哈哈哈，抓到你了！", P_TUSSLE2, M_HAPPY, M_ANGER));
        // 挣脱：抛物线蹦到范围最左边
        host.trackMove(DRAGON, 2750, 3500, 0.86f, 0.56f, 0.06f, 0.55f, ARC, 0.22f, 1f);
        at(2900, () -> {
            raise(DRAGON);
            host.setDragonExpression(D_HAPPY);
            host.setDragonMood(M_SPARKLE);
            host.setFishMood(M_ANGER);
        });
        // 再追
        host.trackMove(FISH, 3500, 4300, 0.86f, 0.56f, 0.34f, 0.64f, SINE, 0.05f, 2f);
        // 折返跑回右边（连跳）
        host.trackMove(DRAGON, 4300, 5100, 0.06f, 0.55f, 0.72f, 0.46f, HOPP, 0.08f, 3f);
        at(4400, () -> host.setDragonExpression(D_SURPRISE));
        host.trackMove(FISH, 5100, 5800, 0.34f, 0.64f, 0.62f, 0.52f, LINE, 0f, 1f);
        at(5900, () -> {                                       // 追不动了，喘口气
            host.setFishExpression(F_HAPPY);
            host.setDragonExpression(D_HAPPY);
            host.setDragonMood(M_HAPPY);
            host.setFishMood(M_HAPPY);
            host.animateInteraction(P_IDLE);
        });
        host.trackPose(DRAGON, 5900, 6600, BOUNCE, 0.6f, 2f);
        host.trackPose(FISH, 6000, 6700, BOUNCE, 0.6f, 2f);
        end(7000);
    }

    // ================= 6 锅盖梗 =================

    /** 大肥鱼临时换成饭盆头（锅盖）并提到前层，连蹦两下得意，小龙女凑近看半天被逗笑。 */
    public void startPotLid() {
        begin();
        oldSkin = host.currentFishSkin();
        float[] d = host.actorRatio(DRAGON), f = host.actorRatio(FISH);
        at(0, () -> {
            host.setFishSkin(0);                     // 0 = 饭盆头（锅盖）
            host.setDragonExpression(D_SURPRISE);
            host.setFishExpression(F_STAND);
            host.setDragonMood(M_SPARKLE);
            host.setFishMood(M_SPARKLE);
            raise(FISH);                             // 戴锅盖的挡在前面
            host.animateInteraction(P_SCARED);
        });
        host.trackMove(DRAGON, 0, 800, d[0], d[1], 0.40f, 0.56f, LINE, 0f, 1f);
        host.trackMove(FISH, 0, 800, f[0], f[1], 0.56f, 0.60f, LINE, 0f, 1f);
        host.trackPose(DRAGON, 800, 1400, LEANP, 10f, 1f);        // 凑近看
        at(950, () -> say(DRAGON, "喂！你的锅盖！", P_SCARED, M_SPARKLE, M_SPARKLE));
        at(1750, () -> {
            host.setFishExpression(F_HAPPY);
            lean(FISH, -6f, 400);                    // 挺起胸膛
            host.animateInteraction(P_PROUD);
        });
        host.trackPose(FISH, 1900, 2500, BOUNCE, 1f, 2f);         // 得意连蹦
        at(2400, () -> say(FISH, "这是我的防御装备！", P_PROUD, M_SPARKLE, M_HAPPY));
        host.trackPose(FISH, 2950, 3450, BOUNCE, 0.8f, 1f);
        at(3600, () -> {
            host.setDragonExpression(D_HAPPY);
            lean(DRAGON, -8f, 500);                  // 被逗得往后仰
        });
        at(4300, () -> host.setFishExpression(F_STAND));
        end(5500);
    }

    // ================= 7 小龙女的魔法梗 =================

    /** 前倾蓄力 → 后仰压扁放出去（紫色星芒＋光圈）→ 大肥鱼被吓得跳退半步 → 惊叹着凑回来 → 得意蹦一下。 */
    public void startMagic() {
        begin();
        float[] d = host.actorRatio(DRAGON), f = host.actorRatio(FISH);
        at(0, () -> {
            host.setDragonExpression(D_HAPPY);
            host.setFishExpression(F_CONFUSED);
            host.setDragonMood(M_MAGIC);
            host.animateInteraction(P_MAGIC);
        });
        host.trackMove(DRAGON, 0, 800, d[0], d[1], 0.42f, 0.58f, LINE, 0f, 1f);
        host.trackMove(FISH, 0, 850, f[0], f[1], 0.66f, 0.60f, LINE, 0f, 1f);
        host.trackPose(DRAGON, 600, 1300, SHAKE, 0.35f, 8f);      // 蓄力发抖
        at(650, () -> lean(DRAGON, 12f, 500));                    // 前倾
        at(1300, () -> say(DRAGON, "看我的小魔法~", P_MAGIC, M_MAGIC, M_NONE));
        at(2100, () -> {                                          // 放出去
            lean(DRAGON, -7f, 260);
            squat(DRAGON, 1.06f, 260);
            host.setDragonMood(M_SPARKLE);
        });
        at(2250, () -> {
            host.animateInteraction(P_SCARED);
            host.setFishExpression(F_SURPRISE);
            host.setFishMood(M_SPARKLE);
        });
        // 被吓得跳退半步（抛物线后退）
        host.trackMove(FISH, 2200, 2750, 0.66f, 0.60f, 0.86f, 0.66f, ARC, 0.12f, 1f);
        host.trackPose(FISH, 2250, 2900, LEANP, -12f, 1f);
        at(3000, () -> say(FISH, "哇！好厉害", P_MAGIC, M_SPARKLE, M_HAPPY));
        host.trackMove(FISH, 3900, 4600, 0.86f, 0.66f, 0.62f, 0.58f, SINE, 0.04f, 2f);   // 凑回来看
        at(3900, () -> {
            host.setFishExpression(F_HAPPY);
            host.setDragonExpression(D_HAPPY);
            host.animateInteraction(P_IDLE);
        });
        host.trackPose(FISH, 4600, 5100, LEANP, 8f, 1f);
        host.trackPose(DRAGON, 4600, 5200, LEANP, 6f, 1f);
        host.trackPose(DRAGON, 5300, 5800, BOUNCE, 0.8f, 1f);     // 得意地蹦一下
        end(6000);
    }

    // ================= 8 互动结束 / 回到待机 =================

    /** 互相道个别，各自走开一点，回到待机。 */
    public void startGoodbye() {
        begin();
        float[] d = host.actorRatio(DRAGON), f = host.actorRatio(FISH);
        at(0, () -> {
            host.setDragonExpression(D_HAPPY);
            host.setFishExpression(F_HAPPY);
            host.setDragonMood(M_LOVE);
            host.setFishMood(M_LOVE);
            host.animateInteraction(P_SNUGGLE);
        });
        host.trackPose(DRAGON, 0, 2200, LEANP, 7f, 1.2f);
        host.trackPose(FISH, 0, 2200, LEANP, -6f, 1.2f);
        at(400, () -> say(DRAGON, "今天玩得好开心呀。", P_SNUGGLE, M_LOVE, M_LOVE));
        // 慢慢分开
        host.trackMove(DRAGON, 1500, 2400, d[0], d[1], Math.max(0f, d[0] - 0.10f), d[1], LINE, 0f, 1f);
        host.trackMove(FISH, 1500, 2400, f[0], f[1], Math.min(1f, f[0] + 0.10f), f[1], LINE, 0f, 1f);
        at(1900, () -> say(FISH, "嗯哼，下次还要一起玩！", P_APPROACH, M_LOVE, M_LOVE));
        at(2900, () -> host.animateInteraction(P_IDLE));
        end(4300, true, false, true);                 // 本来就是结束语，不再补，但表情要回待机
    }

    // ================= 睡觉 =================

    /** 睡觉 1：一起入睡（走到一起，都睡着，Zzz）。 */
    public void startSleepIdle() { sleepScene(0, true); }
    /** 睡觉 2：相互依偎（一个枕在另一个身上）。 */
    public void startSleepCuddle() { sleepScene(1, true); }
    /** 睡觉 3：分开睡（各睡各的，脸朝对方）。 */
    public void startSleepSeparate() { sleepScene(2, false); }
    /** 睡觉 4：梦境（星星 + 泡泡）。 */
    public void startSleepDream() { sleepScene(3, true); }

    private void sleepScene(int which, boolean together) {
        begin();
        float[] d = host.actorRatio(DRAGON), f = host.actorRatio(FISH);
        at(0, () -> {
            host.setDragonExpression(D_SLEEP);
            host.setFishExpression(F_SLEEP);
            if (which == 1) {                    // 依偎：飘个爱心
                host.setDragonMood(M_LOVE);
                host.setFishMood(M_LOVE);
            } else if (which == 3) {             // 梦境：星星 + 泡泡
                host.setDragonMood(M_DREAM);
                host.setFishMood(M_DREAM);
            }
        });
        if (together) {
            host.trackMove(FISH, 0, 1150, f[0], f[1], 0.44f, 0.62f, LINE, 0f, 1f);
            host.trackMove(DRAGON, 200, 1300, d[0], d[1], 0.58f, 0.62f, LINE, 0f, 1f);
            at(1400, () -> {
                raise(which == 1 ? DRAGON : FISH);          // 依偎那段让小龙女压在前面，像把头枕过去
                host.animateInteraction(P_SLEEP);
            });
            // 睡姿：一个往内倾、一个往外倾，叠在云里慢慢呼吸
            host.trackPose(DRAGON, 1400, 6200, LEANP, which == 1 ? 12f : 10f, 1.3f);
            host.trackPose(FISH, 1400, 6200, LEANP, -9f, 1.3f);
        } else {
            host.trackMove(FISH, 0, 1150, f[0], f[1], 0.24f, 0.62f, LINE, 0f, 1f);
            host.trackMove(DRAGON, 200, 1300, d[0], d[1], 0.76f, 0.62f, LINE, 0f, 1f);
            at(1400, () -> host.animateInteraction(P_SLEEP));
            host.trackPose(FISH, 1400, 6200, LEANP, 8f, 1.2f);      // 各睡各的，脸朝对方
            host.trackPose(DRAGON, 1400, 6200, LEANP, -8f, 1.2f);
        }
        at(2000, () -> {
            host.setSleeping(true, true);        // 两只都睡着 → 沉进云里 + Zzz
            host.animateInteraction(P_SLEEP);
        });
        at(2400, () -> say(DRAGON, SLEEP_LINES[which][0], P_SLEEP, sleepMood(which), sleepMood(which)));
        at(3800, () -> say(FISH, SLEEP_LINES[which][1], P_SLEEP2, sleepMood(which), sleepMood(which)));
        at(5200, () -> host.animateInteraction(P_SLEEP2));   // 睡沉了，继续慢慢呼吸
        endQuiet(6400);
    }

    private int sleepMood(int which) {
        return which == 1 ? M_LOVE : (which == 3 ? M_DREAM : M_NONE);
    }

    /**
     * 睡觉待机（趴边满设定分钟触发）。
     *
     * **不挪窗口** —— 触发时她们正扒在屏幕边上，挪走就破坏趴边状态了。
     * 只让睡着的那位出短台词 + Zzz 动效；Zzz 会一直留着，直到被碰醒。
     * 这一段故意不收尾（running 保持 true）：睡着的时候本来就不该再触发别的互动，
     * 被碰一下由 cancel() 收。
     */
    public void startSleepStandby(boolean fishAsleep, boolean dragonAsleep) {
        int pick = random.nextInt(SLEEP_LINES.length);
        if (pick == lastSleep) pick = (pick + 1) % SLEEP_LINES.length;
        lastSleep = pick;
        final int idx = pick;              // lambda 只能引用最终变量
        final boolean dream = idx == 3;
        begin();
        at(0, () -> {
            host.setSleeping(fishAsleep, dragonAsleep);
            if (dragonAsleep) host.setDragonMood(dream ? M_DREAM : M_NONE);
            if (fishAsleep) host.setFishMood(dream ? M_DREAM : M_NONE);
            if (dragonAsleep) host.setDragonExpression(D_SLEEP);
            if (fishAsleep) host.setFishExpression(F_SLEEP);
            host.animateInteraction(P_SLEEP);
        });
        if (dragonAsleep && fishAsleep) {
            at(300, () -> say(DRAGON, SLEEP_LINES[idx][0], P_SLEEP, dream ? M_DREAM : M_NONE, dream ? M_DREAM : M_NONE));
            at(1900, () -> say(FISH, SLEEP_LINES[idx][1], P_SLEEP2, dream ? M_DREAM : M_NONE, dream ? M_DREAM : M_NONE));
        } else if (dragonAsleep) {
            at(300, () -> say(DRAGON, SLEEP_LINES[idx][0], P_SLEEP, dream ? M_DREAM : M_NONE, M_NONE));
        } else {
            at(300, () -> say(FISH, SLEEP_LINES[idx][1], P_SLEEP2, dream ? M_DREAM : M_NONE, M_NONE));
        }
        at(3200, () -> host.animateInteraction(P_SLEEP));   // 继续慢慢呼吸
    }

    // ================= 编排工具 =================

    private void begin() {
        cancel();
        running = true;
        baseMs = System.currentTimeMillis();
        // 新的一段先醒着；睡觉类场景会在自己的时间轴上重新打开 Zzz
        host.setSleeping(false, false);
        host.setSceneMode(true);          // 开演：清轨迹、对齐时间基准、停溜达、窗口留倾斜余量
        host.raiseActor(FISH);            // 默认大肥鱼在前，剧情需要时再换
    }

    /** 在场景开始后 ms 毫秒执行（台词、表情、情绪这些"点事件"用它）。 */
    private void at(long ms, Runnable r) {
        handler.postDelayed(() -> { if (running) r.run(); }, ms);
    }

    /** 说一句台词：气泡 + 动作阶段 + 两个人各自的情绪符号一起上。 */
    private void say(String actor, String text, int phase, int speakerMood, int listenerMood) {
        host.animateInteraction(phase);
        host.showInteractionLine(actor, text);
        host.setDragonMood(DRAGON.equals(actor) ? speakerMood : listenerMood);
        host.setFishMood(FISH.equals(actor) ? speakerMood : listenerMood);
    }

    /** 谁挡在谁前面。 */
    private void raise(String actor) { host.raiseActor(actor); }

    /**
     * 倾斜。角度按角色封顶，并自动配一个缩放 ——
     * 窗口只多留 22% 横向余量，倾得太狠头顶会被自己的窗口裁掉。
     */
    private void lean(String actor, float deg, long durMs) {
        boolean dragon = DRAGON.equals(actor);
        float max = dragon ? 10f : 12f;
        float a = Math.max(-max, Math.min(max, deg));
        float s = Math.abs(a) >= (dragon ? 9f : 10f) ? 0.9f : (Math.abs(a) >= 5f ? 0.96f : 1f);
        if (dragon) { dLean = a; dScale = s; } else { fLean = a; fScale = s; }
        applyPose(actor, durMs);
    }

    /** 压扁 / 拉长（落地、被撞、放魔法的那一下）。 */
    private void squat(String actor, float squash, long durMs) {
        if (DRAGON.equals(actor)) dSquash = squash; else fSquash = squash;
        applyPose(actor, durMs);
    }

    /** 回到原姿态。 */
    private void rest(String actor, long durMs) {
        if (DRAGON.equals(actor)) { dLean = 0f; dSquash = 1f; dLift = 0f; dScale = 1f; }
        else { fLean = 0f; fSquash = 1f; fLift = 0f; fScale = 1f; }
        applyPose(actor, durMs);
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    private void applyPose(String actor, long durMs) {
        boolean dragon = DRAGON.equals(actor);
        host.poseActor(actor, dragon ? dLean : fLean, dragon ? dSquash : fSquash,
                dragon ? dLift : fLift, dragon ? dScale : fScale, durMs);
    }

    /** 结束：恢复动作阶段与临时皮肤（睡觉的 Zzz 不在这里收，要等被碰醒）。 */
    private void end(long ms) { end(ms, true, true, true); }

    private void end(long ms, boolean restoreSkin) { end(ms, restoreSkin, true, true); }

    /** 收尾但不再补结束语（睡觉用：睡颜和睡姿都要留着，别被复位成待机）。 */
    private void endQuiet(long ms) { end(ms, true, false, false); }

    /**
     * 收尾。
     *
     * 文档里说「8. 互动结束 / 回到待机」这两句"每段互动结束时可随机使用"，
     * 所以这里按 45% 的概率把这两句补在剧情后面，然后收起气泡回到待机。
     * 相遇类剧情收尾时把表情和姿态复位，避免吵完架一直挂着生气脸、歪着身子。
     */
    private void end(long ms, boolean restoreSkin, boolean allowCoda, boolean resetFace) {
        if (allowCoda && random.nextInt(100) < 45) {
            at(ms, () -> {
                host.animateInteraction(P_SNUGGLE);
                host.setDragonMood(M_LOVE);
                host.setFishMood(M_LOVE);
                host.showInteractionLine(DRAGON, END_LINES[0]);
            });
            at(ms + 1600, () -> host.showInteractionLine(FISH, END_LINES[1]));
            at(ms + 3400, () -> finish(restoreSkin, resetFace));
            return;
        }
        at(ms, () -> finish(restoreSkin, resetFace));
    }

    private void finish(boolean restoreSkin, boolean resetFace) {
        host.animateInteraction(P_IDLE);
        host.setDragonMood(M_NONE);
        host.setFishMood(M_NONE);
        host.clearBubbles();
        if (resetFace) {
            host.setDragonExpression(D_STAND);
            host.setFishExpression(F_STAND);
            rest(DRAGON, 500);
            rest(FISH, 500);
            host.setSceneMode(false);
            // 收工后各退开一点：相遇类剧情结束时两个人本来就叠在一起，
            // 不挪开的话下一段几乎立刻就会被判定"贴在一起"（用户反映的"分开距离不够"）。
            float[] d = host.actorRatio(DRAGON), f = host.actorRatio(FISH);
            boolean dragonRight = d[0] >= f[0];
            host.moveActor(DRAGON, clamp01(d[0] + (dragonRight ? PART_APART : -PART_APART)), d[1], 700);
            host.moveActor(FISH, clamp01(f[0] + (dragonRight ? -PART_APART : PART_APART)), f[1], 700);
        }
        if (restoreSkin && oldSkin >= 0) {
            host.setFishSkin(oldSkin);
            oldSkin = -1;
        }
        running = false;
    }
}

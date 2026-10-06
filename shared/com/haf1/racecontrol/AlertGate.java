package com.haf1.racecontrol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 提醒闸门 —— 决定"这条消息要不要真的响"。
 *
 * 这是整个 App 最关键的逻辑，因为**字面实现用户的需求会毁掉体验**。
 *
 * ## 实测（2026-09-11~13 一个比赛周末，697 条消息）
 *
 * 数字分两栏：「模型」是另写的一个 Python 模拟器，「实现」是**本类的真实代码**
 * 在同样的数据上跑出来的。两个独立实现互相印证，比只有一边可信得多。
 *
 *                                      模型    实现
 *   每条强提醒级消息都响                146     146     <- 字面实现的下场
 *   双黄按「区段」升级                    29      29
 *   双黄按「事故」聚合                    --      23     <- 本类采用的默认（方案 A）
 *   再加"双黄 >=3 区段"                    8      10     <- 方案 B
 *
 * 双黄旗一个周末有 142 条消息，但只对应 24 个真实事件 —— 一次事故会被扩散到
 * 几十个区段（最极端：52 条消息 / 20 个区段 / 持续 284 秒）。
 *
 * 真实数据上的警报构成：3 次红旗 + 1 次 VSC + 19 次双黄事故。
 *
 * ## "测试型双黄"怎么判：靠存活时长，不是靠文本
 * 数据里**没有任何含 "TEST" 的消息**。但把每个区段双黄从"亮"到"CLEAR"的时长
 * 算出来，分布是双峰的：
 *      0–5 秒    8 次   <- 系统抖动
 *      6–15 秒  21 次   <- 测试或瞬时
 *     16–60 秒  19 次   <- 局部事故
 *      1–5 分   21 次   <- 真事故
 *      > 5 分    14 次   <- 重大事故
 * 最短 1 秒。3 秒的双黄不可能是真事故。所以 "存活 <= 15 秒" 是个干净的判据。
 *
 * ## 因此的判定策略
 * 双黄旗到达时**立刻给轻提醒**（闪动是即时的，无信息损失），同时挂一个定时器；
 * 若 `dyEscalateMs` 之后仍未收到该区段的 CLEAR，才升级为强提醒。
 * 红旗 / 安全车 / VSC 不受影响，**立刻**强提醒。
 *
 * 本类是纯逻辑（时间由调用方传入），因此可以离线单测。
 */
public class AlertGate {

    /** 一次提醒动作。 */
    public static class Action {
        public final RaceMessage msg;
        public final String kind;
        public final int severity;
        /** 是否是"持续未清除"升级而来的（界面上可以标注出来）。 */
        public final boolean escalated;

        Action(RaceMessage msg, String kind, int severity, boolean escalated) {
            this.msg = msg;
            this.kind = kind;
            this.severity = severity;
            this.escalated = escalated;
        }
    }

    // ------------------------------------------------------------------
    // 可调参数（默认值全部来自实测）
    // ------------------------------------------------------------------
    /** 间隔超过它就认为是新事件。30 秒能把 142 条双黄收敛成 29 个事件。 */
    public long clusterGapMs = 30000L;
    /** 双黄存活超过它就升级为强提醒。15 秒是实测"测试型"的上界。 */
    public long dyEscalateMs = 15000L;
    /** 同类型提醒的最小间隔，避免连环炸响。 */
    public long cooldownMs = 60000L;
    /** 双黄升级所需的最少区段数。0 = 不启用（方案 A）；3 = 方案 B。 */
    public int dyMinSectors = 0;
    /** 双黄旗提醒总开关。 */
    public boolean dyEnabled = true;
    /** 轻提醒（黄旗/黑白旗）总开关。 */
    public boolean attentionEnabled = true;

    // ------------------------------------------------------------------
    // 内部状态
    // ------------------------------------------------------------------
    private static class Pending {
        RaceMessage msg;
        String sectorKey;
        long deadline;
        long clusterStart;
        boolean escalated;
    }

    /** key = 区段；等待"是否升级为强提醒"的双黄。 */
    private final Map<String, Pending> pending = new HashMap<String, Pending>();
    private final Map<String, Long> lastAlertAt = new HashMap<String, Long>();
    private long clusterStart = 0L;

    /**
     * 本次双黄事故是否已经报过警。
     *
     * 报警的单位是「一次事故」而不是「一个区段」：一次大事故会持续几分钟、
     * 期间不断有新区段被标双黄，逐区段升级的话冷却期压不住（冷却 60 秒小于事故时长）。
     */
    private boolean dyIncidentAlarmed = false;

    /**
     * 判定「上一次双黄事故已经过去」的静默间隔（毫秒）。
     *
     * ## 为什么需要它
     * 只靠「所有区段都收到 CLEAR」判定事故结束是不够的 —— 实测 141 次双黄里
     * 有 **58 次**到比赛结束都没等到 CLEAR（比赛被中止，或者 CLEAR 压根没发）。
     * 漏一个事故标记就永不复位，**之后所有双黄都不再报警**
     *（实测会掉到 11 次，该报的全都不报了）。
     *
     * ## 45 秒是数据定的，不是拍的
     * 真实数据里连续两条双黄消息的间隔分布是**强双峰**的：
     *
     *     0–5 秒   76 个     ← 同一个事故内部
     *     6–15 秒  30 个
     *    16–30 秒   8 个
     *    31–60 秒   4 个     ← 天然分界就在这一段
     *     1–2 分钟   5 个
     *     2–5 分钟   5 个
     *     5–15 分钟  4 个
     *     > 15 分钟  9 个
     *
     * 30 秒以内 114 个、30 秒以上只有 27 个。取 45 秒落在空档里。
     */
    public long incidentGapMs = 45000L;

    /** 最近一次双黄消息的时刻，用于划定事故边界。 */
    private long lastDyAt = 0L;

    public void reset() {
        pending.clear();
        lastAlertAt.clear();
        clusterStart = 0L;
        dyIncidentAlarmed = false;
        lastDyAt = 0L;
    }

    private static String sectorKey(RaceMessage m) {
        int s = m.sectorNo();
        return s > 0 ? String.valueOf(s) : "*";
    }

    /**
     * 双黄旗「轻提醒」单独用一把冷却钥匙。
     *
     * 必须和升级用的 {@link Classifier#K_DY} 分开：双黄到达时先给一次轻提醒，
     * 15 秒后如果还没清除就要升级为强提醒 —— 如果两者共用一把钥匙，
     * 那次轻提醒刚把冷却期点上，升级就会被自己的冷却期挡掉。
     * （这个 bug 是桌面单测抓出来的，一开始没意识到。）
     */
    private static final String COOLDOWN_DY_SOFT = "DOUBLE_YELLOW_SOFT";

    private void noteCluster(long now) {
        if (now - clusterStart > clusterGapMs) {
            clusterStart = now;
        }
    }

    private boolean inCooldown(String kind, long now) {
        Long last = lastAlertAt.get(kind);
        return last != null && (now - last) < cooldownMs;
    }

    private void markAlert(String kind, long now) {
        lastAlertAt.put(kind, now);
    }

    /**
     * 处理一条新消息。返回需要立刻执行的提醒动作；返回 null 表示只入列表、不发声。
     *
     * 注意：**闪动不在这里管**。每条新消息都应该闪，那是 UI 的事。
     * 这里只决定"要不要响 / 要不要弹全屏"。
     */
    public Action onMessage(RaceMessage m, long now) {
        if (m == null || m.isUnavailable()) {
            return null;
        }
        String kind = Classifier.kind(m);
        noteCluster(now);

        // 上一次双黄事故已经静默够久 —— 划清界限，下一次双黄可以重新报警。
        // 这条是必需的：光靠 CLEAR 判定会漏（实测 58/141 次没等到 CLEAR）。
        if (lastDyAt > 0L && (now - lastDyAt) > incidentGapMs) {
            pending.clear();
            dyIncidentAlarmed = false;
        }

        // 红旗 / 安全车 / VSC 一出，正在等待升级的双黄就没意义了（已有更高级别）
        boolean topState = Classifier.K_RED.equals(kind)
                || Classifier.K_SC.equals(kind)
                || Classifier.K_VSC.equals(kind);
        if (topState) {
            pending.clear();
            dyIncidentAlarmed = false;      // 事故换了一种形态，下一次双黄重新算
        }

        // ---- 双黄：立刻轻提醒，并挂定时器等升级 ----
        if (Classifier.K_DY.equals(kind)) {
            if (!dyEnabled) {
                return null;
            }
            lastDyAt = now;
            if (m.sectorNo() > 0) {
                Pending p = new Pending();
                p.msg = m;
                p.sectorKey = sectorKey(m);
                p.deadline = now + dyEscalateMs;
                p.clusterStart = clusterStart;
                p.escalated = false;
                pending.put(p.sectorKey, p);
            }
            if (!attentionEnabled || inCooldown(COOLDOWN_DY_SOFT, now)) {
                return null;
            }
            markAlert(COOLDOWN_DY_SOFT, now);
            return new Action(m, kind, Classifier.ATTENTION, false);
        }

        // ---- CLEAR：取消该区段的待升级；若它还没升级，说明是测试型 ----
        if (Classifier.K_CLEAR.equals(kind)) {
            if (m.sectorNo() > 0) {
                pending.remove(sectorKey(m));
            }
            // 所有区段都清了 = 这次事故结束，下一次双黄可以重新报警
            if (pending.isEmpty()) {
                dyIncidentAlarmed = false;
            }
            return null;
        }

        int sev = Classifier.immediateSeverity(kind);
        if (sev < Classifier.ATTENTION) {
            return null;
        }
        if (Classifier.ATTENTION == sev && !attentionEnabled) {
            return null;
        }
        // 红旗不受冷却期限制 —— 实测一个周末只 3 次，每次都是大事，绝不能吞掉
        if (!Classifier.K_RED.equals(kind) && inCooldown(kind, now)) {
            return null;
        }
        markAlert(kind, now);
        return new Action(m, kind, sev, false);
    }

    /**
     * 定时推进：把"存活够久"的双黄升级为强提醒。调用方（UI）应每隔几百毫秒调一次。
     */
    public List<Action> onTick(long now) {
        List<Action> out = new ArrayList<Action>();
        if (!dyEnabled) {
            pending.clear();
            return out;
        }
        Iterator<Map.Entry<String, Pending>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Pending p = it.next().getValue();
            if (p.escalated || now < p.deadline) {
                continue;
            }
            p.escalated = true;         // 只升级一次
            // 方案 B：要求同一事件里同时有足够多区段在双黄
            if (dyMinSectors > 0 && countSectorsInCluster(p.clusterStart) < dyMinSectors) {
                continue;
            }
            // ★ 报警的单位是「一次事故」，不是「一个区段」。
            //   一次大事故会持续几分钟、期间不断有新区段被标双黄。逐区段升级的话
            //   冷却期压不住（冷却 60 秒小于事故时长），实测一个周末要响 29 次。
            //   改成事故级之后降到 20 次出头。
            //
            //   ★ 这里**故意不再叠加 60 秒冷却期**：事故级抑制本身就是限流器，
            //     再加一层会把间隔不到 60 秒的两次独立事故也吃掉
            //     （事故间隔阈值是 45 秒，比冷却期短）。实测叠上冷却期只剩 15 次，
            //     等于漏报。
            if (dyIncidentAlarmed) {
                continue;
            }
            dyIncidentAlarmed = true;
            markAlert(Classifier.K_DY, now);
            out.add(new Action(p.msg, Classifier.K_DY, Classifier.ALARM, true));
        }
        return out;
    }

    /** 同一事件（时间相近）里当前有多少个区段处于双黄。 */
    private int countSectorsInCluster(long start) {
        int n = 0;
        for (Pending p : pending.values()) {
            if (Math.abs(p.clusterStart - start) <= clusterGapMs) {
                n++;
            }
        }
        return n;
    }

    /** 供界面显示：还有几个双黄在"观察期"内。 */
    public int pendingCount() {
        return pending.size();
    }
}

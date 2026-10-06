package com.haf1.racecontrol;

import java.util.Locale;

/**
 * 旗语分类器 —— **纯函数，没有状态**，所以能离线单测。
 *
 * 分类**优先用结构化字段**（flag / category），文本匹配只作为属性缺失时的兜底。
 * 这不是洁癖：实测发现照抄上游文档的取值表会写出两个永远不会触发的判断。
 *
 * ⚠️ 坑 A：安全车 / VSC **没有 flag**。
 *    上游作者自己的蓝图把 VSC / SC 列为 flag 取值，真实数据里是：
 *        flag=""  category="SafetyCar"  message="VSC DEPLOYED"
 *    按 flag 判会永远不触发。
 *
 * ⚠️ 坑 B：旗语是 "BLACK AND WHITE" 一个值，不是 "BLACK" / "WHITE" 两个。
 *    蓝图下拉框写的是后者，照抄会漏判。
 */
public final class Classifier {

    // ---- 提醒等级 ----
    /** 不提醒，只入列表。 */
    public static final int SILENT = 0;
    /** 只闪动 + 配色（普通消息）。 */
    public static final int FLASH = 1;
    /** 单次提示音 + 一短震，不弹全屏。 */
    public static final int ATTENTION = 2;
    /** 全屏横幅 + 循环警报音 + 震动 + 亮屏。 */
    public static final int ALARM = 3;

    // ---- 类型 ----
    public static final String K_RED = "RED";
    public static final String K_SC = "SC";
    public static final String K_VSC = "VSC";
    public static final String K_SC_END = "SC_END";
    public static final String K_DY = "DOUBLE_YELLOW";
    public static final String K_YELLOW = "YELLOW";
    public static final String K_BW = "BLACK_AND_WHITE";
    public static final String K_CLEAR = "CLEAR";
    public static final String K_GREEN = "GREEN";
    public static final String K_CHEQUERED = "CHEQUERED";
    public static final String K_BLUE = "BLUE";
    public static final String K_PENALTY = "PENALTY";
    public static final String K_OTHER = "OTHER";

    private Classifier() {
    }

    private static String up(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.US);
    }

    /**
     * 判定消息类型。只看结构化字段，属性缺失时才退回文本。
     */
    public static String kind(RaceMessage m) {
        if (m == null) {
            return K_OTHER;
        }
        String flag = up(m.flag);
        String cat = up(m.category);
        String msg = up(m.text());

        // --- 红旗 ---
        if ("RED".equals(flag) || msg.startsWith("RED FLAG")) {
            return K_RED;
        }

        // --- 坑 A：安全车 / VSC 只能靠 category ---
        if ("SAFETYCAR".equals(cat)) {
            if (msg.indexOf("ENDING") >= 0 || msg.indexOf("IN THIS LAP") >= 0) {
                return K_SC_END;
            }
            if (msg.indexOf("VSC") >= 0 || msg.indexOf("VIRTUAL") >= 0) {
                return K_VSC;
            }
            return K_SC;
        }
        // category 也缺失时，靠文本兜底（顺序要紧：先判 VIRTUAL，否则会被 SAFETY CAR 抢先）
        if (msg.indexOf("DEPLOY") >= 0) {
            if (msg.indexOf("VSC") >= 0 || msg.indexOf("VIRTUAL SAFETY CAR") >= 0) {
                return K_VSC;
            }
            if (msg.indexOf("SAFETY CAR") >= 0) {
                return K_SC;
            }
        }
        if (msg.indexOf("SAFETY CAR IN THIS LAP") >= 0 || msg.indexOf("VSC ENDING") >= 0) {
            return K_SC_END;
        }

        // --- 旗语 ---
        if ("DOUBLE YELLOW".equals(flag)) {
            return K_DY;
        }
        // 坑 B：正确取值是一个词 "BLACK AND WHITE"；同时兼容文档里那两个错的
        if ("BLACK AND WHITE".equals(flag) || "BLACK".equals(flag) || "WHITE".equals(flag)) {
            return K_BW;
        }
        if ("YELLOW".equals(flag)) {
            return K_YELLOW;
        }
        if ("CLEAR".equals(flag)) {
            return K_CLEAR;
        }
        if ("GREEN".equals(flag)) {
            return K_GREEN;
        }
        if ("CHEQUERED".equals(flag)) {
            return K_CHEQUERED;
        }
        if ("BLUE".equals(flag)) {
            return K_BLUE;
        }

        // --- 无 flag 时的文本兜底 ---
        if (msg.indexOf("CHEQUERED") >= 0) {
            return K_CHEQUERED;
        }
        if (msg.indexOf("PENALTY") >= 0) {
            return K_PENALTY;
        }
        return K_OTHER;
    }

    /**
     * 该类型的**即时**提醒等级。
     *
     * 注意双黄旗这里给的是 ATTENTION 而不是 ALARM：实测一个周末有 142 条双黄消息，
     * 其中 29 次是"存活 ≤15 秒"的系统测试/抖动（最短 1 秒，3 秒的双黄不可能是真事故）。
     * 判定"是不是测试"必须等一段时间，所以双黄先给轻提醒，
     * 由 {@link AlertGate} 在存活超过阈值后升级为 ALARM。
     * 这样既没有信息损失（闪动是即时的），又不会为测试消息炸响。
     */
    public static int immediateSeverity(String kind) {
        if (K_RED.equals(kind) || K_SC.equals(kind) || K_VSC.equals(kind)) {
            return ALARM;
        }
        if (K_DY.equals(kind) || K_YELLOW.equals(kind) || K_BW.equals(kind)
                || K_SC_END.equals(kind) || K_PENALTY.equals(kind)) {
            return ATTENTION;
        }
        return FLASH;
    }

    /** 是否会因为"持续未清除"而升级（只有双黄旗）。 */
    public static boolean canEscalate(String kind) {
        return K_DY.equals(kind);
    }

    /** 中文名，用于界面显示。 */
    public static String label(String kind) {
        if (K_RED.equals(kind)) {
            return "红旗";
        }
        if (K_SC.equals(kind)) {
            return "安全车";
        }
        if (K_VSC.equals(kind)) {
            return "虚拟安全车";
        }
        if (K_SC_END.equals(kind)) {
            return "安全车结束";
        }
        if (K_DY.equals(kind)) {
            return "双黄旗";
        }
        if (K_YELLOW.equals(kind)) {
            return "黄旗";
        }
        if (K_BW.equals(kind)) {
            return "黑白旗";
        }
        if (K_CLEAR.equals(kind)) {
            return "解除";
        }
        if (K_GREEN.equals(kind)) {
            return "绿旗";
        }
        if (K_CHEQUERED.equals(kind)) {
            return "格子旗";
        }
        if (K_BLUE.equals(kind)) {
            return "蓝旗";
        }
        if (K_PENALTY.equals(kind)) {
            return "判罚";
        }
        return "其它";
    }

    /**
     * 行背景色（ARGB）。按类型分档，让列表一眼能扫出重点。
     *
     * 注意格子旗**不靠颜色**：它用的是 {@link #isCheckered} 让界面画棋盘格。
     * 这里返回的只是闪动动画的基准色，所以给个中性白。
     */
    public static int color(String kind) {
        if (K_RED.equals(kind)) {
            return 0xFFFFCDD2;      // 红
        }
        if (K_SC.equals(kind)) {
            return 0xFFFFE0B2;      // 橙
        }
        if (K_VSC.equals(kind)) {
            return 0xFFFFF9C4;      // 黄
        }
        if (K_DY.equals(kind)) {
            return 0xFFFFF59D;      // 淡黄
        }
        if (K_YELLOW.equals(kind)) {
            return 0xFFFFFDE7;      // 极淡黄
        }
        if (K_BW.equals(kind)) {
            return 0xFFEEEEEE;      // 灰
        }
        if (K_CHEQUERED.equals(kind)) {
            return 0xFFFFFFFF;      // 白（真正的背景是棋盘格，见 isCheckered）
        }
        if (K_GREEN.equals(kind)) {
            return 0xFFE8F5E9;      // 淡绿
        }
        if (K_BLUE.equals(kind)) {
            return 0xFFE3F2FD;      // 淡蓝（蓝旗就该是蓝的；这里一度写成淡紫，用户指出后改回）
        }
        return 0xFFFFFFFF;
    }

    /** 左侧色条颜色，比背景色饱和。 */
    public static int barColor(String kind) {
        if (K_RED.equals(kind)) {
            return 0xFFD32F2F;
        }
        if (K_SC.equals(kind)) {
            return 0xFFF57C00;
        }
        if (K_VSC.equals(kind)) {
            return 0xFFFBC02D;
        }
        if (K_DY.equals(kind)) {
            return 0xFFF9A825;
        }
        if (K_YELLOW.equals(kind)) {
            return 0xFFFFEB3B;
        }
        if (K_BW.equals(kind)) {
            return 0xFF616161;
        }
        if (K_CHEQUERED.equals(kind)) {
            return 0xFF546E7A;      // 中性深灰（原先是蓝 0xFF0288D1，跟蓝旗撞了；用户指出后改）
        }
        if (K_GREEN.equals(kind)) {
            return 0xFF388E3C;
        }
        if (K_BLUE.equals(kind)) {
            return 0xFF1976D2;      // 蓝（原先是紫 0xFF7B1FA2，用户指出后改回）
        }
        return 0xFFBDBDBD;
    }

    /**
     * 这一类的行背景是不是要画成**灰白棋盘格**。
     *
     * 只有格子旗。字面上就是黑白方格，画出来一眼认得，比给它一个单色准得多
     * （而且单色一定会跟别的旗撞车 —— 之前就撞成了蓝色）。
     */
    public static boolean isCheckered(String kind) {
        return K_CHEQUERED.equals(kind);
    }

    /** 棋盘格的浅色格。 */
    public static int checkerLight() {
        return 0xFFFFFFFF;
    }

    /** 棋盘格的深色格。 */
    public static int checkerDark() {
        return 0xFFB0BEC5;
    }
}

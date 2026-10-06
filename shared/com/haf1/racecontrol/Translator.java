package com.haf1.racecontrol;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把赛事控制的英文消息翻成一句中文简述。
 *
 * ## 为什么要它
 * 仲裁消息长这样：
 *
 *     FIA STEWARDS: TURN 1 INCIDENT INVOLVING CAR 5 (BOR) NOTED -
 *     FAILING TO FOLLOW RACE DIRECTORS INSTRUCTIONS – ESCAPE ROAD INSTRUCTIONS (14:27:29)
 *
 * 比赛时根本没空读。翻成「1 号弯事故（博托莱托）：已记录 —— 未遵守赛会指令（逃生通道）」
 * 一眼就懂。尤其是**判罚**：谁被罚、罚多少、为什么，这三件事必须能秒读。
 *
 * ## 规则是从真实数据里反推的，不是编的
 * 规则覆盖的模板来自 2026-09-11~13 一个比赛周末的 697 条真实消息，按出现频次排：
 *   超赛道限制删圈速  173 条 / 46 种模板
 *   蓝旗               79 条 / 11 种
 *   事故已记录         55 条 / 51 种
 *   仲裁裁决           35 条 / 33 种
 *   黑白旗              5 条
 *   明确判罚            3 条
 *
 * 第二个周末（2026-09-24~26，586 条）又暴露了几个缺口，已补上：
 *   删圈速的第二种原因 DOUBLE YELLOW     23 条
 *   `AFTER THE RACE` 的仲裁决定           4 条
 *   安全车 / 排位赛期间的操作指令        6 种 / 8 条
 *
 * 翻不出来就返回 null —— **宁可显示原文，也不要瞎猜**。
 *
 * 纯函数、不碰 Android API，所以能离线单测。
 */
public final class Translator {

    private Translator() {
    }

    /**
     * 车号 + 车手缩写。**不能写成 `CAR\s+(\d+)\s*\(([A-Z]{3})\)`**：
     * 真实数据里多车事故是这么写的 ——
     *   FIA STEWARDS: TURN 3 INCIDENT INVOLVING CARS 43 (COL) AND 87 (BEA) ...
     * 第二辆（87）前面压根没有 "CAR"，而且 "CARS" 后面是空格再跟数字，
     * 严格要求 "CAR " 会漏掉一大半。
     * 所以直接匹配裸的「数字 (三字母)」。
     */
    /** 译文里用得很多的中文破折号（两个 em dash）。
     * 不直接写字面量是为了避免源码编码差异带来的麦当当。 */
    private static final String DASH = "\u2014\u2014";

    private static final Pattern CAR = Pattern.compile("(\\d{1,2})\\s*\\(([A-Z]{3})\\)");
    private static final Pattern TURN = Pattern.compile("TURN\\s+(\\d+)");
    private static final Pattern LAP = Pattern.compile("LAP\\s+(\\d+)");
    private static final Pattern SECTOR = Pattern.compile("SECTOR\\s+(\\d+)");
    private static final Pattern SECONDS = Pattern.compile("(\\d+)\\s*SECOND");
    /** 排位赛阶段。真实数据里有 `FIA STEWARDS: Q1 INCIDENT INVOLVING CARS 81 (PIA), ...` —— */
    private static final Pattern PHASE = Pattern.compile("\\b(Q[123])\\b");
    // ★ 真实数据里数字和 % 中间**有空格**（`IS 0 %`），
    //   原来的 `(\\d+)%` 要求紧贴，13 条雨情消息全部漏掉。
    private static final Pattern RAIN = Pattern.compile("RISK OF RAIN FOR (.+?) IS (\\d+)\\s*%");
    /** `Q1 WILL START AT 16:04` / `Q2 ...` / `Q3 ...`。 */
    private static final Pattern WILL_START = Pattern.compile("\\b(Q[123])\\b WILL START AT (\\d{1,2}:\\d{2})");
    /** DR1S 分区：`DRS ENABLED IN ZONE 1`。 */
    private static final Pattern DRS_ZONE = Pattern.compile(
            "DRS (?:ENABLED|DISABLED) IN ZONE (\\d+)");
    /** `AIR TEMPERATURE 1 HOUR BEFORE P1 = 28.3 DEGREES`。 */
    private static final Pattern AIR_TEMP = Pattern.compile(
            "AIR TEMPERATURE (.+?) = ([\\d.]+) DEGREES");
    /** `SESSION TEMPERATURES: AIR = 20, TRACK = 38`。 */
    private static final Pattern SESSION_TEMPS = Pattern.compile(
            "SESSION TEMPERATURES:\\s*AIR\\s*=\\s*(-?[\\d.]+)\\s*,\\s*TRACK\\s*=\\s*(-?[\\d.]+)");
    /** `SESSION WILL END AT 20:00`。 */
    private static final Pattern SESSION_END_AT = Pattern.compile(
            "SESSION WILL END AT\\s*([\\d:]+)");
    /** `CAR 30 (LAW) TIME 1:37.515 WILL BE REINSTATED`。 */
    private static final Pattern REINSTATE_TIME = Pattern.compile(
            "TIME\\s+([\\d:.]+)\\s+WILL BE REINSTATED");
    /** `CAR 55 (SAI) LAP 1 WILL BE REINSTATED`。 */
    private static final Pattern REINSTATE_LAP = Pattern.compile(
            "LAP\\s+(\\d+)\\s+WILL BE REINSTATED");
    /** `FREE PRACTICE 1 WILL BE EXTENDED BY A FURTHER 15 MINUTES`。 */
    private static final Pattern EXTENDED = Pattern.compile(
            "(FREE PRACTICE \\d) WILL BE EXTENDED BY (?:A FURTHER )?(\\d+) MINUTES");
    /** `Q1 WILL RESUME AT 16:19` / `RACE WILL START AT 16:33` / `FORMATION LAP WILL START AT 15:40`。 */
    private static final Pattern WILL_START_ANY = Pattern.compile(
            "^(.+?) WILL (RESUME|START) AT (\\d{1,2}:\\d{2})");
    /** `ESTIMATED TIME OF SESSION START - 12:45`。 */
    private static final Pattern ESTIMATED = Pattern.compile(
            "ESTIMATED TIME OF (.+?) (START|RESUMPTION) - (\\d{1,2}:\\d{2})");
    /** `RESUMPTION ORDER: 1, 12, 63` / `PUSH CARS TO FRONT IN ORDER: 41, 23`。 */
    private static final Pattern ORDER = Pattern.compile(
            "(?:RESUMPTION ORDER|PUSH CARS TO FRONT IN ORDER): (.+)$");
    /** `LAP TIME OF CAR 87 (BEA) - UNDER REVIEW`。 */
    private static final Pattern LAP_REVIEW = Pattern.compile(
            "LAP TIME OF CAR (\\d+)\\s*\\(([A-Z]{3})\\)\\s*-\\s*UNDER REVIEW");

    /** 三位缩写 -> 中文姓氏。用数据里实际出现过的车手，查不到就退回缩写。 */
    private static final Map<String, String> DRIVERS = new HashMap<String, String>();

    static {
        DRIVERS.put("VER", "维斯塔潘");
        DRIVERS.put("NOR", "诺里斯");
        DRIVERS.put("PIA", "皮亚");
        DRIVERS.put("LEC", "勒克莱尔");
        DRIVERS.put("HAM", "汉密尔顿");
        DRIVERS.put("RUS", "拉塞尔");
        DRIVERS.put("ALO", "阿隆索");
        DRIVERS.put("SAI", "塞恩斯");
        DRIVERS.put("GAS", "加斯利");
        DRIVERS.put("TSU", "角田");
        DRIVERS.put("ALB", "阿尔本");
        DRIVERS.put("STR", "斯特罗尔");
        DRIVERS.put("BOT", "博塔斯");
        DRIVERS.put("HUL", "霍肯伯格");
        DRIVERS.put("OCO", "奥康");
        DRIVERS.put("BEA", "比尔曼");
        DRIVERS.put("COL", "科拉平托");
        DRIVERS.put("ANT", "安东内利");
        DRIVERS.put("LIN", "林布拉德");
        DRIVERS.put("BOR", "博托莱托");
        DRIVERS.put("PER", "佩雷兹");
        DRIVERS.put("MAG", "马格努森");
        DRIVERS.put("RIC", "里卡多");
        DRIVERS.put("ZHO", "周冠宇");
        DRIVERS.put("SAR", "萨金特");
        DRIVERS.put("DEV", "德弗里斯");
        DRIVERS.put("LAW", "劳森");
        DRIVERS.put("DOO", "杜汉");
        DRIVERS.put("HAD", "哈贾尔");
    }

    /**
     * 处置原因 -> 中文。**按长度降序匹配**，长串必须排在短串前面，
     * 否则短串会抢先吃掉长串的前半截。
     *
     * ★ 复合原因（真实数据里就有的形状，别当成罕见情况）：
     *     FAILING TO FOLLOW RACE DIRECTORS INSTRUCTIONS – ESCAPE ROAD INSTRUCTIONS
     *     FAILING TO FOLLOW RACE DIRECTORS INSTRUCTIONS - MAXIMUM DELTA TIME
     *   连字符后面那半截是**对前半截的限定**（没遵守的是哪一条指令），
     *   所以整条翻成「未遵守赛会指令（逃生通道）」，而不是把后半截丢掉。
     *   只写这几条显式条目就够了 —— 万一将来出现新措辞，
     *   匹配到前半截仍是**正确但不完整**的译文，不会翻错。
     */
    private static final String[][] REASONS = {
            {"FAILING TO FOLLOW RACE DIRECTORS INSTRUCTIONS – ESCAPE ROAD INSTRUCTIONS", "未遵守赛会指令（逃生通道）"},
            {"FAILING TO FOLLOW RACE DIRECTORS INSTRUCTIONS - ESCAPE ROAD INSTRUCTIONS", "未遵守赛会指令（逃生通道）"},
            {"FAILING TO FOLLOW RACE DIRECTORS INSTRUCTIONS – MAXIMUM DELTA TIME", "未遵守赛会指令（超出最大圈速差）"},
            {"FAILING TO FOLLOW RACE DIRECTORS INSTRUCTIONS - MAXIMUM DELTA TIME", "未遵守赛会指令（超出最大圈速差）"},
            {"FAILING TO FOLLOW RACE DIRECTORS INSTRUCTIONS – PRACTICE START INFRINGEMENT", "未遵守赛会指令（起步练习违规）"},
            {"FAILING TO FOLLOW RACE DIRECTORS INSTRUCTIONS - PRACTICE START INFRINGEMENT", "未遵守赛会指令（起步练习违规）"},
            {"FAILING TO FOLLOW RACE DIRECTORS INSTRUCTIONS", "未遵守赛会指令"},
            {"LEAVING THE TRACK AND GAINING AN ADVANTAGE", "离开赛道并获得优势"},
            {"FORCING ANOTHER DRIVER OFF THE TRACK", "把对手逼出赛道"},
            {"SPEEDING IN THE PIT LANE", "维修区超速"},
            {"CAUSING A COLLISION", "造成碰撞"},
            {"REJOINING UNSAFELY", "不安全地回归赛道"},
            {"MOVING UNDER BRAKING", "制动中变线"},
            {"MAXIMUM DELTA TIME", "超出最大圈速差"},
            {"YELLOW FLAG INFRINGEMENT", "黄旗违规"},
            {"UNSAFE RELEASE", "不安全放车"},
            {"IMPEDING", "阻挡他人"},
            {"TRACK LIMITS", "超出赛道限制"},
            {"SAFETY CAR PROCEDURE INFRINGEMENT", "安全车程序违规"},
            {"STARTING PROCEDURE INFRINGEMENT", "起步程序违规"},
            {"DRIVING ERRATICALLY OR IN A POTENTIALLY DANGEROUS MANNER", "危险驾驶"},
            {"DRIVING ERRATICALLY", "危险驾驶"},
            {"UNSAFE CONDITION", "不安全状况"},
            {"PRACTICE START INFRINGEMENT", "练习起步违规"},
    };

    /**
     * 删圈速的原因。加了双黄旗后不再是单一条件，所以单独列表。
     *
     * ★ 双黄旗版本原来一条都翻不出来：判据写死了 `TRACK LIMITS`，
     *   而双黄旗下圈速作废用的是 `DOUBLE YELLOW`。
     *   2026-09-24~26 那个周末 23 条，占删圈速通报的两成。
     */
    private static final String[][] DELETE_REASONS = {
            {"TRACK LIMITS", "超出赛道限制"},
            {"DOUBLE YELLOW", "双黄旗"},
    };

    /**
     * 翻成中文简述。翻不出来返回 null（调用方应显示原文）。
     */
    public static String gloss(String text) {
        if (text == null) {
            return null;
        }
        String raw = text.trim();
        if (raw.length() == 0) {
            return null;
        }
        String up = raw.toUpperCase(Locale.US);

        // 集成自带的自测消息：`... - RACE CONTROL TEST`。
        // 它没有车号，落到下面的兜底会变成「某车手」—— 明明没有车手。
        // 统一标成测试（用户定的译法）。
        if (up.indexOf("RACE CONTROL TEST") >= 0) {
            return "赛会判罚测试";
        }

        // `CORRECTION: ...` / `CORRECTION - ...` / `CORRECTION:: ...`：
        // 把前缀剥掉后**递归译**，前面加「更正：」。
        if (up.startsWith("CORRECTION")) {
            String rest = up.replaceFirst("^CORRECTION[:\\-]*\\s*", "");
            String inner = gloss(rest);
            return inner == null ? "更正消息：" + rest
                    : "更正：" + inner;
        }
        if (up.startsWith("TEST: ")) {
            String inner = gloss(up.substring(6));
            return inner == null ? null : "测试：" + inner;
        }

        String g = reprimand(up);
        if (g != null) {
            return g;
        }
        g = penalty(up);
        if (g != null) {
            return g;
        }
        g = stewards(up);
        if (g != null) {
            return g;
        }
        g = noted(up);
        if (g != null) {
            return g;
        }
        g = lapDeleted(up);
        if (g != null) {
            return g;
        }
        g = blueFlag(up);
        if (g != null) {
            return g;
        }
        g = blackAndWhite(up);
        if (g != null) {
            return g;
        }
        return other(up);
    }

    // ------------------------------------------------------------------
    // 各类模板
    // ------------------------------------------------------------------

    /**
     * 明确判罚。真实模板：
     *   FIA STEWARDS: 5 SECOND TIME PENALTY FOR CAR 55 (SAI) (15:23:42)
     *   FIA STEWARDS: 5 SECOND TIME PENALTY FOR CAR 10 (GAS) - SPEEDING IN THE PIT LANE (16:42:11)
     *   FIA STEWARDS: PENALTY SERVED - 5 SECOND TIME PENALTY FOR CAR 55 (SAI) (15:49:21)
     */
    private static String penalty(String up) {
        if (up.indexOf("PENALTY") < 0) {
            return null;
        }
        boolean served = up.indexOf("PENALTY SERVED") >= 0;
        String who = who(up);
        String secs = secs(up);
        String reason = reason(up);

        if (served) {
            StringBuilder b = new StringBuilder("仲裁：");
            b.append(who.length() > 0 ? who : "某车手").append(" 已执行 ");
            if (secs.length() > 0) {
                b.append(secs).append(" 秒罚时");
            } else {
                b.append("处罚");
            }
            return b.toString();
        }
        StringBuilder b = new StringBuilder("★ 判罚：");
        b.append(who.length() > 0 ? who : "某车手");
        if (secs.length() > 0) {
            b.append(" 罚时 ").append(secs).append(" 秒");
        } else {
            b.append(" 被处罚");
        }
        if (reason.length() > 0) {
            b.append(" —— ").append(reason);
        }
        return b.toString();
    }

    /**
     * 仲裁裁决。真实模板：
     *   FIA STEWARDS: TURN 1 INCIDENT INVOLVING CAR 5 (BOR) REVIEWED NO FURTHER INVESTIGATION - <原因> (ts)
     *   FIA STEWARDS: Q1 INCIDENT INVOLVING CARS 81 (PIA), ... AND 77 (BOT) NO FURTHER ACTION - <原因>
     *   FIA STEWARDS: TURN 5 INCIDENT INVOLVING CAR 77 (BOT) WILL BE INVESTIGATED AFTER THE SESSION - <原因> (ts)
     *   FIA STEWARDS: TURN 5 INCIDENT INVOLVING CAR 5 (BOR) UNDER INVESTIGATION - <原因> (ts)
     *   FIA STEWARDS: WARNING FOR CAR 5 (BOR) - MOVING UNDER BRAKING (ts)
     *
     * ★ `NO FURTHER ACTION` 原来不认识 —— 结果整个周末**最长的两条**仲裁消息
     *   （7 辆车、8 辆车的事故）一条译文都没有。而那恰恰就是「不予追究」，
     *   用户明确说过这类不能被忽略。
     *
     * ★ 原因不再用括号包起来 —— 原因自己就带括号（未遵守赛会指令（逃生通道）），
     *   套起来会变成「（未遵守赛会指令（逃生通道））」。改用冒号：「不予追究：原因」。
     */
    private static String stewards(String up) {
        if (up.indexOf("FIA STEWARDS") < 0) {
            return null;
        }
        String who = who(up);
        String where = where(up);
        String reason = reason(up);

        String verdict;
        if (up.indexOf("REVIEWED NO FURTHER") >= 0) {
            verdict = "复核完毕，不予追究";
        } else if (up.indexOf("NO FURTHER ACTION") >= 0) {
            verdict = "不予追究";
        } else if (up.indexOf("WILL BE INVESTIGATED AFTER THE") >= 0) {
            // ★ 原来只认 SESSION / RACE 两种，而真实数据里还有
            //   ... AFTER THE SPRINT / AFTER THE QUALIFYING
            //   三年语料里这类一共二十多条。改成只认前半截。
            verdict = "赛后调查";
        } else if (up.indexOf("UNDER INVESTIGATION") >= 0) {
            verdict = "调查中";
        } else if (up.indexOf("WARNING") >= 0) {
            verdict = "警告";
        } else if (up.indexOf("SERVED") >= 0) {
            return null;                    // 交给 penalty() 处理
        } else {
            return null;
        }

        StringBuilder b = new StringBuilder("仲裁：");
        if (where.length() > 0) {
            b.append(where).append(' ');
        }
        b.append(who.length() > 0 ? who : "某车手");
        b.append(" —— ").append(verdict);
        if (reason.length() > 0) {
            b.append("：").append(reason);
        }
        return b.toString();
    }

    /**
     * 事故已记录（还没判）。真实模板：
     *   TURN 1 INCIDENT INVOLVING CAR 5 (BOR) NOTED - <原因> (ts)
     *   INCIDENT INVOLVING CAR 41 (LIN) NOTED - UNSAFE RELEASE (ts)     ← 连 TURN 都没有
     *   FIA STEWARDS: Q1 INCIDENT INVOLVING CARS 81 (PIA), ... NOTED - <原因>
     */
    private static String noted(String up) {
        if (up.indexOf("NOTED") < 0 || up.indexOf("INCIDENT") < 0) {
            return null;
        }
        String who = who(up);
        String where = where(up);
        String reason = reason(up);

        StringBuilder b = new StringBuilder();
        String loc = where.length() > 0 ? where : "赛事";
        b.append(loc);
        if (loc.startsWith("Q")) {
            b.append(' ');          // 「Q1事故」不好看，中英之间留个空格
        }
        b.append("事故");
        if (who.length() > 0) {
            b.append("（").append(who).append("）");
        }
        b.append("：已记录");
        if (reason.length() > 0) {
            b.append(" —— ").append(reason);
        }
        return b.toString();
    }

    /**
     * 删圈速。真实模板（**两种原因**）：
     *   CAR 55 (SAI) TIME 1:43.523 DELETED - TRACK LIMITS AT TURN 15 LAP 26 15:48:45
     *   CAR 77 (BOT) TIME 2:23.403 DELETED - DOUBLE YELLOW AT TURN 7 LAP 6 12:53:52
     *   CAR 1 (NOR) LAP DELETED - TRACK LIMITS AT TURN 1 LAP 28 14:34:00 (PIT)
     *
     * 判据必须是「DELETED + 已知原因」，**不能只看原因**：
     *   `BLACK AND WHITE FLAG FOR CAR 44 (HAM) - TRACK LIMITS`
     *   也含 TRACK LIMITS，但那是黑白旗，不是删圈速。
     *
     * 双黄旗那条只陈述事实、不解释为什么删（用户定）：
     *   「7 号弯双黄旗」，而不是「双黄旗未减速」。
     */
    private static String lapDeleted(String up) {
        if (up.indexOf("DELETED") < 0) {
            return null;
        }
        String why = "";
        for (int i = 0; i < DELETE_REASONS.length; i++) {
            if (up.indexOf(DELETE_REASONS[i][0]) >= 0) {
                why = DELETE_REASONS[i][1];
                break;
            }
        }
        if (why.length() == 0) {
            return null;
        }
        String who = who(up);
        String turn = turn(up);
        String lap = lap(up);
        boolean wholeLap = up.indexOf("LAP DELETED") >= 0;

        StringBuilder b = new StringBuilder();
        b.append(who.length() > 0 ? who : "某车手");
        b.append(wholeLap ? "：整圈成绩被删" : "：单圈成绩被删");
        if (turn.length() > 0) {
            b.append(" —— ").append(turn).append(" 号弯").append(why);
        } else {
            b.append(" —— ").append(why);
        }
        if (lap.length() > 0) {
            b.append("（第 ").append(lap).append(" 圈）");
        }
        return b.toString();
    }

    /** WAVED BLUE FLAG FOR CAR 77 (BOT) TIMED AT 16:08:37 */
    private static String blueFlag(String up) {
        if (up.indexOf("BLUE FLAG") < 0) {
            return null;
        }
        String who = who(up);
        return "蓝旗（让车）：" + (who.length() > 0 ? who : "某车手");
    }

    /** BLACK AND WHITE FLAG FOR CAR 55 (SAI) - TRACK LIMITS */
    private static String blackAndWhite(String up) {
        if (up.indexOf("BLACK AND WHITE") < 0) {
            return null;
        }
        String who = who(up);
        String reason = reason(up);
        StringBuilder b = new StringBuilder("黑白旗警告：");
        b.append(who.length() > 0 ? who : "某车手");
        if (reason.length() > 0) {
            b.append(" —— ").append(reason);
        }
        return b.toString();
    }

    /**
     * 其余「内容型」消息 —— 类型徽标说不清楚的那些。
     *
     * ## 为什么单开一类，而不是把旗语也翻了
     * 判据很简单：**徽标已经表达出来的信息不再重复**。
     *   `DOUBLE YELLOW IN TRACK SECTOR 15` 徽标就是「双黄旗」，区段是数字看得懂
     *   -> 不翻（翻了只是把徽标再说一遍）
     *   `YELLOW IN PIT LANE` 徽标只说「黄旗」，没说是**维修区**的
     *   -> 要翻（不翻就丢了关键信息）
     * 所以这里只收「内容型」消息：维修区、比赛环节、天气、赛道状况、车手相关。
     *
     * 这些句式来自一个完整比赛周末的 697 条真实消息，一条不编。
     */
    private static String other(String up) {
        // ---- 2024~2026 三年语料补齐（193 个缺口 / 110 个句式家族）----
        // 顺序要紧：带具体前缀、带 AT hh:mm 的，必须排在通用规则前面。

        // 封闭车检区（PARC FERME）：`F1 - POST-SPRINT PARC FERME - WORK MAY COMMENCE AT 12:58`
        if (up.indexOf("PARC FERME") >= 0 && up.indexOf("WORK MAY COMMENCE AT") >= 0) {
            Matcher pf = Pattern.compile("POST-(.+?) PARC FERME").matcher(up);
            String which = pf.find() ? subjectOf(pf.group(1)) : "";
            String t = clock(up);
            return join(which, "封闭车检区：作业可于 "
                    + (t.length() > 0 ? t : "稍后") + " 开始");
        }
        // 起步程序
        if (up.indexOf("START ABORTED - EXTRA FORMATION LAP") >= 0) {
            return "起步中止 " + DASH + " 增加一圈编队圈";
        }
        if (up.indexOf("EXTRA FORMATION LAP") >= 0) {
            return "增加一圈编队圈";
        }
        if (up.indexOf("ABORTED START") >= 0) {
            return "起步中止";
        }
        if (up.indexOf("STARTING PROCEDURE SUSPENDED") >= 0) {
            return "起步程序暂停";
        }
        if (up.indexOf("NO PRACTICE STARTS") >= 0) {
            return "本轮不允许练习起步";
        }
        if (up.indexOf("START ORDER: ORIGINAL GRID") >= 0) {
            return "起步顺序：按原始发车顺位";
        }
        if (up.indexOf("RACE WILL START BEHIND THE SAFETY CAR") >= 0) {
            return "正赛将在安全车后起步";
        }
        if (up.indexOf("FORMATION LAP(S) BEHIND SAFETY CAR") >= 0
                || up.indexOf("FORMATION LAP WILL BE STARTED BEHIND THE SAFETY CAR") >= 0) {
            return "编队圈在安全车后";
        }
        if (up.indexOf("ROLLING START") >= 0) {
            return "滚动起步";
        }
        if (up.indexOf("STANDING START") >= 0) {
            return "静止起步";
        }
        if (up.indexOf("SPRINT START") >= 0) {
            return "冲刺赛起步";
        }

        // ---- ★ 收紧体检白名单后才露出来的最后一批缺口 ----
        // 这 9 条在三年官方归档语料里**真实出现过**，每条只出现 1 次。
        // 之前看不到，是因为体检脚本的白名单用了 `SAFETY CAR.*` 这类通配，
        // 把它们算成了"设计如此"。通配符写下去的那一刻，它盖住的东西
        // 就再也看不见了 —— 这是那次最该记住的教训。
        Matcher stim = SESSION_TEMPS.matcher(up);
        if (stim.find()) {
            return "环节温度：气温 " + stim.group(1)
                    + "°C / 赛道 " + stim.group(2) + "°C";
        }
        Matcher send = SESSION_END_AT.matcher(up);
        if (send.find()) {
            return "环节将于 " + send.group(1) + " 结束";
        }
        if (up.indexOf("RED FLAG - RACE SUSPENDED") >= 0) {
            return "红旗：比赛暂停";
        }
        if (up.indexOf("SAFETY CAR THROUGH THE PIT LANE") >= 0) {
            return "安全车通过维修区";
        }
        if (up.indexOf("SAFETY CAR WILL USE START/FINISH STRAIGHT") >= 0) {
            return "安全车将使用起终点直道";
        }
        if (up.indexOf("VIRTUAL SAFETY CAR ENDING") >= 0
                || up.indexOf("VSC ENDING") >= 0) {
            return "虚拟安全车结束";
        }

        // ★ 安全车 / 虚拟安全车的**状态变化**。这 5 条是按官方归档的原始
        //   文案补的（2026 Bahrain 真实快照）：三年 HA 语料里没有它们，
        //   因为上游集成改写过措辞，所以"0 缺口"当初只对 HA 那套成立。
        //   它们标出安全车区间的起止，不是可有可无的重复信息。
        //   注意顺序：先判更长的、更具体的，再判短的。
        if (up.indexOf("SAFETY CAR LIGHTS ON") >= 0) {
            return "安全车灯亮起";
        }
        if (up.indexOf("SAFETY CAR LIGHTS OFF") >= 0) {
            return "安全车灯熄灭";
        }
        if (up.indexOf("SAFETY CAR DEPLOYED") >= 0) {
            return "安全车出动";
        }
        if (up.indexOf("SAFETY CAR IN THIS LAP") >= 0) {
            return "安全车本圈进站";
        }
        if (up.indexOf("VSC DEPLOYED") >= 0) {
            return "虚拟安全车出动";
        }

        // 2026 新规：抓地力 / 直线模式
        if (up.indexOf("LOW GRIP DELTA ACTIVE") >= 0) {
            return "低抓地力：圈速差限制生效";
        }
        if (up.indexOf("NORMAL GRIP DELTA ACTIVE") >= 0) {
            return "正常抓地力：圈速差限制生效";
        }
        if (up.indexOf("LOW GRIP CONDITIONS") >= 0) {
            return "赛道低抓地力";
        }
        if (up.indexOf("NORMAL GRIP CONDITIONS") >= 0) {
            return "赛道抓地力正常";
        }
        if (up.indexOf("STRAIGHT MODE - LOW GRIP") >= 0) {
            return "直线模式：低抓地力";
        }
        if (up.indexOf("STRAIGHT MODE - NORMAL GRIP") >= 0) {
            return "直线模式：正常抓地力";
        }
        if (up.indexOf("STRAIGHT MODE - DISABLED") >= 0) {
            return "直线模式：关闭";
        }

        // DRS
        Matcher dz = DRS_ZONE.matcher(up);
        if (dz.find()) {
            return ("DRS" + (up.indexOf("ENABLED") >= 0 ? "启用" : "关闭"))
                    + "（" + dz.group(1) + " 区）";
        }
        if (up.indexOf("DRS ENABLED") >= 0) {
            return "DRS 启用";
        }
        if (up.indexOf("DRS DISABLED") >= 0) {
            return "DRS 关闭";
        }

        // 天气 / 环境
        if (up.indexOf("WEATHER RADAR SYSTEM NOT AVAILABLE") >= 0) {
            return "天气雷达不可用";
        }
        if (up.indexOf("WEATHER RADAR SYSTEM NOW OPERATIONAL") >= 0) {
            return "天气雷达恢复可用";
        }
        if (up.indexOf("CHANGE IN CLIMATIC CONDITIONS") >= 0) {
            return "天气状况发生变化";
        }
        if (up.indexOf("WET TRACK") >= 0) {
            return "赛道湿滑";
        }
        if (up.indexOf("AWNINGS MAY BE USED") >= 0) {
            return "可以使用遮阳棚";
        }
        if (up.indexOf("AWNINGS TO BE REMOVED") >= 0) {
            return "需要撤除遮阳棚";
        }
        Matcher airt = AIR_TEMP.matcher(up);
        if (airt.find()) {
            return "赛前气温通报（" + airt.group(1) + "）：" + airt.group(2) + " 度";
        }

        // 维修区入口 / 维修车
        if (up.indexOf("PIT LANE ENTRY OPEN") >= 0) {
            return "维修区入口开放";
        }
        if (up.indexOf("PIT LANE ENTRY CLOSED") >= 0) {
            return "维修区入口关闭";
        }
        if (up.indexOf("RECOVERY VEHICLE IN PIT ENTRY") >= 0) {
            return "维修区入口有维修车";
        }
        if (up.indexOf("ALL CARS TO FOLLOW THE SAFETY CAR THROUGH THE PIT LANE") >= 0) {
            return "所有赛车跟随安全车通过维修区";
        }

        // 维修区事故（不一定带 FIA STEWARDS 前缀，也不一定有 NOTED）
        if (up.indexOf("PIT LANE INCIDENT INVOLVING") >= 0) {
            String w = who(up);
            String r = reason(up);
            return "维修区事故（" + (w.length() > 0 ? w : "某车手") + "）"
                    + (up.indexOf("WILL BE INVESTIGATED") >= 0 ? "：赛后调查" : "：已记录")
                    + (r.length() > 0 ? " " + DASH + " " + r : "");
        }

        // 圈速「恢复」（删了又撤销）
        Matcher rt = REINSTATE_TIME.matcher(up);
        if (rt.find()) {
            String w = who(up);
            return (w.length() > 0 ? w : "某车手") + "：单圈成绩恢复（" + rt.group(1) + "）";
        }
        Matcher rl = REINSTATE_LAP.matcher(up);
        if (rl.find()) {
            String w = who(up);
            return (w.length() > 0 ? w : "某车手") + "：第 " + rl.group(1) + " 圈成绩恢复";
        }

        // 圈速审核 / 调查
        Matcher lr = LAP_REVIEW.matcher(up);
        if (lr.find()) {
            return name(lr.group(2)) + "(" + lr.group(1) + ")：圈速审核中";
        }
        if (up.indexOf("LAP TIME UNDER INVESTIGATION") >= 0) {
            String w = who(up);
            String t = clock(up);
            return (w.length() > 0 ? w : "某车手") + "：圈速调查中 " + DASH
                    + " 超出赛道限制" + (t.length() > 0 ? "（" + t + "）" : "");
        }

        // 练习赛延长
        Matcher ex = EXTENDED.matcher(up);
        if (ex.find()) {
            return session(ex.group(1)) + "延长 " + ex.group(2) + " 分钟";
        }

        // 环境开始 / 重启时刻
        Matcher wa = WILL_START_ANY.matcher(up);
        if (wa.find()) {
            boolean resume = "RESUME".equals(wa.group(2));
            return join(subjectOf(wa.group(1)), "将于 " + wa.group(3)
                    + (resume ? " 重启" : " 开始"));
        }
        if (up.indexOf("WILL NOT BE RESUMED") >= 0) {
            return subjectOf(up.replace(" WILL NOT BE RESUMED", "")) + " 不再重启";
        }
        if (up.indexOf("START OF ") >= 0 && up.indexOf(" WILL BE DELAYED") >= 0) {
            String mid = up.substring(up.indexOf("START OF ") + 9);
            mid = mid.substring(0, mid.indexOf(" WILL BE DELAYED"));
            // 用「将推迟开始」而不是「推迟开始」：与 v2.0.9 那条只认
            // QUALIFYING 的规则保持一致（单元测试钉的就是这个措辞），
            // 而且中文里多个「将」更顺。这条通用规则覆盖了
            // Q2/Q3/SQ2/SQ3/FREE PRACTICE n/SESSION 等全部变体。
            return join(subjectOf(mid), "将推迟开始");
        }
        Matcher et = ESTIMATED.matcher(up);
        if (et.find()) {
            return join("预计 " + subjectOf(et.group(1)),
                        "将于 " + et.group(3)
                        + ("RESUMPTION".equals(et.group(2)) ? " 重启" : " 开始"));
        }

        // 重启 / 推行顺序
        Matcher od = ORDER.matcher(up);
        if (od.find()) {
            return (up.startsWith("PUSH CARS") ? "把赛车推至前列，顺序："
                    : "重启顺序：") + od.group(1);
        }
        if (up.indexOf("RESUMPTION TEST ABORTED") >= 0) {
            return "重启测试中止";
        }
        if (up.indexOf("TRACK TEST COMPLETED") >= 0) {
            return "赛道测试完成";
        }
        if (up.indexOf("THIS IS A TEST MESSAGE FROM RACE CONTROL") >= 0) {
            return "赛会测试消息";
        }
        if (up.indexOf("BLACK AND ORANGE FLAG") >= 0) {
            String w = who(up);
            return "黑橙旗（机械故障）：" + (w.length() > 0 ? w : "某车手");
        }
        if (up.indexOf("LIGHT BLUE HEAD PADDING MATERIAL MUST BE USED") >= 0) {
            return "必须使用浅蓝色头枕垫料";
        }
        if (up.indexOf("BLUE HEAD PADDING MATERIAL MUST BE USED") >= 0) {
            return "必须使用蓝色头枕垫料";
        }
        // ---- 2026-09-24~26 新周末出现的指令 ----
        // 这五条都是安全车 / 排位赛期间的操作指令，徽标只能说「其它」。
        if (up.indexOf("ALL CARS THROUGH THE PIT LANE") >= 0) {
            return "所有赛车通过维修区";
        }
        if (up.indexOf("ALL CARS USE START/FINISH STRAIGHT") >= 0) {
            return "所有赛车使用起终点直道";
        }
        if (up.indexOf("RECOVERY VEHICLE ON TRACK") >= 0) {
            // 用户定的译法：维修车（不是「救援车」）
            String t = turn(up);
            return (t.length() > 0 ? t + " 号弯" : "赛道") + "有维修车";
        }
        if (up.indexOf("START OF QUALIFYING WILL BE DELAYED") >= 0) {
            return "排位赛将推迟开始";
        }
        Matcher ws = WILL_START.matcher(up);
        if (ws.find()) {
            return ws.group(1) + " 将于 " + ws.group(2) + " 开始";
        }
        // ★ 真实数据里**单复数都有**：
        //   `LAPPED CARS MAY NOW ...` 和 `LAPPED CAR MAY NOW ...`
        if (up.indexOf("LAPPED CAR") >= 0
                && up.indexOf("OVERTAKE THE SAFETY CAR") >= 0) {
            // 冒号后面是车号（用户确认）。
            String n = afterColon(up);
            return "被套圈车可超越安全车"
                    + (n.length() > 0 ? "：" + n + " 号" : "");
        }
        if (up.indexOf("PINK HEAD PADDING MATERIAL MUST BE USED") >= 0) {
            return "必须使用粉色头枕垫料";
        }
        if (up.indexOf("TRACK SURFACE SLIPPERY") >= 0) {
            String sec = sector(up);
            return "赛道湿滑" + (sec.length() > 0 ? "（" + sec + " 号区段）" : "");
        }
        if (up.indexOf("ALL PASS HOLDERS MAY ACCESS THE PIT LANE") >= 0) {
            return "持通行证者可使用维修区";
        }
        if (up.indexOf("YELLOW IN PIT LANE") >= 0) {
            return "维修区黄旗";
        }
        if (up.indexOf("PIT LANE CLEAR") >= 0) {
            return "维修区解除";
        }
        // `SESSION` 指的是**这一个比赛环节**（一练/排位/正赛），简称就是「比赛」。
        // 译成「会话」是计算机味的误译（用户指出）。
        if (up.indexOf("SESSION WILL RESUME") >= 0) {
            String t = clock(up);
            return "比赛将于 " + (t.length() > 0 ? t : "稍后") + " 重启";
        }
        if (up.indexOf("SESSION WILL BE TEMPORARILY STOPPED") >= 0) {
            return "比赛暂时中止";
        }
        if (up.indexOf("MARSHALS ON TRACK") >= 0) {
            String turn = turn(up);
            // 「马歇尔」是照字面音译。中文 F1 圈通用的是**马修**（marshal 的定名），
            // 用户指出过这一点。
            return (turn.length() > 0 ? turn + " 号弯" : "赛道上") + "有马修";
        }
        if (up.indexOf("MEDICAL CAR DEPLOYED") >= 0) {
            return "医疗车出动";
        }
        if (up.indexOf("DELAYED START") >= 0) {
            return "起步推迟";
        }
        if (up.indexOf("PIT EXIT OPEN") >= 0) {
            return "维修区出口开放";
        }
        if (up.indexOf("PIT EXIT CLOSED") >= 0) {
            return "维修区出口关闭";
        }
        if (up.indexOf("OVERTAKE ENABLED") >= 0) {
            return "允许超车";
        }
        if (up.indexOf("OVERTAKE DISABLED") >= 0) {
            return "禁止超车";
        }
        if (up.indexOf("RACE START") >= 0) {
            return "比赛开始";
        }
        if (up.indexOf("FIRST CAR TO TAKE THE FLAG") >= 0) {
            String who = who(up);
            return "首个冲线：" + (who.length() > 0 ? who : "某车手");
        }
        Matcher m = RAIN.matcher(up);
        if (m.find()) {
            return session(m.group(1)) + "降雨概率 " + m.group(2) + "%";
        }
        return null;
    }

    /** `F1 RACE` / `F2 QUALIFYING` / `F1 FREE PRACTICE 2` / `THE F2 SPRINT RACE` -> 中文赛段名。 */
    static String session(String en) {
        String s = en.trim().toUpperCase(Locale.US);
        if (s.startsWith("THE ")) {
            s = s.substring(4);
        }
        String cls;
        if (s.startsWith("F1")) {
            cls = "F1 ";
        } else if (s.startsWith("F2")) {
            cls = "F2 ";
        } else if (s.startsWith("F3")) {
            cls = "F3 ";
        } else {
            return en.trim();
        }
        String kind;
        if (s.indexOf("FIRST PRACTICE SESSION") >= 0) {
            kind = "一练";
        } else if (s.indexOf("SECOND PRACTICE SESSION") >= 0) {
            kind = "二练";
        } else if (s.indexOf("THIRD PRACTICE SESSION") >= 0) {
            kind = "三练";
        } else if (s.indexOf("FREE PRACTICE 1") >= 0) {
            kind = "一练";
        } else if (s.indexOf("FREE PRACTICE 2") >= 0) {
            kind = "二练";
        } else if (s.indexOf("FREE PRACTICE 3") >= 0) {
            kind = "三练";
        } else if (s.indexOf("SPRINT QUALIFYING") >= 0) {
            kind = "冲刺排位";
        } else if (s.indexOf("SPRINT") >= 0) {
            kind = "冲刺赛";
        } else if (s.indexOf("QUALIFYING") >= 0) {
            kind = "排位赛";
        } else if (s.indexOf("RACE") >= 0) {
            kind = "正赛";
        } else {
            return en.trim();
        }
        return cls + kind;
    }

    // ------------------------------------------------------------------
    // 零件
    // ------------------------------------------------------------------

    /**
     * 「数字 (三字母)」里不是车手的标记。
     *
     * 实测：整个比赛周末的数据里这种形状共出现 23 种三字母，其中**只有 `PIT` 不是车手**——
     * 它是"这一圈进过站"的尾巴，出现 41 次：
     *   CAR 5 (BOR) LAP DELETED - TRACK LIMITS AT TURN 5 LAP 7 13:41:22 (PIT)
     * 不排除的话会被解析成"车手 22 号 PIT"，翻译里就多出个不存在的人。
     */
    private static final java.util.Set<String> NOT_DRIVER = new java.util.HashSet<String>(
            java.util.Arrays.asList("PIT", "LAP", "TBC"));

    /**
     * 从 "CAR 55 (SAI)" 取出「塞恩斯(55)」。**一辆都不省。**
     *
     * ★ 原来是最多列两辆 + 「等 N 辆」，被用户否掉了：整个周末最长的两条消息
     *   （8 辆、9 辆车）他一辆都不想漏 —— 车号本身就是要核对的信息。
     *   列全了译文有 70 来个字、手机上要占三四行，所以列表里同时放开了行数限制
     *   （见 MainActivity 的 gloss：不再 setMaxLines/ellipsize）。
     */
    static String who(String up) {
        Matcher m = CAR.matcher(up);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            if (NOT_DRIVER.contains(m.group(2))) {
                continue;               // "(PIT)" 这类尾巴，不是车手
            }
            if (b.length() > 0) {
                b.append("、");
            }
            b.append(name(m.group(2))).append("(").append(m.group(1)).append(")");
        }
        return b.toString();
    }

    static String name(String code) {
        String cn = DRIVERS.get(code);
        return cn == null ? code : cn;
    }

    static String turn(String up) {
        Matcher m = TURN.matcher(up);
        return m.find() ? m.group(1) : "";
    }

    static String lap(String up) {
        Matcher m = LAP.matcher(up);
        return m.find() ? m.group(1) : "";
    }

    static String sector(String up) {
        Matcher m = SECTOR.matcher(up);
        return m.find() ? m.group(1) : "";
    }

    /**
     * 事故地点：优先「N 号弯」，没有的话退回排位赛阶段「Q1/Q2/Q3」。
     *
     * ★ 原来只会输出「N 号弯」，没有 TURN 就一律写「赛事」——
     *   于是 `FIA STEWARDS: Q1 INCIDENT ...` 变成了「赛事事故」，
     *   把"这是排位赛第一节的事故"这个关键上下文丢了。
     */
    static String where(String up) {
        String turn = turn(up);
        if (turn.length() > 0) {
            return turn + " 号弯";
        }
        Matcher m = PHASE.matcher(up);
        if (m.find()) {
            return m.group(1);
        }
        return "";
    }

    /**
     * 训诫：`FIA STEWARDS: REPRIMAND (DRIVING) FOR CAR 23 (ALB) - ...`。
     * 不是罚时，也不是常见的仲裁结论，单独一支。
     */
    private static String reprimand(String up) {
        if (up.indexOf("REPRIMAND") < 0) {
            return null;
        }
        String w = who(up);
        String r = reason(up);
        StringBuilder b = new StringBuilder("★ 训诫：");
        b.append(w.length() > 0 ? w : "某车手");
        if (up.indexOf("(DRIVING)") >= 0) {
            b.append("（驾驶行为）");
        }
        if (r.length() > 0) {
            b.append(" —— ").append(r);
        }
        return b.toString();
    }

    /**
     * 环节名 -> 中文。比 {@link #session} 宽松：
     * 后者只认 `F1/F2/F3 <类型>`，而这里还要认 `SESSION` / `FORMATION LAP` / `SQn`。
     */
    static String subjectOf(String en) {
        String s = en == null ? "" : en.trim().toUpperCase(Locale.US);
        if (s.length() == 0) {
            return "比赛";
        }
        if (s.indexOf("FORMATION LAP") >= 0) {
            return "编队圈";
        }
        if (s.indexOf("FIRST PRACTICE") >= 0 || s.indexOf("FREE PRACTICE 1") >= 0) {
            return "一练";
        }
        if (s.indexOf("SECOND PRACTICE") >= 0 || s.indexOf("FREE PRACTICE 2") >= 0) {
            return "二练";
        }
        if (s.indexOf("THIRD PRACTICE") >= 0 || s.indexOf("FREE PRACTICE 3") >= 0) {
            return "三练";
        }
        if (s.indexOf("SPRINT QUALIFYING") >= 0) {
            return "冲刺排位";
        }
        if (s.indexOf("SPRINT") >= 0) {
            return "冲刺赛";
        }
        if (s.indexOf("RACE") >= 0) {
            return "正赛";
        }
        if (s.indexOf("QUALIFYING") >= 0) {
            return "排位赛";
        }
        if (s.indexOf("SESSION") >= 0) {
            return "比赛";
        }
        if (s.matches("SQ[123]")) {
            return s;
        }
        Matcher q = PHASE.matcher(s);
        if (q.find()) {
            return q.group(1);
        }
        return en.trim();
    }

    /** 中文直接接；英文/数字之间补一个空格（`Q1 将于`）。 */
    private static String join(String a, String b) {
        if (a.length() == 0) {
            return b;
        }
        char c = a.charAt(a.length() - 1);
        return (c < 128 && Character.isLetterOrDigit(c)) ? a + " " + b : a + b;
    }

    /** 冒号后面那串数字：`... SAFETY CAR: 77` -> "77"。 */
    static String afterColon(String up) {
        Matcher m = Pattern.compile(":\\s*(\\d+)").matcher(up);
        return m.find() ? m.group(1) : "";
    }

    /** 消息里的 `17:47` 这种时刻。 */
    static String clock(String up) {
        Matcher m = Pattern.compile("\\b(\\d{1,2}:\\d{2})\\b").matcher(up);
        return m.find() ? m.group(1) : "";
    }

    static String secs(String up) {
        Matcher m = SECONDS.matcher(up);
        return m.find() ? m.group(1) : "";
    }

    /** 处置原因，按最长匹配优先。 */
    static String reason(String up) {
        for (int i = 0; i < REASONS.length; i++) {
            if (up.indexOf(REASONS[i][0]) >= 0) {
                return REASONS[i][1];
            }
        }
        return "";
    }
}

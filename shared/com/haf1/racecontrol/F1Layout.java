package com.haf1.racecontrol;

/**
 * 界面布局的**纯计算**部分 —— 不碰 Canvas，所以能离线单测。
 *
 * ## 为什么把计算抽出来
 * 画的那些类（{@link TrackRingView} 等）必须真机才能验证，但"第 7 个区段
 * 从几度画到几度""第 17 位车手在第几列第几行"这种算术是**纯逻辑**，
 * 算错了界面上只会表现为"有点歪"，肉眼很难发现。所以放这里，用单测钉死。
 *
 * ## 两套角度约定别搞混
 *   本类：**0 度 = 12 点方向，顺时针为正**
 *   Android Canvas.drawArc：0 度 = 3 点方向，顺时针为正
 * 画的时候要减 90。这个偏移是这类图最经典的 off-by-90 错误。
 */
public final class F1Layout {

    private F1Layout() {
    }

    // ------------------------------------------------------------------
    // 圆环（赛道图降级方案）
    // ------------------------------------------------------------------

    /** 每段的张角（度）。n 个区段均布。 */
    public static float arcSweep(int n) {
        return n <= 0 ? 0f : 360f / n;
    }

    /**
     * n 个区段均布时，每段的**起始角**（度，0 = 12 点，顺时针）。
     *
     * 用户要求「区段均布」，所以就是 360/n 等分，从正上方开始。
     */
    public static float[] ringStarts(int n) {
        if (n <= 0) {
            return new float[0];
        }
        float sweep = arcSweep(n);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = i * sweep;
        }
        return out;
    }

    /** 第 i 段（0 起）的中心角。 */
    public static float arcMid(int i, int n) {
        float sweep = arcSweep(n);
        return i * sweep + sweep / 2f;
    }

    /**
     * 圆周上某角度对应的点。角度按本类约定（0 = 12 点，顺时针）。
     *
     * 返回 {x, y}。12 点方向在屏幕坐标里是 y 更小，所以是 -cos 那一套。
     */
    public static float[] polar(float cx, float cy, float r, float deg) {
        double rad = Math.toRadians(deg);
        return new float[]{
                (float) (cx + r * Math.sin(rad)),
                (float) (cy - r * Math.cos(rad)),
        };
    }

    /** 区段标签的点：在环带中间（半径取 rInner..rOuter 的中点）。 */
    public static float[] labelPoint(float cx, float cy, float rInner,
                                     float rOuter, int i, int n) {
        float r = (rInner + rOuter) / 2f;
        return polar(cx, cy, r, arcMid(i, n));
    }

    /** 该区段够不够宽、放得下一个数字标签（太窄就不画，免得糊成一团）。 */
    public static boolean labelFits(float rInner, int n, float minArcPx) {
        if (n <= 0) {
            return false;
        }
        double circumference = 2 * Math.PI * rInner;
        return circumference / n >= minArcPx;
    }

    // ------------------------------------------------------------------
    // 轮胎 / 进站面板：22 个位置、三列、每列 8 行
    // ------------------------------------------------------------------

    /** 一共要预留多少个格子。3 列 × 8 行 = 24，其中用到 22 个。 */
    public static int capacity(int columns, int rowsPerColumn) {
        return columns * rowsPerColumn;
    }

    /**
     * 格子 → 车手序号（0 起）的映射，**列优先**（先填满第一列的 8 个，
     * 再填第二列）。空位是 -1。
     *
     * 用户要求「按 22 个位置预留，分三列，每列 8 个人，顺序按赛道位置」，
     * 也就是 P1..P8 在第一列、P9..P16 在第二列、P17..P22 在第三列。
     */
    public static int[] boardSlots(int total, int columns, int rowsPerColumn) {
        int cap = capacity(columns, rowsPerColumn);
        int[] out = new int[cap];
        for (int i = 0; i < cap; i++) {
            out[i] = i < total ? i : -1;
        }
        return out;
    }

    /** 第 index 位车手在第几列（0 起）。 */
    public static int boardColumn(int index, int rowsPerColumn) {
        return rowsPerColumn <= 0 ? 0 : index / rowsPerColumn;
    }

    /** 第 index 位车手在该列的第几行（0 起）。 */
    public static int boardRow(int index, int rowsPerColumn) {
        return rowsPerColumn <= 0 ? 0 : index % rowsPerColumn;
    }

    // ------------------------------------------------------------------
    // 顶部旗语栏
    // ------------------------------------------------------------------

    /**
     * 顶部旗语栏的分隔线 x 坐标。kinds 种旗语等分整宽。
     *
     * 返回 kinds+1 个值：第一条在 0，最后一条在 width。
     */
    public static float[] barDividers(int kinds, float width) {
        if (kinds <= 0) {
            return new float[]{0f, width};
        }
        float[] out = new float[kinds + 1];
        for (int i = 0; i <= kinds; i++) {
            out[i] = width * i / kinds;
        }
        return out;
    }

    /** 第 i 段的起点 x。 */
    public static float barSegmentLeft(int i, int kinds, float width) {
        return kinds <= 0 ? 0f : width * i / kinds;
    }

    /** 第 i 段的宽度。 */
    public static float barSegmentWidth(int kinds, float width) {
        return kinds <= 0 ? width : width / kinds;
    }

    /**
     * 旗语栏里的一行区段列表文字，太长就省略。
     *
     * 例：[3, 7, 12] -> "3,7,12"；超过 max 个 -> "3,7,12…共 20 个"
     */
    public static String sectorList(java.util.List<Integer> sectors, int max) {
        if (sectors == null || sectors.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        int n = Math.min(sectors.size(), max);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(sectors.get(i).intValue());
        }
        if (sectors.size() > max) {
            b.append('…');
        }
        return b.toString();
    }
}
